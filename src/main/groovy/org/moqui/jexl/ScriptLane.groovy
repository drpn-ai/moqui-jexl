/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.jexl

import groovy.transform.CompileStatic
import org.moqui.Moqui
import org.moqui.context.ExecutionContextFactory
import org.moqui.util.SystemBinding
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The one lane user scripts run in, inside the application's own server. A few dedicated threads (never request
 * threads); one running script per owner key; a capped number waiting, each for a limited time; no start inside an open
 * transaction; a kill switch. A script that ignores its stop past the time limit plus a grace period is STUCK: its
 * thread is given up (a fresh one takes its place) but its owner stays blocked until it really ends, and once the
 * number of stuck runs reaches its cap every new run is held, so a pile of stuck threads can never grow.
 *
 * Settings (SystemBinding.getPropOrEnv): jexl_scripts_enabled (true), jexl_lane_threads (2), jexl_lane_queue (4),
 * jexl_lane_wait_ms (30000), jexl_lane_grace_ms (1000), jexl_lane_stuck_cap (2). Thread and queue sizes are read when
 * the lane starts; the rest on every run.
 */
@CompileStatic
class ScriptLane {
    private static final Logger logger = LoggerFactory.getLogger(ScriptLane)

    private static final Closure<Boolean> DEFAULT_IN_TRANSACTION = { ->
        ExecutionContextFactory ecf = Moqui.getExecutionContextFactory()
        return ecf != null && ecf.getTransaction().isTransactionInPlace()
    }
    /** Whether the calling thread is inside a transaction; replaced in tests. */
    static Closure<Boolean> inTransaction = DEFAULT_IN_TRANSACTION

    private static volatile Boolean runtimeEnabled = null
    private static final AtomicInteger THREAD_COUNT = new AtomicInteger()
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool({ Runnable task ->
        Thread thread = new Thread(task, "jexl-script-${THREAD_COUNT.incrementAndGet()}".toString())
        thread.daemon = true
        thread.priority = Thread.NORM_PRIORITY - 1
        return thread
    } as ThreadFactory)

    private static volatile Semaphore threadSlots = new Semaphore(intSetting('jexl_lane_threads', 2))
    private static volatile int queueCap = intSetting('jexl_lane_queue', 4)
    private static final ConcurrentHashMap<String, Semaphore> OWNERS = new ConcurrentHashMap<>()
    private static final AtomicInteger WAITING = new AtomicInteger()
    private static final AtomicInteger RUNNING = new AtomicInteger()
    private static final AtomicInteger STUCK = new AtomicInteger()

    static boolean enabled() {
        String setting = SystemBinding.getPropOrEnv('jexl_scripts_enabled')
        if (setting != null && setting.trim().equalsIgnoreCase('false')) return false
        return runtimeEnabled != Boolean.FALSE
    }

    /** The runtime toggle; the setting jexl_scripts_enabled=false still wins. */
    static void setEnabled(boolean enabled) { runtimeEnabled = enabled }

    static int stuckCount() { STUCK.get() }

    static int runningCount() { RUNNING.get() }

    static int waitingCount() { WAITING.get() }

    static void resetForTests() {
        inTransaction = DEFAULT_IN_TRANSACTION
        runtimeEnabled = null
        threadSlots = new Semaphore(intSetting('jexl_lane_threads', 2))
        queueCap = intSetting('jexl_lane_queue', 4)
        OWNERS.clear()
        WAITING.set(0)
        RUNNING.set(0)
        STUCK.set(0)
    }

    /** A user script's run. */
    static LaneResult run(String ownerKey, long timeoutMs, Callable<Object> work) { run(ownerKey, timeoutMs, work, true) }

    /**
     * Runs work on a lane thread and waits for it, at most timeoutMs. userCode false (a .jexl file that ships with the
     * application) skips the transaction check and the kill switch; the owner slot, queue and stuck handling still apply.
     */
    static LaneResult run(String ownerKey, long timeoutMs, Callable<Object> work, boolean userCode) {
        if (userCode && !enabled()) return LaneResult.of('DISABLED', 'Scripts are switched off on this server.')
        if (userCode && inTransaction.call()) {
            return LaneResult.of('IN_TRANSACTION', 'A script cannot start inside an open transaction.')
        }
        int stuckNow = STUCK.get()
        if (stuckNow >= intSetting('jexl_lane_stuck_cap', 2)) {
            return LaneResult.of('HELD', "scripts are paused: ${stuckNow} stuck runs".toString())
        }
        if (WAITING.incrementAndGet() > queueCap) {
            WAITING.decrementAndGet()
            return LaneResult.of('BUSY', 'Scripts are busy right now; try again in a moment.')
        }

        String key = ownerKey ?: '-'
        Semaphore owner = OWNERS.computeIfAbsent(key, { String k -> new Semaphore(1) })
        Semaphore threads = threadSlots
        long waitMs = intSetting('jexl_lane_wait_ms', 30_000)
        long deadline = System.currentTimeMillis() + waitMs
        try {
            if (!owner.tryAcquire(waitMs, TimeUnit.MILLISECONDS)) {
                return LaneResult.of('HELD', "Another script of the same owner ran past ${waitMs} ms; this one was held.".toString())
            }
            if (!threads.tryAcquire(Math.max(0L, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS)) {
                owner.release()
                return LaneResult.of('HELD', "Every script thread was busy for ${waitMs} ms; this run was held.".toString())
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
            return LaneResult.of('HELD', 'Interrupted while waiting for a script thread.')
        } finally {
            WAITING.decrementAndGet()
        }

        RunState state = new RunState()
        Future<Object> future = EXECUTOR.submit({ ->
            RUNNING.incrementAndGet()
            try {
                return work.call()
            } finally {
                RUNNING.decrementAndGet()
                synchronized (state) {
                    state.finished = true
                    if (state.stuck) {
                        STUCK.decrementAndGet()
                        logger.info("jexl script no longer stuck owner=${key} thread=${Thread.currentThread().name}")
                    } else {
                        threads.release()
                    }
                    owner.release()
                }
                state.done.countDown()
            }
        } as Callable<Object>)

        try {
            return new LaneResult('OK', future.get(timeoutMs, TimeUnit.MILLISECONDS), null, null, false)
        } catch (TimeoutException ignored) {
            future.cancel(true)
            boolean ended
            try {
                ended = state.done.await(intSetting('jexl_lane_grace_ms', 1_000), TimeUnit.MILLISECONDS)
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt()
                ended = false
            }
            boolean stuck = false
            if (!ended) {
                synchronized (state) {
                    if (!state.finished) {
                        state.stuck = true
                        stuck = true
                        STUCK.incrementAndGet()
                        // NOTE: the thread slot goes back now, so a fresh thread takes the stuck one's place
                        threads.release()
                        logger.warn("jexl script stuck owner=${key} thread=jexl-script (ignored its stop past ${timeoutMs} ms)")
                    }
                }
            }
            return new LaneResult('TIME', null, null, "The script ran past ${timeoutMs} ms and was stopped.".toString(), stuck)
        } catch (ExecutionException e) {
            return new LaneResult('FAILED', null, e.cause ?: e, null, false)
        } catch (InterruptedException e) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            return new LaneResult('FAILED', null, e, 'Interrupted while waiting for the script.', false)
        }
    }

    private static int intSetting(String name, int defaultValue) {
        String raw = SystemBinding.getPropOrEnv(name)
        if (raw == null || raw.trim().isEmpty()) return defaultValue
        try {
            int value = Integer.parseInt(raw.trim())
            return value >= 0 ? value : defaultValue
        } catch (NumberFormatException ignored) {
            return defaultValue
        }
    }

    private static class RunState {
        boolean finished = false
        boolean stuck = false
        final CountDownLatch done = new CountDownLatch(1)
    }
}

/** How a lane run ended: OK, HELD, DISABLED, BUSY, TIME, FAILED or IN_TRANSACTION. */
@CompileStatic
class LaneResult {
    final String status
    final Object value
    final Throwable error
    final String message
    final boolean stuck

    LaneResult(String status, Object value, Throwable error, String message, boolean stuck) {
        this.status = status
        this.value = value
        this.error = error
        this.message = message
        this.stuck = stuck
    }

    static LaneResult of(String status, String message) { new LaneResult(status, null, null, message, false) }
}

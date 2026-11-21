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

import spock.lang.Specification

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** The lane every user script runs in: owner slots, a capped queue, holds, the kill switch and stuck threads. */
class ScriptLaneTests extends Specification {
    static final List<String> PROPS = ['jexl_lane_threads', 'jexl_lane_queue', 'jexl_lane_wait_ms', 'jexl_lane_grace_ms',
                                       'jexl_lane_stuck_cap', 'jexl_scripts_enabled']

    def setup() { ScriptLane.resetForTests() }

    def cleanup() {
        PROPS.each { System.clearProperty(it) }
        ScriptLane.resetForTests()
    }

    private static LaneResultHolder async(String owner, long timeoutMs, Callable<Object> work) {
        LaneResultHolder holder = new LaneResultHolder()
        holder.thread = Thread.start { holder.result = ScriptLane.run(owner, timeoutMs, work) }
        return holder
    }

    def "oneOwnerRunsOneAtATime"() {
        given:
        CountDownLatch release = new CountDownLatch(1)
        CountDownLatch firstStarted = new CountDownLatch(1)
        AtomicBoolean secondStartedEarly = new AtomicBoolean(false)
        AtomicBoolean firstDone = new AtomicBoolean(false)

        when:
        LaneResultHolder first = async('A', 5_000L, { firstStarted.countDown(); release.await(); firstDone.set(true); 1 } as Callable<Object>)
        firstStarted.await(2, TimeUnit.SECONDS)
        LaneResultHolder second = async('A', 5_000L, { if (!firstDone.get()) secondStartedEarly.set(true); 2 } as Callable<Object>)
        LaneResult other = ScriptLane.run('B', 5_000L, { 3 } as Callable<Object>)
        release.countDown()
        first.thread.join(); second.thread.join()

        then:
        other.status == 'OK' && other.value == 3
        first.result.value == 1
        second.result.value == 2
        !secondStartedEarly.get()
    }

    def "aFullQueueIsBusy"() {
        given:
        System.setProperty('jexl_lane_threads', '1')
        System.setProperty('jexl_lane_queue', '1')
        ScriptLane.resetForTests()
        CountDownLatch release = new CountDownLatch(1)
        CountDownLatch started = new CountDownLatch(1)

        when:
        LaneResultHolder running = async('A', 5_000L, { started.countDown(); release.await(); 1 } as Callable<Object>)
        started.await(2, TimeUnit.SECONDS)
        LaneResultHolder waiting = async('B', 5_000L, { 2 } as Callable<Object>)
        Thread.sleep(200)
        LaneResult third = ScriptLane.run('C', 5_000L, { 3 } as Callable<Object>)
        release.countDown()
        running.thread.join(); waiting.thread.join()

        then:
        third.status == 'BUSY'
        waiting.result.status == 'OK'
    }

    def "aLongWaitIsHeld"() {
        given:
        System.setProperty('jexl_lane_wait_ms', '50')
        ScriptLane.resetForTests()
        CountDownLatch release = new CountDownLatch(1)
        CountDownLatch started = new CountDownLatch(1)

        when:
        LaneResultHolder running = async('A', 5_000L, { started.countDown(); release.await(); 1 } as Callable<Object>)
        started.await(2, TimeUnit.SECONDS)
        LaneResult held = ScriptLane.run('A', 5_000L, { 2 } as Callable<Object>)
        release.countDown()
        running.thread.join()

        then:
        held.status == 'HELD'
    }

    def "theKillSwitchStopsNewRuns"() {
        given:
        AtomicBoolean ran = new AtomicBoolean(false)

        when:
        ScriptLane.setEnabled(false)
        LaneResult off = ScriptLane.run('A', 1_000L, { ran.set(true); 1 } as Callable<Object>)
        ScriptLane.setEnabled(true)
        LaneResult on = ScriptLane.run('A', 1_000L, { 2 } as Callable<Object>)

        then:
        off.status == 'DISABLED'
        !ran.get()
        on.status == 'OK'
    }

    def "theSettingAlsoStopsRuns"() {
        given:
        System.setProperty('jexl_scripts_enabled', 'false')

        expect:
        ScriptLane.run('A', 1_000L, { 1 } as Callable<Object>).status == 'DISABLED'
    }

    def "insideATransactionIsRefused"() {
        given:
        ScriptLane.inTransaction = { -> true }

        expect:
        ScriptLane.run('A', 1_000L, { 1 } as Callable<Object>).status == 'IN_TRANSACTION'
    }

    def "applicationCodeSkipsTheTransactionAndSwitchChecks"() {
        given:
        ScriptLane.inTransaction = { -> true }
        ScriptLane.setEnabled(false)

        expect:
        ScriptLane.run('.jexl', 1_000L, { 1 } as Callable<Object>, false).status == 'OK'
    }

    def "aFailureCarriesItsCause"() {
        when:
        LaneResult failed = ScriptLane.run('A', 1_000L, { throw new IllegalStateException('boom') } as Callable<Object>)

        then:
        failed.status == 'FAILED'
        failed.error instanceof IllegalStateException
    }

    def "stuckRunsPastTheCapPauseTheLane"() {
        given:
        System.setProperty('jexl_lane_stuck_cap', '2')
        System.setProperty('jexl_lane_grace_ms', '50')
        ScriptLane.resetForTests()
        AtomicBoolean spin = new AtomicBoolean(true)
        Callable<Object> ignoresInterrupts = { ->
            while (spin.get()) { Thread.onSpinWait() }
            return 0
        } as Callable<Object>

        when:
        LaneResult one = ScriptLane.run('A', 50L, ignoresInterrupts)
        LaneResult two = ScriptLane.run('B', 50L, ignoresInterrupts)
        int stuckBefore = ScriptLane.stuckCount()
        LaneResult paused = ScriptLane.run('C', 1_000L, { 3 } as Callable<Object>)
        spin.set(false)
        long until = System.currentTimeMillis() + 2_000L
        while (ScriptLane.stuckCount() > 0 && System.currentTimeMillis() < until) Thread.sleep(10)
        LaneResult after = ScriptLane.run('C', 1_000L, { 4 } as Callable<Object>)

        then:
        one.status == 'TIME' && one.stuck
        two.status == 'TIME' && two.stuck
        stuckBefore == 2
        paused.status == 'HELD'
        paused.message.contains('scripts are paused: 2 stuck runs')
        after.status == 'OK' && after.value == 4
    }

    def "aRunThatStopsWhenInterruptedIsNotStuck"() {
        when:
        LaneResult timed = ScriptLane.run('A', 50L, { Thread.sleep(10_000L); 1 } as Callable<Object>)

        then:
        timed.status == 'TIME'
        !timed.stuck
        ScriptLane.stuckCount() == 0
    }

    def "aNullOwnerDoesNotSerialize"() {
        given:
        CountDownLatch release = new CountDownLatch(1)
        CountDownLatch firstStarted = new CountDownLatch(1)

        when:
        LaneResultHolder first = async(null, 5_000L, { firstStarted.countDown(); release.await(); 1 } as Callable<Object>)
        firstStarted.await(2, TimeUnit.SECONDS)
        LaneResult second = ScriptLane.run(null, 1_000L, { 2 } as Callable<Object>)
        release.countDown()
        first.thread.join()

        then:
        second.status == 'OK'
        first.result.status == 'OK'
    }

    def "aRunCancelledBeforeItStartsLeaksNothing"() {
        given:
        System.setProperty('jexl_lane_grace_ms', '50')
        ScriptLane.resetForTests()
        AtomicBoolean ran = new AtomicBoolean(false)
        ScriptLane.beforeStart = { -> Thread.sleep(400) }

        when:
        LaneResult late = ScriptLane.run('A', 50L, { ran.set(true); 1 } as Callable<Object>)
        ScriptLane.beforeStart = null
        Thread.sleep(500)
        LaneResult next = ScriptLane.run('A', 1_000L, { 2 } as Callable<Object>)

        then:
        late.status == 'TIME'
        !late.stuck
        ScriptLane.stuckCount() == 0
        !ran.get()
        next.status == 'OK'
    }

    static class LaneResultHolder {
        Thread thread
        volatile LaneResult result
    }
}

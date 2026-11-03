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
import org.moqui.context.ExecutionContext
import org.moqui.util.SystemBinding
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.lang.management.ManagementFactory
import java.lang.management.MemoryPoolMXBean
import java.lang.management.MemoryType
import java.lang.management.MemoryUsage

/**
 * User scripts run last: one starts only while heap after GC is under script_hold_heap_percent (70) and process CPU is
 * under script_hold_cpu_percent (75); a threshold over 100 turns that check off. An application adds its own reasons
 * to wait (its job queue is full, say) with a service named in script_busy_service whose out-parameter busyReason is
 * text while scripts must wait and empty when they may run.
 */
@CompileStatic
class ScriptLoadGate {
    protected final static Logger logger = LoggerFactory.getLogger(ScriptLoadGate.class)

    // NOTE: live heap reads ~90% on a healthy JVM just before a young GC, so it is used only while heap after GC is unknown
    static final int LIVE_HEAP_PERCENT = 85

    /** {heapPercent, liveHeapPercent, cpuPercent}, null when unknown; replaced in tests */
    static Closure<Map<String, Object>> probe = { -> readLoad() }

    static int holdAtHeapPercent() { intSetting('script_hold_heap_percent', 70) }
    static int holdAtCpuPercent() { intSetting('script_hold_cpu_percent', 75) }
    /** how long a due scheduled run waits for room before it is skipped */
    static int scheduledMaxWaitMinutes() { intSetting('script_max_wait_minutes', 60) }
    static String busyServiceName() { SystemBinding.getPropOrEnv('script_busy_service') ?: null }

    /** Why scripts must wait right now, or null when there is room for one. */
    static String busyReason(ExecutionContext ec) {
        String applicationReason = applicationBusyReason(ec)
        if (applicationReason) return applicationReason

        Map<String, Object> load
        try {
            load = probe.call()
        } catch (Throwable t) {
            logger.warn("Could not read load for user scripts, letting them run", t)
            return null
        }
        Integer heap = load.heapPercent as Integer, liveHeap = load.liveHeapPercent as Integer, cpu = load.cpuPercent as Integer
        if (holds(heap, holdAtHeapPercent())) return "memory is ${heap}% used".toString()
        if (heap == null && holds(liveHeap, Math.max(holdAtHeapPercent(), LIVE_HEAP_PERCENT))) return "memory is ${liveHeap}% used".toString()
        if (holds(cpu, holdAtCpuPercent())) return "CPU is at ${cpu}%".toString()
        return null
    }

    static String busyMessage(String reason) { "The server is busy (${reason}), and scripts run last. Try again in a minute.".toString() }

    /** The application's reason to wait, from script_busy_service; a failing check lets scripts run. */
    private static String applicationBusyReason(ExecutionContext ec) {
        String serviceName = busyServiceName()
        if (!serviceName || ec == null) return null
        ec.message.pushErrors()
        try {
            Map<String, Object> out = ec.service.sync().name(serviceName).disableAuthz().call()
            if (ec.message.hasError()) {
                logger.warn("Script busy check ${serviceName} failed, letting scripts run: ${ec.message.errorsString}")
                return null
            }
            return out?.get('busyReason')?.toString() ?: null
        } catch (Throwable t) {
            logger.warn("Script busy check ${serviceName} failed, letting scripts run", t)
            return null
        } finally {
            ec.message.clearErrors()
            ec.message.popErrors()
        }
    }

    // NOTE: process CPU can read over 100% (seen at 140% on macOS), so over 100 means off, not unreachable
    private static boolean holds(Integer reading, int holdAt) { reading != null && holdAt <= 100 && reading >= holdAt }

    private static int intSetting(String name, int defaultValue) {
        String value = SystemBinding.getPropOrEnv(name)
        return value?.isInteger() ? value.toInteger() : defaultValue
    }

    private static Map<String, Object> readLoad() {
        Runtime runtime = Runtime.runtime
        int liveHeapPercent = (int) Math.round(100d * (runtime.totalMemory() - runtime.freeMemory()) / runtime.maxMemory())
        return [heapPercent: heapAfterGcPercent(runtime.maxMemory()), liveHeapPercent: liveHeapPercent,
                cpuPercent: processCpuPercent()] as Map<String, Object>
    }

    /**
     * Old-generation occupancy as of the last collection, the figure that means memory pressure; null when the JVM
     * does not report it.
     */
    static Integer heapAfterGcPercent(long heapMax) {
        try {
            MemoryPoolMXBean oldGen = ManagementFactory.memoryPoolMXBeans.find { MemoryPoolMXBean pool ->
                pool.type == MemoryType.HEAP && pool.collectionUsage != null && (pool.name =~ /(?i)old|tenured/)
            }
            MemoryUsage usage = oldGen?.collectionUsage
            if (usage == null || usage.used <= 0) return null
            long max = usage.max > 0 ? usage.max : heapMax
            return max > 0 ? (int) Math.round(100d * usage.used / max) : null
        } catch (Throwable ignored) {
            return null
        }
    }

    static Integer processCpuPercent() {
        try {
            def os = ManagementFactory.operatingSystemMXBean
            if (!(os instanceof com.sun.management.OperatingSystemMXBean)) return null
            double load = ((com.sun.management.OperatingSystemMXBean) os).processCpuLoad
            return load < 0 ? null : (int) Math.round(load * 100)
        } catch (Throwable ignored) {
            return null
        }
    }
}

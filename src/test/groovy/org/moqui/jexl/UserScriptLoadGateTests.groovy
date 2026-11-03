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

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/** User scripts take last priority: heap and CPU hold them back. UserScriptDeploymentTests covers script_busy_service. */
class UserScriptLoadGateTests extends Specification {
    private static final Map CALM = [heapPercent: 40, liveHeapPercent: 60, cpuPercent: 30]
    private final Closure original = ScriptLoadGate.probe

    def cleanup() {
        ScriptLoadGate.probe = original
        System.clearProperty('script_hold_heap_percent')
        System.clearProperty('script_hold_cpu_percent')
    }

    private static void holdAt(int heapPercent, int cpuPercent) {
        System.setProperty('script_hold_heap_percent', heapPercent.toString())
        System.setProperty('script_hold_cpu_percent', cpuPercent.toString())
    }

    private static void load(Map readings) { ScriptLoadGate.probe = { -> readings as Map<String, Object> } }

    def "heapOrCpuMakesScriptsWait"() {
        given:
        holdAt(70, 75)
        load(CALM)
        assertNull(ScriptLoadGate.busyReason(null))
        [[heapPercent: 72]: 'memory is 72% used', [cpuPercent: 90]: 'CPU is at 90%'].each { Map change, String said ->
            load(CALM + change)
            assertEquals(said, ScriptLoadGate.busyReason(null), "${change}")
        }
    }

    def "theDefaultsHoldAtSeventyAndSeventyFive"() {
        given:
        load(CALM + [heapPercent: 70])
        assertEquals('memory is 70% used', ScriptLoadGate.busyReason(null))
        load(CALM + [cpuPercent: 75])
        assertEquals('CPU is at 75%', ScriptLoadGate.busyReason(null))
    }

    def "liveHeapStandsInOnlyWhileHeapAfterGcIsUnknown"() {
        given:
        holdAt(70, 75)
        load(CALM + [heapPercent: null, liveHeapPercent: 84])
        assertNull(ScriptLoadGate.busyReason(null))
        load(CALM + [heapPercent: null, liveHeapPercent: 85])
        assertEquals('memory is 85% used', ScriptLoadGate.busyReason(null))
        load(CALM + [liveHeapPercent: 92])
        assertNull(ScriptLoadGate.busyReason(null), 'a young-GC spike in live heap does not hold scripts')
    }

    def "oneHundredOneSwitchesACheckOff"() {
        given:
        load(CALM + [heapPercent: null, liveHeapPercent: 99, cpuPercent: 140])
        holdAt(101, 101)
        assertNull(ScriptLoadGate.busyReason(null))
        holdAt(101, 95)
        assertEquals('CPU is at 140%', ScriptLoadGate.busyReason(null))
    }

    def "aBrokenReadingLetsScriptsRun"() {
        given:
        ScriptLoadGate.probe = { -> throw new IllegalStateException('no MXBean') }
        assertNull(ScriptLoadGate.busyReason(null))
    }

    def "theBusyMessageSaysScriptsRunLast"() {
        given:
        assertTrue(ScriptLoadGate.busyMessage('CPU is at 90%').contains('scripts run last'))
    }
}

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
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Two traps a genuine user fell into: list + list returned "[I@6eeb90e5..." as a success, and a misspelt field gave
 * "null operand: NullOperand". + on a list now joins or appends, and the error names the field, its line and the
 * fields the record does have.
 */
class UserScriptUserMistakesTests extends Specification {
    private static final Map ORDERS = [orders: [[orderId: 'SO-1', lines: [[sku: 'A', ordered: 5, shipped: 3, price: 2.5]]]]]

    private static Map jexl(String body, Object input = null) { ScriptSupervisor.evaluate('SCRIPT', body, input) }

    // -- (1) lists and +

    def "listPlusListJoinsTheLists"() {
        given:
        Map out = jexl('var l = [1]; l = l + [2, 3]; l')
        assertTrue(out.ok as boolean, "${out.error}")
        assertEquals([1, 2, 3], (out.result as List).collect { (it as Number).intValue() })
    }

    def "plusEqualsAppendsAnItemIncludingText"() {
        given:
        Map out = jexl("var l = []; l += 'a'; l += 'b'; l")
        assertTrue(out.ok as boolean, "${out.error}")
        assertEquals(['a', 'b'], out.result)
        Map maps = jexl("var rows = []; rows += {'sku': 'A'}; rows += {'sku': 'B'}; size(rows)")
        assertTrue(maps.ok as boolean, "${maps.error}")
        assertEquals(2, (maps.result as Number).intValue())
    }

    def "aListBuiltWithPlusIsStillCountedAndCapped"() {
        given:
        Map out = jexl('var l = []; for (var i : 1..20000) { l += i; } size(l)')
        assertFalse(out.ok as boolean)
        assertEquals('MEMORY', (out.error as Map).kind, "${out.error}")
    }

    def "appendingInALoopStaysCheapLikeAGenuineUserWouldWriteIt"() {
        given:
        // 6,000 appends (the genuine-user order-line count) must not cost a fresh copy each time.
        Map out = jexl('var rows = []; for (var i : 1..6000) { rows += i; } size(rows)')
        assertTrue(out.ok as boolean, "${out.error}")
        assertEquals(6000, (out.result as Number).intValue())
        assertTrue((out.units as long) < 50_000L, "units ${out.units}")
    }

    def "appendingToAListFromTheInputLeavesTheInputAlone"() {
        given:
        Map input = [lines: [1, 2]]
        Map out = jexl('var l = input.lines; l += 3; [size(l), size(input.lines)]', input)
        assertTrue(out.ok as boolean, "${out.error}")
        assertEquals([3, 2], (out.result as List).collect { (it as Number).intValue() })
    }

    def "plusNeverTurnsAListIntoTextAnyMore"() {
        given:
        ['var l = [1]; l = l + [2]; l', 'var l = [1]; l += 2; l', "var l = ['x']; l + 'y'"].each { String body ->
            Map out = jexl(body)
            assertFalse(String.valueOf(out.result).contains('@'), "${body} -> ${out.result}")
        }
    }

    def "plusOnAMapIsRefusedWithAClearMessage"() {
        given:
        Map out = jexl("var m = {'a': 1}; m + {'b': 2}")
        assertFalse(out.ok as boolean)
        assertEquals('ACCESS', (out.error as Map).kind)
        assertTrue(((out.error as Map).message as String).contains('map'), "${out.error}")
        Map num = jexl('1 + [2]')
        assertFalse(num.ok as boolean, 'a number plus a list is not a list operation')
        assertTrue(((num.error as Map).message as String).contains('list first'), "${num.error}")
    }

    def "numbersAndTextStillAddAsBefore"() {
        given:
        assertEquals(3, (jexl('1 + 2').result as Number).intValue())
        assertEquals('ab', jexl("'a' + 'b'").result)
        assertEquals('n=1', jexl("'n=' + 1").result)
    }

    // -- (2) a misspelt field

    def "jexlNamesTheMisspeltFieldItsLineAndTheRecordsFields"() {
        given:
        Map out = jexl('var t = 0;\nfor (var o : input.orders) {\n  for (var l : o.lines) { t = t + (l.ordred - l.shipped); }\n}\nt', ORDERS)
        assertFalse(out.ok as boolean)
        Map error = out.error as Map
        String message = error.message as String
        assertEquals('ERROR', error.kind)
        assertEquals('ordred', error.field)
        assertTrue(message.contains("'ordred'") && message.contains('line 3'), message)
        assertTrue(message.contains('ordered') && message.contains('shipped'), "lists the record's fields: ${message}")
        assertFalse(message.contains('NullOperand'), message)
    }

    def "jexlExplainsAnEmptyValueThatIsNotAMisspelling"() {
        given:
        Map out = jexl('var x = null;\nx + 1')
        assertFalse(out.ok as boolean)
        String message = (out.error as Map).message as String
        assertTrue(message.contains('empty') && message.contains('line 2'), message)
        assertFalse(message.contains('NullOperand'), message)
    }

    def "readingAnOptionalFieldWithADefaultStillWorks"() {
        given:
        Map out = jexl("var l = input.orders[0].lines[0]; (l.discount ?? 0) + l.ordered", ORDERS)
        assertTrue(out.ok as boolean, "${out.error}")
        assertEquals(5, (out.result as Number).intValue())
    }
}

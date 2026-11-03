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

import groovy.json.JsonOutput
import spock.lang.Specification

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/** The trial run on the sample input that store#UserScript does before it saves a script. */
class UserScriptCheckTests extends Specification {
    private static final String ORDERS = '{"orders": [{"orderId": "SO-1", "lines": [{"sku": "A", "ordered": 5, "shipped": 3}]}]}'

    private static Map check(String body, String sample = ORDERS) { ScriptSupervisor.check('SCRIPT', body, sample) }

    private static Map problem(Map out) {
        assertFalse(out.ok as boolean, "expected a refusal, got ${out}")
        return (out.problems as List<Map>)[0]
    }

    def "aGoodScriptPasses"() {
        given:
        Map out = check('''var owed = 0;
for (var o : input.orders) { for (var l : o.lines) { owed = owed + (l.ordered - l.shipped); } }
owed''')
        assertTrue(out.ok as boolean)
        assertEquals([], out.problems)
        assertEquals([], out.notes)
    }

    def "aMisspeltFieldIsRefusedWithItsLineAndTheRecordsFields"() {
        given:
        Map found = problem(check('''var t = 0;
for (var o : input.orders) {
  for (var l : o.lines) { t = t + (l.ordred - l.shipped); }
}
t'''))
        assertEquals('FAILS_ON_SAMPLE', found.rule)
        assertEquals(3, found.line)
        assertTrue((found.message as String).contains("'ordred' is not a field") && (found.message as String).contains('ordered'), "${found}")
    }

    def "aLoopThatNeverEndsIsRefusedWithTheUsualCause"() {
        given:
        Map found = problem(check('''var i = 0;
var t = 0;
while (i < size(input.orders)) { t = t + 1; }
t'''))
        assertEquals('FAILS_ON_SAMPLE', found.rule)
        assertEquals(3, found.line)
        assertTrue((found.message as String).contains('A loop that never ends'), "${found}")
    }

    def "textThatKeepsGrowingIsRefusedWithTheUsualCause"() {
        given:
        Map found = problem(check('''var s = 'xxxxxxxxxx';
for (var i : 1..30) { s = s + s; }
s'''))
        assertEquals('FAILS_ON_SAMPLE', found.rule)
        assertTrue((found.message as String).contains('keeps growing'), "${found}")
    }

    def "aMisspeltLibraryFunctionIsRefused"() {
        given:
        Map found = problem(check("fn:uper('abc')"))
        assertEquals('FAILS_ON_SAMPLE', found.rule)
        assertTrue((found.message as String).contains("'uper' is not a script function"), "${found}")
    }

    def "aScriptThatSpendsMostOfItsBudgetOnTheSampleIsRefused"() {
        given:
        // a naive nested scan, 500 payments x 500 orders: fine on a few rows, too heavy for real data
        String sample = JsonOutput.toJson([orders: (1..500).collect { [orderId: "SO-${it}"] }, payments: (1..500).collect { [orderId: "SO-${it}"] }])
        Map found = problem(check('''var matched = 0;
for (var p : input.payments) {
  for (var o : input.orders) { if (o.orderId == p.orderId) { matched = matched + 1; } }
}
matched''', sample))
        assertEquals('TOO_HEAVY', found.rule)
        assertTrue((found.message as String).contains('map keyed by id'), "${found}")
    }

    def "aSampleThatIsNotJsonIsRefused"() {
        given:
        assertEquals('BAD_SAMPLE', problem(check('1', '{oops')).rule)
    }

    def "withoutASampleNothingRuns"() {
        given:
        assertTrue(check('input.orders[0].ordred', null).ok as boolean)
    }

    def "whatATrialRunCannotSeeIsSaved"() {
        given:
        // the accepted trade-off: valid JEXL that runs cleanly on the sample, so nothing stops it
        assertTrue(check('var x = 1; if (x = 2) { 1 } else { 0 }').ok as boolean, '= in a condition')
        assertTrue(check('input.orders[0].ordred').ok as boolean, 'a misspelt field that is only returned reads as empty')
    }
}

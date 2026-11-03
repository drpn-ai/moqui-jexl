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
import org.junit.jupiter.api.function.Executable

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * The user script engine, no Moqui boot: what a script can do, what each profile refuses, and the hostile suite
 * (host access, runaway loops, memory growth, giant numbers, ReDoS). UserScriptLoadGateTests covers the busy gate.
 */
class UserScriptSupervisorTests extends Specification {

    private static final Map ORDER = [
            orderId : 'SO-10001',
            placedAt: '2026-10-01T10:00:00Z',
            billedAt: '2026-10-02T16:30:00Z',
            payments: [[tender: 'CARD', amount: '40.00'], [tender: 'GIFT_CARD', amount: 10], [tender: 'CARD', amount: '5.5']],
    ]

    private static Map run(String profile, String body, Object input = ORDER) {
        return ScriptSupervisor.evaluate(profile, body, input)
    }

    private static void assertRefused(Map out, String kind) {
        assertFalse(out.ok as boolean, "expected a ${kind} refusal, got ${out}")
        assertEquals(kind, (out.error as Map).kind, "wrong kind: ${out.error}")
    }

    // -- what a user can write

    def "anExpressionReadsTheInputThroughTheLibrary"() {
        given:
        Map out = run('EXPRESSION', "fn:sum(input.payments, 'amount') > fn:round('50', 2) && fn:hoursBetween(input.placedAt, input.billedAt) <= 36")
        assertTrue(out.ok as boolean, "${out}")
        assertEquals(true, out.result)
        assertTrue((out.units as long) > 0)
    }

    def "aScriptLoopsKeepsStateAndReturnsAMap"() {
        given:
        String body = '''
            var paid = 0;
            var byTender = {:};
            for (var p : input.payments) {
                paid = paid + fn:dec(p.amount);
                byTender[p.tender] = fn:dec(byTender[p.tender] ?? 0) + fn:dec(p.amount);
            }
            fn:log('paid ' + paid);
            { 'orderId': input.orderId, 'paid': fn:round(paid, 2), 'tenders': fn:join(fn:sorted(fn:keys(byTender)), '+'), 'byTender': byTender }
        '''
        Map out = run('SCRIPT', body)
        assertTrue(out.ok as boolean, "${out}")
        Map result = out.result as Map
        assertEquals('SO-10001', result.orderId)
        assertEquals(new BigDecimal('55.50'), result.paid)
        assertEquals('CARD+GIFT_CARD', result.tenders)
        assertEquals(new BigDecimal('45.50'), (result.byTender as Map).CARD)
        assertEquals(['paid 55.50'], out.logs)
    }

    def "aScriptCanUseLambdasAndTheListHelpers"() {
        given:
        Map out = run('SCRIPT', '''
            var big = (p) -> fn:dec(p.amount) >= 10;
            var n = 0;
            for (var p : input.payments) { if (big(p)) { n = n + 1 } }
            [n, size(fn:where(input.payments, 'tender', 'CARD')), fn:groupCount(input.payments, 'tender'), size(input.orderId)]
        ''')
        assertTrue(out.ok as boolean, "${out}")
        assertEquals([2, 2, [CARD: 2, GIFT_CARD: 1], 8], out.result)
    }

    def "jexlsOwnBuiltInsCoverWhatTheLibraryLeavesOut"() {
        given:
        // the fn: library has no len, begins, ends, min, max, abs, count or money because these do the same
        Map line = [sku: 'A-1', qty: -3, note: 'abcd']
        [
                "size(input.note)"                                   : 4,
                "size(input.payments)"                               : 3,
                "empty(input.missing)"                               : true,
                "input.line.sku =^ 'A-'"                             : true,
                "input.line.sku =\$ '-1'"                           : true,
                "'CARD' =~ ['CARD', 'CASH']"                         : true,
                "var q = input.line.qty; q < 0 ? -q : q"             : 3,
                "size(fn:where(input.payments, 'tender', 'CARD'))"    : 2,
                "fn:round('2.499', 2)"                              : new BigDecimal('2.50'),
        ].each { String body, Object expected ->
            Map out = run('SCRIPT', body, ORDER + [line: line, note: 'abcd'])
            assertTrue(out.ok as boolean, "${body} -> ${out}")
            assertEquals(expected, out.result, body)
        }
    }

    def "theSameInputAlwaysGivesTheSameResult"() {
        given:
        String body = "var t = 0; for (var p : input.payments) { t = t + fn:dec(p.amount) } t"
        assertEquals(run('SCRIPT', body).result, run('SCRIPT', body).result)
    }

    def "theInputIsReadOnlyAllTheWayDownAndALocalCopyIsNot"() {
        given:
        Map input = [items: [[qty: 1]]]
        assertRefused(run('SCRIPT', "input.items[0].qty = 99; input.items[0].qty", input), 'NOT_ALLOWED')
        Map out = run('SCRIPT', "var row = {'qty': input.items[0].qty}; row.qty = row.qty + 98; row.qty", input)
        assertTrue(out.ok as boolean, "${out}")
        assertEquals(99, out.result)
        assertEquals(1, ((input.items as List)[0] as Map).qty)
    }

    def "aRuntimeErrorNamesTheLine"() {
        given:
        Map out = run('SCRIPT', "var a = 1;\nvar b = fn:dec('not a number');\nb")
        assertFalse(out.ok as boolean)
        assertEquals('ERROR', (out.error as Map).kind)
        assertTrue((out.error as Map).message.toString().contains('not a number'), "${out.error}")
        assertEquals(2, (out.error as Map).line)
    }

    // -- profiles

    def "theExpressionProfileRefusesStatementsLoopsLambdasAndVariables"() {
        given:
        assertRefused(run('EXPRESSION', 'for (var p : input.payments) { 1 }'), 'NOT_ALLOWED')
        assertRefused(run('EXPRESSION', 'var x = 1'), 'NOT_ALLOWED')
        assertRefused(run('EXPRESSION', '(x) -> x'), 'NOT_ALLOWED')
        assertRefused(run('EXPRESSION', 'while (true) { }'), 'NOT_ALLOWED')
    }

    def "anUnknownProfileOrEmptyOrOversizedSourceIsRefusedBeforeRunning"() {
        given:
        assertRefused(run('GROOVY', '1'), 'INVALID')
        assertRefused(run('SCRIPT', '   '), 'INVALID')
        assertRefused(run('EXPRESSION', '1 + ' * 1000 + '1'), 'INVALID')
    }

    def "validateListsTheNamesAndRefusesAnythingButInput"() {
        given:
        Map ok = ScriptSupervisor.validate('SCRIPT', 'var x = input.a; x + 1')
        assertTrue(ok.ok as boolean, "${ok}")
        assertEquals(['input'], ok.variables)
        Map unknown = ScriptSupervisor.validate('SCRIPT', 'secret + input.a')
        assertRefused(unknown, 'ACCESS')
        assertTrue((unknown.error as Map).message.toString().contains("'secret'"))
    }

    // -- hostile suite: host access

    def "noMethodCallsNoConstructorsNoPragmas"() {
        given:
        assertRefused(run('SCRIPT', 'input.getClass()'), 'NOT_ALLOWED')
        assertRefused(run('SCRIPT', "''.class.forName('java.lang.Runtime')"), 'NOT_ALLOWED')
        assertRefused(run('SCRIPT', "input.orderId.length()"), 'NOT_ALLOWED')
        assertRefused(run('SCRIPT', "new('java.lang.ProcessBuilder', 'id')"), 'NOT_ALLOWED')
        assertRefused(run('SCRIPT', "#pragma jexl.namespace.rt java.lang.Runtime\n1"), 'NOT_ALLOWED')
        assertRefused(run('SCRIPT', "#pragma jexl.import java.lang\n1"), 'NOT_ALLOWED')
    }

    def "dotClassReachesNoJavaClass"() {
        given:
        // On a map, .class is just a key that is not there.
        Map onMap = run('SCRIPT', 'input.class')
        assertTrue(onMap.ok as boolean, "${onMap}")
        assertNull(onMap.result)
        ["'abc'.class", 'var n = 1; n.class', '(x -> x).class', 'input.payments.class', 'input.class.name'].each { String body ->
            Map out = run('SCRIPT', body)
            assertFalse(out.ok as boolean, "${body} must fail, got ${out}")
        }
    }

    def "theInputCannotBeReassigned"() {
        given:
        assertRefused(run('SCRIPT', 'input = 5'), 'NOT_ALLOWED')
    }

    def "groovyObjectMethodsOnTheLibraryAreUnreachable"() {
        given:
        assertFalse(run('SCRIPT', "fn:getMetaClass()").ok as boolean)
        assertFalse(run('SCRIPT', "fn:invokeMethod('dec', ['1'])").ok as boolean)
        assertFalse(run('SCRIPT', "fn:asList(input.payments, 'x')").ok as boolean)
    }

    def "permissionsAdmitOnlyTheLibraryAndTheArithmeticOverloads"() {
        given:
        ScriptPermissions permissions = ScriptPermissions.INSTANCE
        assertFalse(permissions.allow(Runtime))
        assertFalse(permissions.allow(HashMap))
        assertFalse(permissions.allow(String.getMethod('length')))
        assertFalse(permissions.allow(Object.getMethod('getClass')))
        assertFalse(permissions.allow(ScriptLibrary.getMethod('getMetaClass')))
        assertFalse(permissions.allow(ScriptLibrary.getDeclaredConstructor()))
        assertTrue(permissions.allow(ScriptLibrary.getMethod('dec', Object)))
        assertTrue(permissions.allow(ScriptArithmetic.getMethod('forEach', Object)))
        assertTrue(permissions.compose('java.lang.*').is(permissions), 'compose must not widen the set')
    }

    def "aShiftPastTheNumberCapIsRefusedBeforeItIsBuilt"() {
        given:
        // BigInteger.shiftLeft would build (or overflow on) a 2^31-bit number before any check after it ran
        assertRefused(run('SCRIPT', 'var big = 1; for (var i : 1..70) { big = big * 2; } big << 2147483647'), 'NUMBER')
        assertRefused(run('EXPRESSION', "1 << '2000000000'"), 'NUMBER')
        // a shift whose low 32 bits are 2^31 - 1 but whose long value is negative: checked as the whole number
        assertRefused(run('EXPRESSION', 'input.big << input.by', [big: new BigInteger('2').pow(70), by: new BigInteger('-4294967296') + 2147483647]), 'NUMBER')
        assertRefused(run('EXPRESSION', 'input.big << input.by', [big: new BigInteger('2').pow(70), by: new BigInteger('18446744073709551616') + 2000000000]), 'NUMBER')
        assertTrue(run('EXPRESSION', '1024 << -10').ok as boolean, 'a small negative shift is in range (Java long rules apply)')
        assertEquals(1024L, run('EXPRESSION', '1 << 10').result as long)
        // the amount is bounded by its type before it is converted: 1E999999999 as a BigInteger is a billion digits
        [new BigDecimal('1E999999999'), new BigDecimal('7.' + '1' * 100), new BigInteger('2').pow(4000), 1e300d, Double.NaN, '99999999999', [1]].each { Object amount ->
            try {
                ScriptArithmetic.shiftAmount(amount)
                assertTrue(false, "${amount} should be refused")
            } catch (ScriptBudget.LimitReached expected) {
                assertEquals('NUMBER', expected.kind)
            }
        }
        assertEquals(-3, ScriptArithmetic.shiftAmount(new BigDecimal('-3.7')))
        assertEquals(0, ScriptArithmetic.shiftAmount(new BigDecimal('1E-999999999')), 'under one is zero, never divided out')
        assertEquals(12, ScriptArithmetic.shiftAmount(' 12 '))
        assertEquals(256, ScriptArithmetic.shiftAmount(256L))
    }

    def "aHugeExponentLiteralIsRefusedBeforeTheScriptRuns"() {
        given:
        // 1E999999999B passes no input cap, and a failing script's source is rendered through DecimalFormat in full
        ['1E999999999B + 1', '1E-999999999B - 1', '1E999999999B % 7', '1E5000B', 'var x = 2.5E+0012345B; input.nope.nope'].each { String body ->
            Map out = run('SCRIPT', body)
            assertRefused(out, 'NOT_ALLOWED')
            assertEquals(1, (out.error as Map).line)
        }
        assertTrue(run('EXPRESSION', '1E300B * 2 > 1E299B').ok as boolean, 'an exponent up to 1000 stays available')
        assertTrue(run('EXPRESSION', "size('4E1200 is a product id')").ok as boolean, 'text that looks like a number is only text')
        assertTrue(run('EXPRESSION', '1e5000 > 1').ok as boolean, 'a double literal past its range is Infinity, nothing is built')
        // refused before JEXL parses: a parse error (a method call here) could otherwise render the literal
        Map parseTime = run('SCRIPT', 'var a = 1E999999999B; a.foo()')
        assertRefused(parseTime, 'NOT_ALLOWED')
        assertTrue(((parseTime.error as Map).message as String).contains('more than'), "${parseTime.error}")
        // text JEXL turns into a number is checked before it is computed with
        ["'1E999999999' - 1", "'1E999999999' * 2", "'1E-999999999' % 7", 'input.s - 1', 'input.s / 3'].each { String body ->
            assertRefused(run('EXPRESSION', body, [s: '1E999999999']), 'NUMBER')
        }
        assertEquals('41.5', String.valueOf(run('EXPRESSION', "'42.5' - 1").result))
        assertEquals('4E1200-A', run('EXPRESSION', "'4E1200' + '-A'").result, 'text that looks like a number still joins')
        // an input integer past the number cap is refused before an operator builds on it
        assertRefused(run('EXPRESSION', 'input.x + 1', [x: new BigInteger('2').pow(300)]), 'NUMBER')
        assertEquals(new BigDecimal('2.50'), run('EXPRESSION', '1.25B * 2').result)
    }

    // NOTE: '0' * 60 + '1E999999999' is 71 characters, past the old 64-character parse gate, yet reads as the same compact number
    private static final String PADDED_HUGE = '0' * 60 + '1E999999999'

    def "longPaddedExponentTextIsRefusedAsANumber"() {
        given:
        assertTrue(PADDED_HUGE.length() > 64)
        assertTimeoutPreemptively(Duration.ofSeconds(5), {
            // the check itself, and the integer conversion JEXL makes: neither may let the text through unparsed
            Map<String, String> outcomes = [:]
            outcomes['checkNumber'] = refusalKind { ScriptArithmetic.checkNumber(PADDED_HUGE) }
            outcomes['toBigInteger'] = refusalKind { new ScriptArithmetic().toBigInteger(PADDED_HUGE) }
            outcomes['toBigDecimal'] = refusalKind { new ScriptArithmetic().toBigDecimal(PADDED_HUGE) }
            // and every operator that converts the text
            ['input.s << 1', 'input.s * 2', 'input.s - 1', 'input.s / 3', 'input.s % 7', '1 - input.s'].each { String body ->
                Map out = run('EXPRESSION', body, [s: PADDED_HUGE])
                outcomes[body] = out.ok ? "ok: ${out.result}".toString() : (out.error as Map).kind as String
            }
            assertEquals(outcomes.keySet().collectEntries { [(it): 'NUMBER'] }, outcomes, 'every path refuses the text as a NUMBER')
        } as Executable)
    }

    def "longOrdinaryTextStillWorks"() {
        given:
        String left = 'order note ' * 10
        String right = 'x' * 70 + '-E9'
        Map out = run('EXPRESSION', 'input.a + input.b', [a: left, b: right])
        assertTrue(out.ok as boolean, "${out}")
        assertEquals(left + right, out.result)
        ScriptArithmetic.checkNumber(left)
        ScriptArithmetic.checkNumber(right)
        assertEquals('4E1200' * 20 + '-A', run('EXPRESSION', 'input.id + \'-A\'', [id: '4E1200' * 20]).result, 'long text that looks like a number still joins')
    }

    def "longOrdinaryNumberTextStillComputes"() {
        given:
        // 70 digits is under the 256-bit cap and the 1,000-digit decimal cap, so the check lets it through and it computes
        String seventyDigits = '1' + '0' * 69
        ScriptArithmetic.checkNumber(seventyDigits)
        // NOTE: JEXL rounds a decimal conversion to its 34-digit context; 1E+69 is the same value
        assertEquals(0, new BigDecimal(seventyDigits).compareTo(new ScriptArithmetic().toBigDecimal(seventyDigits)))
        Map decimal = run('EXPRESSION', 'input.s - 0.5', [s: seventyDigits])
        assertTrue(decimal.ok as boolean, "${decimal}")
        // NOTE: an integer conversion of it stays refused by JEXL's own 34-digit integer precision, as it was before
        // zero-padded past 64 characters, inside that precision: computes as the number it reads as
        String padded = '0' * 40 + '123456789012345678901234567890'
        assertTrue(padded.length() > 64)
        assertEquals(new BigInteger('123456789012345678901234567890'), new ScriptArithmetic().toBigInteger(padded))
        assertEquals('123456789012345678901234567889', String.valueOf(run('EXPRESSION', 'input.s - 1', [s: padded]).result))
        assertEquals('246913578024691357802469135780', String.valueOf(run('EXPRESSION', 'input.s * 2', [s: padded]).result))
        assertEquals('41', String.valueOf(run('EXPRESSION', 'input.s - 1', [s: '0' * 80 + '42']).result))
    }

    /** the LimitReached kind a call throws, 'none' when it returns, or the other exception's class */
    private static String refusalKind(Closure call) {
        try {
            call()
            return 'none'
        } catch (ScriptBudget.LimitReached e) {
            return e.kind
        } catch (Exception e) {
            return e.getClass().simpleName
        }
    }

    def "textPatternMatchingIsRefused"() {
        given:
        assertRefused(run('SCRIPT', "'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!' =~ '(a+)+b'"), 'ACCESS')
        Map inList = run('EXPRESSION', "'CARD' =~ ['CARD', 'CASH']")
        assertTrue(inList.ok as boolean, "membership in a list stays available: ${inList}")
    }

    // -- hostile suite: runaway work and memory

    def "anEmptyEndlessLoopRunsOutOfUnits"() {
        given:
        assertRefused(run('SCRIPT', 'while (true) { }'), 'UNITS')
    }

    def "aHugeRangeRunsOutOfUnits"() {
        given:
        assertRefused(run('SCRIPT', 'for (var i : 1..100000000) { }'), 'UNITS')
    }

    def "endlessRecursionIsStopped"() {
        given:
        Map out = run('SCRIPT', 'var f = (n) -> f(n + 1); f(0)')
        assertFalse(out.ok as boolean, "${out}")
    }

    def "doublingTextHitsTheTextCap"() {
        given:
        assertRefused(run('SCRIPT', "var s = 'x'; while (true) { s = s + s }"), 'MEMORY')
    }

    def "squaringANumberHitsTheNumberCap"() {
        given:
        assertRefused(run('SCRIPT', 'var n = 3; while (true) { n = n * n }'), 'NUMBER')
    }

    def "growingAMapHitsTheCollectionCap"() {
        given:
        assertRefused(run('SCRIPT', 'var m = {:}; for (var i : 1..20000) { m[i] = i } m'), 'MEMORY')
    }

    def "returningAFunctionIsNotData"() {
        given:
        assertRefused(run('SCRIPT', 'var f = (x) -> x; f'), 'OUTPUT')
    }

    def "anOversizedResultIsRefused"() {
        given:
        Map out = run('SCRIPT', "var m = {:}; for (var i : 1..5000) { m[i] = 'xxxxxxxxxxxxxxxxxxxx' } m")
        assertRefused(out, 'MEMORY')
    }

    // -- hostile suite: caps hold at the peak allocation, not after it

    def "replaceIsRefusedBeforeItBuildsAnOversizedText"() {
        given:
        Map input = [t: 'a' * 1000, big: 'x' * 1000]
        assertRefused(run('SCRIPT', "fn:replace(input.t, 'a', input.big)", input), 'MEMORY')
    }

    def "joinIsRefusedBeforeItBuildsAnOversizedText"() {
        given:
        Map input = [parts: (1..100).collect { 'y' * 1000 }]
        assertRefused(run('SCRIPT', "fn:join(input.parts, '')", input), 'MEMORY')
        assertFalse(run('SCRIPT', "fn:join([input.parts], ',')", input).ok as boolean, 'join refuses a list of lists')
    }

    def "selfAddDoublingHitsTheTextCap"() {
        given:
        assertRefused(run('SCRIPT', "var s = 'x'; while (true) { s += s }"), 'MEMORY')
    }

    def "textCannotBeJoinedWithACollection"() {
        given:
        assertRefused(run('SCRIPT', "'paid: ' + input.payments"), 'ACCESS')
        Map ok = run('SCRIPT', "'paid: ' + fn:str(input.payments[0])")
        assertTrue(ok.ok as boolean, "${ok}")
        assertTrue(ok.result.toString().startsWith('paid: {tender: CARD'), "${ok.result}")
    }

    def "aHugeRangeIsNeverCopiedIntoMemory"() {
        given:
        assertRefused(run('SCRIPT', 'fn:sum(1..100000000)'), 'MEMORY')
        assertRefused(run('SCRIPT', 'size(fn:distinct(1..100000000))'), 'MEMORY')
    }

    def "aHugeRangeReturnedAsTheResultIsRefusedInsideTheBudget"() {
        given:
        assertRefused(run('SCRIPT', '1..100000000'), 'MEMORY')
        assertRefused(run('EXPRESSION', '1..100000000'), 'MEMORY')
    }

    def "anyBacktickIsRefusedWithItsLine"() {
        given:
        Map out = run('SCRIPT', 'var s = \'x\';\nvar t = `${s}${s}`;\nt')
        assertRefused(out, 'NOT_ALLOWED')
        assertEquals(2, (out.error as Map).line)
        // a regex literal holding a quote cannot hide a template from the check
        assertRefused(run('SCRIPT', 'var r = ~/\'/;\nvar s = \'x\';\n`${s}${s}`'), 'NOT_ALLOWED')
        assertRefused(run('SCRIPT', "'a ` in text'"), 'NOT_ALLOWED')
    }

    // -- hostile suite: work that hides inside one operation

    def "deepEqualityIsChargedAtEveryDepth"() {
        given:
        List rows = (1..100).collect { int i -> (1..100).collectEntries { int j -> ["k${j}".toString(), i * j] } }
        Map out = run('SCRIPT', 'input.a == input.b', [a: rows, b: rows.collect { new LinkedHashMap(it) }])
        assertTrue(out.ok as boolean, "${out}")
        assertEquals(true, out.result)
        assertTrue((out.units as long) >= 10_000, "a 10,000-value comparison must cost at least 10,000 units, cost ${out.units}")
        assertEquals(false, run('SCRIPT', 'input.a == input.b', [a: [[x: 1]], b: [[x: 2]]]).result)
    }

    def "aHugeExponentDecimalNeverPrintsItsDigits"() {
        given:
        assertRefused(run('SCRIPT', 'fn:str(input.x)', [x: new BigDecimal('1E999999999')]), 'INPUT')
        assertRefused(run('SCRIPT', "fn:str(fn:dec('1E999999999'))"), 'NUMBER')
        assertRefused(run('SCRIPT', "fn:dec('1E900') * fn:dec('1E900')"), 'NUMBER')
        Map fine = run('SCRIPT', "fn:str(fn:dec('1E20'))")
        assertTrue(fine.ok as boolean, "${fine}")
        assertEquals('100000000000000000000', fine.result)
    }

    def "oneSharedInputStringCannotInflateTheResult"() {
        given:
        Map out = run('SCRIPT', "{'a': input.big, 'b': input.big, 'c': input.big}", [big: 'z' * 30_000])
        assertRefused(out, 'MEMORY')
        assertTrue((out.error as Map).message.toString().contains('characters of JSON'), "${out.error}")
    }

    def "aRangeWhoseSizeOverflowsIsStillCapped"() {
        given:
        assertRefused(run('SCRIPT', 'fn:sum(1..3000000000)'), 'MEMORY')
        assertRefused(run('SCRIPT', '1..3000000000'), 'MEMORY')
    }

    def "logRendersAMapWithoutItsToString"() {
        given:
        Map out = run('SCRIPT', 'fn:log(input.payments); 1')
        assertTrue(out.ok as boolean, "${out}")
        assertTrue((out.logs as List)[0].toString().startsWith('[{tender: CARD, amount: 40.00}'), "${out.logs}")
    }

    // -- a question over a list of records: units backordered per SKU

    static final String BACKORDERS_BY_SKU = '''
        var bySku = {:};
        for (var order : input.orders) {
            for (var line : order.itemsBackordered ?? []) {
                var row = bySku[line.sku] ?? {'sku': line.sku, 'units': 0, 'lines': 0};
                row.units = row.units + fn:dec(line.quantity);
                row.lines = row.lines + 1;
                bySku[line.sku] = row;
            }
        }
        { 'skus': size(bySku), 'units': fn:sum(fn:vals(bySku), 'units'), 'bySku': fn:sortBy(fn:vals(bySku), '-units') }
    '''

    def "backordersPerSkuFromOrders"() {
        given:
        List orders = [
                [orderId: 'SO1', itemsBackordered: [[quantity: '1', sku: 'A:S']]],
                [orderId: 'SO2', itemsBackordered: [[quantity: '2', sku: 'A:S'], [quantity: '1', sku: 'B:M']]],
                [orderId: 'SO3', itemsBackordered: []],
                [orderId: 'SO4'],
        ]
        Map out = run('SCRIPT', BACKORDERS_BY_SKU, [orders: orders])
        assertTrue(out.ok as boolean, "${out}")
        Map result = out.result as Map
        assertEquals(2, result.skus)
        assertEquals(new BigDecimal('4'), result.units)
        assertEquals([[sku: 'A:S', units: new BigDecimal('3'), lines: 2], [sku: 'B:M', units: new BigDecimal('1'), lines: 1]], result.bySku)
    }

    def "sortByTakesAMoquiOrderByWithinBounds"() {
        given:
        Map out = run('EXPRESSION', "fn:pluck(fn:sortBy([{'k': 'b', 'n': 2}, {'k': 'a', 'n': 10}, {'k': 'c', 'n': 1}], '-n'), 'k')")
        assertEquals(['a', 'b', 'c'], out.result)
        assertEquals(['a', 'b', 'c'], run('EXPRESSION', "fn:pluck(fn:sortBy([{'k': 'c'}, {'k': 'a'}, {'k': 'b'}], 'k'), 'k')").result)
        assertFalse(run('EXPRESSION', "fn:sortBy([1, 2], 'k')").ok as boolean, 'sortBy takes maps')
        // Moqui's comparator is unmetered, so the order-by is bounded before it sorts
        assertFalse(run('EXPRESSION', "fn:sortBy([{'k': 1}], '" + (['k'] * 6).join(',') + "')").ok as boolean, 'at most 5 fields')
        assertFalse(run('EXPRESSION', "fn:sortBy([{'k': 1}], '-')").ok as boolean, 'a bare prefix is not a field')
        assertEquals(['a', 'c', 'b'], run('EXPRESSION', "fn:pluck(fn:sortBy([{'k': 'b', 'n': 1}, {'k': 'c', 'n': 2}, {'k': 'a', 'n': 2}], '-n,k'), 'k')").result)
    }

    // -- the choke point

    def "onlyTheSandboxClassesReferenceJexl"() {
        given:
        // the script-runner, the scheduler and the services reach JEXL only through ScriptSupervisor.evaluate
        List<String> sandbox = ['ScriptArithmetic.groovy', 'ScriptPermissions.groovy', 'ScriptSupervisor.groovy']
        Path main = Paths.get('src/main/groovy')
        List<String> offenders = []
        Files.walk(main).filter { Path f -> f.toString().endsWith('.groovy') || f.toString().endsWith('.java') }.each { Path f ->
            if (Files.readString(f).contains('org.apache.commons.jexl3') && !(f.fileName.toString() in sandbox)) {
                offenders << main.relativize(f).toString()
            }
        }
        assertEquals([], offenders, 'jexl3 may be referenced only from ' + sandbox)
    }
}

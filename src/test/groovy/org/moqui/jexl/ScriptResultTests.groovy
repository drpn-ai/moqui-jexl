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

import org.moqui.jexl.script.ScriptResult
import spock.lang.Specification

import java.nio.charset.StandardCharsets

/** What a script returns, checked and capped, and its files rendered as CSV a spreadsheet opens safely. */
class ScriptResultTests extends Specification {
    static final ScriptProfile P = ScriptProfiles.get('SCRIPT')

    private static Map file(String name, List columns, List rows) { [name: name, columns: columns, rows: rows] }

    def "nothingMeansNothing"() {
        expect:
        ScriptResult.normalize(null, P).nothing
        ScriptResult.normalize('', P).nothing
        ScriptResult.normalize([:], P).nothing
    }

    def "aBareStringIsASummary"() {
        expect:
        ScriptResult.normalize('3 late orders', P) == [summary: '3 late orders', files: [], data: null]
    }

    def "aNumberIsASummaryToo"() {
        expect:
        ScriptResult.normalize(42, P).summary == '42'
    }

    def "aFullResultKeepsItsParts"() {
        when:
        Map out = ScriptResult.normalize([summary: 'two', files: [file('late', ['id'], [['A'], ['B']])], data: [n: 2]], P)

        then:
        out.summary == 'two'
        out.files[0].name == 'late.csv'
        out.files[0].rows.size() == 2
        out.data == [n: 2]
    }

    def "tooManyFilesIsInvalid"() {
        when:
        Map out = ScriptResult.normalize([summary: 's', files: (1..6).collect { file("f${it}.csv", ['a'], [['x']]) }], P)

        then:
        out.invalid
        out.message.contains('at most 5 files')
    }

    def "aRaggedRowIsInvalid"() {
        when:
        Map out = ScriptResult.normalize([files: [file('x.csv', ['a', 'b'], [['1', '2'], ['3']])]], P)

        then:
        out.invalid
        out.message.contains('x.csv')
        out.message.contains('row 2')
    }

    def "aBadFileNameIsInvalid"() {
        expect:
        ScriptResult.normalize([files: [file('../x', ['a'], [['1']])]], P).invalid
    }

    def "summaryOverTheCapIsInvalid"() {
        expect:
        ScriptResult.normalize([summary: 'x' * 4_001], P).invalid
    }

    def "rowsOverTheProfileCapAreInvalid"() {
        given:
        System.setProperty('jexl_profile_SCRIPT_maxCollectionSize', '2')

        expect:
        ScriptResult.normalize([files: [file('x.csv', ['a'], [['1'], ['2'], ['3']])]], ScriptProfiles.get('SCRIPT')).invalid

        cleanup:
        System.clearProperty('jexl_profile_SCRIPT_maxCollectionSize')
    }

    def "csvCellsAreQuotedAndFormulaSafe"() {
        when:
        byte[] csv = ScriptResult.toCsv(file('x.csv', ['a', 'b'], [['x,y', 'say "hi"'], ['=SUM(A1)', 'line1\nline2'], [null, 7]]))

        then:
        new String(csv, StandardCharsets.UTF_8) == '﻿a,b\r\n"x,y","say ""hi"""\r\n\'=SUM(A1),"line1\nline2"\r\n,7\r\n'
    }

    def "everyFormulaLeadIsNeutralised"() {
        expect:
        ['+1', '-1', '@x', '=1'].every {
            new String(ScriptResult.toCsv(file('x.csv', ['a'], [[it]])), StandardCharsets.UTF_8).endsWith("'${it}\r\n")
        }
    }

    def "aFormulaBehindWhitespaceOrAControlCharacterIsNeutralised"() {
        expect:
        ['\t=1', '\r=1', ' =1', '\u0001+1', '\uFF1D1', '\uFF0B1', '\uFF0D1', '\uFF201'].every { String cell ->
            String csv = new String(ScriptResult.toCsv(file('x.csv', ['a'], [[cell]])), StandardCharsets.UTF_8)
            csv.contains("'" + cell) || csv.contains('"\'' + cell)
        }
    }

    def "ordinaryTextIsLeftAlone"() {
        expect:
        new String(ScriptResult.toCsv(file('x.csv', ['a'], [['plain'], [' leading space']])), StandardCharsets.UTF_8) == '\uFEFFa\r\nplain\r\n leading space\r\n'
    }

    def "invisibleLeadsAndPipesAreNeutralisedToo"() {
        expect:
        ['\u200B=1', '\u00A0=1', '\uFEFF+1', '|cmd'].every { String cell ->
            new String(ScriptResult.toCsv(file('x.csv', ['a'], [[cell]])), StandardCharsets.UTF_8).contains("'" + cell)
        }
    }

    def "numbersAreNeverPrefixed"() {
        expect:
        new String(ScriptResult.toCsv(file('x.csv', ['a'], [[-5], [-1.5G]])), StandardCharsets.UTF_8) == '\uFEFFa\r\n-5\r\n-1.5\r\n'
    }
}

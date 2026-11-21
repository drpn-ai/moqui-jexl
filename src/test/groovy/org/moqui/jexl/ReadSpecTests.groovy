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

import org.moqui.jexl.script.ReadConditions
import org.moqui.jexl.script.ReadSpec
import spock.lang.Specification
import spock.lang.Timeout
import spock.lang.Unroll

/** The read spec (inputs a script reads before it runs) and its conditions, matched and grouped in memory. */
class ReadSpecTests extends Specification {
    static final String EXAMPLE = '''{ "inputs": [
        { "name": "orders", "source": "entity", "entityName": "mantle.order.OrderHeader",
          "fields": ["orderId", "statusId", "grandTotal", "placedDate"],
          "where": { "and": [ { "field": "statusId", "op": "in", "value": ["OrderApproved"] } ] },
          "window": { "field": "placedDate", "lastDays": 30 },
          "groupBy": ["statusId"], "measures": [ { "fn": "sum", "field": "grandTotal", "as": "total" }, { "fn": "count", "as": "n" } ],
          "orderBy": ["-total"] } ] }'''

    private static String why(String json) {
        try { ReadSpec.parse(json); return 'parsed' } catch (ReadSpec.Invalid e) { return e.message }
    }

    def "aValidSpecParses"() {
        when:
        ReadSpec spec = ReadSpec.parse(EXAMPLE)
        ReadSpec.Input input = spec.inputs[0]

        then:
        input.name == 'orders'
        input.source == 'entity'
        input.entityName == 'mantle.order.OrderHeader'
        input.fields == ['orderId', 'statusId', 'grandTotal', 'placedDate']
        input.window == [field: 'placedDate', lastDays: 30]
        input.groupBy == ['statusId']
        input.measures*.as == ['total', 'n']
        input.orderBy == ['-total']
        input.raw.entityName == 'mantle.order.OrderHeader'
    }

    def "anAppSourceKeepsItsOwnFields"() {
        expect:
        ReadSpec.parse('{"inputs":[{"name":"rows","source":"kept","datasetId":"D1","fields":["a"]}]}').inputs[0].raw.datasetId == 'D1'
    }

    def "badSpecsSayWhy"() {
        expect:
        why('{"inputs":' + (1..11).collect { "{\"name\":\"i${it}\",\"source\":\"entity\",\"entityName\":\"x.Y\",\"fields\":[\"a\"]}" }.toString() + '}').contains('at most 10')
        why('{"inputs":[{"name":"a","source":"entity","entityName":"x.Y","fields":["f"]},{"name":"a","source":"entity","entityName":"x.Y","fields":["f"]}]}').contains("'a' twice")
        why('{"inputs":[{"name":"a","source":"entity","entityName":"x.Y","fields":["f"],"where":{"field":"f","op":"regex","value":"x"}}]}').contains('regex')
        why('{"inputs":[{"name":"a","source":"entity","entityName":"x.Y","fields":["f"],"window":{"field":"f","lastDays":0}}]}').contains('lastDays')
        why('{"inputs":[{"name":"A b","source":"entity","entityName":"x.Y","fields":["f"]}]}').contains('name')
        why('{"inputs":[{"name":"a","source":"entity","fields":["f"]}]}').contains('entityName')
        why('{"inputs":[{"name":"a","source":"entity","entityName":"x.Y"}]}').contains('fields')
        why('{"inputs":[{"name":"a","source":"entity","entityName":"x.Y","measures":[{"fn":"sum","as":"t"}]}]}').contains('field')
        why('not json').contains('JSON')
    }

    def "onlyMeasuresNeedNoFields"() {
        expect:
        why('{"inputs":[{"name":"a","source":"entity","entityName":"x.Y","measures":[{"fn":"count","as":"n"}]}]}') == 'parsed'
    }

    @Unroll
    def "conditionsMatchInMemory #where on #record is #expected"() {
        expect:
        ReadConditions.matches(where, record) == expected

        where:
        where                                                       | record             || expected
        [field: 'a', op: 'equals', value: 1]                        | [a: 1]             || true
        [field: 'a', op: 'not-equals', value: 1]                    | [a: 2]             || true
        [field: 'a', op: 'less', value: 5]                          | [a: 4]             || true
        [field: 'a', op: 'greater-equals', value: 5]                | [a: 5]             || true
        [field: 'a', op: 'like', value: 'A%']                       | [a: 'Abc']         || true
        [field: 'a', op: 'like', value: 'A_c']                      | [a: 'Abc']         || true
        [field: 'a', op: 'like', value: 'a%']                       | [a: 'Abc']         || false
        [field: 'a', op: 'not-like', value: 'A%']                   | [a: 'Bc']          || true
        [field: 'a', op: 'between', value: [1, 5]]                  | [a: 5]             || true
        [field: 'a', op: 'in', value: ['x', 'y']]                   | [a: 'y']           || true
        [field: 'a', op: 'in', value: ['x']]                        | [a: null]          || false
        [field: 'a', op: 'not-in', value: ['x']]                    | [a: 'z']           || true
        [field: 'a', op: 'is-null']                                 | [:]                || true
        [field: 'a', op: 'is-not-null']                             | [a: 0]             || true
        [field: 'a', op: 'greater', value: 1]                       | [a: null]          || false
        [and: [[field: 'a', op: 'equals', value: 1], [or: [[field: 'b', op: 'equals', value: 2], [field: 'b', op: 'equals', value: 3]]]]] | [a: 1, b: 3] || true
        [and: [[field: 'a', op: 'equals', value: 1], [field: 'b', op: 'equals', value: 2]]] | [a: 1, b: 3] || false
        [field: 'a', op: 'equals', value: '10']                     | [a: 10]            || true
    }

    def "conditionsAreValidated"() {
        when:
        ReadConditions.validate([field: 'a', op: 'in', value: (1..1001).toList()])

        then:
        ReadSpec.Invalid e = thrown()
        e.message.contains('1000')
    }

    def "tooDeepIsRefused"() {
        given:
        Map where = [field: 'a', op: 'equals', value: 1]
        9.times { where = [and: [where]] }

        when:
        ReadConditions.validate(where)

        then:
        thrown(ReadSpec.Invalid)
    }

    def "groupSumsAndCounts"() {
        expect:
        ReadConditions.group([[s: 'A', t: 2], [s: 'A', t: 3], [s: 'B', t: 1]], ['s'],
                [[fn: 'sum', field: 't', as: 'total'], [fn: 'count', as: 'n']]) == [[s: 'A', total: 5, n: 2], [s: 'B', total: 1, n: 1]]
    }

    def "groupMinMaxAvg"() {
        expect:
        ReadConditions.group([[t: 2], [t: 4]], [], [[fn: 'min', field: 't', as: 'lo'], [fn: 'max', field: 't', as: 'hi'],
                [fn: 'avg', field: 't', as: 'mean']]) == [[lo: 2, hi: 4, mean: 3]]
    }

    @Timeout(2)
    def "likeMatchingIsLinear"() {
        expect:
        !ReadConditions.matches([field: 'a', op: 'like', value: '%a%a%a%a%a%a%a%a%a%a%a%a%b'], [a: 'a' * 20_000])
        ReadConditions.matches([field: 'a', op: 'like', value: '%a%a%b'], [a: 'xaxxaxxb'])
        ReadConditions.matches([field: 'a', op: 'like', value: 'a%'], [a: 'a'])
        !ReadConditions.matches([field: 'a', op: 'like', value: '_'], [a: ''])
        ReadConditions.matches([field: 'a', op: 'like', value: '%'], [a: ''])
        ReadConditions.matches([field: 'a', op: 'like', value: 'a.b'], [a: 'a.b'])
        !ReadConditions.matches([field: 'a', op: 'like', value: 'a.b'], [a: 'axb'])
    }
}

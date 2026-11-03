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

import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.util.ContextStack
import spock.lang.Shared
import spock.lang.Specification

/** The .jexl script-runner: a .jexl location runs sandboxed wherever Moqui runs a script. */
class JexlScriptRunnerTests extends Specification {
    static final String DIR = 'classpath://script/moqui/jexl/test/'

    @Shared ExecutionContext ec

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.user.loginUser("john.doe", "moqui")
    }

    def cleanupSpec() {
        ec.user.logoutUser()
        ec.destroy()
    }

    def setup() { ec.artifactExecution.disableAuthz() }

    def cleanup() {
        ec.message.clearAll()
        ec.artifactExecution.enableAuthz()
    }

    def "a service with type script and a .jexl location runs in the sandbox, its returned map is the out-parameters"() {
        when:
        Map out = ec.service.sync().name('moqui.jexl.test.ScriptTestServices.sum#Lines')
                .parameters([lines: [[sku: 'A', quantity: 2], [sku: 'B', quantity: 3.5]]]).call()

        then:
        !ec.message.hasError()
        out.total == new BigDecimal('5.5')
        out.lineCount == 2
    }

    def "a .jexl script reads the context's plain values as input, and never ec or the context itself"() {
        given:
        ec.context.push()
        ec.context.putAll([name: 'north', tags: ['a', 'b']])

        when:
        Map result = ec.resource.script(DIR + 'ReadsContext.jexl', null) as Map

        then:
        !ec.message.hasError()
        result.name == 'north'
        result.tags == ['a', 'b']
        result.ec == 'absent'
        result.context == 'absent'

        cleanup:
        ec.context.pop()
    }

    def "a .jexl script that hits a limit adds an error naming the file and returns null"() {
        when:
        Object result = ec.resource.script(DIR + 'RunsForever.jexl', null)

        then:
        result == null
        ec.message.hasError()
        ec.message.errorsString.contains('RunsForever.jexl')
    }

    def "only plain values reach input"() {
        given:
        ContextStack stack = new ContextStack()
        stack.putAll([text: 'x', number: 1, flag: true, none: null, date: new Date(0), nested: [list: [1, [k: 'v']]],
                host: new Object(), mixed: [ok: 1, host: new Object()], stack: new ContextStack()])

        when:
        Map plain = JexlScriptRunner.plainValues(stack)

        then:
        plain.keySet() == ['text', 'number', 'flag', 'none', 'date', 'nested'] as Set
    }
}

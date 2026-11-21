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
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import spock.lang.Shared
import spock.lang.Specification

/** The read step: a script's declared inputs, read from sources as the owner, in the lane, checked every run. */
class ReadStepTests extends Specification {
    static final String SVC = 'moqui.jexl.ScriptServices'
    static final String FIXED = 'moqui.jexl.test.ScriptTestServices.read#FixedSource'

    @Shared ExecutionContext ec

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.user.loginUser('john.doe', 'moqui')
        System.setProperty('script_hold_heap_percent', '101')
        System.setProperty('script_hold_cpu_percent', '101')
        ec.artifactExecution.disableAuthz()
        ec.service.sync().name("${SVC}.store#ScriptSource").parameters([sourceName: 'fixed', serviceName: FIXED]).call()
        // the owner reads through a source only with a grant on it: the test source joins JEXL_SOURCES
        ec.entity.makeValue('moqui.security.ArtifactGroupMember').setAll([artifactGroupId: 'JEXL_SOURCES', artifactName: FIXED,
                nameIsPattern: 'N', artifactTypeEnumId: 'AT_SERVICE', inheritAuthz: 'N']).createOrUpdate()
        ec.artifactExecution.enableAuthz()
    }

    def cleanupSpec() {
        ec.artifactExecution.disableAuthz()
        ec.entity.find('jexl.script.ScriptSource').condition('sourceName', 'in', ['fixed', 'rogue']).deleteAll()
        ec.entity.find('moqui.security.ArtifactGroupMember').condition([artifactGroupId: 'JEXL_SOURCES', artifactName: FIXED]).deleteAll()
        ec.entity.find('jexl.script.UserScript').condition('userScriptId', 'like', 'USS_READ_%').deleteAll()
        ec.artifactExecution.enableAuthz()
        ['script_hold_heap_percent', 'script_hold_cpu_percent', 'jexl_read_timeout_ms'].each { System.clearProperty(it) }
        ec.user.logoutUser()
        ec.destroy()
    }

    private Map run(Map readSpec, String body) {
        Map out = ec.service.sync().name("${SVC}.run#UserScript").parameters([readSpecJson: JsonOutput.toJson(readSpec), scriptBody: body]).call()
        String errors = ec.message.errorsString
        ec.message.clearErrors()
        return out + [errors: errors]
    }

    def "aScriptReadsItsDeclaredInputs"() {
        when:
        Map out = run([inputs: [[name: 'lines', source: 'fixed', fields: ['line', 'qty'], where: [field: 'qty', op: 'greater', value: 1]]]],
                "'' + size(input.lines)")

        then:
        out.ok
        out.scriptResult == '2'
        out.normalizedResult.summary == '2'
        out.readCounts == [lines: 2]
    }

    def "theReadRunsAsTheOwner"() {
        when:
        Map out = run([inputs: [[name: 'who', source: 'fixed', fields: ['userId'], echoUser: true]]], 'input.who[0].userId')

        then:
        out.ok
        out.scriptResult == ec.user.userId
    }

    def "aSourceThatIsNotAnInterfaceImplIsRefused"() {
        when:
        ec.service.sync().name("${SVC}.store#ScriptSource").parameters([sourceName: 'rogue',
                serviceName: 'moqui.jexl.test.ScriptTestServices.delete#Everything']).call()
        String storeErrors = ec.message.errorsString
        ec.message.clearErrors()
        ec.artifactExecution.disableAuthz()
        ec.entity.makeValue('jexl.script.ScriptSource').setAll([sourceName: 'rogue',
                serviceName: 'moqui.jexl.test.ScriptTestServices.delete#Everything']).createOrUpdate()
        ec.artifactExecution.enableAuthz()
        Map out = run([inputs: [[name: 'x', source: 'rogue', fields: ['a']]]], 'input.x')

        then:
        storeErrors.contains('does not implement moqui.jexl.ScriptServices.read#ScriptSource')
        !out.ok
        out.error.kind == 'READ_ERROR'
        out.error.message.contains('does not implement')
    }

    def "anUnknownSourceIsAReadError"() {
        when:
        Map out = run([inputs: [[name: 'x', source: 'nowhere', fields: ['a']]]], 'input.x')

        then:
        !out.ok
        out.error.kind == 'READ_ERROR'
        out.error.message.contains('nowhere')
    }

    def "readTimeIsCapped"() {
        given:
        System.setProperty('jexl_read_timeout_ms', '200')

        when:
        Map out = run([inputs: [[name: 'slow', source: 'fixed', fields: ['line'], sleepMs: 2000]]], 'input.slow')

        then:
        !out.ok
        out.error.kind == 'READ_ERROR'
        out.error.message.contains('took longer than')

        cleanup:
        System.clearProperty('jexl_read_timeout_ms')
    }

    def "aSourceErrorStopsTheRun"() {
        when:
        Map out = run([inputs: [[name: 'x', source: 'entity', entityName: 'moqui.security.UserAccount', fields: ['username']]]], 'input.x')

        then:
        !out.ok
        out.error.kind == 'READ_NOT_ALLOWED'
    }

    def "aBadReadSpecIsRefusedOnStore"() {
        when:
        ec.service.sync().name("${SVC}.store#UserScript").parameters([userScriptId: 'USS_READ_BAD', scriptName: 'bad read',
                scriptBody: 'input', readSpecJson: '{"inputs":[{"name":"x","source":"fixed","fields":["a"],"where":{"field":"a","op":"regex","value":"1"}}]}']).call()
        String errors = ec.message.errorsString
        ec.message.clearErrors()

        then:
        errors.contains('regex')
    }

    def "aSavedScriptRunsItsSavedRead"() {
        when:
        ec.service.sync().name("${SVC}.store#UserScript").parameters([userScriptId: 'USS_READ_OK', scriptName: 'saved read',
                scriptBody: 'size(input.lines)', readSpecJson: '{"inputs":[{"name":"lines","source":"fixed","fields":["line"]}]}']).call()
        ec.message.clearErrors()
        Map out = ec.service.sync().name("${SVC}.run#UserScript").parameters([userScriptId: 'USS_READ_OK']).call()

        then:
        out.ok
        out.scriptResult == 3
        ec.entity.find('jexl.script.UserScript').condition('userScriptId', 'USS_READ_OK').disableAuthz().one().ownerUserId == ec.user.userId
    }

    def "aSourceTheOwnerHasNoGrantOnIsRefused"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.find('moqui.security.ArtifactGroupMember').condition([artifactGroupId: 'JEXL_SOURCES', artifactName: FIXED]).deleteAll()
        ec.artifactExecution.enableAuthz()

        when:
        Map out = run([inputs: [[name: 'lines', source: 'fixed', fields: ['line']]]], 'input.lines')

        then:
        !out.ok
        out.error.message.contains('not authorized')

        cleanup:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeValue('moqui.security.ArtifactGroupMember').setAll([artifactGroupId: 'JEXL_SOURCES', artifactName: FIXED,
                nameIsPattern: 'N', artifactTypeEnumId: 'AT_SERVICE', inheritAuthz: 'N']).createOrUpdate()
        ec.artifactExecution.enableAuthz()
    }
}

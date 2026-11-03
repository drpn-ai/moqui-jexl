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

import groovy.json.JsonSlurper
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue
import spock.lang.Shared
import spock.lang.Specification

import java.sql.Timestamp
import java.time.Instant

/** The moqui.jexl.ScriptServices user script and deployment services, against moqui.jexl.test.ScriptTestServices. */
class UserScriptDeploymentTests extends Specification {
    static final String SVC = 'moqui.jexl.ScriptServices'
    static final String TEST_SVC = 'moqui.jexl.test.ScriptTestServices'
    static final String BACKORDERS = '''
        var bySku = {:};
        for (var order : input.orders) {
            for (var line : order.itemsBackordered ?? []) { bySku[line.sku] = (bySku[line.sku] ?? 0) + line.quantity; }
        }
        bySku'''

    @Shared ExecutionContext ec

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.user.loginUser("john.doe", "moqui")
        // this machine's own heap and CPU must not hold the scripts under test
        System.setProperty('script_hold_heap_percent', '101')
        System.setProperty('script_hold_cpu_percent', '101')
    }

    def cleanupSpec() {
        ec.artifactExecution.disableAuthz()
        ec.entity.find('moqui.jexl.UserScriptDeployment').condition('scriptDeploymentId', 'like', 'USD_TEST_%').deleteAll()
        ec.entity.find('moqui.jexl.UserScript').condition('userScriptId', 'like', 'USS_TEST_%').deleteAll()
        ec.entity.find('moqui.test.TestEntity').condition('testMedium', 'like', 'USD_TEST_%').deleteAll()
        ec.artifactExecution.enableAuthz()
        ['script_hold_heap_percent', 'script_hold_cpu_percent', 'script_busy_service'].each { System.clearProperty(it) }
        ec.user.logoutUser()
        ec.destroy()
    }

    def setup() { ec.artifactExecution.disableAuthz() }

    def cleanup() {
        ec.message.clearAll()
        System.clearProperty('script_busy_service')
        ec.artifactExecution.enableAuthz()
    }

    Map call(String name, Map parameters) { ec.service.sync().name(name).parameters(parameters).call() }

    void storeScript(String id, String body, String sampleJson = null) {
        call("${SVC}.store#UserScript", [userScriptId: id, scriptName: id, scriptBody: body, sampleInputJson: sampleJson])
        assert !ec.message.hasError(), ec.message.errorsString
    }

    void storeDeployment(String id, String scriptId, String deliveryService, String inputService = "${TEST_SVC}.read#Orders") {
        call("${SVC}.store#UserScriptDeployment", [scriptDeploymentId: id, userScriptId: scriptId, cronExpression: '0 0 9 * * ?',
                timeZone: 'UTC', inputServiceName: inputService, deliveryServiceName: deliveryService])
        assert !ec.message.hasError(), ec.message.errorsString
    }

    /** makes a deployment due at dueAt */
    void dueAt(String id, Timestamp dueAt) {
        ec.entity.find('moqui.jexl.UserScriptDeployment').condition('scriptDeploymentId', id).one()
                .set('nextRunDate', dueAt).update()
    }

    EntityValue deployment(String id) {
        ec.entity.find('moqui.jexl.UserScriptDeployment').condition('scriptDeploymentId', id).useCache(false).one()
    }

    List<EntityValue> deliveries(String id) {
        ec.entity.find('moqui.test.TestEntity').condition('testMedium', id).useCache(false).list()
    }

    static Timestamp at(String instant) { Timestamp.from(Instant.parse(instant)) }

    def "a script is tried on its sample before it is saved"() {
        when:
        call("${SVC}.store#UserScript", [userScriptId: 'USS_TEST_BAD', scriptName: 'bad', scriptBody: 'input.orders[0].total * 2',
                sampleInputJson: '{"orders": [{"orderId": "SO-1"}]}'])

        then:
        ec.message.hasError()
        ec.message.errorsString.contains('On its sample input the script stops')
        ec.entity.find('moqui.jexl.UserScript').condition('userScriptId', 'USS_TEST_BAD').one() == null
    }

    def "a saved script runs on its sample, and a refused call is an outcome not a service error"() {
        when:
        storeScript('USS_TEST_SUM', "fn:sum(input.payments, 'amount')", '{"payments": [{"amount": "40.00"}, {"amount": 2.5}]}')
        Map out = call("${SVC}.run#UserScript", [userScriptId: 'USS_TEST_SUM'])
        Map refused = call("${SVC}.run#UserScript", [scriptBody: 'java.lang.System.exit(0)', inputJson: '{}'])

        then:
        out.ok
        out.scriptResult == new BigDecimal('42.50')
        !refused.ok
        refused.error.kind == 'NOT_ALLOWED'
        !ec.message.hasError()
    }

    def "a deployment's cron, time zone and services are checked, and its next run is read in its own zone"() {
        when:
        storeScript('USS_TEST_BACK', BACKORDERS)
        call("${SVC}.store#UserScriptDeployment", [scriptDeploymentId: 'USD_TEST_BAD', userScriptId: 'USS_TEST_BACK',
                cronExpression: 'every morning', timeZone: 'Mars/Olympus', deliveryServiceName: 'no.such.Services.send#It'])
        String errors = ec.message.errorsString
        ec.message.clearErrors()
        Map stored = call("${SVC}.store#UserScriptDeployment", [scriptDeploymentId: 'USD_TEST_ZONE', userScriptId: 'USS_TEST_BACK',
                cronExpression: '0 0 9 * * ?', timeZone: 'Asia/Kolkata', deliveryServiceName: "${TEST_SVC}.deliver#ToTestEntity"])

        then:
        errors.contains('is not a Quartz cron expression')
        errors.contains("'Mars/Olympus' is not a time zone id")
        errors.contains('Delivery service no.such.Services.send#It does not implement')
        !ec.message.hasError()
        // 09:00 in Kolkata is 03:30 UTC
        (stored.nextRunDate as Timestamp).toInstant().toString().substring(11, 16) == '03:30'
    }

    def "a deployment names only services that implement the input or delivery interface, at store and at run"() {
        when:
        storeScript('USS_TEST_BACK', BACKORDERS)
        call("${SVC}.store#UserScriptDeployment", [scriptDeploymentId: 'USD_TEST_UNMARKED', userScriptId: 'USS_TEST_BACK',
                cronExpression: '0 0 9 * * ?', inputServiceName: "${TEST_SVC}.delete#Everything",
                deliveryServiceName: "${TEST_SVC}.delete#Everything"])
        String errors = ec.message.errorsString
        ec.message.clearErrors()
        // a row written straight to the database is refused again when it runs
        storeDeployment('USD_TEST_UNMARKED', 'USS_TEST_BACK', "${TEST_SVC}.deliver#ToTestEntity")
        ec.entity.find('moqui.jexl.UserScriptDeployment').condition('scriptDeploymentId', 'USD_TEST_UNMARKED').one()
                .set('deliveryServiceName', "${TEST_SVC}.delete#Everything".toString()).update()
        Map out = call("${SVC}.run#UserScriptDeployment", [scriptDeploymentId: 'USD_TEST_UNMARKED', deliver: true])

        then:
        errors.contains("Input service ${TEST_SVC}.delete#Everything does not implement moqui.jexl.ScriptServices.read#UserScriptDeploymentInput")
        errors.contains("Delivery service ${TEST_SVC}.delete#Everything does not implement moqui.jexl.ScriptServices.deliver#UserScriptDeploymentResult")
        out.status == 'NOT_DELIVERED'
        out.message.contains('does not implement')
        deliveries('USD_TEST_UNMARKED_RAN').isEmpty()
    }

    def "a preview runs the script and delivers nothing"() {
        when:
        storeScript('USS_TEST_BACK', BACKORDERS)
        storeDeployment('USD_TEST_PREVIEW', 'USS_TEST_BACK', "${TEST_SVC}.deliver#ToTestEntity")
        Map out = call("${SVC}.run#UserScriptDeployment", [scriptDeploymentId: 'USD_TEST_PREVIEW'])

        then:
        out.status == 'PREVIEW'
        out.scriptResult == [A: 3, B: 4]
        out.inputSummary.source == 'orders'
        deliveries('USD_TEST_PREVIEW').isEmpty()
        deployment('USD_TEST_PREVIEW').lastRunStatus == null
    }

    def "the scheduled scan delivers a due deployment once and moves its next run forward"() {
        given:
        storeScript('USS_TEST_BACK', BACKORDERS)
        storeDeployment('USD_TEST_DUE', 'USS_TEST_BACK', "${TEST_SVC}.deliver#ToTestEntity")
        storeDeployment('USD_TEST_OFF', 'USS_TEST_BACK', "${TEST_SVC}.deliver#ToTestEntity")
        dueAt('USD_TEST_DUE', at('2030-01-01T09:00:00Z'))
        call("${SVC}.store#UserScriptDeployment", [scriptDeploymentId: 'USD_TEST_OFF', userScriptId: 'USS_TEST_BACK',
                cronExpression: '0 0 9 * * ?', deliveryServiceName: "${TEST_SVC}.deliver#ToTestEntity", isActive: 'N'])
        Timestamp now = at('2030-01-01T09:02:00Z')

        when:
        Map first = call("${SVC}.run#DueUserScriptDeployments", [nowTimestamp: now])
        Map second = call("${SVC}.run#DueUserScriptDeployments", [nowTimestamp: now])
        EntityValue ran = deployment('USD_TEST_DUE')

        then:
        first.results.find { it.scriptDeploymentId == 'USD_TEST_DUE' }.status == 'DELIVERED'
        !first.results.any { it.scriptDeploymentId == 'USD_TEST_OFF' }
        !second.results.any { it.scriptDeploymentId == 'USD_TEST_DUE' }
        deliveries('USD_TEST_DUE').size() == 1
        new JsonSlurper().parseText(deliveries('USD_TEST_DUE')[0].testLong as String) == [A: 3, B: 4]
        ran.lastRunStatus == 'DELIVERED'
        ran.lastRunMessage == 'Recorded.'
        (ran.lastRunUnits as long) > 0
        ran.nextRunDate == at('2030-01-02T09:00:00Z')
        !ec.message.hasError()
    }

    def "each way a run can end is recorded on its deployment, and one failing does not stop the others"() {
        given:
        storeScript('USS_TEST_BACK', BACKORDERS)
        storeScript('USS_TEST_EMPTY', "size(input.orders) > 99 ? 'many' : ''")
        storeScript('USS_TEST_GONE', '1')
        storeDeployment('USD_TEST_NOTHING', 'USS_TEST_EMPTY', "${TEST_SVC}.deliver#ToTestEntity")
        storeDeployment('USD_TEST_NOINPUT', 'USS_TEST_BACK', "${TEST_SVC}.deliver#ToTestEntity", "${TEST_SVC}.read#Nothing")
        storeDeployment('USD_TEST_REFUSED', 'USS_TEST_BACK', "${TEST_SVC}.deliver#Refused")
        storeDeployment('USD_TEST_MISSING', 'USS_TEST_GONE', "${TEST_SVC}.deliver#ToTestEntity")
        call('update#moqui.jexl.UserScript', [userScriptId: 'USS_TEST_GONE', isActive: 'N'])
        ['USD_TEST_NOTHING', 'USD_TEST_NOINPUT', 'USD_TEST_REFUSED', 'USD_TEST_MISSING'].each { dueAt(it, at('2031-01-01T09:00:00Z')) }

        when:
        call("${SVC}.run#DueUserScriptDeployments", [nowTimestamp: at('2031-01-01T09:01:00Z')])

        then:
        deployment('USD_TEST_NOTHING').lastRunStatus == 'NOTHING_TO_DELIVER'
        deployment('USD_TEST_NOINPUT').lastRunStatus == 'NO_INPUT'
        deployment('USD_TEST_NOINPUT').lastRunMessage == 'No finished result to read yet.'
        deployment('USD_TEST_REFUSED').lastRunStatus == 'NOT_DELIVERED'
        deployment('USD_TEST_REFUSED').lastRunMessage.contains('The endpoint refused it.')
        deployment('USD_TEST_MISSING').lastRunStatus == 'SCRIPT_MISSING'
        ['USD_TEST_NOTHING', 'USD_TEST_NOINPUT', 'USD_TEST_MISSING'].every { deliveries(it).isEmpty() }
        // the refused delivery's error stays in its outcome, off the scan's own messages
        !ec.message.hasError()
    }

    def "while the application's busy check holds, a due run waits, then expires and is skipped"() {
        given:
        storeScript('USS_TEST_BACK', BACKORDERS)
        storeDeployment('USD_TEST_BUSY', 'USS_TEST_BACK', "${TEST_SVC}.deliver#ToTestEntity")
        dueAt('USD_TEST_BUSY', at('2032-01-01T09:00:00Z'))
        System.setProperty('script_busy_service', "${TEST_SVC}.check#QueueFull")

        when:
        call("${SVC}.run#DueUserScriptDeployments", [nowTimestamp: at('2032-01-01T09:10:00Z')])
        EntityValue waiting = deployment('USD_TEST_BUSY')
        call("${SVC}.run#DueUserScriptDeployments", [nowTimestamp: at('2032-01-01T10:05:00Z')])
        EntityValue expired = deployment('USD_TEST_BUSY')

        then:
        waiting.lastRunStatus == 'WAITING'
        waiting.lastRunMessage.contains('the order queue is full')
        waiting.nextRunDate == at('2032-01-01T09:00:00Z')
        expired.lastRunStatus == 'EXPIRED'
        expired.nextRunDate == at('2032-01-02T09:00:00Z')
        deliveries('USD_TEST_BUSY').isEmpty()
    }
}

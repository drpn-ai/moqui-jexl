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
        ec.artifactExecution.disableAuthz()
        [[id: 'JEXL_SCHEDULER', name: 'jexl.scheduler', group: 'JEXL_TEST_SCHEDULERS', grants: ['JEXL_SCRIPT_SCHEDULE', 'JEXL_SCRIPT_EDIT']],
         [id: 'JEXL_EDITOR', name: 'jexl.editor', group: 'JEXL_TEST_EDITORS', grants: ['JEXL_SCRIPT_EDIT']]].each { Map u ->
            ec.entity.makeValue('moqui.security.UserAccount').setAll([userId: u.id, username: u.name, userFullName: u.name]).createOrUpdate()
            ec.entity.makeValue('moqui.security.UserGroup').setAll([userGroupId: u.group, description: u.group]).createOrUpdate()
            ec.entity.makeValue('moqui.security.UserGroupMember').setAll([userGroupId: u.group, userId: u.id,
                    fromDate: Timestamp.valueOf('2020-01-01 00:00:00')]).createOrUpdate()
            ((List) u.grants).each { String g ->
                ec.entity.makeValue('moqui.security.ArtifactAuthz').setAll([artifactAuthzId: "JT_${u.id.take(6)}_${g.replace("JEXL_SCRIPT_", "")}".toString(),
                        userGroupId: u.group, artifactGroupId: g, authzTypeEnumId: 'AUTHZT_ALLOW', authzActionEnumId: 'AUTHZA_ALL']).createOrUpdate()
            }
        }
        ec.artifactExecution.enableAuthz()
    }

    def cleanupSpec() {
        ec.artifactExecution.disableAuthz()
        ec.entity.find('jexl.script.UserScriptDeploymentDelivery').condition('scriptDeploymentId', 'like', 'USD_TEST_%').deleteAll()
        ec.entity.find('jexl.script.UserScriptDeployment').condition('scriptDeploymentId', 'like', 'USD_TEST_%').deleteAll()
        ec.entity.find('jexl.script.UserScript').condition('userScriptId', 'like', 'USS_TEST_%').deleteAll()
        ec.entity.find('moqui.test.TestEntity').condition('testMedium', 'like', 'USD_TEST_%').deleteAll()
        ec.entity.find('jexl.script.UserScriptRun').condition('scriptDeploymentId', 'like', 'USD_TEST_%').deleteAll()
        ec.entity.find('jexl.script.UserScriptRun').condition('scriptRunId', 'like', 'USR_TEST_%').deleteAll()
        ec.entity.find('moqui.security.ArtifactAuthz').condition('userGroupId', 'in', ['JEXL_TEST_SCHEDULERS', 'JEXL_TEST_EDITORS']).deleteAll()
        ec.entity.find('moqui.security.UserGroupMember').condition('userId', 'in', ['JEXL_SCHEDULER', 'JEXL_EDITOR']).deleteAll()
        ec.entity.find('moqui.security.UserGroup').condition('userGroupId', 'in', ['JEXL_TEST_SCHEDULERS', 'JEXL_TEST_EDITORS']).deleteAll()
        ec.entity.find('moqui.security.UserLoginHistory').condition('userId', 'in', ['JEXL_SCHEDULER', 'JEXL_EDITOR']).deleteAll()
        ec.entity.find('moqui.security.UserAccount').condition('userId', 'in', ['JEXL_SCHEDULER', 'JEXL_EDITOR']).deleteAll()
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
        ec.entity.find('jexl.script.UserScriptDeployment').condition('scriptDeploymentId', id).one()
                .set('nextRunDate', dueAt).update()
    }

    EntityValue deployment(String id) {
        ec.entity.find('jexl.script.UserScriptDeployment').condition('scriptDeploymentId', id).useCache(false).one()
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
        ec.entity.find('jexl.script.UserScript').condition('userScriptId', 'USS_TEST_BAD').one() == null
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
        ec.entity.find('jexl.script.UserScriptDeploymentDelivery').condition('scriptDeploymentId', 'USD_TEST_UNMARKED').one()
                .set('deliveryServiceName', "${TEST_SVC}.delete#Everything".toString()).update()
        Map out = call("${SVC}.run#UserScriptDeployment", [scriptDeploymentId: 'USD_TEST_UNMARKED', deliver: true])

        then:
        errors.contains("Input service ${TEST_SVC}.delete#Everything does not implement moqui.jexl.ScriptServices.read#UserScriptDeploymentInput")
        errors.contains("Delivery service ${TEST_SVC}.delete#Everything does not implement moqui.jexl.ScriptServices.deliver#UserScriptDeploymentResult")
        out.status == 'DELIVERY_FAILED'
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
        first.results.find { it.scriptDeploymentId == 'USD_TEST_DUE' }.status == 'OK'
        !first.results.any { it.scriptDeploymentId == 'USD_TEST_OFF' }
        !second.results.any { it.scriptDeploymentId == 'USD_TEST_DUE' }
        deliveries('USD_TEST_DUE').size() == 1
        new JsonSlurper().parseText(deliveries('USD_TEST_DUE')[0].testLong as String).data == [A: 3, B: 4]
        ran.lastRunStatus == 'OK'
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
        call('update#jexl.script.UserScript', [userScriptId: 'USS_TEST_GONE', isActive: 'N'])
        ['USD_TEST_NOTHING', 'USD_TEST_NOINPUT', 'USD_TEST_REFUSED', 'USD_TEST_MISSING'].each { dueAt(it, at('2031-01-01T09:00:00Z')) }

        when:
        call("${SVC}.run#DueUserScriptDeployments", [nowTimestamp: at('2031-01-01T09:01:00Z')])

        then:
        deployment('USD_TEST_NOTHING').lastRunStatus == 'NOTHING_TO_SEND'
        deployment('USD_TEST_NOINPUT').lastRunStatus == 'NO_INPUT'
        deployment('USD_TEST_NOINPUT').lastRunMessage == 'No finished result to read yet.'
        deployment('USD_TEST_REFUSED').lastRunStatus == 'DELIVERY_FAILED'
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

    def "theToggleServiceStopsAndRestartsScripts"() {
        when:
        ec.service.sync().name("${SVC}.set#UserScriptsEnabled").parameters([enabled: false]).call()
        Map off = ec.service.sync().name("${SVC}.run#UserScript").parameters([scriptBody: '1 + 1', inputJson: '{}']).call()
        Map status = ec.service.sync().name("${SVC}.get#UserScriptLaneStatus").call()
        ec.service.sync().name("${SVC}.set#UserScriptsEnabled").parameters([enabled: true]).call()
        Map on = ec.service.sync().name("${SVC}.run#UserScript").parameters([scriptBody: '1 + 1', inputJson: '{}']).call()

        then:
        !off.ok
        off.error.kind == 'DISABLED'
        status.enabled == false
        status.stuckCount == 0
        on.ok
        on.scriptResult == 2
    }

    def "aRunRecordsEveryStep"() {
        given:
        storeScript('USS_TEST_BACK', BACKORDERS)
        storeDeployment('USD_TEST_RECORD', 'USS_TEST_BACK', "${TEST_SVC}.deliver#ToTestEntity")
        dueAt('USD_TEST_RECORD', at('2033-01-01T09:00:00Z'))

        when:
        call("${SVC}.run#DueUserScriptDeployments", [nowTimestamp: at('2033-01-01T09:01:00Z')])
        List<EntityValue> runs = ec.entity.find('jexl.script.UserScriptRun').condition('scriptDeploymentId', 'USD_TEST_RECORD').useCache(false).list()

        then:
        runs.size() == 1
        runs[0].statusId == 'OK'
        runs[0].ownerUserId == ec.user.userId
        (runs[0].units as long) > 0
        runs[0].startDate != null && runs[0].endDate != null
        new JsonSlurper().parseText(runs[0].deliveryOutcomesJson as String)[0].outcome == 'OK'
    }

    def "oneFailedDeliveryIsPartial"() {
        given:
        storeScript('USS_TEST_BACK', BACKORDERS)
        call("${SVC}.store#UserScriptDeployment", [scriptDeploymentId: 'USD_TEST_PARTIAL', userScriptId: 'USS_TEST_BACK',
                cronExpression: '0 0 9 * * ?', timeZone: 'UTC', inputServiceName: "${TEST_SVC}.read#Orders",
                deliveries: [[deliveryServiceName: "${TEST_SVC}.deliver#ToTestEntity"], [deliveryServiceName: "${TEST_SVC}.deliver#Refused"]]])
        assert !ec.message.hasError(), ec.message.errorsString
        dueAt('USD_TEST_PARTIAL', at('2034-01-01T09:00:00Z'))

        when:
        call("${SVC}.run#DueUserScriptDeployments", [nowTimestamp: at('2034-01-01T09:01:00Z')])
        List<EntityValue> sent = ec.entity.find('jexl.script.UserScriptDeploymentDelivery').condition('scriptDeploymentId', 'USD_TEST_PARTIAL')
                .orderBy('deliverySeqId').useCache(false).list()

        then:
        deployment('USD_TEST_PARTIAL').lastRunStatus == 'DELIVERY_PARTIAL'
        sent*.lastOutcome == ['OK', 'FAILED']
        sent[1].lastMessage.contains('The endpoint refused it.')
        deliveries('USD_TEST_PARTIAL').size() == 1
        !ec.message.hasError()
    }

    def "aDisabledOwnerStopsTheDeployment"() {
        given:
        storeScript('USS_TEST_BACK', BACKORDERS)
        storeDeployment('USD_TEST_OWNER', 'USS_TEST_BACK', "${TEST_SVC}.deliver#ToTestEntity")
        deployment('USD_TEST_OWNER').set('ownerUserId', 'JEXL_SCHEDULER').update()
        ec.entity.find('moqui.security.UserAccount').condition('userId', 'JEXL_SCHEDULER').one().set('disabled', 'Y').update()
        dueAt('USD_TEST_OWNER', at('2035-01-01T09:00:00Z'))

        when:
        call("${SVC}.run#DueUserScriptDeployments", [nowTimestamp: at('2035-01-01T09:01:00Z')])
        EntityValue stopped = deployment('USD_TEST_OWNER')

        then:
        stopped.lastRunStatus == 'OWNER_LOST'
        stopped.isActive == 'N'
        deliveries('USD_TEST_OWNER').isEmpty()

        cleanup:
        ec.entity.find('moqui.security.UserAccount').condition('userId', 'JEXL_SCHEDULER').one().set('disabled', 'N').update()
    }

    def "anOwnerWithoutTheSchedulePermissionStopsTheDeployment"() {
        given:
        storeScript('USS_TEST_BACK', BACKORDERS)
        storeDeployment('USD_TEST_NOPERM', 'USS_TEST_BACK', "${TEST_SVC}.deliver#ToTestEntity")
        deployment('USD_TEST_NOPERM').set('ownerUserId', 'JEXL_EDITOR').update()
        dueAt('USD_TEST_NOPERM', at('2035-02-01T09:00:00Z'))

        when:
        call("${SVC}.run#DueUserScriptDeployments", [nowTimestamp: at('2035-02-01T09:01:00Z')])

        then:
        deployment('USD_TEST_NOPERM').lastRunStatus == 'OWNER_LOST'
        deployment('USD_TEST_NOPERM').isActive == 'N'
    }

    def "editingAScheduledScriptMovesOwnership"() {
        given:
        storeScript('USS_TEST_OWNED', BACKORDERS)
        storeDeployment('USD_TEST_OWNED', 'USS_TEST_OWNED', "${TEST_SVC}.deliver#ToTestEntity")

        when:
        ec.user.internalLoginUser('jexl.scheduler')
        call("${SVC}.store#UserScript", [userScriptId: 'USS_TEST_OWNED', scriptName: 'USS_TEST_OWNED', scriptBody: BACKORDERS + ' '])
        String schedulerErrors = ec.message.errorsString
        ec.message.clearErrors()
        String ownerAfterScheduler = deployment('USD_TEST_OWNED').ownerUserId
        ec.user.internalLoginUser('jexl.editor')
        call("${SVC}.store#UserScript", [userScriptId: 'USS_TEST_OWNED', scriptName: 'USS_TEST_OWNED', scriptBody: BACKORDERS + '  '])
        String editorErrors = ec.message.errorsString
        ec.message.clearErrors()

        then:
        schedulerErrors == ''
        ownerAfterScheduler == 'JEXL_SCHEDULER'
        editorErrors.contains('this script runs on a schedule owned by jexl.scheduler')
        deployment('USD_TEST_OWNED').ownerUserId == 'JEXL_SCHEDULER'

        cleanup:
        ec.user.logoutUser()
        ec.user.loginUser('john.doe', 'moqui')
    }

    def "oldRunsArePruned"() {
        given:
        Timestamp now = at('2036-01-31T00:00:00Z')
        ec.entity.makeValue('jexl.script.UserScriptRun').setAll([scriptRunId: 'USR_TEST_OLD', startDate: at('2035-12-31T00:00:00Z')]).create()
        ec.entity.makeValue('jexl.script.UserScriptRun').setAll([scriptRunId: 'USR_TEST_NEW', startDate: at('2036-01-02T00:00:00Z')]).create()

        when:
        ScriptDeploymentRunner.pruneRuns(ec, now)

        then:
        ec.entity.find('jexl.script.UserScriptRun').condition('scriptRunId', 'USR_TEST_OLD').useCache(false).one() == null
        ec.entity.find('jexl.script.UserScriptRun').condition('scriptRunId', 'USR_TEST_NEW').useCache(false).one() != null
    }
}

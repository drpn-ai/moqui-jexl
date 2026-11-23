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
import org.moqui.entity.EntityValue
import spock.lang.Shared
import spock.lang.Specification

import java.sql.Timestamp
import java.time.Instant

/** The scheduler thread that replaces a ServiceJob: started by ScriptDeploymentToolFactory, one pass per runDue. */
class ScriptDeploymentSchedulerTests extends Specification {
    static final String SVC = 'moqui.jexl.ScriptServices'
    static final String TEST_SVC = 'moqui.jexl.test.ScriptTestServices'

    @Shared ExecutionContext ec

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        // deliveries run as the owner with authz on: the test deliveries are granted like an application's own
        ec.artifactExecution.disableAuthz()
        ['deliver#ToTestEntity', 'deliver#Refused'].each { String svc ->
            ec.entity.makeValue('moqui.security.ArtifactGroupMember').setAll([artifactGroupId: 'JEXL_SCRIPT_SCHEDULE',
                    artifactName: "moqui.jexl.test.ScriptTestServices.${svc}".toString(), nameIsPattern: 'N', artifactTypeEnumId: 'AT_SERVICE',
                    inheritAuthz: 'Y']).createOrUpdate()
        }
        ec.artifactExecution.enableAuthz()
        ec.user.loginUser("john.doe", "moqui")
        System.setProperty('jexl_hold_heap_percent', '101')
        System.setProperty('jexl_hold_cpu_percent', '101')
    }

    def cleanupSpec() {
        ec.artifactExecution.disableAuthz()
        ec.entity.find('jexl.script.UserScriptDeploymentDelivery').condition('scriptDeploymentId', 'like', 'USD_SCHED_%').deleteAll()
        ec.entity.find('jexl.script.UserScriptDeployment').condition('scriptDeploymentId', 'like', 'USD_SCHED_%').deleteAll()
        ec.entity.find('jexl.script.UserScript').condition('userScriptId', 'like', 'USS_SCHED_%').deleteAll()
        ec.entity.find('moqui.test.TestEntity').condition('testMedium', 'like', 'USD_SCHED_%').deleteAll()
        ec.artifactExecution.enableAuthz()
        ['jexl_hold_heap_percent', 'jexl_hold_cpu_percent'].each { System.clearProperty(it) }
        ec.user.logoutUser()
        ec.destroy()
    }

    def setup() { ec.artifactExecution.disableAuthz() }

    def cleanup() {
        ec.message.clearAll()
        ec.artifactExecution.enableAuthz()
    }

    ScriptDeploymentScheduler scheduler() { ec.factory.getTool(ScriptDeploymentToolFactory.TOOL_NAME, ScriptDeploymentScheduler.class) }

    def "the component registers the scheduler as a tool, and the test JVM keeps its timer off"() {
        expect:
        scheduler() != null
        ScriptDeploymentToolFactory.checkSeconds() == 0L
    }

    def "unset, the timer follows Moqui's scheduler switch; set, the setting wins"() {
        given:
        String saved = System.getProperty('jexl_deployment_check_seconds')
        String savedJobs = System.getProperty('scheduled_job_check_time')

        when:
        System.clearProperty('jexl_deployment_check_seconds')
        System.setProperty('scheduled_job_check_time', '0')
        long off = ScriptDeploymentToolFactory.checkSeconds()
        System.setProperty('scheduled_job_check_time', '60')
        long on = ScriptDeploymentToolFactory.checkSeconds()
        System.setProperty('jexl_deployment_check_seconds', '30')
        System.setProperty('scheduled_job_check_time', '0')
        long explicit = ScriptDeploymentToolFactory.checkSeconds()

        then:
        off == 0L
        on == 60L
        explicit == 30L

        cleanup:
        if (saved == null) System.clearProperty('jexl_deployment_check_seconds') else System.setProperty('jexl_deployment_check_seconds', saved)
        if (savedJobs == null) System.clearProperty('scheduled_job_check_time') else System.setProperty('scheduled_job_check_time', savedJobs)
    }

    def "a pass runs a due deployment once, from a thread of its own with no user"() {
        given:
        ec.service.sync().name("${SVC}.store#UserScript").parameters([userScriptId: 'USS_SCHED_1', scriptName: 'count',
                scriptBody: 'size(input.orders)']).call()
        ec.service.sync().name("${SVC}.store#UserScriptDeployment").parameters([scriptDeploymentId: 'USD_SCHED_1',
                userScriptId: 'USS_SCHED_1', cronExpression: '0 0 9 * * ?', timeZone: 'UTC',
                inputServiceName: "${TEST_SVC}.read#Orders", deliveryServiceName: "${TEST_SVC}.deliver#ToTestEntity"]).call()
        assert !ec.message.hasError(), ec.message.errorsString
        Timestamp now = Timestamp.from(Instant.parse('2026-06-02T10:00:00Z'))
        ec.artifactExecution.disableAuthz()
        ec.entity.find('jexl.script.UserScriptDeployment').condition('scriptDeploymentId', 'USD_SCHED_1').one()
                .set('nextRunDate', Timestamp.from(Instant.parse('2026-06-02T09:00:00Z'))).update()
        ec.artifactExecution.enableAuthz()
        Map first = null
        Map second = null

        when:
        // the scheduled executor's threads have no ExecutionContext and no user; run the pass the same way
        Thread pass = Thread.start { first = scheduler().runDue(now); second = scheduler().runDue(now) }
        pass.join(60_000)
        ec.artifactExecution.disableAuthz()
        EntityValue deployment = ec.entity.find('jexl.script.UserScriptDeployment').condition('scriptDeploymentId', 'USD_SCHED_1')
                .useCache(false).one()
        List<EntityValue> deliveries = ec.entity.find('moqui.test.TestEntity').condition('testMedium', 'USD_SCHED_1').useCache(false).list()
        ec.artifactExecution.enableAuthz()

        then:
        first.ran == 1
        second.ran == 0
        deployment.lastRunStatus == 'OK'
        deployment.nextRunDate == Timestamp.from(Instant.parse('2026-06-03T09:00:00Z'))
        deliveries.size() == 1
        new groovy.json.JsonSlurper().parseText(deliveries[0].testLong as String).summary == '2'
    }

    def "run never throws, so the scheduled executor never drops it"() {
        when:
        new ScriptDeploymentScheduler(null).run()

        then:
        noExceptionThrown()
    }
}

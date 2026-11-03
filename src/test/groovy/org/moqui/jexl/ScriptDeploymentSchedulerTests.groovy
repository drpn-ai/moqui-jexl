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
        ec.user.loginUser("john.doe", "moqui")
        System.setProperty('script_hold_heap_percent', '101')
        System.setProperty('script_hold_cpu_percent', '101')
    }

    def cleanupSpec() {
        ec.artifactExecution.disableAuthz()
        ec.entity.find('moqui.jexl.UserScriptDeployment').condition('scriptDeploymentId', 'like', 'USD_SCHED_%').deleteAll()
        ec.entity.find('moqui.jexl.UserScript').condition('userScriptId', 'like', 'USS_SCHED_%').deleteAll()
        ec.entity.find('moqui.test.TestEntity').condition('testMedium', 'like', 'USD_SCHED_%').deleteAll()
        ec.artifactExecution.enableAuthz()
        ['script_hold_heap_percent', 'script_hold_cpu_percent'].each { System.clearProperty(it) }
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
        ec.entity.find('moqui.jexl.UserScriptDeployment').condition('scriptDeploymentId', 'USD_SCHED_1').one()
                .set('nextRunDate', Timestamp.from(Instant.parse('2026-06-02T09:00:00Z'))).update()
        ec.artifactExecution.enableAuthz()
        Map first = null
        Map second = null

        when:
        // the scheduled executor's threads have no ExecutionContext and no user; run the pass the same way
        Thread pass = Thread.start { first = scheduler().runDue(now); second = scheduler().runDue(now) }
        pass.join(60_000)
        ec.artifactExecution.disableAuthz()
        EntityValue deployment = ec.entity.find('moqui.jexl.UserScriptDeployment').condition('scriptDeploymentId', 'USD_SCHED_1')
                .useCache(false).one()
        List<EntityValue> deliveries = ec.entity.find('moqui.test.TestEntity').condition('testMedium', 'USD_SCHED_1').useCache(false).list()
        ec.artifactExecution.enableAuthz()

        then:
        first.ran == 1
        second.ran == 0
        deployment.lastRunStatus == 'DELIVERED'
        deployment.nextRunDate == Timestamp.from(Instant.parse('2026-06-03T09:00:00Z'))
        deliveries.size() == 1
        deliveries[0].testLong == '2'
    }

    def "run never throws, so the scheduled executor never drops it"() {
        when:
        new ScriptDeploymentScheduler(null).run()

        then:
        noExceptionThrown()
    }
}

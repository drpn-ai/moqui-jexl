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
import org.moqui.screen.ScreenTest
import org.moqui.screen.ScreenTest.ScreenTestRender
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

import java.sql.Timestamp

/** The User Scripts screens in the Tools app render for someone allowed to edit scripts, and not for anyone else. */
class JexlScreensTests extends Specification {
    @Shared ExecutionContext ec
    @Shared ScreenTest screenTest

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.artifactExecution.disableAuthz()
        ec.entity.makeValue('jexl.script.UserScript').setAll([userScriptId: 'USS_SCREEN', scriptName: 'Screen script',
                scriptProfile: 'SCRIPT', scriptBody: "'hi'", isActive: 'Y']).createOrUpdate()
        ec.entity.makeValue('jexl.script.UserScriptDeployment').setAll([scriptDeploymentId: 'USD_SCREEN', userScriptId: 'USS_SCREEN',
                cronExpression: '0 0 9 * * ?', timeZone: 'UTC', ownerUserId: 'EX_JOHN_DOE', isActive: 'N']).createOrUpdate()
        ec.entity.makeValue('jexl.script.UserScriptDeploymentDelivery').setAll([scriptDeploymentId: 'USD_SCREEN', deliverySeqId: '01',
                deliveryServiceName: 'moqui.jexl.ScriptServices.deliver#ScriptEmail', parametersJson: '{"toAddresses":"ops@example.test"}',
                isActive: 'Y']).createOrUpdate()
        ec.entity.makeValue('moqui.security.UserAccount').setAll([userId: 'JEXL_NOBODY', username: 'jexl.nobody', userFullName: 'Nobody']).createOrUpdate()
        ec.artifactExecution.enableAuthz()
        ec.user.loginUser('john.doe', 'moqui')
        screenTest = ec.screen.makeTest().baseScreenPath('apps/tools')
    }

    def cleanupSpec() {
        ec.artifactExecution.disableAuthz()
        ec.entity.find('jexl.script.UserScriptDeploymentDelivery').condition('scriptDeploymentId', 'USD_SCREEN').deleteAll()
        ec.entity.find('jexl.script.UserScriptDeployment').condition('scriptDeploymentId', 'USD_SCREEN').deleteAll()
        ec.entity.find('jexl.script.UserScript').condition('userScriptId', 'USS_SCREEN').deleteAll()
        ec.entity.find('moqui.security.UserLoginHistory').condition('userId', 'JEXL_NOBODY').deleteAll()
        ec.entity.find('moqui.security.UserAccount').condition('userId', 'JEXL_NOBODY').deleteAll()
        ec.artifactExecution.enableAuthz()
        ec.user.logoutUser()
        ec.destroy()
    }

    @Unroll
    def "render #screenPath"() {
        when:
        ScreenTestRender str = screenTest.render(screenPath, [lastStandalone: '-2'], null)

        then:
        !str.errorMessages
        str.assertContains(text)

        where:
        screenPath                                          | text
        'JexlScripts/ScriptList'                            | 'Screen script'
        'JexlScripts/EditScript?userScriptId=USS_SCREEN'    | 'Screen script'
        'JexlScripts/EditDeployment?scriptDeploymentId=USD_SCREEN' | 'ops@example.test'
    }

    def "someoneWithoutTheEditGrantIsRefused"() {
        given:
        ec.user.logoutUser()
        ec.user.internalLoginUser('jexl.nobody')

        when:
        ScreenTestRender str = ec.screen.makeTest().baseScreenPath('apps/tools').render('JexlScripts/ScriptList', [lastStandalone: '-2'], null)

        then:
        str.errorMessages || !str.output?.contains('Screen script')

        cleanup:
        ec.user.logoutUser()
        ec.user.loginUser('john.doe', 'moqui')
    }
}

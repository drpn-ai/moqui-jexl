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
import spock.lang.Shared
import spock.lang.Specification

/** The jexl.script entities (outside moqui.*, so entity filters apply to them) and the seeded authz groups. */
class UserScriptEntitiesTests extends Specification {
    @Shared ExecutionContext ec

    def setupSpec() { ec = Moqui.getExecutionContext() }

    def cleanupSpec() { ec.destroy() }

    def "theEntitiesLiveInJexlScript"() {
        expect:
        ['UserScript', 'UserScriptDeployment', 'UserScriptDeploymentDelivery', 'UserScriptRun', 'ScriptSource'].every {
            ec.entity.getEntityDefinition("jexl.script.${it}") != null
        }
        !ec.entity.isEntityDefined('moqui.jexl.UserScript')
        ec.entity.getEntityDefinition('jexl.script.UserScript').isField('readSpecJson')
        ec.entity.getEntityDefinition('jexl.script.UserScriptDeployment').isField('ownerUserId')
        !ec.entity.getEntityDefinition('jexl.script.UserScriptDeployment').isField('deliveryServiceName')
    }

    def "theAuthzGroupsAreSeeded"() {
        given:
        ec.artifactExecution.disableAuthz()

        expect:
        ['JEXL_READABLE', 'JEXL_SOURCES', 'JEXL_SCRIPT_EDIT', 'JEXL_SCRIPT_SCHEDULE'].every {
            ec.entity.find('moqui.security.ArtifactGroup').condition('artifactGroupId', it).one() != null
        }
        ec.entity.find('moqui.security.ArtifactGroupMember').condition('artifactGroupId', 'JEXL_READABLE').count() == 0
        ec.entity.find('moqui.security.ArtifactAuthz').condition([userGroupId: 'ADMIN', artifactGroupId: 'JEXL_SCRIPT_SCHEDULE']).count() == 1
        ec.entity.find('moqui.security.ArtifactGroupMember')
                .condition([artifactGroupId: 'JEXL_SCRIPT_SCHEDULE', artifactName: 'moqui.jexl.ScriptServices.set#UserScriptsEnabled']).count() == 1

        cleanup:
        ec.artifactExecution.enableAuthz()
    }
}

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
package org.moqui.jexl.script

import groovy.transform.CompileStatic
import org.moqui.context.ExecutionContext
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityFacadeImpl

/**
 * Fields an application adds to the jexl.script entities with extend-entity (an owner or organization field, say).
 * The store services take them as ownerFields; a core field of the component can never be set that way. A run record
 * copies the extended fields it shares with its deployment.
 */
@CompileStatic
class OwnerFields {
    static final Map<String, Set<String>> CORE = [
            'jexl.script.UserScript'          : ['userScriptId', 'scriptName', 'description', 'scriptProfile', 'readSpecJson',
                                                 'scriptBody', 'sampleInputJson', 'ownerUserId', 'isActive'] as Set<String>,
            'jexl.script.UserScriptDeployment': ['scriptDeploymentId', 'userScriptId', 'cronExpression', 'timeZone', 'ownerUserId',
                                                 'inputServiceName', 'isActive', 'nextRunDate', 'lastRunDate', 'lastRunStatus',
                                                 'lastRunMessage', 'lastRunUnits'] as Set<String>,
            'jexl.script.UserScriptRun'       : ['scriptRunId', 'userScriptId', 'scriptDeploymentId', 'ownerUserId', 'startDate',
                                                 'endDate', 'statusId', 'errorKind', 'units', 'elapsedMs', 'readCountsJson',
                                                 'deliveryOutcomesJson', 'message'] as Set<String>,
    ].asImmutable() as Map<String, Set<String>>

    /** The fields of an entity that the application added (not the component's, not Moqui's stamps). */
    static List<String> extended(ExecutionContext ec, String entityName) {
        EntityDefinition ed = ((EntityFacadeImpl) ec.entity).getEntityDefinition(entityName)
        Set<String> core = CORE[entityName] ?: ([] as Set<String>)
        return ed.getAllFieldNames().findAll { String f -> !core.contains(f) && f != 'lastUpdatedStamp' } as List<String>
    }

    /** null when every key is an extended field of the entity, else what is wrong. */
    static String problem(ExecutionContext ec, String entityName, Map ownerFields) {
        if (!ownerFields) return null
        List<String> allowed = extended(ec, entityName)
        String bad = ownerFields.keySet().collect { it?.toString() }.find { String k -> !allowed.contains(k) }
        return bad ? "ownerFields may set only fields the application added to ${entityName}, not ${bad}.".toString() : null
    }
}

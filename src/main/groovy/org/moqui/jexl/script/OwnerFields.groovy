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
import org.moqui.entity.EntityValue
import org.moqui.util.SystemBinding
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityFacadeImpl

/**
 * Fields an application adds to the jexl.script entities with extend-entity (an owner or organization field, say).
 * The store services take them as ownerFields; a core field of the component can never be set that way, an existing
 * record's owner fields never change (no moving a script to another owner), and the application may approve the values
 * through the service named by the setting jexl_owner_fields_service (in entityName, ownerFields; out problem), for
 * example "the caller acts for this organization". A run record copies the extended fields it shares with its
 * deployment.
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

    /**
     * null when the record may be stored with these owner fields, else what is wrong. Runs on every store, with or without
     * ownerFields: a key must be an extended field; an existing record's owner fields never change (a stored empty value
     * included); and the application's hook (jexl_owner_fields_service) approves the effective owner values (the stored
     * ones overlaid with the given ones) for creates and updates alike, so an update cannot reach a record its caller
     * may not act for.
     */
    static String problem(ExecutionContext ec, String entityName, Map ownerFields, EntityValue existing = null) {
        Map given = ownerFields ?: [:]
        List<String> allowed = extended(ec, entityName)
        String bad = given.keySet().collect { it?.toString() }.find { String k -> !allowed.contains(k) }
        if (bad) return "ownerFields may set only fields the application added to ${entityName}, not ${bad}.".toString()
        if (existing != null) {
            String moved = given.keySet().collect { it?.toString() }.find { String k ->
                existing.get(k)?.toString() != given.get(k)?.toString()
            }
            if (moved) return "ownerFields cannot change ${moved} of an existing ${entityName}.".toString()
        }
        String hook = SystemBinding.getPropOrEnv('jexl_owner_fields_service')
        if (hook) {
            Map<String, Object> effective = [:]
            if (existing != null) allowed.each { String f -> effective.put(f, existing.get(f)) }
            effective.putAll(given)
            Map out = ec.service.sync().name(hook).parameters([entityName: entityName, ownerFields: effective,
                    existing: existing != null]).call()
            if (ec.message.hasError()) {
                String errors = ec.message.errorsString
                ec.message.clearErrors()
                return errors
            }
            if (out?.problem) return out.problem.toString()
        }
        return null
    }
}

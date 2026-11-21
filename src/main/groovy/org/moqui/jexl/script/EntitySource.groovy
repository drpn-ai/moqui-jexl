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
import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityFind
import org.moqui.entity.EntityListIterator
import org.moqui.entity.EntityValue
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.util.MNode

import java.sql.Timestamp

/**
 * The built-in "entity" read source. It reads an entity only when the application lists it in the artifact group
 * JEXL_READABLE, never a moqui.* entity (Moqui applies no entity filters to those) nor a view with one among its members,
 * and it reads as the logged-in user with authorization on: the user's grant on the entity decides whether it may be
 * read, and the filter sets attached to that grant decide which rows. Out: [rows, rowCount] or [error: {kind, message}].
 */
@CompileStatic
class EntitySource {
    static final String READABLE_GROUP = 'JEXL_READABLE'
    private static final Closure<Boolean> DEFAULT_STOP_REQUESTED = { -> Thread.currentThread().isInterrupted() }
    /** Whether the read must stop (its lane run timed out); a seam so tests need not interrupt a JDBC thread. */
    static Closure<Boolean> stopRequested = DEFAULT_STOP_REQUESTED

    static void resetForTests() { stopRequested = DEFAULT_STOP_REQUESTED }

    static Map<String, Object> read(ExecutionContext ec, Map input, Timestamp runDate, String timeZone, int maxRows) {
        String name = input.get('name')?.toString() ?: 'input'
        String entityName = input.get('entityName')?.toString()
        if (!entityName) return error('READ_ERROR', "Input '${name}' names no entity.")
        EntityDefinition ed = ((EntityFacadeImpl) ec.entity).getEntityDefinition(entityName)
        if (ed == null) return error('READ_ERROR', "There is no entity ${entityName}.")
        // NOTE: checked on the resolved name (a short name like UserAccount resolves to moqui.security.UserAccount),
        // and that same name is the one queried
        entityName = ed.getFullEntityName()
        if (entityName.startsWith('moqui.')) return error('READ_NOT_ALLOWED', "Scripts never read ${entityName}: moqui.* entities are not readable.")
        String moquiMember = moquiMember(ec, ed, 0)
        if (moquiMember) return error('READ_NOT_ALLOWED', "Scripts never read ${entityName}: it joins ${moquiMember}, a moqui.* entity.")
        if (ed.entityInfo.authorizeSkipView) {
            return error('READ_NOT_ALLOWED', "Scripts never read ${entityName}: it is authorize-skip=\"view\", so no grant or filter would apply.")
        }
        if (!Boolean.TRUE.is(((org.moqui.impl.context.ExecutionContextFactoryImpl) ec.factory).artifactTypeAuthzEnabled
                .get(org.moqui.context.ArtifactExecutionInfo.ArtifactType.AT_ENTITY))) {
            return error('READ_NOT_ALLOWED', 'Scripts read entities only while entity authorization is on.')
        }
        if (!listed(ec, entityName)) {
            return error('READ_NOT_ALLOWED', "${entityName} is not one of the entities scripts may read (artifact group ${READABLE_GROUP}).")
        }

        List<String> fields = (List<String>) (input.get('fields') ?: [])
        List<String> groupBy = (List<String>) (input.get('groupBy') ?: [])
        List<Map> measures = (List<Map>) (input.get('measures') ?: [])
        List<String> orderBy = (List<String>) (input.get('orderBy') ?: [])
        boolean grouped = !groupBy.isEmpty() || !measures.isEmpty()
        Map where = (Map) input.get('where')
        Map window = (Map) input.get('window')

        Set<String> named = new LinkedHashSet<>()
        named.addAll(fields)
        named.addAll(groupBy)
        measures.each { Map m -> if (m.get('field')) named.add(m.get('field').toString()) }
        if (where) collectFields(where, named)
        if (window) named.add(window.get('field').toString())
        if (!grouped) orderBy.each { String o -> named.add(o.startsWith('-') ? o.substring(1) : o) }
        String unknown = named.find { String f -> !ed.isField(f) }
        if (unknown) return error('READ_ERROR', "${entityName} has no field ${unknown}.")

        List<Map> conditions = []
        if (where) conditions.add(where)
        if (window) conditions.add(ReadConditions.windowCondition(window, runDate, timeZone))

        try {
            EntityFind find = ec.entity.find(entityName).useCache(false)
            if (conditions) find.condition(ReadConditions.toEntityCondition(ec, conditions.size() == 1 ? conditions[0] : [and: conditions] as Map))
            if (!grouped) {
                List<EntityValue> found = find.selectFields(fields).orderBy(orderBy).limit(maxRows + 1).list()
                if (found.size() > maxRows) {
                    long total = find.limit(null).count()
                    return error('READ_TOO_LARGE', "Input '${name}' has ${total} rows; the limit is ${maxRows}. Narrow it or group it.")
                }
                List<Map> rows = []
                for (EntityValue v : found) {
                    Map row = new LinkedHashMap()
                    fields.each { String f -> row.put(f, v.get(f)) }
                    rows.add(row)
                }
                return [rows: rows, rowCount: (long) rows.size()] as Map<String, Object>
            }

            List<String> selected = new ArrayList<String>(groupBy)
            measures.each { Map m -> if (m.get('field') && !selected.contains(m.get('field'))) selected.add(m.get('field').toString()) }
            if (selected) find.selectFields(selected)
            ReadConditions.GroupAccumulator groups = new ReadConditions.GroupAccumulator(groupBy, measures)
            EntityListIterator records = find.iterator()
            try {
                EntityValue record
                while ((record = records.next()) != null) {
                    if (stopRequested.call()) return error('READ_ERROR', "Input '${name}': the read was stopped (it ran past its time).")
                    groups.add(record.getMap())
                    if (groups.size() > maxRows) {
                        return error('READ_TOO_LARGE', "Input '${name}' has more than ${maxRows} groups; the limit is ${maxRows}. Group it more coarsely or narrow it.")
                    }
                }
            } finally {
                try { records.close() } catch (Exception ignored) { }
            }
            List<Map> rows = ReadConditions.order(groups.rows(), orderBy)
            return [rows: rows, rowCount: (long) rows.size()] as Map<String, Object>
        } catch (ArtifactAuthorizationException e) {
            return error('READ_NOT_ALLOWED', "Not allowed to read ${entityName}: ${e.message}")
        }
    }

    /** Whether the application lists the entity (by name, or by a pattern member) in JEXL_READABLE. */
    static boolean listed(ExecutionContext ec, String fullEntityName) {
        List<EntityValue> members = ec.entity.find('moqui.security.ArtifactGroupMember')
                .condition('artifactGroupId', READABLE_GROUP).condition('artifactTypeEnumId', 'AT_ENTITY')
                .disableAuthz().useCache(true).list()
        return members.any { EntityValue m ->
            String artifactName = m.getString('artifactName')
            return m.getString('nameIsPattern') == 'Y' ? fullEntityName.matches(artifactName) : artifactName == fullEntityName
        }
    }

    /** The first moqui.* entity among a view's members (nested views too), or null. */
    private static String moquiMember(ExecutionContext ec, EntityDefinition ed, int depth) {
        if (!ed.isViewEntity || depth > 8) return null
        for (MNode member : ed.getEntityNode().children('member-entity')) {
            String memberName = member.attribute('entity-name')
            EntityDefinition memberEd = ((EntityFacadeImpl) ec.entity).getEntityDefinition(memberName)
            String full = memberEd?.getFullEntityName() ?: memberName
            if (full.startsWith('moqui.')) return full
            if (memberEd != null) {
                String nested = moquiMember(ec, memberEd, depth + 1)
                if (nested) return nested
            }
        }
        return null
    }

    private static void collectFields(Map where, Set<String> out) {
        if (where.containsKey('and') || where.containsKey('or')) {
            ((List) where.get(where.containsKey('and') ? 'and' : 'or')).each { Object c -> collectFields((Map) c, out) }
        } else if (where.get('field')) {
            out.add(where.get('field').toString())
        }
    }

    private static Map<String, Object> error(String kind, Object message) {
        return [error: [kind: kind, message: message.toString()]] as Map<String, Object>
    }
}

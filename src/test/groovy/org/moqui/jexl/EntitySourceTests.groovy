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

import java.sql.Timestamp

/**
 * The built-in entity source: it reads only entities the application lists in JEXL_READABLE, as the logged-in owner,
 * so the filters attached to the owner's grant apply; moqui.* entities never, not even through a view.
 */
class EntitySourceTests extends Specification {
    static final String SVC = 'moqui.jexl.ScriptServices.read#EntityScriptSource'
    static final Timestamp RUN = Timestamp.valueOf('2026-11-21 09:00:00')

    @Shared ExecutionContext ec

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location('component://moqui-jexl/src/test/resources/data/JexlReadTestData.xml').load()
        ec.artifactExecution.enableAuthz()
    }

    def cleanupSpec() {
        ec.user.logoutUser()
        ec.artifactExecution.disableAuthz()
        ec.entity.find('jexl.test.ReadThing').deleteAll()
        ec.entity.find('moqui.security.ArtifactAuthzFilter').condition('artifactAuthzId', 'JEXL_TEST_READABLE').deleteAll()
        ec.entity.find('moqui.security.EntityFilter').condition('entityFilterSetId', 'JEXL_TEST_OWN_THINGS').deleteAll()
        ec.entity.find('moqui.security.EntityFilterSet').condition('entityFilterSetId', 'JEXL_TEST_OWN_THINGS').deleteAll()
        ec.entity.find('moqui.security.ArtifactAuthz').condition('artifactAuthzId', 'in', ['JEXL_TEST_READABLE', 'JEXL_TEST_SOURCES']).deleteAll()
        ec.entity.find('moqui.security.ArtifactGroupMember').condition('artifactName', 'like', 'jexl.test.%').deleteAll()
        ec.entity.find('moqui.security.UserGroupMember').condition('userGroupId', 'JEXL_TEST_READERS').deleteAll()
        ec.entity.find('moqui.security.UserGroup').condition('userGroupId', 'JEXL_TEST_READERS').deleteAll()
        ec.entity.find('moqui.security.UserAccount').condition('userId', 'JEXL_READER').deleteAll()
        ec.artifactExecution.enableAuthz()
        ec.destroy()
    }

    def setup() { ec.user.internalLoginUser('jexl.reader') }

    def cleanup() { ec.user.logoutUser() }

    private Map read(Map input, int maxRows = 100) {
        Map out = ec.service.sync().name(SVC).parameters([input: input, ownerUserId: ec.user.userId, runDate: RUN,
                timeZone: 'UTC', maxRows: maxRows]).call()
        ec.message.clearErrors()
        return out
    }

    def "aListedEntityReadsWithItsFilters"() {
        when:
        Map out = read([name: 'things', source: 'entity', entityName: 'jexl.test.ReadThing', fields: ['thingId', 'qty'],
                orderBy: ['thingId']])

        then:
        out.error == null
        out.rows*.thingId == ['RT1', 'RT2', 'RT4']
        out.rows[0].keySet() == ['thingId', 'qty'] as Set
        out.rowCount == 3
    }

    def "conditionsAndTheWindowNarrowTheRead"() {
        when:
        Map out = read([name: 'things', source: 'entity', entityName: 'jexl.test.ReadThing', fields: ['thingId'],
                where: [and: [[field: 'statusId', op: 'in', value: ['OPEN', 'DONE']], [field: 'qty', op: 'greater', value: 1]]],
                window: [field: 'madeDate', lastDays: 30], orderBy: ['-thingId']])

        then:
        out.rows*.thingId == ['RT2', 'RT1']
    }

    def "anUnlistedEntityIsRefused"() {
        when:
        Map out = read([name: 'o', source: 'entity', entityName: 'jexl.test.OtherThing', fields: ['otherId']])

        then:
        out.error.kind == 'READ_NOT_ALLOWED'
        out.error.message.contains('jexl.test.OtherThing')
    }

    def "aMoquiEntityIsAlwaysRefused"() {
        given:
        ec.user.logoutUser()
        ec.user.loginUser('john.doe', 'moqui')

        when:
        Map out = read([name: 'u', source: 'entity', entityName: 'moqui.security.UserAccount', fields: ['username']])

        then:
        out.error.kind == 'READ_NOT_ALLOWED'
    }

    def "aViewOverAMoquiEntityIsRefused"() {
        when:
        Map out = read([name: 'v', source: 'entity', entityName: 'jexl.test.ThingWithUser', fields: ['thingId', 'username']])

        then:
        out.error.kind == 'READ_NOT_ALLOWED'
        out.error.message.contains('moqui.security.UserAccount')
    }

    def "overTheRowCapSaysHowMany"() {
        when:
        Map out = read([name: 't', source: 'entity', entityName: 'jexl.test.ReadThing', fields: ['thingId']], 2)

        then:
        out.error.kind == 'READ_TOO_LARGE'
        out.error.message.contains('3 rows')
        out.rows == null || out.rows.isEmpty()
    }

    def "groupByCountsAndSums"() {
        when:
        Map out = read([name: 't', source: 'entity', entityName: 'jexl.test.ReadThing', groupBy: ['statusId'],
                measures: [[fn: 'count', as: 'n'], [fn: 'sum', field: 'qty', as: 'total']], orderBy: ['statusId']])

        then:
        out.error == null
        out.rows == [[statusId: 'DONE', n: 1, total: 3], [statusId: 'OPEN', n: 2, total: 9]]
    }

    def "theGroupCapCountsGroupsNotRows"() {
        when:
        Map out = read([name: 't', source: 'entity', entityName: 'jexl.test.ReadThing', groupBy: ['statusId'],
                measures: [[fn: 'count', as: 'n']]], 2)

        then:
        out.error == null
        out.rows.size() == 2
    }

    def "anUnknownFieldIsAReadError"() {
        when:
        Map out = read([name: 't', source: 'entity', entityName: 'jexl.test.ReadThing', fields: ['nope']])

        then:
        out.error.kind == 'READ_ERROR'
        out.error.message.contains('nope')
    }

    def "aFieldOnlyInTheConditionIsCheckedToo"() {
        when:
        Map out = read([name: 't', source: 'entity', entityName: 'jexl.test.ReadThing', fields: ['thingId'],
                where: [field: 'secret', op: 'equals', value: 'x']])

        then:
        out.error.kind == 'READ_ERROR'
        out.error.message.contains('secret')
    }
}

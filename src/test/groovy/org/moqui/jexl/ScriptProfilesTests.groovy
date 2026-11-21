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

import spock.lang.Specification

/** Named limit profiles: the two built in, and the ones an application adds or tunes by setting. */
class ScriptProfilesTests extends Specification {
    static final List<String> PROPS = ['jexl_profiles', 'jexl_profile_QUERY_units', 'jexl_profile_QUERY_maxReadRows',
                                       'jexl_profile_SCRIPT_timeoutMs', 'jexl_profile_SCRIPT_units']

    def cleanup() { PROPS.each { System.clearProperty(it) } }

    def "builtInsKeepTodaysLimits"() {
        expect:
        ScriptProfiles.get('SCRIPT').units == 2_000_000L
        ScriptProfiles.get('SCRIPT').timeoutMs == 2_000L
        ScriptProfiles.get('SCRIPT').maxCollectionSize == 10_000
        ScriptProfiles.get('SCRIPT').statements
        ScriptProfiles.get('EXPRESSION').timeoutMs == 250L
        ScriptProfiles.get('EXPRESSION').units == 100_000L
        !ScriptProfiles.get('EXPRESSION').statements
        ScriptProfiles.get('SCRIPT').maxAllocatedBytes == 64L * 1024 * 1024
    }

    def "anAppProfileStartsFromScriptAndOverrides"() {
        given:
        System.setProperty('jexl_profiles', 'QUERY')
        System.setProperty('jexl_profile_QUERY_units', '5000000')
        System.setProperty('jexl_profile_QUERY_maxReadRows', '20000')

        expect:
        ScriptProfiles.names().contains('QUERY')
        ScriptProfiles.get('QUERY').units == 5_000_000L
        ScriptProfiles.get('QUERY').maxReadRows == 20_000
        ScriptProfiles.get('QUERY').timeoutMs == 2_000L
        ScriptProfiles.get('QUERY').statements
    }

    def "aBuiltInCanBeTunedBySetting"() {
        given:
        System.setProperty('jexl_profile_SCRIPT_timeoutMs', '3000')

        expect:
        ScriptProfiles.get('SCRIPT').timeoutMs == 3_000L
    }

    def "anUnknownProfileIsRefused"() {
        when:
        ScriptProfiles.get('NOPE')

        then:
        IllegalArgumentException e = thrown()
        e.message.contains('NOPE')
    }

    def "aBadNumberFallsBack"() {
        given:
        System.setProperty('jexl_profile_SCRIPT_units', 'abc')

        expect:
        ScriptProfiles.get('SCRIPT').units == 2_000_000L
    }

    def "theSupervisorRunsAnAppProfile"() {
        given:
        System.setProperty('jexl_profiles', 'QUERY')

        when:
        Map out = ScriptSupervisor.evaluate('QUERY', 'var s = 0; for (var x : input) { s = s + x; } s', [1, 2, 3])

        then:
        out.ok
        out.result == 6
        out.profile == 'QUERY'
    }
}

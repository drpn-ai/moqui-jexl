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
import org.moqui.jexl.script.ScriptDeliveries
import org.moqui.jexl.script.ScriptResult
import spock.lang.Shared
import spock.lang.Specification

/** The built-in deliveries: email with the result's CSV files, and a Moqui notification with the summary only. */
class ScriptDeliveryTests extends Specification {
    static final String SVC = 'moqui.jexl.ScriptServices'
    static final Map FILE = [name: 'late.csv', columns: ['orderId', 'days'], rows: [['SO-1', 3], ['SO-2', 5]]]

    @Shared ExecutionContext ec
    List<Map> sent = []

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.user.loginUser('john.doe', 'moqui')
    }

    def cleanupSpec() {
        ec.artifactExecution.disableAuthz()
        ec.entity.find('moqui.basic.email.EmailMessage').condition('emailTemplateId', 'JEXL_SCRIPT_RESULT').deleteAll()
        ec.artifactExecution.enableAuthz()
        ec.user.logoutUser()
        ec.destroy()
    }

    def setup() {
        ScriptDeliveries.sendEmail = { def c, Map parameters -> sent << parameters; return [:] }
        ScriptDeliveries.sendNotification = { def c, Map notice -> sent << notice }
    }

    def cleanup() {
        ScriptDeliveries.resetForTests()
        System.clearProperty('jexl_email_attachment_bytes')
        System.clearProperty('jexl_email_from')
        ec.message.clearAll()
    }

    private Map deliver(String service, Map parameters, Map result = [summary: '2 late orders', files: [FILE], data: null]) {
        List<Map> files = ((List<Map>) result.files).collect { Map f -> [name: f.name, contentType: 'text/csv', contentBytes: ScriptResult.toCsv(f)] }
        Map out = ec.service.sync().name("${SVC}.${service}").parameters([scriptDeployment: [scriptDeploymentId: 'USD_MAIL', userScriptId: 'Late orders'],
                delivery: [parameters: parameters], scriptResult: result, files: files, scriptRunId: 'RUN1']).call()
        String errors = ec.message.errorsString
        ec.message.clearErrors()
        return out + [errors: errors]
    }

    def "emailCarriesSummaryAndCsvFiles"() {
        when:
        Map out = deliver('deliver#ScriptEmail', [toAddresses: 'ops@example.test', ccAddresses: 'lead@example.test'])

        then:
        out.outcome == 'OK'
        sent.size() == 1
        sent[0].emailTemplateId == 'JEXL_SCRIPT_RESULT'
        sent[0].toAddresses == 'ops@example.test'
        sent[0].ccAddresses == 'lead@example.test'
        sent[0].attachments*.fileName == ['late.csv']
        sent[0].attachments[0].contentType == 'text/csv'
        new String((byte[]) sent[0].attachments[0].contentBytes, 'UTF-8').contains('SO-2,5')
        sent[0].bodyParameters.summary == '2 late orders'
        sent[0].bodyParameters.fileNames == ['late.csv']
        !sent[0].bodyParameters.filesTooLarge
    }

    def "tooLargeEmailGoesWithoutFiles"() {
        given:
        System.setProperty('jexl_email_attachment_bytes', '10')

        when:
        Map out = deliver('deliver#ScriptEmail', [toAddresses: 'ops@example.test'])

        then:
        out.outcome == 'TOO_LARGE'
        out.message.contains('too large')
        sent[0].attachments == []
        sent[0].bodyParameters.filesTooLarge
    }

    def "missingToAddressesFails"() {
        when:
        Map out = deliver('deliver#ScriptEmail', [:])

        then:
        out.errors.contains('toAddresses')
        sent.isEmpty()
    }

    def "notificationCarriesSummaryNotFiles"() {
        when:
        Map out = deliver('deliver#ScriptNotification', [topic: 'JexlScriptResult', userGroupId: 'ADMIN'])

        then:
        out.outcome == 'OK'
        sent.size() == 1
        sent[0].topic == 'JexlScriptResult'
        sent[0].userGroupId == 'ADMIN'
        sent[0].message.summary == '2 late orders'
        sent[0].message.fileNames == ['late.csv']
        !sent[0].message.containsKey('files')
    }

    def "aRealEmailIsStoredWithItsBody"() {
        given:
        ScriptDeliveries.resetForTests()
        System.setProperty('jexl_email_from', 'scripts@example.test')

        when:
        Map out = deliver('deliver#ScriptEmail', [toAddresses: 'ops@example.test'])
        def stored = ec.entity.find('moqui.basic.email.EmailMessage').condition('emailTemplateId', 'JEXL_SCRIPT_RESULT')
                .orderBy('-sentDate').disableAuthz().useCache(false).list()

        then:
        out.outcome == 'OK'
        stored.size() >= 1
        (stored.find { it.toAddresses == 'ops@example.test' }?.body as String)?.contains('2 late orders')
    }

    def "onlyAllowedTemplatesAreUsed"() {
        when:
        Map refused = deliver('deliver#ScriptEmail', [toAddresses: 'ops@example.test', emailTemplateId: 'PASSWORD_RESET'])
        System.setProperty('jexl_email_templates', 'JEXL_SCRIPT_RESULT,APP_TEMPLATE')
        Map allowed = deliver('deliver#ScriptEmail', [toAddresses: 'ops@example.test', emailTemplateId: 'APP_TEMPLATE'])

        then:
        refused.errors.contains('PASSWORD_RESET')
        sent.size() == 1
        sent[0].emailTemplateId == 'APP_TEMPLATE'
        allowed.outcome == 'OK'

        cleanup:
        System.clearProperty('jexl_email_templates')
    }

    def "notificationsGoOnlyToTheOwnerOrTheOwnersGroups"() {
        when:
        Map otherUser = deliver('deliver#ScriptNotification', [topic: 'JexlScriptResult', userId: 'SOMEONE_ELSE'])
        Map otherGroup = deliver('deliver#ScriptNotification', [topic: 'JexlScriptResult', userGroupId: 'NOT_MY_GROUP'])
        Map self = deliver('deliver#ScriptNotification', [topic: 'JexlScriptResult', userId: ec.user.userId])

        then:
        otherUser.errors.contains('SOMEONE_ELSE')
        otherGroup.errors.contains('NOT_MY_GROUP')
        self.outcome == 'OK'
        sent.size() == 1
    }

    def "everyoneIsNotTheOwnersGroup"() {
        when:
        Map out = deliver('deliver#ScriptNotification', [topic: 'JexlScriptResult', userGroupId: 'ALL_USERS'])

        then:
        out.errors.contains('ALL_USERS')
        sent.isEmpty()
    }
}

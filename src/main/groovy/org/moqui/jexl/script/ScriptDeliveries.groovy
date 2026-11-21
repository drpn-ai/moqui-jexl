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

/**
 * The built-in deliveries. Email: the summary in the body (EmailTemplate JEXL_SCRIPT_RESULT unless the delivery names
 * another) and the result's files attached as CSV, through Moqui's send#EmailTemplate; over jexl_email_attachment_bytes
 * (20 MB) in all, the email goes without its files and says so (TOO_LARGE). Notification: a Moqui NotificationMessage
 * to a topic, with the summary and the file names, never the files.
 */
@CompileStatic
class ScriptDeliveries {
    static final String DEFAULT_TEMPLATE = 'JEXL_SCRIPT_RESULT'
    static final long DEFAULT_ATTACHMENT_BYTES = 20L * 1024 * 1024

    private static final Closure DEFAULT_SEND_EMAIL = { ExecutionContext ec, Map parameters ->
        return ec.service.sync().name('org.moqui.impl.EmailServices.send#EmailTemplate').parameters(parameters).call()
    }
    private static final Closure DEFAULT_SEND_NOTIFICATION = { ExecutionContext ec, Map notice ->
        def message = ec.makeNotificationMessage().topic((String) notice.topic).title((String) notice.title)
                .message((Map<String, Object>) notice.message)
        if (notice.userGroupId) message.userGroupId((String) notice.userGroupId)
        if (notice.userId) message.userId((String) notice.userId)
        message.send()
    }
    /** Seams: what actually sends; replaced in tests. */
    static Closure sendEmail = DEFAULT_SEND_EMAIL
    static Closure sendNotification = DEFAULT_SEND_NOTIFICATION

    static void resetForTests() {
        sendEmail = DEFAULT_SEND_EMAIL
        sendNotification = DEFAULT_SEND_NOTIFICATION
    }

    static long maxAttachmentBytes() {
        String raw = SystemBinding.getPropOrEnv('jexl_email_attachment_bytes')
        try { return raw ? Long.parseLong(raw.trim()) : DEFAULT_ATTACHMENT_BYTES } catch (NumberFormatException ignored) { return DEFAULT_ATTACHMENT_BYTES }
    }

    /** [outcome, message], or [error] when the delivery's parameters cannot be used. */
    static Map<String, Object> email(ExecutionContext ec, Map scriptDeployment, Map parameters, Map scriptResult, List<Map> files) {
        String to = parameters?.get('toAddresses')?.toString()?.trim()
        if (!to) return [error: 'The email delivery needs toAddresses.'] as Map<String, Object>
        long total = (long) (files ?: []).sum(0L) { Map f -> ((byte[]) f.contentBytes)?.length ?: 0 }
        boolean tooLarge = total > maxAttachmentBytes()
        List<String> fileNames = (files ?: []).collect { Map f -> f.name.toString() }
        List<Map> attachments = tooLarge ? [] : (files ?: []).collect { Map f ->
            [fileName: f.name, contentType: f.contentType ?: 'text/csv', contentBytes: f.contentBytes] as Map
        }
        String scriptName = scriptName(ec, scriptDeployment)
        Map body = [scriptName: scriptName, summary: scriptResult?.get('summary'), fileNames: fileNames, filesTooLarge: tooLarge,
                    attachmentBytes: total, maxAttachmentBytes: maxAttachmentBytes()] as Map
        sendEmail.call(ec, [emailTemplateId: parameters.get('emailTemplateId') ?: DEFAULT_TEMPLATE, toAddresses: to,
                ccAddresses: parameters.get('ccAddresses') ?: null, bodyParameters: body, attachments: attachments] as Map)
        if (ec.message.hasError()) return [error: ec.message.errorsString] as Map<String, Object>
        if (tooLarge) {
            return [outcome: 'TOO_LARGE', message: "Sent to ${to} without its files: ${total} bytes is too large to attach (limit ${maxAttachmentBytes()}).".toString()] as Map<String, Object>
        }
        return [outcome: 'OK', message: "Sent to ${to}${fileNames ? ' with ' + fileNames.size() + ' file' + (fileNames.size() == 1 ? '' : 's') : ''}.".toString()] as Map<String, Object>
    }

    static Map<String, Object> notification(ExecutionContext ec, Map scriptDeployment, Map parameters, Map scriptResult, List<Map> files) {
        String topic = parameters?.get('topic')?.toString()?.trim()
        if (!topic) return [error: 'The notification delivery needs a topic.'] as Map<String, Object>
        String scriptName = scriptName(ec, scriptDeployment)
        sendNotification.call(ec, [topic: topic, title: scriptName, userGroupId: parameters.get('userGroupId'), userId: parameters.get('userId'),
                message: [scriptName: scriptName, summary: scriptResult?.get('summary'),
                          fileNames: (files ?: []).collect { Map f -> f.name.toString() }]] as Map)
        return [outcome: 'OK', message: "Notified ${topic}.".toString()] as Map<String, Object>
    }

    private static String scriptName(ExecutionContext ec, Map scriptDeployment) {
        String id = scriptDeployment?.get('userScriptId')?.toString()
        EntityValue script = id ? ec.entity.find('jexl.script.UserScript').condition('userScriptId', id).disableAuthz().useCache(false).one() : null
        return script?.getString('scriptName') ?: id ?: 'User script'
    }
}

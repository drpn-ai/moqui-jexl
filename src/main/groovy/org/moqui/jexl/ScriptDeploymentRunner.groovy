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

import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityValue
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.service.ScheduledJobRunner
import org.moqui.impl.service.ServiceDefinition
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A user script put to work (moqui.jexl.UserScriptDeployment): on its cron, in its own time zone, the input service
 * reads the input (or the script's sample is used), the script runs in ScriptSupervisor, and the delivery service
 * receives what it returned as scriptResult. The script stays pure: it reads and returns data, the services do the I/O.
 *
 * NOTE: a result of null, blank text or an empty list or map means nothing to deliver, so the delivery service is not called
 */
class ScriptDeploymentRunner {
    protected final static Logger logger = LoggerFactory.getLogger(ScriptDeploymentRunner.class)

    static final String ENTITY = 'moqui.jexl.UserScriptDeployment'
    static final String SCRIPT_ENTITY = 'moqui.jexl.UserScript'
    /** the only services a deployment may name: the scheduler runs them with no user, so a free name would run anything */
    static final String INPUT_INTERFACE = 'moqui.jexl.ScriptServices.read#UserScriptDeploymentInput'
    static final String DELIVERY_INTERFACE = 'moqui.jexl.ScriptServices.deliver#UserScriptDeploymentResult'

    static final String DELIVERED = 'DELIVERED'
    static final String PREVIEW = 'PREVIEW'
    static final String NOTHING_TO_DELIVER = 'NOTHING_TO_DELIVER'
    static final String SCRIPT_ERROR = 'SCRIPT_ERROR'
    static final String NO_INPUT = 'NO_INPUT'
    static final String NOT_DELIVERED = 'NOT_DELIVERED'
    static final String SCRIPT_MISSING = 'SCRIPT_MISSING'
    /** due but the server is busy, retried on the next scan */
    static final String WAITING = 'WAITING'
    /** the server stayed busy past this run's window; skipped, never forced */
    static final String EXPIRED = 'EXPIRED'

    /** The next time the cron fires after from, read in timeZone (the server's zone when empty); null when none. */
    static Timestamp nextRunDate(String cronExpression, String timeZone, Timestamp from) {
        if (!cronExpression?.trim()) return null
        ZoneId zone = timeZone ? ZoneId.of(timeZone) : ZoneId.systemDefault()
        Timestamp after = from ?: new Timestamp(System.currentTimeMillis())
        Optional<ZonedDateTime> next = ScheduledJobRunner.getExecutionTime(cronExpression.trim())
                .nextExecution(ZonedDateTime.ofInstant(after.toInstant(), zone))
        return next.isPresent() ? Timestamp.from(next.get().toInstant()) : null
    }

    /**
     * Runs one deployment. deliver=false is a preview: the script runs and its result comes back, the delivery service
     * is not called and the deployment's last-run fields are left alone. Never throws; errors the input or delivery
     * service adds are reported in the outcome and kept off the caller's messages.
     * {status, message, result, run, inputSummary, delivery}
     */
    static Map<String, Object> execute(ExecutionContext ec, EntityValue deployment, boolean deliver) {
        ec.message.pushErrors()
        try {
            return executeChecked(ec, deployment, deliver)
        } catch (Throwable t) {
            logger.warn("Script deployment ${deployment.scriptDeploymentId} failed", t)
            return [status: SCRIPT_ERROR, message: "The deployment failed: ${t.message ?: t.getClass().simpleName}".toString()] as Map<String, Object>
        } finally {
            ec.message.clearErrors()
            ec.message.popErrors()
        }
    }

    private static Map<String, Object> executeChecked(ExecutionContext ec, EntityValue deployment, boolean deliver) {
        EntityValue script = ec.entity.find(SCRIPT_ENTITY).condition('userScriptId', deployment.userScriptId)
                .useCache(false).disableAuthz().one()
        if (script == null || script.isActive == 'N') {
            return [status: SCRIPT_MISSING, message: 'The script was deleted or switched off.'] as Map<String, Object>
        }

        Map<String, Object> prepared = readInput(ec, deployment, script)
        if (prepared.status) return prepared

        Map<String, Object> run = ScriptSupervisor.evaluate(script.scriptProfile, script.scriptBody as String, prepared.input)
        Map<String, Object> base = [run: run, inputSummary: prepared.inputSummary] as Map<String, Object>
        if (!run.ok) {
            Map error = run.error as Map
            return base + [status: SCRIPT_ERROR, message: "${error?.line ? 'Line ' + error.line + ': ' : ''}${error?.message}".toString()]
        }

        Object result = run.result
        base.result = result
        if (isNothing(result)) return base + [status: NOTHING_TO_DELIVER, message: 'The script returned nothing, so nothing was delivered.']
        if (!deliver) return base + [status: PREVIEW, message: 'Preview only: nothing was delivered.']

        String deliveryServiceName = deployment.deliveryServiceName
        if (!implementsInterface(ec, deliveryServiceName, DELIVERY_INTERFACE)) {
            return base + [status: NOT_DELIVERED, message: "Not delivered: ${deliveryServiceName} does not implement ${DELIVERY_INTERFACE}.".toString()]
        }
        Map<String, Object> delivery = ec.service.sync().name(deliveryServiceName)
                .parameters([scriptDeployment: deployment.getMap(), scriptResult: result]).call()
        if (ec.message.hasError()) {
            return base + [status: NOT_DELIVERED, message: "Not delivered: ${ec.message.errorsString}".toString()]
        }
        return base + [status: DELIVERED, delivery: delivery, message: (delivery?.message ?: 'Delivered.').toString()]
    }

    /** {input, inputSummary}, or an outcome with status NO_INPUT */
    private static Map<String, Object> readInput(ExecutionContext ec, EntityValue deployment, EntityValue script) {
        String inputServiceName = deployment.inputServiceName
        if (!inputServiceName) {
            String json = script.sampleInputJson
            try {
                return [input: ScriptSupervisor.parseInputJson(json), inputSummary: [source: 'SAMPLE', characters: (json ?: '').length()]] as Map<String, Object>
            } catch (IllegalArgumentException e) {
                return [status: NO_INPUT, message: "Sample input: ${e.message}".toString()] as Map<String, Object>
            }
        }
        if (!implementsInterface(ec, inputServiceName, INPUT_INTERFACE)) {
            return [status: NO_INPUT, message: "${inputServiceName} does not implement ${INPUT_INTERFACE}.".toString()] as Map<String, Object>
        }
        Map<String, Object> out = ec.service.sync().name(inputServiceName)
                .parameters([scriptDeployment: deployment.getMap(), userScriptId: script.userScriptId]).call()
        if (ec.message.hasError()) return [status: NO_INPUT, message: ec.message.errorsString] as Map<String, Object>
        if (out?.get('input') == null) {
            return [status: NO_INPUT, message: (out?.get('message') ?: 'The input service returned no input.').toString()] as Map<String, Object>
        }
        return [input: out.input, inputSummary: out.inputSummary ?: [source: inputServiceName]] as Map<String, Object>
    }

    /** True when serviceName is defined and declares implements service=interfaceName. */
    static boolean implementsInterface(ExecutionContext ec, String serviceName, String interfaceName) {
        if (!serviceName) return false
        ServiceDefinition sd = ((ExecutionContextImpl) ec).serviceFacade.getServiceDefinition(serviceName)
        if (sd == null) return false
        return sd.serviceNode.children('implements').any { MNode node -> node.attribute('service') == interfaceName }
    }

    static boolean isNothing(Object result) {
        if (result == null) return true
        if (result instanceof CharSequence) return !result.toString().trim()
        if (result instanceof Map) return ((Map) result).isEmpty()
        if (result instanceof Collection) return ((Collection) result).isEmpty()
        return false
    }

    /** Stamps a real run (scheduled or delivered by hand) on the deployment. A preview is never recorded. */
    static void recordOutcome(ExecutionContext ec, String deploymentId, Map<String, Object> outcome) {
        Map run = outcome.run instanceof Map ? (Map) outcome.run : [:]
        ec.transaction.runUseOrBegin(30, 'Error recording a script deployment run', {
            EntityValue deployment = ec.entity.find(ENTITY).condition('scriptDeploymentId', deploymentId)
                    .forUpdate(true).useCache(false).disableAuthz().one()
            if (deployment == null) return
            deployment.set('lastRunDate', ec.user.nowTimestamp)
            deployment.set('lastRunStatus', outcome.status?.toString())
            deployment.set('lastRunMessage', fitField(outcome.message?.toString()))
            deployment.set('lastRunUnits', run.units != null ? ((Number) run.units).longValue() : null)
            deployment.update()
        })
    }

    /**
     * The scheduled pass: every active deployment whose nextRunDate has come, each claimed (its next run date moved
     * forward) before it runs, so a slow run or an overlapping scan never delivers twice. While the server is busy a due
     * deployment is held instead (holdForLoad). {ran, held, results: [{scriptDeploymentId, status, message, nextRunDate}]}
     */
    static Map<String, Object> scanDue(ExecutionContext ec, Timestamp now, int limit) {
        Timestamp scanTime = now ?: new Timestamp(System.currentTimeMillis())
        List<EntityValue> due = ec.entity.find(ENTITY).condition('isActive', 'Y')
                .condition('nextRunDate', EntityCondition.LESS_THAN_EQUAL_TO, scanTime)
                .orderBy('nextRunDate').limit(Math.max(1, limit)).useCache(false).disableAuthz().list()
        List<Map<String, Object>> results = []
        for (EntityValue deployment in due) {
            String id = deployment.scriptDeploymentId
            String busyReason = ScriptLoadGate.busyReason(ec)
            if (busyReason) {
                Map<String, Object> held = holdForLoad(ec, deployment, scanTime, busyReason)
                if (held != null) results << held
                continue
            }
            Timestamp next = nextRunDate(deployment.cronExpression as String, deployment.timeZone as String, scanTime)
            // claimed by moving its next run date forward first, so a slow run or an overlapping scan never delivers twice
            if (!updateIfStillDue(ec, id, scanTime) { EntityValue current -> current.set('nextRunDate', next) }) continue
            Map<String, Object> outcome = execute(ec, deployment, true)
            recordOutcome(ec, id, outcome)
            logger.info("Script deployment ${id} ran: ${outcome.status} ${outcome.message}")
            results << ([scriptDeploymentId: id, status: outcome.status, message: outcome.message, nextRunDate: next] as Map<String, Object>)
        }
        int held = results.count { Map r -> r.status == WAITING || r.status == EXPIRED } as int
        return [ran: results.size() - held, held: held, results: results] as Map<String, Object>
    }

    /**
     * The server is busy: leave this due run for the next scan (WAITING), or once its window has passed skip it
     * (EXPIRED), never forced. The window ends at due + script_max_wait_minutes, or at the cron's next run if sooner.
     */
    static Map<String, Object> holdForLoad(ExecutionContext ec, EntityValue deployment, Timestamp now, String reason) {
        String id = deployment.scriptDeploymentId
        String cronExpression = deployment.cronExpression, timeZone = deployment.timeZone
        Timestamp dueAt = deployment.nextRunDate as Timestamp
        Timestamp followingRun = nextRunDate(cronExpression, timeZone, dueAt)
        long windowEnd = dueAt.time + ScriptLoadGate.scheduledMaxWaitMinutes() * 60_000L
        Timestamp expireAt = new Timestamp(followingRun != null ? Math.min(windowEnd, followingRun.time) : windowEnd)
        boolean expired = !now.before(expireAt)
        Timestamp next = expired ? nextRunDate(cronExpression, timeZone, now) : dueAt
        String status = expired ? EXPIRED : WAITING
        String message = expired
                ? "Not run: the server stayed busy (${reason}) from ${utc(dueAt)} until this run expired at ${utc(expireAt)}. Next run ${utc(next)}.".toString()
                : "Waiting since ${utc(dueAt)}: the server is busy (${reason}) and scripts run last. Expires at ${utc(expireAt)} if it stays busy.".toString()
        boolean written = updateIfStillDue(ec, id, now) { EntityValue current ->
            if (expired) current.set('nextRunDate', next)
            current.set('lastRunStatus', status)
            current.set('lastRunMessage', fitField(message))
        }
        if (!written) return null
        logger.info("Script deployment ${id} ${status}: ${message}")
        return [scriptDeploymentId: id, status: status, message: message, nextRunDate: next, expireAt: expireAt] as Map<String, Object>
    }

    /**
     * Re-reads one deployment under a row lock and, while it is still active and due, applies change and saves it.
     * False when another scan got there first or it was switched off.
     */
    private static boolean updateIfStillDue(ExecutionContext ec, String deploymentId, Timestamp now, Closure change) {
        return ec.transaction.runUseOrBegin(30, 'Error updating a due script deployment', {
            EntityValue current = ec.entity.find(ENTITY).condition('scriptDeploymentId', deploymentId)
                    .forUpdate(true).useCache(false).disableAuthz().one()
            Timestamp nextRun = current?.nextRunDate as Timestamp
            if (current == null || current.isActive != 'Y' || nextRun == null || nextRun.after(now)) return false
            change.call(current)
            current.update()
            return true
        }) as boolean
    }

    /** a message cut to fit a 255-character field */
    private static String fitField(String message) { message && message.length() > 255 ? message.substring(0, 254) + '…' : message }

    private static String utc(Timestamp time) { time == null ? '-' : time.toInstant().toString().take(16).replace('T', ' ') + ' UTC' }
}

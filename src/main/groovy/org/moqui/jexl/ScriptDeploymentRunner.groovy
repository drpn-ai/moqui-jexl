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

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityCondition
import org.moqui.jexl.script.ReadSpec
import org.moqui.jexl.script.ReadStep
import org.moqui.jexl.script.ScriptResult
import org.moqui.entity.EntityValue
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.service.ScheduledJobRunner
import org.moqui.impl.service.ServiceDefinition
import org.moqui.util.MNode
import org.moqui.util.SystemBinding
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A user script put to work (jexl.script.UserScriptDeployment): on its cron, in its own time zone, as its owner (re-checked
 * every run), the script's read step (or an input service, or its sample) gives the input, the script runs in
 * ScriptSupervisor, its result is checked against the result contract (ScriptResult), and every active delivery receives
 * it; one failing delivery never stops the others. A real run is recorded (UserScriptRun and the last-run fields). The
 * script stays pure: it reads and returns data, the services do the I/O.
 *
 * NOTE: a result of null, blank text or an empty list or map is nothing to send, so no delivery is called
 */
class ScriptDeploymentRunner {
    protected final static Logger logger = LoggerFactory.getLogger(ScriptDeploymentRunner.class)

    static final String ENTITY = 'jexl.script.UserScriptDeployment'
    static final String SCRIPT_ENTITY = 'jexl.script.UserScript'
    /** the only services a deployment may name: the scheduler runs them with no user, so a free name would run anything */
    static final String INPUT_INTERFACE = 'moqui.jexl.ScriptServices.read#UserScriptDeploymentInput'
    static final String DELIVERY_INTERFACE = 'moqui.jexl.ScriptServices.deliver#UserScriptDeploymentResult'

    static final String SCHEDULE_GROUP = 'JEXL_SCRIPT_SCHEDULE'

    static final String OK = 'OK'
    static final String PREVIEW = 'PREVIEW'
    static final String NOTHING_TO_SEND = 'NOTHING_TO_SEND'
    static final String SCRIPT_ERROR = 'SCRIPT_ERROR'
    static final String RESULT_INVALID = 'RESULT_INVALID'
    static final String NO_INPUT = 'NO_INPUT'
    static final String OWNER_LOST = 'OWNER_LOST'
    static final String DELIVERY_PARTIAL = 'DELIVERY_PARTIAL'
    static final String DELIVERY_FAILED = 'DELIVERY_FAILED'
    static final String SCRIPT_MISSING = 'SCRIPT_MISSING'
    static final String STUCK = 'STUCK'
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
     * Runs one deployment. deliver=false is a preview: the script runs and its result comes back, no delivery is called
     * and nothing is recorded. Never throws; errors a service adds are reported in the outcome and kept off the caller's
     * messages. {status, message, result (raw), normalized, run, inputSummary, readCounts, deliveries, scriptRunId}
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
        Timestamp started = ec.user.nowTimestamp
        EntityValue script = ec.entity.find(SCRIPT_ENTITY).condition('userScriptId', deployment.userScriptId)
                .useCache(false).disableAuthz().one()
        if (script == null || script.isActive == 'N') {
            return [status: SCRIPT_MISSING, message: 'The script was deleted or switched off.', startDate: started] as Map<String, Object>
        }
        String ownerUserId = deployment.ownerUserId
        Map<String, Object> problem = ownerProblem(ec, ownerUserId, deployment)
        if (problem) return problem + [startDate: started]
        String ownerKey = ownerKey(ec, deployment)
        ScriptProfile profile = ScriptProfiles.get(script.getString('scriptProfile') ?: ScriptProfiles.SCRIPT)

        Map<String, Object> prepared
        if (script.readSpecJson) {
            ReadSpec spec
            try {
                spec = ReadSpec.parse(script.getString('readSpecJson'))
            } catch (ReadSpec.Invalid e) {
                return [status: 'READ_ERROR', message: "Read spec: ${e.message}".toString(), startDate: started] as Map<String, Object>
            }
            Map<String, Object> read = ReadStep.run(ec, spec, ownerUserId, ownerKey, started, deployment.getString('timeZone'), profile,
                    deployment.getString('userScriptId'))
            if (!read.ok) return [status: read.status, message: read.message, startDate: started] as Map<String, Object>
            prepared = [input: read.input, inputSummary: [source: 'READ'], readCounts: read.readCounts] as Map<String, Object>
        } else {
            prepared = readInput(ec, deployment, script)
            if (prepared.status) return prepared + [startDate: started]
        }

        Map<String, Object> run = ScriptSupervisor.evaluate(script.scriptProfile, script.scriptBody as String, prepared.input, ownerKey)
        Map<String, Object> base = [run: run, inputSummary: prepared.inputSummary, readCounts: prepared.readCounts, startDate: started] as Map<String, Object>
        if (!run.ok) {
            Map error = run.error as Map
            String kind = error?.kind
            String status = kind in ['HELD', 'DISABLED', 'BUSY'] ? (kind == 'BUSY' ? 'HELD' : kind) : (run.stuck ? STUCK : SCRIPT_ERROR)
            return base + [status: status, errorKind: kind, message: "${error?.line ? 'Line ' + error.line + ': ' : ''}${error?.message}".toString()]
        }

        base.result = run.result
        Map<String, Object> normalized = ScriptResult.normalize(run.result, profile)
        base.normalized = normalized
        if (normalized.invalid) return base + [status: RESULT_INVALID, message: normalized.message]
        if (normalized.nothing) return base + [status: NOTHING_TO_SEND, message: 'The script returned nothing, so nothing was sent.']
        if (!deliver) return base + [status: PREVIEW, message: 'Preview only: nothing was sent.']

        String scriptRunId = ec.entity.sequencedIdPrimary('jexl.script.UserScriptRun', null, null)
        base.scriptRunId = scriptRunId
        List<Map> files = ((List<Map>) normalized.files).collect { Map f ->
            [name: f.name, contentType: 'text/csv', contentBytes: ScriptResult.toCsv(f)] as Map
        }
        List<EntityValue> deliveries = ec.entity.find('jexl.script.UserScriptDeploymentDelivery')
                .condition('scriptDeploymentId', deployment.scriptDeploymentId).condition('isActive', 'Y')
                .orderBy('deliverySeqId').useCache(false).disableAuthz().list()
        if (deliveries.isEmpty()) return base + [status: DELIVERY_FAILED, message: 'The deployment has no active delivery.', deliveries: []]
        // NOTE: deliveries run as the owner, in a fresh context with authz on, like the read; past the time limit no
        // further delivery starts, and the outcomes already known are kept
        long timeout = deliveryTimeoutMs()
        long deadline = System.currentTimeMillis() + timeout
        List<Map> known = Collections.synchronizedList(new ArrayList<Map>())
        LaneResult lane = org.moqui.jexl.script.AsOwner.run(ec, ownerUserId, ownerKey, timeout) { ExecutionContext own ->
            for (EntityValue d : deliveries) {
                if (System.currentTimeMillis() >= deadline || Thread.currentThread().isInterrupted()) break
                known.add(deliverOne(own, deployment, d, normalized, files, scriptRunId))
            }
            return known
        }
        List<Map> outcomes = new ArrayList<Map>(known)
        if (lane.status != 'OK' || outcomes.size() < deliveries.size()) {
            String why = lane.status == 'OK' || lane.status == 'TIME' ? "the deliveries took longer than ${timeout} ms" : (lane.message ?: lane.error?.message ?: lane.status)
            return base + [status: DELIVERY_FAILED, message: "Not delivered: ${why}.".toString(), deliveries: outcomes]
        }
        int failed = outcomes.count { Map o -> o.outcome == 'FAILED' } as int
        String status = failed == 0 ? OK : (failed == outcomes.size() ? DELIVERY_FAILED : DELIVERY_PARTIAL)
        String message = outcomes.size() == 1 ? outcomes[0].message :
                outcomes.collect { Map o -> "${o.deliveryServiceName}: ${o.outcome}${o.message ? ' (' + o.message + ')' : ''}" }.join('; ')
        return base + [status: status, message: message, deliveries: outcomes]
    }

    /** One delivery, interface-checked; its outcome (OK, TOO_LARGE or FAILED) also lands on its own row. */
    private static Map deliverOne(ExecutionContext ec, EntityValue deployment, EntityValue delivery, Map normalized, List<Map> files,
                                  String scriptRunId) {
        String service = delivery.getString('deliveryServiceName')
        Map outcome
        if (!implementsInterface(ec, service, DELIVERY_INTERFACE)) {
            outcome = [outcome: 'FAILED', message: "${service} does not implement ${DELIVERY_INTERFACE}.".toString()]
        } else {
            Map out = null
            try {
                Object parsed = delivery.parametersJson ? new JsonSlurper().parseText(delivery.getString('parametersJson')) : [:]
                if (!(parsed instanceof Map)) throw new IllegalArgumentException('its parameters are not a JSON object')
                Map deliveryMap = delivery.getMap() + [parameters: (Map) parsed]
                out = ec.service.sync().name(service).parameters([scriptDeployment: deployment.getMap(), delivery: deliveryMap,
                        scriptResult: normalized, files: files, scriptRunId: scriptRunId]).call()
            } catch (Throwable t) {
                ec.message.addError(t.message ?: t.getClass().simpleName)
            }
            if (ec.message.hasError()) {
                outcome = [outcome: 'FAILED', message: ec.message.errorsString.trim()]
                ec.message.clearErrors()
            } else {
                outcome = [outcome: (out?.outcome ?: 'OK').toString(), message: (out?.message ?: 'Delivered.').toString()]
            }
        }
        // NOTE: the outcome is the component's own record, not the owner's action
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            ec.transaction.runUseOrBegin(30, 'Error recording a script delivery outcome', {
                EntityValue row = ec.entity.find('jexl.script.UserScriptDeploymentDelivery').condition([scriptDeploymentId: delivery.scriptDeploymentId,
                        deliverySeqId: delivery.deliverySeqId]).useCache(false).one()
                row?.set('lastOutcome', outcome.outcome)
                row?.set('lastMessage', fitField(outcome.message as String))
                row?.update()
            })
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
        }
        return outcome + [deliverySeqId: delivery.deliverySeqId, deliveryServiceName: service]
    }

    static long deliveryTimeoutMs() {
        String raw = SystemBinding.getPropOrEnv('jexl_delivery_timeout_ms')
        try { return raw ? Long.parseLong(raw.trim()) : 120_000L } catch (NumberFormatException ignored) { return 120_000L }
    }

    /**
     * Why the owner cannot run this deployment now, as an outcome, or null. OWNER_LOST (the deployment switches off):
     * no owner, missing, disabled for good, terminated, or no longer allowed to schedule. HELD (tried again next time):
     * a temporary lockout after failed logins, which anyone who knows the username can cause.
     */
    static Map<String, Object> ownerProblem(ExecutionContext ec, String userId, EntityValue deployment = null) {
        if (!userId) return lost('The deployment has no owner.')
        EntityValue account = ec.entity.find('moqui.security.UserAccount').condition('userId', userId).useCache(false).disableAuthz().one()
        if (account == null) return lost("The owner ${userId} no longer exists, so the deployment was switched off.")
        Timestamp now = ec.user.nowTimestamp
        if (account.terminateDate != null && !((Timestamp) account.terminateDate).after(now)) {
            return lost("The owner ${account.username}'s account was terminated, so the deployment was switched off.")
        }
        if (account.disabled == 'Y') {
            if (account.disabledDateTime != null) {
                return [status: 'HELD', message: "Not run: the owner ${account.username} is locked out for now; it runs again at its next time.".toString()] as Map<String, Object>
            }
            return lost("The owner ${account.username} is disabled, so the deployment was switched off.")
        }
        if (!maySchedule(ec, userId)) return lost("The owner ${account.username} may no longer schedule scripts, so the deployment was switched off.")
        // NOTE: the application's own rule (e.g. the owner still acts for the deployment's organization)
        String hook = SystemBinding.getPropOrEnv('jexl_owner_check_service')
        if (hook && deployment != null) {
            boolean hadError = ec.message.hasError()
            Map out = ec.service.sync().name(hook).parameters([scriptDeployment: deployment.getMap(), ownerUserId: userId]).disableAuthz().call()
            String said = !hadError && ec.message.hasError() ? ec.message.errorsString : out?.problem?.toString()
            if (!hadError) ec.message.clearErrors()
            if (said) return lost("${said} The deployment was switched off.")
        }
        return null
    }

    private static Map<String, Object> lost(Object message) { [status: OWNER_LOST, message: message.toString()] as Map<String, Object> }

    /** Whether one of the user's current groups (or ALL_USERS) has a grant on JEXL_SCRIPT_SCHEDULE; any DENY wins. */
    static boolean maySchedule(ExecutionContext ec, String userId) {
        Timestamp now = ec.user.nowTimestamp
        List<String> groups = ec.entity.find('moqui.security.UserGroupMember').condition('userId', userId).useCache(false).disableAuthz().list()
                .findAll { EntityValue m -> (m.fromDate == null || !((Timestamp) m.fromDate).after(now)) && (m.thruDate == null || ((Timestamp) m.thruDate).after(now)) }
                .collect { EntityValue m -> m.getString('userGroupId') }
        groups.add('ALL_USERS')
        List<EntityValue> grants = ec.entity.find('moqui.security.ArtifactAuthz').condition('artifactGroupId', SCHEDULE_GROUP)
                .condition('userGroupId', EntityCondition.IN, groups).useCache(false).disableAuthz().list()
        if (grants.any { EntityValue g -> g.authzTypeEnumId == 'AUTHZT_DENY' }) return false
        return grants.any { EntityValue g -> g.authzTypeEnumId in ['AUTHZT_ALLOW', 'AUTHZT_ALWAYS'] }
    }

    /** The lane owner key: what the service named by jexl_owner_key_service returns, else the owner's user id. */
    static String ownerKey(ExecutionContext ec, EntityValue deployment) {
        String keyService = SystemBinding.getPropOrEnv('jexl_owner_key_service')
        if (keyService) {
            Map out = ec.service.sync().name(keyService).parameters([scriptDeployment: deployment.getMap()]).disableAuthz().call()
            if (out?.ownerKey) return out.ownerKey.toString()
        }
        return deployment.getString('ownerUserId')
    }

    /** {input, inputSummary}, or an outcome with status NO_INPUT */
    private static Map<String, Object> readInput(ExecutionContext ec, EntityValue deployment, EntityValue script) {
        String inputServiceName = deployment.inputServiceName
        if (!inputServiceName) {
            String json = script.sampleInputJson
            try {
                return [input: ScriptSupervisor.parseInputJson(json, script.getString("scriptProfile")), inputSummary: [source: 'SAMPLE', characters: (json ?: '').length()]] as Map<String, Object>
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
        if (beforeRecord != null) beforeRecord.call(deploymentId)
        Map run = outcome.run instanceof Map ? (Map) outcome.run : [:]
        ec.transaction.runUseOrBegin(30, 'Error recording a script deployment run', {
            EntityValue deployment = ec.entity.find(ENTITY).condition('scriptDeploymentId', deploymentId)
                    .forUpdate(true).useCache(false).disableAuthz().one()
            if (deployment == null) return
            deployment.set('lastRunDate', ec.user.nowTimestamp)
            deployment.set('lastRunStatus', outcome.status?.toString())
            deployment.set('lastRunMessage', fitField(outcome.message?.toString()))
            deployment.set('lastRunUnits', run.units != null ? ((Number) run.units).longValue() : null)
            // NOTE: an owner who can no longer run it switches it off; turning it back on makes the new person owner
            if (outcome.status == OWNER_LOST) deployment.set('isActive', 'N')
            deployment.update()

            boolean wasDisabled = ec.artifactExecution.disableAuthz()
            try {
            // NOTE: the application's extended fields (an owner or organization field) follow the deployment onto its runs
            Map<String, Object> carried = [:]
            for (String f : org.moqui.jexl.script.OwnerFields.extended(ec, 'jexl.script.UserScriptRun')) {
                if (deployment.getEntityDefinition().isField(f)) carried.put(f, deployment.get(f))
            }
            ec.entity.makeValue('jexl.script.UserScriptRun').setAll(carried).setAll([scriptRunId: outcome.scriptRunId ?: ec.entity.sequencedIdPrimary('jexl.script.UserScriptRun', null, null),
                    userScriptId: deployment.userScriptId, scriptDeploymentId: deploymentId, ownerUserId: deployment.ownerUserId,
                    startDate: outcome.startDate ?: ec.user.nowTimestamp, endDate: ec.user.nowTimestamp, statusId: outcome.status?.toString(),
                    errorKind: outcome.errorKind ?: (run.error instanceof Map ? ((Map) run.error).kind : null),
                    units: run.units, elapsedMs: run.elapsedMs,
                    readCountsJson: outcome.readCounts ? JsonOutput.toJson(outcome.readCounts) : null,
                    deliveryOutcomesJson: outcome.deliveries != null ? JsonOutput.toJson(outcome.deliveries) : null,
                    message: fitField(outcome.message?.toString())]).create()
            } finally {
                if (!wasDisabled) ec.artifactExecution.enableAuthz()
            }
        })
    }

    private static volatile long lastPruned = 0L
    /** Test seam: runs once a scan has loaded its due list, before it claims any of them. */
    static Closure afterDueLoaded = null
    /** Test seam: runs before a run is recorded, with the deployment id. */
    static Closure beforeRecord = null

    /** Deletes run records older than jexl_run_retention_days (default 30). */
    static int pruneRuns(ExecutionContext ec, Timestamp now) {
        String raw = SystemBinding.getPropOrEnv('jexl_run_retention_days')
        int days = 30
        try { if (raw) days = Integer.parseInt(raw.trim()) } catch (NumberFormatException ignored) { }
        Timestamp before = new Timestamp((now ?: ec.user.nowTimestamp).time - days * 86_400_000L)
        return ec.entity.find('jexl.script.UserScriptRun').condition('startDate', EntityCondition.LESS_THAN, before)
                .disableAuthz().deleteAll()
    }

    static void resetForTests() { lastPruned = 0L; afterDueLoaded = null; beforeRecord = null }

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
        if (afterDueLoaded != null) afterDueLoaded.call(ec)
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
            EntityValue claimed = null
            if (!updateIfStillDue(ec, id, scanTime) { EntityValue current -> current.set('nextRunDate', next); claimed = current }) continue
            // NOTE: runs on the row re-read under the lock, not the one loaded when the scan started (its owner may have changed)
            Map<String, Object> outcome = execute(ec, claimed ?: deployment, true)
            // NOTE: one run that cannot be recorded never stops the rest of the pass
            try {
                recordOutcome(ec, id, outcome)
            } catch (Throwable t) {
                logger.error("Script deployment ${id} ran (${outcome.status}) but its run could not be recorded", t)
            }
            logger.info("Script deployment ${id} ran: ${outcome.status} ${outcome.message}")
            results << ([scriptDeploymentId: id, status: outcome.status, message: outcome.message, nextRunDate: next] as Map<String, Object>)
        }
        // NOTE: run records are pruned at most once an hour, on a scan
        if (System.currentTimeMillis() - lastPruned > 3_600_000L) {
            lastPruned = System.currentTimeMillis()
            // NOTE: by the wall clock (the time run records are stamped with), not the scan's own time
            pruneRuns(ec, new Timestamp(System.currentTimeMillis()))
        }
        int held = results.count { Map r -> r.status == WAITING || r.status == EXPIRED } as int
        return [ran: results.size() - held, held: held, results: results] as Map<String, Object>
    }

    /**
     * The server is busy: leave this due run for the next scan (WAITING), or once its window has passed skip it
     * (EXPIRED), never forced. The window ends at due + jexl_max_wait_minutes, or at the cron's next run if sooner.
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

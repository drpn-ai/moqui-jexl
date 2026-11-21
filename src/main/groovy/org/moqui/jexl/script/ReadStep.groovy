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
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue
import org.moqui.jexl.LaneResult
import org.moqui.jexl.ScriptDeploymentRunner
import org.moqui.jexl.ScriptLane
import org.moqui.jexl.ScriptProfile
import org.moqui.util.SystemBinding

import java.sql.Timestamp
import java.util.concurrent.Callable

/**
 * Runs a script's read spec. Each input's source service is resolved and checked against the read#ScriptSource
 * interface (here, on every run, not only when stored), then every input is read on a lane thread, in the owner's slot,
 * logged in as the owner, under the read time cap (jexl_read_timeout_ms, default 60,000) and the profile's maxReadRows.
 * Out: [ok: true, input: {name: rows, run: {date, timeZone}}, readCounts] or [ok: false, status, message].
 */
@CompileStatic
class ReadStep {
    static final String SOURCE_INTERFACE = 'moqui.jexl.ScriptServices.read#ScriptSource'
    static final String ENTITY_SOURCE = 'moqui.jexl.ScriptServices.read#EntityScriptSource'

    static long timeoutMs() {
        String raw = SystemBinding.getPropOrEnv('jexl_read_timeout_ms')
        try { return raw ? Long.parseLong(raw.trim()) : 60_000L } catch (NumberFormatException ignored) { return 60_000L }
    }

    /** Each input's source service, or [status: READ_ERROR, message] for the first that cannot be used. */
    static Map<String, Object> resolveSources(ExecutionContext ec, ReadSpec spec) {
        Map<String, String> services = new LinkedHashMap<>()
        for (ReadSpec.Input input : spec.inputs) {
            String service
            if (input.source == 'entity') {
                service = ENTITY_SOURCE
            } else {
                EntityValue row = ec.entity.find('jexl.script.ScriptSource').condition('sourceName', input.source)
                        .disableAuthz().useCache(false).one()
                if (row == null) return failed('READ_ERROR', "Input '${input.name}': there is no read source '${input.source}'.")
                service = row.getString('serviceName')
            }
            if (!ScriptDeploymentRunner.implementsInterface(ec, service, SOURCE_INTERFACE)) {
                return failed('READ_ERROR', "Input '${input.name}': ${service} does not implement ${SOURCE_INTERFACE}.")
            }
            services.put(input.name, service)
        }
        return [ok: true, services: services] as Map<String, Object>
    }

    static Map<String, Object> run(ExecutionContext ec, ReadSpec spec, String ownerUserId, String ownerKey, Timestamp runDate,
                                   String timeZone, ScriptProfile profile) {
        Map<String, Object> resolved = resolveSources(ec, spec)
        if (!resolved.ok) return resolved
        Map<String, String> services = (Map<String, String>) resolved.services
        EntityValue owner = ownerUserId ? ec.entity.find('moqui.security.UserAccount').condition('userId', ownerUserId)
                .disableAuthz().useCache(false).one() : null
        if (owner == null) return failed('READ_NOT_ALLOWED', 'A read runs as its owner, and this run has none.')
        String username = owner.getString('username')
        int maxRows = profile.maxReadRows
        long timeout = timeoutMs()

        LaneResult lane = ScriptLane.run(ownerKey ?: ownerUserId, timeout, { ->
            ExecutionContext own = Moqui.getExecutionContext()
            try {
                ((org.moqui.impl.context.UserFacadeImpl) own.user).internalLoginUser(username, false)
                Map<String, Object> input = new LinkedHashMap<>()
                Map<String, Long> counts = new LinkedHashMap<>()
                for (ReadSpec.Input item : spec.inputs) {
                    Map out = own.service.sync().name(services[item.name]).parameters([input: item.raw, ownerUserId: ownerUserId,
                            runDate: runDate, timeZone: timeZone, maxRows: maxRows]).call()
                    if (own.message.hasError()) {
                        String errors = own.message.errorsString
                        own.message.clearErrors()
                        return failed('READ_ERROR', "Input '${item.name}': ${errors}")
                    }
                    Map error = (Map) out?.get('error')
                    if (error) return failed((error.get('kind') ?: 'READ_ERROR').toString(), "Input '${item.name}': ${error.get('message')}")
                    List rows = (List) (out?.get('rows') ?: [])
                    if (rows.size() > maxRows) {
                        return failed('READ_TOO_LARGE', "Input '${item.name}' has ${rows.size()} rows; the limit is ${maxRows}.")
                    }
                    input.put(item.name, rows)
                    counts.put(item.name, (long) rows.size())
                }
                input.put('run', [date: runDate.toInstant().toString(), timeZone: timeZone])
                return [ok: true, input: input, readCounts: counts] as Map<String, Object>
            } finally {
                own.destroy()
            }
        } as Callable<Object>)

        switch (lane.status) {
            case 'OK': return (Map<String, Object>) lane.value
            case 'TIME': return failed('READ_ERROR', "The read took longer than ${timeout} ms and was stopped.")
            case 'FAILED': return failed('READ_ERROR', "The read failed: ${lane.error?.message}")
            default: return failed(lane.status, lane.message)
        }
    }

    private static Map<String, Object> failed(String status, Object message) {
        return [ok: false, status: status, message: message.toString()] as Map<String, Object>
    }
}

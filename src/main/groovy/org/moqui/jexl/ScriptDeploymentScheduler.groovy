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

import groovy.transform.CompileStatic
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp

/**
 * Runs the due user script deployments on the factory's scheduled executor, the way ScheduledJobRunner runs service
 * jobs: ScriptDeploymentToolFactory schedules it every script_deployment_check_seconds. Each pass calls
 * run#DueUserScriptDeployments, which claims a deployment under a row lock before running it, so servers sharing a
 * database never deliver the same run twice.
 */
@CompileStatic
class ScriptDeploymentScheduler implements Runnable {
    protected final static Logger logger = LoggerFactory.getLogger(ScriptDeploymentScheduler.class)

    static final String RUN_DUE_SERVICE = 'moqui.jexl.ScriptServices.run#DueUserScriptDeployments'

    private final ExecutionContextFactoryImpl ecfi

    ScriptDeploymentScheduler(ExecutionContextFactoryImpl ecfi) { this.ecfi = ecfi }

    /** NOTE: never throws; a Runnable that throws is dropped by the scheduled executor and would never run again */
    @Override
    synchronized void run() {
        try {
            runDue(null)
        } catch (Throwable t) {
            logger.error('Script deployment pass failed, catching so the next pass still runs', t)
        }
    }

    /** One pass: runs what is due at now (the current time when null). {ran, held, results} */
    synchronized Map<String, Object> runDue(Timestamp now) {
        boolean ownContext = ecfi.activeContext.get() == null
        ExecutionContextImpl eci = ecfi.getEci()
        try {
            Map<String, Object> out = eci.serviceFacade.sync().name(RUN_DUE_SERVICE)
                    .parameters(now != null ? [nowTimestamp: now] as Map<String, Object> : [:] as Map<String, Object>)
                    .disableAuthz().call()
            if (eci.messageFacade.hasError()) {
                logger.warn("Script deployment pass reported errors: ${eci.messageFacade.errorsString}")
                eci.messageFacade.clearErrors()
            } else if (out?.get('ran') || out?.get('held')) {
                logger.info("Script deployment pass: ran ${out.get('ran')}, held ${out.get('held')}")
            }
            return out
        } finally {
            if (ownContext) eci.destroy()
        }
    }
}

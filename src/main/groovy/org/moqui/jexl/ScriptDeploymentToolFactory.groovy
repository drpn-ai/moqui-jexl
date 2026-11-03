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
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.ToolFactory
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.util.SystemBinding
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Starts the ScriptDeploymentScheduler when Moqui starts, as ServiceFacadeImpl starts ScheduledJobRunner: every
 * script_deployment_check_seconds (60; 0 turns it off) after a 120 second initial delay. The tool is the scheduler,
 * so ec.factory.getTool('ScriptDeployment', ScriptDeploymentScheduler.class).runDue(null) runs one pass by hand.
 */
@CompileStatic
class ScriptDeploymentToolFactory implements ToolFactory<ScriptDeploymentScheduler> {
    protected final static Logger logger = LoggerFactory.getLogger(ScriptDeploymentToolFactory.class)

    static final String TOOL_NAME = 'ScriptDeployment'
    static final long INITIAL_DELAY_SECONDS = 120L

    private ScriptDeploymentScheduler scheduler = null

    @Override String getName() { TOOL_NAME }

    @Override
    void init(ExecutionContextFactory ecf) {
        ExecutionContextFactoryImpl ecfi = (ExecutionContextFactoryImpl) ecf
        scheduler = new ScriptDeploymentScheduler(ecfi)
        long checkSeconds = checkSeconds()
        if (checkSeconds > 0L) {
            logger.info("Starting the script deployment scheduler, checking every ${checkSeconds} seconds after a ${INITIAL_DELAY_SECONDS} second initial delay")
            ecfi.scheduleAtFixedRate(scheduler, INITIAL_DELAY_SECONDS, checkSeconds)
        } else {
            logger.warn('Not starting the script deployment scheduler (script_deployment_check_seconds is 0)')
        }
    }

    @Override ScriptDeploymentScheduler getInstance(Object... parameters) { scheduler }

    static long checkSeconds() {
        String value = SystemBinding.getPropOrEnv('script_deployment_check_seconds')
        if (!value) return 60L
        try {
            return Math.max(0L, Long.parseLong(value.trim()))
        } catch (NumberFormatException ignored) {
            logger.warn("script_deployment_check_seconds '${value}' is not a whole number, using 60")
            return 60L
        }
    }
}

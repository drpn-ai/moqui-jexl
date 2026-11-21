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
import org.apache.shiro.subject.SimplePrincipalCollection
import org.apache.shiro.subject.Subject
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.UserFacadeImpl
import org.moqui.jexl.LaneResult
import org.moqui.jexl.ScriptLane

import java.util.concurrent.Callable

/**
 * Runs work as a script's owner: on a lane thread (the owner's slot, a time cap), in a fresh ExecutionContext with an
 * empty artifact stack, acting as the owner, with authorization on, so nothing the caller is allowed (an anonymous-all
 * scheduler service, an inheriting grant) carries over. The owner is pushed as an already-authenticated Subject, never
 * through the login realm, so a run never touches the owner's login state (failed-login count, logged-out flag) and
 * the caller (ownerProblem) decides beforehand whether the owner may still run. The context is destroyed afterwards.
 */
@CompileStatic
class AsOwner {
    /** LaneResult of work(ownContext); status NO_OWNER (message) when the owner is unknown. */
    static LaneResult run(ExecutionContext ec, String ownerUserId, String ownerKey, long timeoutMs, Closure work) {
        EntityValue owner = ownerUserId ? ec.entity.find('moqui.security.UserAccount').condition('userId', ownerUserId)
                .disableAuthz().useCache(false).one() : null
        if (owner == null) return LaneResult.of('NO_OWNER', 'This runs as its owner, and there is none.')
        String username = owner.getString('username')
        return ScriptLane.run(ownerKey ?: ownerUserId, timeoutMs, { ->
            ExecutionContext own = Moqui.getExecutionContext()
            try {
                ExecutionContextFactoryImpl ecfi = (ExecutionContextFactoryImpl) own.factory
                Subject subject = new Subject.Builder(ecfi.getSecurityManager())
                        .principals(new SimplePrincipalCollection(username, 'moquiRealm')).authenticated(true).buildSubject()
                if (!((UserFacadeImpl) own.user).internalLoginSubject(subject) || own.user.userId != ownerUserId) {
                    throw new IllegalStateException("Could not act as the owner ${username}.".toString())
                }
                return work.call(own)
            } finally {
                own.destroy()
            }
        } as Callable<Object>)
    }
}

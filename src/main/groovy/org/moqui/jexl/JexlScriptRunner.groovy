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
import org.moqui.BaseArtifactException
import org.moqui.BaseException
import org.moqui.context.ExecutionContext
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.ScriptRunner
import org.moqui.util.ContextStack

import java.time.temporal.Temporal

/**
 * The script-runner for .jexl files, registered beside the groovy and xml runners in this component's MoquiConf.xml, so
 * a .jexl location works wherever Moqui runs a script: a service with type="script", an XML actions script location,
 * ec.resource.script(). It runs in the same sandbox as user scripts (ScriptSupervisor, SCRIPT profile): the script reads
 * input, which holds the context's plain values (text, numbers, true/false, dates, and maps and lists of them), and
 * what it returns is the result; for a script service a returned map is the service's out-parameters.
 *
 * NOTE: context values that are not plain (ec, entity facades, any other object) are left out of input, never exposed
 * NOTE: a script that fails or hits a limit adds an error message (with the file and line) and returns null
 * NOTE: not held by ScriptLoadGate: a .jexl file is application code, the gate is for scripts users write
 */
@CompileStatic
class JexlScriptRunner implements ScriptRunner {
    static final int MAX_DEPTH = 32

    @Override ScriptRunner init(ExecutionContextFactory ecf) { this }

    @Override
    Object run(String location, String method, ExecutionContext ec) throws BaseException {
        String body = ec.resource.getLocationText(location, true)
        if (body == null) throw new BaseArtifactException("No JEXL script found at ${location}")

        Map<String, Object> run = ScriptSupervisor.evaluate(ScriptSupervisor.SCRIPT, body, plainValues(ec.context))
        if (!run.ok) {
            Map error = run.error as Map
            ec.message.addError("${location}${error?.line ? ' line ' + error.line : ''}: ${error?.message}".toString())
            return null
        }
        return run.result
    }

    @Override void destroy() { }

    /** The entries of context whose values are plain JSON-shaped data; the rest are left out. */
    static Map<String, Object> plainValues(Map<String, Object> context) {
        Map<String, Object> out = new LinkedHashMap<>()
        if (context == null) return out
        for (Map.Entry<String, Object> entry in context.entrySet()) {
            if (isPlain(entry.value, 0)) out.put(entry.key, entry.value)
        }
        return out
    }

    static boolean isPlain(Object value, int depth) {
        if (depth > MAX_DEPTH) return false
        if (value instanceof ContextStack) return false
        if (value == null || value instanceof Boolean || value instanceof CharSequence || value instanceof Number) return true
        if (value instanceof Date || value instanceof Temporal) return true
        if (value instanceof Map) return ((Map) value).values().every { Object item -> isPlain(item, depth + 1) }
        if (value instanceof Collection) return ((Collection) value).every { Object item -> isPlain(item, depth + 1) }
        return false
    }
}

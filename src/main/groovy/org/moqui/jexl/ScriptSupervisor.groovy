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
import org.moqui.jexl.ScriptBudget.LimitReached
import groovy.transform.CompileStatic
import org.apache.commons.jexl3.JexlBuilder
import org.apache.commons.jexl3.JexlEngine
import org.apache.commons.jexl3.JexlArithmetic
import org.apache.commons.jexl3.JexlException
import org.apache.commons.jexl3.JexlFeatures
import org.apache.commons.jexl3.JexlInfo
import org.apache.commons.jexl3.JexlScript
import org.apache.commons.jexl3.MapContext
import org.apache.commons.jexl3.internal.Script
import org.apache.commons.jexl3.parser.ASTNumberLiteral
import org.apache.commons.jexl3.parser.JexlNode

import java.lang.reflect.Field
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The one place Moqui runs user-written JEXL. EXPRESSION is one expression with no loops, locals or side effects;
 * SCRIPT adds statements, locals, loops and lambdas. Both read only input (plain JSON) and the fn: library, on a small
 * pool of their own, under a work budget, text and list caps and a time limit.
 *
 * NOTE: only the sandbox classes (ScriptSupervisor, ScriptPermissions, ScriptArithmetic) reference jexl3, UserScriptSupervisorTests pins that
 * NOTE: scripts never touch the database; the caller reads the input first and checks ScriptLoadGate (scripts run last)
 */
@CompileStatic
class ScriptSupervisor {

    static final String EXPRESSION = 'EXPRESSION'
    static final String SCRIPT = 'SCRIPT'
    static final List<String> PROFILES = [EXPRESSION, SCRIPT].asImmutable()

    /** The one name a script may read. */
    static final String INPUT_NAME = 'input'

    static final Map<String, Object> LIMITS = [
            (EXPRESSION): [maxSourceChars: 2_000, unitLimit: 100_000L, timeoutMs: 250L],
            (SCRIPT)    : [maxSourceChars: 64_000, unitLimit: 2_000_000L, timeoutMs: 2_000L],
    ].asImmutable() as Map<String, Object>
    static final int MAX_STRING_CHARS = 65_536
    static final int MAX_COLLECTION_SIZE = 10_000
    static final int MAX_INPUT_JSON_CHARS = 2_000_000
    static final int MAX_OUTPUT_JSON_CHARS = 65_536
    static final int MAX_OUTPUT_NODES = 20_000
    static final int MAX_INPUT_VALUES = 500_000
    static final int MAX_DEPTH = 32

    // NOTE: what usually lies behind each limit, so a refused save says what to change
    private static final Map<String, String> LIMIT_HINTS = [
            UNITS : 'A loop that never ends, or a loop inside a loop, is the usual cause.',
            TIME  : 'A loop that never ends, or a loop inside a loop, is the usual cause.',
            MEMORY: 'A list or text that keeps growing is the usual cause.',
    ].asImmutable()

    private static final JexlFeatures SCRIPT_FEATURES = JexlFeatures.createScript()
            .methodCall(false).newInstance(false).annotation(false)
            .pragma(false).pragmaAnywhere(false).importPragma(false).namespacePragma(false).namespaceInstantiation(false)
            .sideEffectGlobal(false).register(false)
    private static final JexlFeatures EXPRESSION_FEATURES = new JexlFeatures(SCRIPT_FEATURES)
            .script(false).loops(false).lambda(false).thinArrow(false).fatArrow(false).localVar(false).sideEffect(false)

    private static final Map<String, JexlEngine> ENGINES = [
            (EXPRESSION): engine(EXPRESSION_FEATURES),
            (SCRIPT)    : engine(SCRIPT_FEATURES),
    ].asImmutable()

    private static final AtomicInteger threadCount = new AtomicInteger()
    /** two threads and sixteen waiting places: a burst queues briefly, then is refused, never piles onto Moqui */
    private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(2, 2, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(16), { Runnable task -> scriptThread(task) } as ThreadFactory)

    private static JexlEngine engine(JexlFeatures features) {
        return new JexlBuilder()
                .features(features)
                .permissions(ScriptPermissions.INSTANCE)
                .namespaces([fn: (Object) ScriptLibrary] as Map<String, Object>)
                .arithmetic(new ScriptArithmetic())
                .antish(false)        // a.b.c is never a variable named "a.b.c"
                .strict(true)
                .safe(false)
                .silent(false)
                .cancellable(true)
                .cache(512)
                .create()
    }

    private static Thread scriptThread(Runnable task) {
        Thread thread = new Thread(task, "user-script-${threadCount.incrementAndGet()}".toString())
        thread.daemon = true
        thread.priority = Thread.NORM_PRIORITY - 1
        return thread
    }

    private static Map limitsOf(String profileName) { (Map) LIMITS[profileName] }

    static String profileOf(Object profile) {
        String value = profile?.toString()?.trim()?.toUpperCase(Locale.ROOT) ?: SCRIPT
        return PROFILES.contains(value) ? value : null
    }

    /**
     * Parses without running. {ok, profile, variables, error: {kind, message, line, column}}. Refuses syntax the
     * profile leaves out, and any name other than {@code input} (locals declared with var are fine).
     */
    static Map<String, Object> validate(Object profile, String body) {
        String profileName = profileOf(profile)
        if (profileName == null) return failure(null, 'INVALID', "Profile must be one of ${PROFILES.join(', ')}.".toString())
        String source = body ?: ''
        if (!source.trim()) return failure(profileName, 'INVALID', 'The script is empty.')
        int maxChars = limitsOf(profileName).maxSourceChars as int
        if (source.length() > maxChars) {
            return failure(profileName, 'INVALID', "The script is ${source.length()} characters; the ${profileName.toLowerCase(Locale.ROOT)} limit is ${maxChars}.".toString())
        }
        int templateLine = templateLiteralLine(source)
        if (templateLine > 0) {
            Map<String, Object> refused = failure(profileName, 'NOT_ALLOWED', 'The ` character is not available (template text skips the limits); join text with +.')
            (refused.error as Map).line = templateLine
            return refused
        }
        int bigLiteralLine = bigDecimalLiteralLine(source)
        if (bigLiteralLine > 0) {
            Map<String, Object> refused = failure(profileName, 'NOT_ALLOWED', "A number written with more than ${ScriptBudget.MAX_DECIMAL_CHARS} digits (like 1E5000B) is not available.".toString())
            (refused.error as Map).line = bigLiteralLine
            return refused
        }
        JexlScript script
        try {
            script = ENGINES[profileName].createScript(new JexlInfo('script', 1, 1), source)
        } catch (JexlException e) {
            return failure(profileName, e instanceof JexlException.Feature ? 'NOT_ALLOWED' : 'SYNTAX', message(e), e)
        }
        int literalLine = hugeNumberLiteralLine(script)
        if (literalLine > 0) {
            Map<String, Object> refused = failure(profileName, 'NOT_ALLOWED', "A number written with more than ${ScriptBudget.MAX_DECIMAL_CHARS} digits (like 1E5000B) is not available.".toString())
            (refused.error as Map).line = literalLine
            return refused
        }
        List<String> names = (script.variables ?: [] as Set<List<String>>).collect { List<String> path -> path ? path[0] : null }
                .findAll { it }.unique() as List<String>
        List<String> unknown = names.findAll { it != INPUT_NAME }
        if (unknown) {
            return failure(profileName, 'ACCESS', "Unknown name${unknown.size() > 1 ? 's' : ''} ${unknown.collect { "'${it}'" }.join(', ')}: a script reads only input (declare your own with var).".toString())
        }
        return [ok: true, profile: profileName, variables: names, error: null] as Map<String, Object>
    }

    /**
     * Runs the script against input (any JSON-shaped value, copied before the script sees it).
     * {ok, profile, result, units, elapsedMs, logs, error: {kind, message, line, column}}. Never throws.
     */
    static Map<String, Object> evaluate(Object profile, String body, Object input) {
        Map<String, Object> checked = validate(profile, body)
        if (!checked.ok) return notRun(checked)
        String profileName = checked.profile as String
        Map limits = limitsOf(profileName)

        Object plainInput
        try {
            plainInput = copyNode(input, 0, new Tally())
        } catch (IllegalArgumentException e) {
            return notRun(failure(profileName, 'INPUT', e.message))
        }

        // NOTE: the engine caches the parse validate just did
        JexlScript script = ENGINES[profileName].createScript(new JexlInfo('script', 1, 1), body)
        ScriptBudget budget = new ScriptBudget(limits.unitLimit as long, MAX_STRING_CHARS, MAX_COLLECTION_SIZE)
        long started = System.nanoTime()
        Future<Object> future
        try {
            future = POOL.submit({ ->
                ScriptBudget.open(budget)
                try {
                    MapContext context = new MapContext()
                    context.set(INPUT_NAME, plainInput)
                    Object raw = script.execute(context)
                    // shaped here, inside the budget and the time limit: a result can be a huge range or a deep graph
                    Object plain = toPlain(raw, 0, new Tally())
                    String json = JsonOutput.toJson(plain)
                    if (json.length() > MAX_OUTPUT_JSON_CHARS) {
                        throw new LimitReached('MEMORY', "The result is ${json.length()} characters of JSON; the limit is ${MAX_OUTPUT_JSON_CHARS}.".toString())
                    }
                    return plain
                } finally {
                    ScriptBudget.close()
                }
            } as java.util.concurrent.Callable<Object>)
        } catch (RejectedExecutionException ignored) {
            return notRun(failure(profileName, 'BUSY', 'Scripts are busy right now; try again in a moment.'))
        }

        Map<String, Object> out
        try {
            Object plain = future.get(limits.timeoutMs as long, TimeUnit.MILLISECONDS)
            out = [ok: true, profile: profileName, result: plain, error: null] as Map<String, Object>
        } catch (TimeoutException ignored) {
            future.cancel(true)
            out = failure(profileName, 'TIME', "The script ran past ${limits.timeoutMs} ms and was stopped.".toString())
        } catch (ExecutionException e) {
            out = failureFrom(profileName, e.cause ?: e, body, budget)
        }
        out.units = budget.units
        out.elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        out.logs = new ArrayList<String>(budget.logs)
        return out
    }

    /**
     * Runs a script on its own sample input before it is saved: {ok, problems: [{rule, line, message}], notes}.
     * Refused as BAD_SAMPLE, FAILS_ON_SAMPLE, or TOO_HEAVY when the sample alone takes over half the work budget.
     */
    static Map<String, Object> check(Object profile, String body, String sampleJson) {
        if (!sampleJson?.trim()) return passed(null)
        String profileName = profileOf(profile) ?: SCRIPT
        Object sample
        try {
            sample = parseInputJson(sampleJson)
        } catch (IllegalArgumentException e) {
            return refused('BAD_SAMPLE', null, "Sample input: ${e.message}".toString())
        }

        Map<String, Object> run = evaluate(profileName, body, sample)
        Map error = run.error as Map
        if (error?.kind == 'BUSY') return passed('Scripts are busy, so the script was saved without a trial run on its sample input.')
        if (!run.ok) {
            String hint = LIMIT_HINTS[error.kind as String]
            return refused('FAILS_ON_SAMPLE', error.line, "On its sample input the script stops: ${error.message}${hint ? ' ' + hint : ''}".toString())
        }
        long unitLimit = limitsOf(profileName).unitLimit as long
        long units = run.units as long
        if (units * 2 > unitLimit) {
            return refused('TOO_HEAVY', null, ("On its sample input alone the script uses ${Math.round(100d * units / unitLimit)}% of its work budget, " +
                    "and real data is usually bigger. A loop inside a loop is the usual cause: build a map keyed by id first, then look items up in it.").toString())
        }
        return passed(null)
    }

    private static Map<String, Object> passed(String note) { [ok: true, problems: [], notes: note ? [note] : []] as Map<String, Object> }

    private static Map<String, Object> refused(String rule, Object line, String message) {
        return [ok: false, problems: [[rule: rule, line: line, message: message]], notes: []] as Map<String, Object>
    }

    /** a decimal literal with an exponent: only the B suffix makes a BigDecimal, an unsuffixed one is a double */
    private static final java.util.regex.Pattern BIG_DECIMAL_LITERAL = ~/(?<![\w$])(\d[\d_]*(?:\.\d*)?[eE][+-]?\d+)[bB](?![\w$])/

    /**
     * The line of the first B literal past ScriptBudget.MAX_DECIMAL_CHARS, read from the source before JEXL parses it:
     * a parse error can render the source, literal and all, before hugeNumberLiteralLine sees the tree. Measured on a
     * compact BigDecimal, never written out.
     * NOTE: refused anywhere, even in a string, for the same reason as templateLiteralLine
     */
    static int bigDecimalLiteralLine(String source) {
        java.util.regex.Matcher found = BIG_DECIMAL_LITERAL.matcher(source)
        while (found.find()) {
            BigDecimal literal
            try {
                literal = new BigDecimal(found.group(1).replace('_', ''))
            } catch (NumberFormatException ignored) {
                literal = null
            }
            if (literal == null || ScriptBudget.plainLength(literal) > ScriptBudget.MAX_DECIMAL_CHARS) {
                return source.substring(0, found.start()).count('\n') + 1
            }
        }
        return 0
    }

    private static final Field SCRIPT_TREE = scriptTreeField()

    private static Field scriptTreeField() {
        Field field = Script.getDeclaredField('script')
        field.accessible = true
        return field
    }

    /**
     * The line of the first number literal whose plain text is longer than ScriptBudget.MAX_DECIMAL_CHARS, or 0.
     * 1E999999999B is a BigDecimal no input cap sees, and whenever the script fails JEXL renders its source through
     * DecimalFormat, which writes that number out in full: a billion characters on the caller's thread, outside the
     * time limit. Read from the parsed tree, so text that only looks like a number ('4E1200' in a string) is fine.
     */
    static int hugeNumberLiteralLine(JexlScript script) {
        Deque<JexlNode> todo = new ArrayDeque<>()
        todo.push((JexlNode) SCRIPT_TREE.get(script))
        while (!todo.isEmpty()) {
            JexlNode node = todo.pop()
            if (node instanceof ASTNumberLiteral) {
                Number literal = ((ASTNumberLiteral) node).literal
                if (literal instanceof BigDecimal && ScriptBudget.plainLength((BigDecimal) literal) > ScriptBudget.MAX_DECIMAL_CHARS) {
                    return node.jexlInfo()?.line ?: 1
                }
            }
            for (int i = 0; i < node.jjtGetNumChildren(); i++) todo.push(node.jjtGetChild(i))
        }
        return 0
    }

    /**
     * The line of the first backtick, or 0. JEXL builds `${a}` template text in its template engine, outside the
     * arithmetic and its text caps, and JexlFeatures has no switch for it.
     * NOTE: refused anywhere, even in a string or comment: a lexer that skips quotes can be fooled (a ~/'/ regex literal)
     */
    static int templateLiteralLine(String source) {
        int at = source.indexOf('`')
        return at < 0 ? 0 : source.substring(0, at).count('\n') + 1
    }

    /** Parses JSON text for an input; blank is an empty map. */
    static Object parseInputJson(String json) {
        if (!json?.trim()) return [:]
        if (json.length() > MAX_INPUT_JSON_CHARS) throw new IllegalArgumentException("The input is more than ${MAX_INPUT_JSON_CHARS} characters.".toString())
        try {
            return new JsonSlurper().parseText(json)
        } catch (Exception e) {
            throw new IllegalArgumentException("The input is not valid JSON: ${e.message?.readLines()?.first()}".toString())
        }
    }

    /** The library's functions and one-line help, for the editor. */
    static List<Map<String, String>> libraryCatalog() { ScriptLibrary.CATALOG }

    // -- input and output shaping

    /** how much of an input or a result has been walked */
    private static class Tally {
        int values = 0
        long jsonChars = 0L
    }

    /** A deep copy of the input holding only maps, lists, text, numbers, true/false and null. */
    private static Object copyNode(Object value, int depth, Tally tally) {
        if (++tally.values > MAX_INPUT_VALUES) throw new IllegalArgumentException("The input holds more than ${MAX_INPUT_VALUES} values.".toString())
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("The input is nested more than ${MAX_DEPTH} levels deep.".toString())
        if (value == null || value instanceof Boolean || value instanceof String) return value
        if (value instanceof BigDecimal) return checkInputDecimal((BigDecimal) value)
        if (value instanceof BigInteger || value instanceof Long || value instanceof Integer) return value
        if (value instanceof Number) return checkInputDecimal(new BigDecimal(value.toString()))
        if (value instanceof CharSequence || value instanceof Date || value instanceof java.time.temporal.Temporal) return value.toString()
        if (value instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>()
            ((Map) value).each { Object key, Object item -> out.put(String.valueOf(key), copyNode(item, depth + 1, tally)) }
            return out
        }
        if (value instanceof Collection) return ((Collection) value).collect { Object item -> copyNode(item, depth + 1, tally) }
        throw new IllegalArgumentException("The input holds a ${value.getClass().simpleName}, which is not a JSON value.".toString())
    }

    private static BigDecimal checkInputDecimal(BigDecimal number) {
        if (ScriptBudget.plainLength(number) > ScriptBudget.MAX_DECIMAL_CHARS) {
            throw new IllegalArgumentException("The input holds a number with more than ${ScriptBudget.MAX_DECIMAL_CHARS} digits.".toString())
        }
        return number
    }

    /**
     * What the script returned, as JSON values; anything else (a function, a host object) is refused. Runs on the
     * pool thread with the budget open: every node is charged, at most MAX_OUTPUT_NODES are shaped, and a non-list
     * collection (a range) is size-checked before it is walked.
     */
    private static Object toPlain(Object value, int depth, Tally tally) {
        ScriptBudget.charge()
        // NOTE: the JSON size is counted as it is built, so the JSON is never written past the limit
        tally.jsonChars += 4
        if (value instanceof String) tally.jsonChars += ((String) value).length() + 2
        else if (value instanceof BigDecimal) tally.jsonChars += ScriptBudget.plainLength(ScriptBudget.checkDecimal((BigDecimal) value))
        if (tally.jsonChars > MAX_OUTPUT_JSON_CHARS) {
            throw new LimitReached('MEMORY', "The result is more than ${MAX_OUTPUT_JSON_CHARS} characters of JSON.".toString())
        }
        if (++tally.values > MAX_OUTPUT_NODES) throw new LimitReached('MEMORY', "The result holds more than ${MAX_OUTPUT_NODES} values.".toString())
        if (depth > MAX_DEPTH) throw new LimitReached('OUTPUT', "The result is nested more than ${MAX_DEPTH} levels deep.".toString())
        if (value == null || value instanceof Boolean || value instanceof String) return value
        if (value instanceof BigDecimal) return value
        if (value instanceof Number) {
            if (value instanceof Double || value instanceof Float) {
                double number = ((Number) value).doubleValue()
                if (number.isNaN() || number.isInfinite()) return null
            }
            return value
        }
        if (value instanceof Character) return value.toString()
        if (value instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>()
            ((Map) value).each { Object rawKey, Object item ->
                String key = ScriptBudget.render(rawKey, 200)
                tally.jsonChars += key.length() + 3
                out.put(key, toPlain(item, depth + 1, tally))
            }
            return out
        }
        if (value instanceof Collection) {
            List out = []
            for (Object item : (Collection) value) {
                out.add(toPlain(item, depth + 1, tally))
                ScriptBudget.checkSize(out.size())
            }
            return out
        }
        if (value.getClass().isArray()) return ScriptArithmetic.arrayItems(value).collect { Object item -> toPlain(item, depth + 1, tally) }
        throw new LimitReached('OUTPUT', 'The script returned something that is not data (a function?); return a value, list or map.')
    }

    // -- errors

    private static Map<String, Object> notRun(Map<String, Object> out) {
        out.units = 0L
        out.elapsedMs = 0L
        out.logs = [] as List<String>
        return out
    }

    private static Map<String, Object> failureFrom(String profile, Throwable thrown, String body = null, ScriptBudget budget = null) {
        boolean nullOperand = thrown instanceof JexlException.Variable && !((JexlException.Variable) thrown).isUndefined()
        Throwable cause = thrown
        for (int hops = 0; cause != null && hops < 10; hops++) {
            if (cause instanceof LimitReached) return failure(profile, ((LimitReached) cause).kind, cause.message, thrown)
            if (cause instanceof JexlException.Cancel || cause instanceof InterruptedException) return failure(profile, 'TIME', 'The script ran out of time and was stopped.', thrown)
            if (cause instanceof JexlArithmetic.NullOperand) nullOperand = true
            cause = cause.cause == cause ? null : cause.cause
        }
        if (nullOperand) return emptyValueFailure(profile, thrown, body, budget)
        if (thrown instanceof JexlException.Method) {
            String name = ((JexlException.Method) thrown).method
            return failure(profile, 'ACCESS', "'${name}' is not a script function; the fn: functions are listed in the library catalog.".toString(), thrown)
        }
        String kind = thrown instanceof JexlException.Variable || thrown instanceof JexlException.Property ? 'ACCESS' : 'ERROR'
        String text = thrown instanceof JexlException ? message((JexlException) thrown) : null
        if (thrown instanceof JexlException && thrown.cause != null && !(thrown.cause instanceof JexlException)) {
            text = (text ? text + ': ' : '') + (thrown.cause.message ?: thrown.cause.getClass().simpleName)
        }
        return failure(profile, kind, text ?: (thrown.message ?: thrown.getClass().simpleName), thrown)
    }

    private static Map<String, Object> failure(String profile, String kind, String message, Throwable thrown = null) {
        Map<String, Object> error = [kind: kind, message: message] as Map<String, Object>
        JexlInfo info = thrown instanceof JexlException ? ((JexlException) thrown).info : null
        if (info != null) {
            error.line = info.line
            error.column = info.column
        }
        return [ok: false, profile: profile, result: null, error: error] as Map<String, Object>
    }

    /** A calculation hit an empty value: say where, and name the field when that line read one the record lacks. */
    private static Map<String, Object> emptyValueFailure(String profile, Throwable thrown, String body, ScriptBudget budget) {
        JexlInfo info = thrown instanceof JexlException ? ((JexlException) thrown).info : null
        int line = info?.line ?: 0
        String source = body && line > 0 ? (body.readLines()[line - 1] ?: '').trim().take(120) : ''
        String where = line > 0 ? "line ${line}${source ? ': ' + source : ''}" : 'this script'
        String field = budget?.missingField
        if (!field || !source || !(source.contains('.' + field) || source.contains("'" + field + "'") || source.contains('"' + field + '"'))) {
            return failure(profile, 'ERROR', "An empty value (null) was used in a calculation at ${where}. Check that it has a value, or give a default with ?? .".toString(), thrown)
        }
        Map<String, Object> out = failure(profile, 'ERROR', ("'${field}' is not a field of this record (${where}). The record has: " +
                "${budget.recordFields.join(', ')}. A misspelt field reads as empty: fix the name, or give a default with ?? .").toString(), thrown)
        (out.error as Map).field = field
        return out
    }

    /** JEXL's message without its "script@1:5 " location prefix (the location is returned as line/column). */
    private static String message(JexlException e) {
        String raw = e.message ?: e.getClass().simpleName
        return raw.replaceFirst(/^\S*@\d+:\d+(?::\d+:\d+)?\s+/, '').trim()
    }
}

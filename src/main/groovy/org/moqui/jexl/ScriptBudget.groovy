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

/**
 * What one evaluation may still spend, and what it logged. Held in a ThreadLocal while the evaluation runs, because
 * the engine and its arithmetic are shared by every script.
 *
 * NOTE: with no budget open (a host call from a test or validation) every check is free
 */
@CompileStatic
class ScriptBudget {
    /** a decimal's plain text is at most this long; 1E999999999 would print a billion digits */
    static final int MAX_DECIMAL_CHARS = 1_000
    static final int MAX_LOG_LINES = 50
    static final int MAX_LOG_LINE_CHARS = 500

    private static final ThreadLocal<ScriptBudget> CURRENT = new ThreadLocal<>()

    /** A script hit one of its limits; kind is UNITS, TIME, MEMORY, NUMBER, ACCESS or OUTPUT. */
    static class LimitReached extends ArithmeticException {
        final String kind

        LimitReached(String kind, String message) {
            super(message)
            this.kind = kind
        }
    }

    final long unitLimit
    final int maxStringChars
    final int maxCollectionSize
    /** all the text one evaluation may build: 64 of its longest text */
    final long maxTotalChars
    long units = 0L
    long charsMade = 0L
    final List<String> logs = []
    /** the last field read from a record that lacks it, so an empty-value error can name a misspelt field */
    String missingField
    List<String> recordFields = []

    ScriptBudget(long unitLimit, int maxStringChars, int maxCollectionSize) {
        this.unitLimit = unitLimit
        this.maxStringChars = maxStringChars
        this.maxCollectionSize = maxCollectionSize
        this.maxTotalChars = maxStringChars * 64L
    }

    static ScriptBudget current() { CURRENT.get() }

    static void open(ScriptBudget budget) { CURRENT.set(budget) }

    static void close() { CURRENT.remove() }

    /** Spends work units; past the limit, or once the run is interrupted, the script stops. */
    static void charge(long cost = 1L) {
        ScriptBudget budget = CURRENT.get()
        if (budget == null) return
        // NOTE: an overflowed Collection.size() is negative and would refund units
        if (cost < 0) throw new LimitReached('MEMORY', 'A list is too large to count.')
        budget.units += cost
        if (budget.units > budget.unitLimit) throw new LimitReached('UNITS', "The script used more than ${budget.unitLimit} units of work.".toString())
        if (Thread.currentThread().isInterrupted()) throw new LimitReached('TIME', 'The script ran out of time.')
    }

    /** Refuses text of this length before it is built, so the cap holds at the peak allocation. */
    static void checkLength(long length) {
        ScriptBudget budget = CURRENT.get()
        if (budget == null) return
        if (length > budget.maxStringChars) throw new LimitReached('MEMORY', "A text value would grow past ${budget.maxStringChars} characters.".toString())
        if (budget.charsMade + length > budget.maxTotalChars) throw new LimitReached('MEMORY', "The script built more than ${budget.maxTotalChars} characters of text.".toString())
    }

    /** Text the script made: checked like checkLength, then counted toward all the text it made. */
    static String checkString(String value) {
        ScriptBudget budget = CURRENT.get()
        if (budget == null || value == null) return value
        checkLength(value.length())
        budget.charsMade += value.length()
        return value
    }

    static void checkSize(int size) {
        if (size < 0) throw new LimitReached('MEMORY', 'A list is too large to count.')
        ScriptBudget budget = CURRENT.get()
        if (budget != null && size > budget.maxCollectionSize) throw new LimitReached('MEMORY', "A list or map grew past ${budget.maxCollectionSize} entries.".toString())
    }

    /** Refuses a decimal whose plain text would be longer than MAX_DECIMAL_CHARS, wherever one enters. */
    static BigDecimal checkDecimal(BigDecimal number) {
        if (number != null && plainLength(number) > MAX_DECIMAL_CHARS) throw new LimitReached('NUMBER', "A number has more than ${MAX_DECIMAL_CHARS} digits.".toString())
        return number
    }

    /** a decimal's plain-text length, worked out without building it */
    static long plainLength(BigDecimal number) {
        return (long) number.precision() + Math.max(0L, -(long) number.scale()) + Math.max(0L, (long) number.scale() - number.precision()) + 3L
    }

    /** a value is scalar when its text is its own size: text, numbers, true/false, null */
    static boolean isScalar(Object value) {
        return value == null || value instanceof CharSequence || value instanceof Number || value instanceof Boolean || value instanceof Character
    }

    /** A copy of any collection, counted as it is walked; never trusts size(), which a huge range overflows. */
    static List copyCounted(Iterable items) {
        List copy = new ArrayList()
        for (Object item : items) {
            charge()
            copy.add(item)
            checkSize(copy.size())
        }
        return copy
    }

    /** Text of any value, at most limit characters then '…', built node by node and charged, never by toString. */
    static String render(Object value, int limit) {
        StringBuilder out = new StringBuilder()
        renderInto(value, out, limit)
        return out.length() > limit ? out.substring(0, limit) + '…' : out.toString()
    }

    static void log(String line) {
        ScriptBudget budget = CURRENT.get()
        if (budget == null || budget.logs.size() >= MAX_LOG_LINES) return
        budget.logs << (line.length() > MAX_LOG_LINE_CHARS ? line.substring(0, MAX_LOG_LINE_CHARS) + '…' : line)
    }

    static void noteMissing(Object field, Map record) {
        ScriptBudget budget = CURRENT.get()
        if (budget == null) return
        budget.missingField = String.valueOf(field)
        budget.recordFields = record.keySet().take(12).collect { String.valueOf(it) }
    }

    private static void renderInto(Object value, StringBuilder out, int limit) {
        if (out.length() > limit) return
        charge()
        if (value instanceof BigDecimal) {
            out.append(checkDecimal((BigDecimal) value).toPlainString())
            return
        }
        if (isScalar(value)) {
            String text = String.valueOf(value)
            out.append(text, 0, Math.min(text.length(), Math.max(0, limit + 1 - out.length())))
            return
        }
        boolean isMap = value instanceof Map
        if (!isMap && !(value instanceof Iterable) && !value.getClass().isArray()) {
            out.append('(not data)')
            return
        }
        Iterable items = isMap ? ((Map) value).entrySet() : (value instanceof Iterable ? (Iterable) value : ScriptArithmetic.arrayItems(value))
        out.append(isMap ? '{' : '[')
        boolean first = true
        for (Object item : items) {
            if (out.length() > limit) return
            if (!first) out.append(', ')
            first = false
            if (isMap) {
                renderInto(((Map.Entry) item).key, out, limit)
                out.append(': ')
                renderInto(((Map.Entry) item).value, out, limit)
            } else {
                renderInto(item, out, limit)
            }
        }
        out.append(isMap ? '}' : ']')
    }
}

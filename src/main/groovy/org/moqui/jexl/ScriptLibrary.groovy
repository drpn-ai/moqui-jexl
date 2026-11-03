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

import org.moqui.jexl.ScriptBudget.LimitReached
import groovy.transform.CompileStatic
import org.moqui.util.CollectionUtilities

import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * The fn: functions, the only ones a user script can call. Each takes and returns plain JSON values and charges the
 * ScriptBudget for its work. There is no clock and no randomness, so the same input always gives the same result.
 *
 * Only what JEXL cannot do itself: size(x), empty(x), =^ / =$ (begins / ends with), =~ on a list (is in), ?: and ??
 * are built in, so there is no fn:len, fn:begins, fn:ends, fn:min, fn:max, fn:abs or fn:count.
 *
 * NOTE: names avoid JEXL's reserved words (size, empty, new, not, and, or); only public static methods are callable
 */
@CompileStatic
class ScriptLibrary {
    /** every function and its one-line help, for the editor; a field, not a property, so Groovy adds no getter */
    public static final List<Map<String, String>> CATALOG = [
            [name: 'dec(x)', help: 'x as a decimal number (text or number); null stays null'],
            [name: 'round(x, places)', help: 'x rounded half-up to places decimals; fn:round(x, 2) for money'],
            [name: 'str(x)', help: 'x as text'],
            [name: 'lower(s) / upper(s) / trim(s)', help: 'text case and surrounding spaces'],
            [name: 'has(s, part)', help: 'true when text s contains part'],
            [name: 'replace(s, from, to)', help: 'every from in s replaced with to (plain text, no patterns)'],
            [name: 'split(s, sep)', help: 'text s split on sep into a list'],
            [name: 'join(list, sep)', help: 'the list as one text, sep between items'],
            [name: 'keys(map) / vals(map)', help: "a map's keys / values as a list"],
            [name: 'pluck(list, field)', help: 'field from each map in the list'],
            [name: 'where(list, field, value)', help: 'the maps in the list whose field equals value; size(...) counts them'],
            [name: 'sum(list) / sum(list, field)', help: 'the total of the numbers, or of field across maps'],
            [name: 'groupCount(list, field)', help: 'a map of each value of field to how many maps have it'],
            [name: 'sorted(list) / distinct(list)', help: 'the list sorted / with repeats removed'],
            [name: "sortBy(list, 'field')", help: "the maps ordered by field: '-field' descending, '^field' ignoring case, 'a,b' by several"],
            [name: 'day(x)', help: 'a date or timestamp as yyyy-MM-dd (UTC)'],
            [name: 'daysBetween(a, b) / hoursBetween(a, b)', help: 'whole days / hours from a to b (dates, timestamps or epoch ms)'],
            [name: 'log(x)', help: 'adds x to the run log and returns it'],
    ].collect { it as Map<String, String> }.asImmutable()

    // -- numbers

    static BigDecimal dec(Object value) {
        ScriptBudget.charge()
        if (value == null) return null
        if (value instanceof BigDecimal) return ScriptBudget.checkDecimal((BigDecimal) value)
        if (value instanceof Number) return ScriptBudget.checkDecimal(new BigDecimal(value.toString()))
        String text = value.toString().trim().replace(',', '')
        if (text.isEmpty()) return null
        if (text.length() > 64) throw new LimitReached('NUMBER', "'${text.take(20)}…' is too long to be a number.".toString())
        try {
            return ScriptBudget.checkDecimal(new BigDecimal(text))
        } catch (NumberFormatException ignored) {
            throw new IllegalArgumentException("'${text}' is not a number.".toString())
        }
    }

    static BigDecimal round(Object value, Object places) {
        BigDecimal number = dec(value)
        int scale = places == null ? 0 : dec(places).intValue()
        if (scale < 0 || scale > 12) throw new IllegalArgumentException('round takes 0 to 12 places.')
        return number?.setScale(scale, RoundingMode.HALF_UP)
    }

    // -- text

    /** Text of a value; a list or map renders node by node up to the text cap, never through its own toString. */
    static String str(Object value) {
        ScriptBudget.charge()
        if (value == null) return null
        if (value instanceof CharSequence) return value.toString()
        if (value instanceof BigDecimal) return ScriptBudget.checkDecimal((BigDecimal) value).toPlainString()
        if (ScriptBudget.isScalar(value)) return value.toString()
        int maxChars = ScriptBudget.current()?.maxStringChars ?: 65_536
        return ScriptBudget.checkString(ScriptBudget.render(value, maxChars))
    }

    static String lower(Object text) { str(text)?.toLowerCase(Locale.ROOT) }

    static String upper(Object text) { str(text)?.toUpperCase(Locale.ROOT) }

    static String trim(Object text) { str(text)?.trim() }

    static boolean has(Object text, Object part) {
        String whole = str(text), piece = str(part)
        ScriptBudget.charge(1 + (whole?.length() ?: 0).intdiv(64))
        return whole != null && piece != null && whole.contains(piece)
    }

    static String replace(Object text, Object from, Object to) {
        String whole = str(text), target = str(from)
        if (whole == null || !target) return whole
        String replacement = str(to) ?: ''
        // the result's length is known before it is built: count the matches first
        long matches = 0
        for (int at = whole.indexOf(target); at >= 0; at = whole.indexOf(target, at + target.length())) matches++
        ScriptBudget.charge(1 + whole.length().intdiv(64) + matches)
        ScriptBudget.checkLength(whole.length() + matches * (replacement.length() - target.length()))
        return ScriptBudget.checkString(whole.replace(target, replacement))
    }

    static List<String> split(Object text, Object separator) {
        String whole = str(text), sep = str(separator)
        if (whole == null) return []
        if (!sep) throw new IllegalArgumentException('split needs a separator.')
        List<String> parts = []
        int from = 0
        while (true) {
            ScriptBudget.charge()
            int at = whole.indexOf(sep, from)
            parts << (at < 0 ? whole.substring(from) : whole.substring(from, at))
            ScriptBudget.checkSize(parts.size())
            if (at < 0) break
            from = at + sep.length()
        }
        return parts
    }

    static String join(Object list, Object separator) {
        List items = asList(list, 'join')
        ScriptBudget.charge(items.size())
        String sep = str(separator) ?: ''
        List<String> parts = items.collect { Object item ->
            if (!ScriptBudget.isScalar(item)) throw new IllegalArgumentException('join takes a list of text or numbers.')
            item instanceof BigDecimal ? ScriptBudget.checkDecimal((BigDecimal) item).toPlainString() : String.valueOf(item)
        }
        // summed before joining, so the cap holds at the peak allocation
        long total = (parts.sum(0L) { String part -> (long) part.length() } as long) + Math.max(0, parts.size() - 1) * (long) sep.length()
        ScriptBudget.checkLength(total)
        return ScriptBudget.checkString(parts.join(sep))
    }

    // -- lists and maps

    static List keys(Object map) {
        Map entries = asMap(map, 'keys')
        ScriptBudget.charge(entries.size())
        return new ArrayList(entries.keySet())
    }

    static List vals(Object map) {
        Map entries = asMap(map, 'vals')
        ScriptBudget.charge(entries.size())
        return new ArrayList(entries.values())
    }

    static List pluck(Object list, Object field) {
        List items = asList(list, 'pluck')
        ScriptBudget.charge(items.size())
        return items.collect { Object item -> fieldOf(item, field) }
    }

    static List where(Object list, Object field, Object value) {
        List items = asList(list, 'where')
        ScriptBudget.charge(items.size())
        return items.findAll { Object item -> item instanceof Map && same(fieldOf(item, field), value) }
    }

    static BigDecimal sum(Object list) { sum(list, null) }

    static BigDecimal sum(Object list, Object field) {
        List items = asList(list, 'sum')
        ScriptBudget.charge(items.size())
        BigDecimal total = BigDecimal.ZERO
        for (Object item : items) {
            BigDecimal number = dec(field == null ? item : fieldOf(item, field))
            if (number != null) total = total.add(number)
        }
        return total
    }

    static Map<String, Integer> groupCount(Object list, Object field) {
        List items = asList(list, 'groupCount')
        ScriptBudget.charge(items.size())
        return items.countBy { Object item -> ScriptBudget.render(fieldOf(item, field), 200) } as Map<String, Integer>
    }

    static List sorted(Object list) {
        List items = new ArrayList(asList(list, 'sorted'))
        if (items.any { !ScriptBudget.isScalar(it) }) throw new IllegalArgumentException('sorted takes a list of text or numbers.')
        ScriptBudget.charge(items.size() * 4L)
        items.sort { Object first, Object second ->
            if (first instanceof Number && second instanceof Number) return dec(first) <=> dec(second)
            if (first == null || second == null) return first == null ? (second == null ? 0 : -1) : 1
            return String.valueOf(first) <=> String.valueOf(second)
        }
        return items
    }

    /** The maps in the list in Moqui order-by form: 'total', '-total' descending, '^name' ignoring case, 'a,b' by several. */
    static List sortBy(Object list, Object orderBy) {
        List items = new ArrayList(asList(list, 'sortBy'))
        if (items.any { !(it instanceof Map) }) throw new IllegalArgumentException('sortBy takes a list of maps.')
        List<String> fields = (str(orderBy) ?: '').split(',').collect { String field -> field.trim() } as List<String>
        if (fields.size() > MAX_SORT_FIELDS || fields.any { String field -> !field.replaceFirst(/^[-+]?\^?/, '') }) {
            throw new IllegalArgumentException("sortBy takes 1 to ${MAX_SORT_FIELDS} fields, like '-total,orderId'.".toString())
        }
        // NOTE: Moqui's comparator is not metered, so the whole sort is charged first: items x log2(items) x fields
        ScriptBudget.charge((long) items.size() * (32 - Integer.numberOfLeadingZeros(items.size() + 1)) * fields.size())
        try {
            return CollectionUtilities.orderMapList((List<Map<String, Object>>) items, fields)
        } catch (ClassCastException ignored) {
            throw new IllegalArgumentException('sortBy sorts on text or numbers.')
        }
    }

    static List distinct(Object list) {
        List items = asList(list, 'distinct')
        if (items.any { !ScriptBudget.isScalar(it) }) throw new IllegalArgumentException('distinct takes a list of text or numbers.')
        ScriptBudget.charge(items.size())
        return new ArrayList(new LinkedHashSet(items))
    }

    // -- dates, from the input only: there is no clock

    static String day(Object value) { instant(value)?.atOffset(ZoneOffset.UTC)?.toLocalDate()?.toString() }

    static Long daysBetween(Object from, Object to) { between(from, to)?.toDays() }

    static Long hoursBetween(Object from, Object to) { between(from, to)?.toHours() }

    // -- log

    static Object log(Object value) {
        ScriptBudget.charge()
        ScriptBudget.log(ScriptBudget.render(value, 500))
        return value
    }

    // -- helpers, private so a script cannot call them

    private static final int MAX_SORT_FIELDS = 5

    private static final List<Closure<Instant>> DATE_FORMATS = [
            { String text -> Instant.parse(text) },
            { String text -> OffsetDateTime.parse(text).toInstant() },
            { String text -> LocalDateTime.parse(text.replace(' ', 'T')).toInstant(ZoneOffset.UTC) },
            { String text -> LocalDate.parse(text.take(10)).atStartOfDay().toInstant(ZoneOffset.UTC) },
    ] as List<Closure<Instant>>

    private static List asList(Object value, String function) {
        if (value == null) return []
        if (value instanceof List) return (List) value
        // NOTE: a range (1..100000000) is lazy and its size() can overflow, so it is copied item by item, counted
        if (value instanceof Collection) return ScriptBudget.copyCounted((Collection) value)
        if (value.getClass().isArray()) return ScriptArithmetic.arrayItems(value)
        throw new IllegalArgumentException("${function} takes a list.".toString())
    }

    private static Map asMap(Object value, String function) {
        if (value == null) return [:]
        if (value instanceof Map) return (Map) value
        throw new IllegalArgumentException("${function} takes a map.".toString())
    }

    private static Object fieldOf(Object item, Object field) { item instanceof Map ? ((Map) item).get(field) : null }

    private static boolean same(Object first, Object second) {
        if (first instanceof Number && second instanceof Number) return dec(first).compareTo(dec(second)) == 0
        if (!ScriptBudget.isScalar(first) || !ScriptBudget.isScalar(second)) return first.is(second)
        return first == second || (first != null && second != null && first.toString() == second.toString())
    }

    private static Duration between(Object from, Object to) {
        Instant start = instant(from), end = instant(to)
        return start == null || end == null ? null : Duration.between(start, end)
    }

    private static Instant instant(Object value) {
        ScriptBudget.charge()
        if (value == null) return null
        if (value instanceof Number) return Instant.ofEpochMilli(((Number) value).longValue())
        String text = value.toString().trim()
        if (text.isEmpty()) return null
        if (text.isLong()) return Instant.ofEpochMilli(text.toLong())
        for (Closure<Instant> format : DATE_FORMATS) {
            try {
                return format.call(text)
            } catch (DateTimeParseException ignored) {
                // not this format, try the next
            }
        }
        throw new IllegalArgumentException("'${text.take(40)}' is not a date or timestamp.".toString())
    }
}

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
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityCondition

import java.math.RoundingMode
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.regex.Pattern

/**
 * Conditions in a read spec: {field, op, value}, combined with {and: [...]} / {or: [...]}, nested at most 8 deep. The
 * operators are exactly Moqui's EntityCondition operators, so the entity source turns a condition into an
 * EntityCondition one to one, and a source that reads something else matches records in memory with the same meaning
 * (matches), groups them (group) and orders them (order).
 */
@CompileStatic
class ReadConditions {
    static final List<String> OPS = ['equals', 'not-equals', 'less', 'greater', 'less-equals', 'greater-equals', 'in',
                                     'not-in', 'between', 'like', 'not-like', 'is-null', 'is-not-null'].asImmutable()
    static final int MAX_DEPTH = 8
    static final int MAX_IN_VALUES = 1_000

    static void validate(Map where) { validate(where, 0) }

    private static void validate(Map where, int depth) {
        if (depth >= MAX_DEPTH) throw new ReadSpec.Invalid("Conditions nest at most ${MAX_DEPTH} levels deep.".toString())
        if (where.containsKey('and') || where.containsKey('or')) {
            Object list = where.containsKey('and') ? where.get('and') : where.get('or')
            if (!(list instanceof List) || ((List) list).isEmpty()) throw new ReadSpec.Invalid('and / or take a non-empty list of conditions.')
            for (Object item : (List) list) {
                if (!(item instanceof Map)) throw new ReadSpec.Invalid('Each condition is a map.')
                validate((Map) item, depth + 1)
            }
            return
        }
        String field = where.get('field')?.toString()
        if (!field) throw new ReadSpec.Invalid('A condition needs a field.')
        String op = where.get('op')?.toString()
        if (!OPS.contains(op)) throw new ReadSpec.Invalid("Condition operator '${op}' is not one of ${OPS.join(', ')}.".toString())
        Object value = where.get('value')
        switch (op) {
            case 'is-null': case 'is-not-null': return
            case 'in': case 'not-in':
                if (!(value instanceof List)) throw new ReadSpec.Invalid("${op} takes a list of values.".toString())
                if (((List) value).size() > MAX_IN_VALUES) throw new ReadSpec.Invalid("${op} takes at most ${MAX_IN_VALUES} values.".toString())
                ((List) value).each { Object v -> scalar(v, field) }
                return
            case 'between':
                if (!(value instanceof List) || ((List) value).size() != 2) throw new ReadSpec.Invalid('between takes a list of two values.')
                ((List) value).each { Object v -> scalar(v, field) }
                return
            default:
                scalar(value, field)
        }
    }

    private static void scalar(Object value, String field) {
        if (!(value == null || value instanceof CharSequence || value instanceof Number || value instanceof Boolean)) {
            throw new ReadSpec.Invalid("The value compared with ${field} must be text, a number or true/false.".toString())
        }
    }

    /** The same condition as a Moqui EntityCondition. */
    static EntityCondition toEntityCondition(ExecutionContext ec, Map where) {
        def factory = ec.entity.conditionFactory
        if (where.containsKey('and') || where.containsKey('or')) {
            boolean and = where.containsKey('and')
            List<EntityCondition> parts = ((List) where.get(and ? 'and' : 'or')).collect { Object item -> toEntityCondition(ec, (Map) item) }
            return factory.makeCondition(parts, and ? EntityCondition.JoinOperator.AND : EntityCondition.JoinOperator.OR)
        }
        String field = where.get('field').toString()
        String op = where.get('op').toString()
        EntityCondition.ComparisonOperator operator = factory.comparisonOperatorFromEnumId(enumIdOf(op))
        Object value = where.get('value')
        if (op == 'is-null') return factory.makeCondition(field, EntityCondition.ComparisonOperator.EQUALS, null)
        if (op == 'is-not-null') return factory.makeCondition(field, EntityCondition.ComparisonOperator.NOT_EQUAL, null)
        return factory.makeCondition(field, operator, value)
    }

    private static String enumIdOf(String op) {
        switch (op) {
            case 'equals': return 'ENTCO_EQUALS'
            case 'not-equals': return 'ENTCO_NOT_EQUAL'
            case 'less': return 'ENTCO_LESS'
            case 'greater': return 'ENTCO_GREATER'
            case 'less-equals': return 'ENTCO_LESS_EQ'
            case 'greater-equals': return 'ENTCO_GREATER_EQ'
            case 'in': return 'ENTCO_IN'
            case 'not-in': return 'ENTCO_NOT_IN'
            case 'between': return 'ENTCO_BETWEEN'
            case 'like': return 'ENTCO_LIKE'
            case 'not-like': return 'ENTCO_NOT_LIKE'
            default: return 'ENTCO_EQUALS'
        }
    }

    /** The window as a condition: field on or after the start of the day lastDays before runDate, in timeZone. */
    static Map windowCondition(Map window, Timestamp runDate, String timeZone) {
        ZoneId zone = timeZone ? ZoneId.of(timeZone) : ZoneId.systemDefault()
        LocalDate from = runDate.toInstant().atZone(zone).toLocalDate().minusDays((window.get('lastDays') as Number).longValue())
        return [field: window.get('field'), op: 'greater-equals', value: Timestamp.from(from.atStartOfDay(zone).toInstant())] as Map
    }

    /** Whether a record (a map of field to value) meets the condition, with the entity source's meaning. */
    static boolean matches(Map where, Map record) {
        if (where.containsKey('and')) return ((List) where.get('and')).every { Object c -> matches((Map) c, record) }
        if (where.containsKey('or')) return ((List) where.get('or')).any { Object c -> matches((Map) c, record) }
        Object actual = record.get(where.get('field'))
        Object value = where.get('value')
        switch (where.get('op')?.toString()) {
            case 'is-null': return actual == null
            case 'is-not-null': return actual != null
        }
        if (actual == null) return where.get('op') == 'not-equals' ? value != null : (where.get('op') == 'not-in')
        switch (where.get('op')?.toString()) {
            case 'equals': return compare(actual, value) == 0
            case 'not-equals': return compare(actual, value) != 0
            case 'less': return compare(actual, value) < 0
            case 'greater': return compare(actual, value) > 0
            case 'less-equals': return compare(actual, value) <= 0
            case 'greater-equals': return compare(actual, value) >= 0
            case 'in': return ((List) value).any { Object v -> compare(actual, v) == 0 }
            case 'not-in': return !((List) value).any { Object v -> compare(actual, v) == 0 }
            case 'between': return compare(actual, ((List) value)[0]) >= 0 && compare(actual, ((List) value)[1]) <= 0
            case 'like': return likePattern(value?.toString()).matcher(actual.toString()).matches()
            case 'not-like': return !likePattern(value?.toString()).matcher(actual.toString()).matches()
            default: return false
        }
    }

    /** Numbers as numbers (text that is a number too), dates as instants (ISO text too), everything else as text. */
    static int compare(Object a, Object b) {
        if (b == null) return a == null ? 0 : 1
        BigDecimal na = number(a), nb = number(b)
        if (na != null && nb != null) return na <=> nb
        Instant ia = instant(a), ib = instant(b)
        if (ia != null && ib != null && (a instanceof Date || b instanceof Date)) return ia <=> ib
        return a.toString() <=> b.toString()
    }

    private static BigDecimal number(Object v) {
        if (v instanceof BigDecimal) return (BigDecimal) v
        if (v instanceof Number) return new BigDecimal(v.toString())
        if (v instanceof CharSequence) {
            String s = v.toString().trim()
            if (s.length() > 0 && s.length() <= 40 && s ==~ /-?\d+(\.\d+)?/) return new BigDecimal(s)
        }
        return null
    }

    private static Instant instant(Object v) {
        if (v instanceof Date) return ((Date) v).toInstant()
        if (!(v instanceof CharSequence)) return null
        String s = v.toString().trim()
        try {
            if (s.length() == 10) return LocalDate.parse(s).atStartOfDay(ZoneId.systemDefault()).toInstant()
            if (s.endsWith('Z') || s.contains('+')) return Instant.parse(s)
            return LocalDateTime.parse(s.replace(' ', 'T')).atZone(ZoneId.systemDefault()).toInstant()
        } catch (Exception ignored) {
            return null
        }
    }

    private static Pattern likePattern(String like) {
        StringBuilder regex = new StringBuilder()
        for (char c : (like ?: '').toCharArray()) {
            if (c == ('%' as char)) regex.append('.*')
            else if (c == ('_' as char)) regex.append('.')
            else regex.append(Pattern.quote(String.valueOf(c)))
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL)
    }

    /** In-memory group-by: one row per distinct groupBy values (first-seen order), with each measure under its as name. */
    static List<Map> group(List<Map> rows, List<String> groupBy, List<Map> measures) {
        Map<List, Map> groups = new LinkedHashMap<>()
        Map<List, List<Map>> members = new LinkedHashMap<>()
        for (Map row : rows) {
            List key = groupBy.collect { String f -> row.get(f) }
            if (!groups.containsKey(key)) {
                Map out = new LinkedHashMap()
                groupBy.eachWithIndex { String f, int i -> out.put(f, key[i]) }
                groups.put(key, out)
                members.put(key, [])
            }
            members.get(key).add(row)
        }
        for (Map.Entry<List, Map> entry : groups.entrySet()) {
            List<Map> group = members.get(entry.key)
            for (Map measure : measures) entry.value.put(measure.get("as"), measureOf(group, measure))
        }
        return new ArrayList<Map>(groups.values())
    }

    private static Object measureOf(List<Map> group, Map measure) {
        String fn = measure.get('fn')
        if (fn == 'count') return (long) group.size()
        List values = group.collect { Map r -> r.get(measure.get('field')) }.findAll { it != null }
        if (fn == 'min') return values ? values.min { Object x, Object y -> compare(x, y) } : null
        if (fn == 'max') return values ? values.max { Object x, Object y -> compare(x, y) } : null
        List<BigDecimal> numbers = values.collect { Object v -> number(v) }.findAll { it != null } as List<BigDecimal>
        if (numbers.isEmpty()) return null
        BigDecimal sum = numbers.inject(BigDecimal.ZERO) { BigDecimal acc, BigDecimal v -> acc + v } as BigDecimal
        if (fn == 'sum') return plain(sum)
        return plain(sum.divide(new BigDecimal(numbers.size()), 10, RoundingMode.HALF_UP))
    }

    private static Object plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros()
        if (stripped.scale() <= 0 && stripped.abs() < new BigDecimal(Long.MAX_VALUE)) return stripped.longValueExact()
        return stripped
    }

    /** In-memory order: field names, each led by - for descending; nulls last. */
    static List<Map> order(List<Map> rows, List<String> orderBy) {
        if (!orderBy) return rows
        List<Map> out = new ArrayList<Map>(rows)
        out.sort { Map a, Map b ->
            for (String entry : orderBy) {
                boolean desc = entry.startsWith('-')
                String f = desc ? entry.substring(1) : entry
                Object x = a.get(f), y = b.get(f)
                int c = x == null ? (y == null ? 0 : 1) : (y == null ? -1 : compare(x, y) * (desc ? -1 : 1))
                if (c != 0) return c
            }
            return 0
        }
        return out
    }
}

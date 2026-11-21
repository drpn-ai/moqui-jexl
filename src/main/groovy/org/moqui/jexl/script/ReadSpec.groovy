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

import groovy.json.JsonSlurper
import groovy.transform.CompileStatic

import java.util.regex.Pattern

/**
 * The read step a script declares: up to 10 named inputs, each read from the built-in entity source or a source the
 * application registers (ScriptSource), with the fields to keep, conditions (ReadConditions), an optional window of the
 * last N days on a date field, and optional group-by with count / sum / min / max / avg. An application source may
 * carry its own keys (raw keeps the whole input map).
 */
@CompileStatic
class ReadSpec {
    static final int MAX_INPUTS = 10
    static final int MAX_LAST_DAYS = 3_660
    static final List<String> MEASURES = ['count', 'sum', 'min', 'max', 'avg'].asImmutable()
    private static final Pattern INPUT_NAME = Pattern.compile('^[a-z][A-Za-z0-9]{0,39}$')
    private static final Pattern FIELD_NAME = Pattern.compile('^[A-Za-z_][A-Za-z0-9_.]{0,99}$')

    /** A read spec that cannot be used, and why. */
    static class Invalid extends RuntimeException {
        Invalid(String message) { super(message) }
    }

    /** One input: what the script sees as input.&lt;name&gt;. */
    static class Input {
        String name
        String source
        String entityName
        List<String> fields = []
        Map where
        Map window
        List<String> groupBy = []
        List<Map> measures = []
        List<String> orderBy = []
        /** the input exactly as written, for a source's own keys */
        Map raw
    }

    final List<Input> inputs

    private ReadSpec(List<Input> inputs) { this.inputs = inputs.asImmutable() }

    static ReadSpec parse(String json) {
        Object parsed
        try {
            parsed = new JsonSlurper().parseText(json ?: '')
        } catch (Exception e) {
            throw new Invalid("The read spec is not valid JSON: ${e.message?.readLines()?.first()}".toString())
        }
        if (!(parsed instanceof Map) || !(((Map) parsed).get('inputs') instanceof List)) throw new Invalid('The read spec needs an inputs list.')
        List rawInputs = (List) ((Map) parsed).get('inputs')
        if (rawInputs.isEmpty()) throw new Invalid('The read spec needs at least one input.')
        if (rawInputs.size() > MAX_INPUTS) throw new Invalid("A read spec has at most ${MAX_INPUTS} inputs; this one has ${rawInputs.size()}.".toString())
        List<Input> inputs = []
        Set<String> names = new HashSet<>()
        for (Object item : rawInputs) {
            if (!(item instanceof Map)) throw new Invalid('Each input must be a map.')
            Input input = input((Map) item)
            if (!names.add(input.name)) throw new Invalid("The read spec names input '${input.name}' twice.".toString())
            inputs.add(input)
        }
        return new ReadSpec(inputs)
    }

    private static Input input(Map m) {
        Input input = new Input(raw: m)
        input.name = m.get('name')?.toString()
        if (!input.name || !INPUT_NAME.matcher(input.name).matches()) {
            throw new Invalid("An input name starts with a lower-case letter and has only letters and digits (up to 40): '${input.name}'.".toString())
        }
        String at = "Input '${input.name}'"
        input.source = m.get('source')?.toString()?.trim()
        if (!input.source) throw new Invalid("${at} needs a source.".toString())
        if (input.source == 'entity') {
            input.entityName = m.get('entityName')?.toString()?.trim()
            if (!input.entityName) throw new Invalid("${at} reads the entity source and needs an entityName.".toString())
        }
        input.fields = names(m.get('fields'), "${at} fields")
        input.groupBy = names(m.get('groupBy'), "${at} groupBy")
        input.orderBy = orderBy(m.get('orderBy'), "${at} orderBy".toString())
        input.measures = measures(m.get('measures'), at)
        if (input.fields.isEmpty() && input.measures.isEmpty()) throw new Invalid("${at} needs fields (or measures).".toString())
        if (m.get('where') != null) {
            if (!(m.get('where') instanceof Map)) throw new Invalid("${at} where must be a condition map.".toString())
            ReadConditions.validate((Map) m.get('where'))
            input.where = (Map) m.get('where')
        }
        if (m.get('window') != null) input.window = window(m.get('window'), at)
        return input
    }

    private static List<String> names(Object raw, String what) {
        if (raw == null) return []
        if (!(raw instanceof List)) throw new Invalid("${what} must be a list of field names.".toString())
        List<String> out = []
        for (Object item : (List) raw) {
            String name = item?.toString()
            if (!name || !FIELD_NAME.matcher(name).matches()) throw new Invalid("${what}: '${name}' is not a field name.".toString())
            out.add(name)
        }
        return out
    }

    /** field names, each optionally led by - for descending */
    private static List<String> orderBy(Object raw, String what) {
        if (raw == null) return []
        if (!(raw instanceof List)) throw new Invalid("${what} must be a list of field names.".toString())
        List<String> out = []
        for (Object item : (List) raw) {
            String entry = item?.toString()
            String name = entry?.startsWith('-') ? entry.substring(1) : entry
            if (!name || !FIELD_NAME.matcher(name).matches()) throw new Invalid("${what}: '${entry}' is not a field name.".toString())
            out.add(entry)
        }
        return out
    }

    private static List<Map> measures(Object raw, String at) {
        if (raw == null) return []
        if (!(raw instanceof List)) throw new Invalid("${at} measures must be a list.".toString())
        List<Map> out = []
        for (Object item : (List) raw) {
            if (!(item instanceof Map)) throw new Invalid("${at}: each measure is a map of fn, field and as.".toString())
            Map measure = (Map) item
            String fn = measure.get('fn')?.toString()
            if (!MEASURES.contains(fn)) throw new Invalid("${at}: measure fn '${fn}' is not one of ${MEASURES.join(', ')}.".toString())
            String as = measure.get('as')?.toString()
            if (!as || !FIELD_NAME.matcher(as).matches()) throw new Invalid("${at}: each measure needs an as name.".toString())
            String field = measure.get('field')?.toString()
            if (fn != 'count' && !field) throw new Invalid("${at}: measure ${fn} needs a field.".toString())
            if (field && !FIELD_NAME.matcher(field).matches()) throw new Invalid("${at}: '${field}' is not a field name.".toString())
            out.add([fn: fn, field: field, as: as] as Map)
        }
        return out
    }

    private static Map window(Object raw, String at) {
        if (!(raw instanceof Map)) throw new Invalid("${at} window must be {field, lastDays}.".toString())
        Map w = (Map) raw
        String field = w.get('field')?.toString()
        if (!field || !FIELD_NAME.matcher(field).matches()) throw new Invalid("${at} window needs a field.".toString())
        Object days = w.get('lastDays')
        if (!(days instanceof Number) || ((Number) days).intValue() < 1 || ((Number) days).intValue() > MAX_LAST_DAYS
                || ((Number) days).doubleValue() != (double) ((Number) days).intValue()) {
            throw new Invalid("${at} window lastDays must be a whole number from 1 to ${MAX_LAST_DAYS}.".toString())
        }
        return [field: field, lastDays: ((Number) days).intValue()] as Map
    }
}

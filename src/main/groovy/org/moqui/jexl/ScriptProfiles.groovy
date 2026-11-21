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
import org.moqui.util.SystemBinding
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Named limit profiles. EXPRESSION (one expression, no statements) and SCRIPT are built in. An application adds a
 * profile by listing its name in the setting jexl_profiles; it starts from SCRIPT's limits. Any limit of any profile is
 * tuned by the setting jexl_profile_&lt;NAME&gt;_&lt;field&gt; (for example jexl_profile_SCRIPT_timeoutMs). Settings are read
 * with SystemBinding.getPropOrEnv on every get, so a change takes effect on the next run.
 */
@CompileStatic
class ScriptProfiles {
    private static final Logger logger = LoggerFactory.getLogger(ScriptProfiles)

    static final String EXPRESSION = 'EXPRESSION'
    static final String SCRIPT = 'SCRIPT'

    private static final Map<String, Object> SCRIPT_LIMITS = [
            maxSourceChars    : 64_000, units: 2_000_000L, timeoutMs: 2_000L, maxStringChars: 65_536,
            maxCollectionSize : 10_000, maxInputValues: 500_000, maxInputJsonChars: 2_000_000,
            maxOutputJsonChars: 65_536, maxOutputNodes: 20_000, maxDepth: 32, maxReadRows: 2_000,
            maxAllocatedBytes : 64L * 1024 * 1024, statements: true,
    ].asImmutable() as Map<String, Object>
    private static final Map<String, Map<String, Object>> BUILT_IN = [
            (SCRIPT)    : SCRIPT_LIMITS,
            (EXPRESSION): (SCRIPT_LIMITS + ([maxSourceChars: 2_000, units: 100_000L, timeoutMs: 250L, statements: false] as Map<String, Object>)).asImmutable(),
    ].asImmutable() as Map<String, Map<String, Object>>

    /** Built-in names first, then the application's, upper case, without duplicates. */
    static List<String> names() {
        List<String> out = [EXPRESSION, SCRIPT]
        String extra = SystemBinding.getPropOrEnv('jexl_profiles')
        if (extra) {
            for (String name : extra.split(',')) {
                String clean = name.trim().toUpperCase(Locale.ROOT)
                if (clean && !out.contains(clean)) out.add(clean)
            }
        }
        return out
    }

    static ScriptProfile get(String name) {
        String clean = name?.trim()?.toUpperCase(Locale.ROOT)
        if (!clean || !names().contains(clean)) {
            throw new IllegalArgumentException("Unknown script profile '${name}'; profiles are ${names().join(', ')}.".toString())
        }
        Map<String, Object> base = BUILT_IN[clean] ?: SCRIPT_LIMITS
        Map<String, Object> values = new LinkedHashMap<>(base)
        for (String field : base.keySet()) {
            String raw = SystemBinding.getPropOrEnv("jexl_profile_${clean}_${field}".toString())
            if (raw == null || raw.trim().isEmpty()) continue
            Object parsed = parse(base[field], raw.trim())
            if (parsed == null) {
                logger.warn("Setting jexl_profile_${clean}_${field}='${raw}' is not a valid value; using ${base[field]}")
            } else {
                values[field] = parsed
            }
        }
        return new ScriptProfile(clean, values)
    }

    private static Object parse(Object example, String raw) {
        try {
            if (example instanceof Boolean) return raw.equalsIgnoreCase('true') ? true : raw.equalsIgnoreCase('false') ? false : null
            if (example instanceof Long) { long v = Long.parseLong(raw); return v > 0 ? v : null }
            int v = Integer.parseInt(raw)
            return v > 0 ? v : null
        } catch (NumberFormatException ignored) {
            return null
        }
    }
}

/** One profile's limits, read once per run. */
@CompileStatic
class ScriptProfile {
    final String name
    final int maxSourceChars
    final long units
    final long timeoutMs
    final int maxStringChars
    final int maxCollectionSize
    final int maxInputValues
    final int maxInputJsonChars
    final int maxOutputJsonChars
    final int maxOutputNodes
    final int maxDepth
    final int maxReadRows
    final long maxAllocatedBytes
    final boolean statements

    ScriptProfile(String name, Map<String, Object> v) {
        this.name = name
        maxSourceChars = v.maxSourceChars as int
        units = v.units as long
        timeoutMs = v.timeoutMs as long
        maxStringChars = v.maxStringChars as int
        maxCollectionSize = v.maxCollectionSize as int
        maxInputValues = v.maxInputValues as int
        maxInputJsonChars = v.maxInputJsonChars as int
        maxOutputJsonChars = v.maxOutputJsonChars as int
        maxOutputNodes = v.maxOutputNodes as int
        maxDepth = v.maxDepth as int
        maxReadRows = v.maxReadRows as int
        maxAllocatedBytes = v.maxAllocatedBytes as long
        statements = v.statements as boolean
    }

    /** The limits as a map, for the editor's library call. */
    Map<String, Object> toMap() {
        return [name: name, maxSourceChars: maxSourceChars, units: units, timeoutMs: timeoutMs, maxStringChars: maxStringChars,
                maxCollectionSize: maxCollectionSize, maxInputValues: maxInputValues, maxInputJsonChars: maxInputJsonChars,
                maxOutputJsonChars: maxOutputJsonChars, maxOutputNodes: maxOutputNodes, maxDepth: maxDepth,
                maxReadRows: maxReadRows, maxAllocatedBytes: maxAllocatedBytes, statements: statements] as Map<String, Object>
    }
}

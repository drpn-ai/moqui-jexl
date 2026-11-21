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
import org.moqui.jexl.ScriptProfile
import org.moqui.util.SystemBinding

import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/**
 * The result contract. A script returns nothing (nothing to say) or {summary, files, data}: summary is text, files are
 * [{name, columns, rows}] rendered to CSV by toCsv, data is passed to deliveries untouched. A bare text or number is a
 * summary; a map with none of those keys, or a list, is data. Checked and capped here, so a delivery receives a known
 * shape. Caps: summary jexl_result_summary_chars (4,000), files jexl_result_files (5), rows per file the profile's
 * maxCollectionSize, a cell 32,767 characters (a spreadsheet's own limit).
 */
@CompileStatic
class ScriptResult {
    static final int MAX_CELL_CHARS = 32_767
    private static final Pattern FILE_NAME = Pattern.compile('^[A-Za-z0-9._ -]{1,100}$')
    private static final String FORMULA_LEADS = '=+-@\uFF1D\uFF0B\uFF0D\uFF20'
    private static final byte[] BOM = [(byte) 0xEF, (byte) 0xBB, (byte) 0xBF] as byte[]

    static int maxSummaryChars() { intSetting('jexl_result_summary_chars', 4_000) }

    static int maxFiles() { intSetting('jexl_result_files', 5) }

    /** [nothing: true], [summary, files, data], or [invalid: true, message]. */
    static Map<String, Object> normalize(Object raw, ScriptProfile profile) {
        if (raw == null) return [nothing: true] as Map<String, Object>
        if (raw instanceof CharSequence || raw instanceof Number || raw instanceof Boolean) {
            String text = raw.toString()
            if (text.isEmpty()) return [nothing: true] as Map<String, Object>
            return shaped(text, [], null)
        }
        if (raw instanceof Collection) return ((Collection) raw).isEmpty() ? [nothing: true] as Map<String, Object> : shaped(null, [], raw)
        if (!(raw instanceof Map)) return invalid('The script returned something that is not text, a list or a map.')
        Map map = (Map) raw
        if (map.isEmpty()) return [nothing: true] as Map<String, Object>
        if (!map.containsKey('summary') && !map.containsKey('files') && !map.containsKey('data')) return shaped(null, [], map)

        Object summary = map.get('summary')
        if (summary != null && !(summary instanceof CharSequence || summary instanceof Number)) return invalid('summary must be text.')
        Object filesRaw = map.get('files')
        if (filesRaw != null && !(filesRaw instanceof List)) return invalid('files must be a list.')
        List files = (List) (filesRaw ?: [])
        if (files.size() > maxFiles()) return invalid("A result has at most ${maxFiles()} files; this one has ${files.size()}.".toString())
        List<Map<String, Object>> checkedFiles = []
        for (Object item : files) {
            Object checked = checkFile(item, profile)
            if (checked instanceof String) return invalid((String) checked)
            checkedFiles.add((Map<String, Object>) checked)
        }
        String text = summary?.toString()
        if (!text && checkedFiles.isEmpty() && map.get('data') == null) return [nothing: true] as Map<String, Object>
        return shaped(text ?: null, checkedFiles, map.get('data'))
    }

    private static Map<String, Object> shaped(String summary, List files, Object data) {
        if (summary != null && summary.length() > maxSummaryChars()) {
            return invalid("The summary is ${summary.length()} characters; the limit is ${maxSummaryChars()}.".toString())
        }
        return [summary: summary, files: files, data: data] as Map<String, Object>
    }

    /** The checked file, or a message saying what is wrong. */
    private static Object checkFile(Object item, ScriptProfile profile) {
        if (!(item instanceof Map)) return 'Each file must be a map of name, columns and rows.'
        Map file = (Map) item
        String name = file.get('name')?.toString()?.trim()
        if (!name || !FILE_NAME.matcher(name).matches() || name.startsWith('.')) {
            return "A file name must be 1-100 letters, digits, spaces, dots, dashes or underscores: '${name}'.".toString()
        }
        if (!name.toLowerCase(Locale.ROOT).endsWith('.csv')) name = name + '.csv'
        Object columnsRaw = file.get('columns')
        if (!(columnsRaw instanceof List) || ((List) columnsRaw).isEmpty()) return "${name}: columns must be a non-empty list.".toString()
        List columns = ((List) columnsRaw).collect { Object c -> c?.toString() ?: '' }
        Object rowsRaw = file.get('rows')
        if (rowsRaw != null && !(rowsRaw instanceof List)) return "${name}: rows must be a list.".toString()
        List rows = (List) (rowsRaw ?: [])
        if (rows.size() > profile.maxCollectionSize) {
            return "${name} has ${rows.size()} rows; the limit is ${profile.maxCollectionSize}.".toString()
        }
        int rowNumber = 0
        for (Object row : rows) {
            rowNumber++
            if (!(row instanceof List) || ((List) row).size() != columns.size()) {
                return "${name}: row ${rowNumber} must have ${columns.size()} values, one per column.".toString()
            }
            for (Object cell : (List) row) {
                if (cell != null && cell.toString().length() > MAX_CELL_CHARS) {
                    return "${name}: row ${rowNumber} has a value longer than ${MAX_CELL_CHARS} characters.".toString()
                }
            }
        }
        return [name: name, columns: columns, rows: rows] as Map<String, Object>
    }

    private static Map<String, Object> invalid(String message) { [invalid: true, message: message] as Map<String, Object> }

    /** RFC 4180 CSV in UTF-8 with a byte order mark; a cell whose first visible character is = + - @ (or a fullwidth one) gets a leading ' so it is never a formula. */
    static byte[] toCsv(Map file) {
        StringBuilder out = new StringBuilder()
        appendRow(out, (List) file.get('columns'))
        for (Object row : (List) (file.get('rows') ?: [])) appendRow(out, (List) row)
        byte[] body = out.toString().getBytes(StandardCharsets.UTF_8)
        byte[] all = new byte[BOM.length + body.length]
        System.arraycopy(BOM, 0, all, 0, BOM.length)
        System.arraycopy(body, 0, all, BOM.length, body.length)
        return all
    }

    private static void appendRow(StringBuilder out, List cells) {
        boolean first = true
        for (Object cell : cells) {
            if (!first) out.append(',')
            first = false
            out.append(csvCell(cell))
        }
        out.append('\r\n')
    }

    private static String csvCell(Object cell) {
        if (cell == null) return ''
        String text = cell.toString()
        // NOTE: a spreadsheet skips leading whitespace and control characters, and reads fullwidth signs too
        String lead = text.replaceFirst('^[\\s\\u0000-\\u001F]+', '')
        if (!lead.isEmpty() && FORMULA_LEADS.indexOf((int) lead.charAt(0)) >= 0) text = "'" + text
        if (text.contains(',') || text.contains('"') || text.contains('\n') || text.contains('\r')) {
            return '"' + text.replace('"', '""') + '"'
        }
        return text
    }

    private static int intSetting(String name, int defaultValue) {
        String raw = SystemBinding.getPropOrEnv(name)
        try { return raw ? Integer.parseInt(raw.trim()) : defaultValue } catch (NumberFormatException ignored) { return defaultValue }
    }
}

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
import org.apache.commons.jexl3.JexlArithmetic
import org.apache.commons.jexl3.JexlEngine

import java.lang.reflect.Array
import java.math.MathContext

/**
 * The arithmetic every user script runs through, and the meter: every operator, loop step and field read spends a
 * ScriptBudget unit, a script reads only map keys and list items, and text, lists, maps and numbers are capped.
 *
 * NOTE: without the read overloads input.class returned the java.lang.Class of the input map
 * NOTE: =~ on text is a Java regex, whose catastrophic backtracking cannot be interrupted, so it is refused
 */
@CompileStatic
class ScriptArithmetic extends JexlArithmetic {
    static final int MAX_NUMBER_BITS = 256

    ScriptArithmetic() { super(true) }

    ScriptArithmetic(boolean strict, MathContext context, int scale) { super(strict, context, scale) }

    /** JEXL builds a fresh arithmetic when per-call options differ; it must stay this class */
    @Override
    protected JexlArithmetic createWithOptions(boolean strict, MathContext context, int scale) { new ScriptArithmetic(strict, context, scale) }

    /** A list the script built itself; only these grow in place under +=, so an input list never changes. */
    static class ScriptList extends ArrayList<Object> { }

    // -- operators, a unit each

    /** list + x joins or appends; text is length-checked before it is built; a map never combines */
    @Override
    Object add(Object left, Object right) {
        ScriptBudget.charge()
        // NOTE: without this JEXL joins lists as text, and list + list returns "[I@6eeb90e5[I@..."
        if (isListLike(left)) return appendAll(appendAll(new ScriptList(), left), right)
        if (isListLike(right)) throw new LimitReached('ACCESS', 'Put the list first: list + item, or list + list.')
        if (left instanceof Map || right instanceof Map) throw new LimitReached('ACCESS', '+ does not combine a map; set its keys one at a time: m[key] = value.')
        if (left instanceof CharSequence || right instanceof CharSequence) {
            // a list or map's toString would build the whole thing first, outside every cap
            if (!ScriptBudget.isScalar(left) || !ScriptBudget.isScalar(right)) throw new LimitReached('ACCESS', 'Text can only be joined with text or numbers; use fn:join for a list.')
            ScriptBudget.checkLength(scalarLength(left) + scalarLength(right))
        }
        // NOTE: text + anything joins text, so an id like '4E1200' is never read as a number here
        if (!(left instanceof CharSequence || right instanceof CharSequence)) checkOperands(left, right)
        return guard(super.add(left, right))
    }

    /** SELF_ADD: list += x appends in place, so appending in a loop does not copy the list each time */
    Object selfAdd(Object left, Object right) {
        // NOTE: JEXL ignores this result; anything but a ScriptList falls back to l = l + r, which assigns a new ScriptList
        if (!(left instanceof ScriptList)) return JexlEngine.TRY_FAILED
        return appendAll((ScriptList) left, right)
    }

    @Override
    Object multiply(Object left, Object right) {
        ScriptBudget.charge()
        try {
            checkOperands(left, right)
            return guard(super.multiply(left, right))
        } catch (ArithmeticException e) {
            // NOTE: JEXL 3.7 has its own BigInteger precision cap; report it as the same limit
            if (!(e instanceof LimitReached) && e.message?.contains('precision')) throw new LimitReached('NUMBER', 'A number grew too large.')
            throw e
        }
    }

    @Override Object subtract(Object left, Object right) { ScriptBudget.charge(); checkOperands(left, right); guard(super.subtract(left, right)) }
    @Override Object divide(Object left, Object right) { ScriptBudget.charge(); checkOperands(left, right); guard(super.divide(left, right)) }
    @Override Object mod(Object left, Object right) { ScriptBudget.charge(); checkOperands(left, right); guard(super.mod(left, right)) }
    /** BigInteger.shiftLeft builds the whole result before guard sees it, so a shift past the number cap is refused first */
    @Override
    Object shiftLeft(Object left, Object right) {
        ScriptBudget.charge()
        if (right == null) return guard(super.shiftLeft(left, right))
        checkOperands(left, null)
        return guard(super.shiftLeft(left, shiftAmount(right)))
    }

    /**
     * The shift as an int within +/-MAX_NUMBER_BITS, bounded by its type before any conversion: JEXL's toInteger keeps
     * a big number's low 32 bits (2^32 + n shifts by n), and converting 1E999999999 (or 1E-999999999) builds a billion digits.
     */
    static int shiftAmount(Object right) {
        BigInteger by
        if (right instanceof Integer || right instanceof Long || right instanceof Short || right instanceof Byte) {
            by = BigInteger.valueOf(((Number) right).longValue())
        } else if (right instanceof BigInteger) {
            by = ((BigInteger) right).bitLength() > 31 ? null : (BigInteger) right
        } else if (right instanceof BigDecimal) {
            // NOTE: toBigInteger divides by 10^scale, so 1E-999999999 would build a billion-digit power of ten
            BigDecimal number = (BigDecimal) right
            int integerDigits = number.precision() - number.scale()
            by = integerDigits <= 0 ? BigInteger.ZERO : (integerDigits > 10 || number.scale() > 64 ? null : number.toBigInteger())
        } else if (right instanceof Double || right instanceof Float) {
            double number = ((Number) right).doubleValue()
            by = Double.isFinite(number) && Math.abs(number) <= MAX_NUMBER_BITS ? BigInteger.valueOf((long) number) : null
        } else if (right instanceof CharSequence && right.toString().trim().length() <= 10 && right.toString().trim().isInteger()) {
            by = new BigInteger(right.toString().trim())
        } else {
            throw new LimitReached('NUMBER', 'A shift takes a whole number.')
        }
        if (by == null || by.abs() > MAX_NUMBER_BITS) throw new LimitReached('NUMBER', 'A number grew too large.')
        return by.intValue()
    }
    @Override boolean testPredicate(Object value) { ScriptBudget.charge(); super.testPredicate(value) }
    @Override boolean lessThan(Object left, Object right) { ScriptBudget.charge(); super.lessThan(left, right) }
    @Override boolean greaterThan(Object left, Object right) { ScriptBudget.charge(); super.greaterThan(left, right) }
    @Override boolean lessThanOrEqual(Object left, Object right) { ScriptBudget.charge(); super.lessThanOrEqual(left, right) }
    @Override boolean greaterThanOrEqual(Object left, Object right) { ScriptBudget.charge(); super.greaterThanOrEqual(left, right) }
    @Override Iterable<?> createRange(Object from, Object to) { ScriptBudget.charge(); super.createRange(from, to) }

    /** == on lists and maps walks them here, a unit per node, where the default deep equals is one uncharged call */
    @Override
    boolean equals(Object left, Object right) {
        ScriptBudget.charge()
        if (!(left instanceof Map || left instanceof Collection || right instanceof Map || right instanceof Collection)) return super.equals(left, right)
        if (left.is(right)) return true
        if (left instanceof Map && right instanceof Map) {
            Map leftMap = (Map) left, rightMap = (Map) right
            if (leftMap.size() != rightMap.size()) return false
            for (Map.Entry entry : (Set<Map.Entry>) leftMap.entrySet()) {
                if (!rightMap.containsKey(entry.key) || !equals(entry.value, rightMap.get(entry.key))) return false
            }
            return true
        }
        if (left instanceof Collection && right instanceof Collection) {
            Iterator leftItems = ((Collection) left).iterator(), rightItems = ((Collection) right).iterator()
            while (leftItems.hasNext() && rightItems.hasNext()) {
                if (!equals(leftItems.next(), rightItems.next())) return false
            }
            return !leftItems.hasNext() && !rightItems.hasNext()
        }
        return false
    }

    @Override
    Boolean contains(Object container, Object value) {
        ScriptBudget.charge()
        if (container instanceof CharSequence || container instanceof java.util.regex.Pattern) {
            throw new LimitReached('ACCESS', 'Pattern matching (=~ on text) is not available; use fn:has(text, part).')
        }
        if (container instanceof Collection) ScriptBudget.charge(((Collection) container).size())
        return super.contains(container, value)
    }

    // -- loops and field access

    /** FOR_EACH: the loop's list, or a map's values, a unit per step */
    Iterator<?> forEach(Object collection) {
        ScriptBudget.charge()
        if (collection == null) return Collections.emptyIterator()
        if (!(collection instanceof Map || collection instanceof Iterable || isArray(collection))) throw new LimitReached('ACCESS', 'A loop needs a list or a map.')
        Iterator<?> source = collection instanceof Map ? ((Map) collection).values().iterator()
                : (collection instanceof Iterable ? ((Iterable) collection).iterator() : arrayItems(collection).iterator())
        return new Iterator<Object>() {
            boolean hasNext() { source.hasNext() }
            Object next() { ScriptBudget.charge(); source.next() }
        }
    }

    /** PROPERTY_GET: x.key reads a map key or a list item, nothing else */
    Object propertyGet(Object target, Object key) {
        ScriptBudget.charge()
        if (target instanceof Map) {
            Map map = (Map) target
            Object value = map.get(key)
            if (value == null && key instanceof Number) value = map.get(key.toString())
            // an absent field is legal (?? defaults), but remembered so an empty-value error can name it
            if (value == null && !map.containsKey(key) && !(key instanceof Number && map.containsKey(key.toString()))) ScriptBudget.noteMissing(key, map)
            return value
        }
        if (target instanceof List) return ((List) target).get(indexIn(key, ((List) target).size()))
        if (isArray(target)) return Array.get(target, indexIn(key, Array.getLength(target)))
        throw new LimitReached('ACCESS', "'${key}' can only be read from a map or a list.".toString())
    }

    /** ARRAY_GET: x[key], same rule */
    Object arrayGet(Object target, Object key) { propertyGet(target, key) }

    /** PROPERTY_SET: m.key = v on a map, or a list item already there */
    Object propertySet(Object target, Object key, Object value) {
        ScriptBudget.charge()
        if (target instanceof Map) {
            Map map = (Map) target
            if (!map.containsKey(key)) ScriptBudget.checkSize(map.size() + 1)
            map.put(key, value)
        } else if (target instanceof List) {
            ((List) target).set(indexIn(key, ((List) target).size()), value)
        } else if (isArray(target)) {
            Array.set(target, indexIn(key, Array.getLength(target)), value)
        } else {
            throw new LimitReached('ACCESS', 'Only a map or a list can be changed.')
        }
        return value
    }

    /** ARRAY_SET: x[key] = v, same rule */
    Object arraySet(Object target, Object key, Object value) { propertySet(target, key, value) }

    // -- helpers

    /**
     * Refuses an operand too big to compute with, before the operator builds anything: a literal (1E999999999B) never
     * passes the input or fn:dec caps, and BigDecimal.add of it builds a billion digits that no time limit can interrupt.
     */
    static void checkOperands(Object left, Object right) {
        checkNumber(left)
        checkNumber(right)
    }

    /**
     * Refuses a value too big to compute with, without building it: a decimal past MAX_DECIMAL_CHARS, an integer past
     * MAX_NUMBER_BITS, or text that reads as one ('1E999999999' - 1 turns the text into a compact BigDecimal first).
     */
    static void checkNumber(Object value) {
        if (value instanceof BigDecimal) {
            ScriptBudget.checkDecimal((BigDecimal) value)
        } else if (value instanceof BigInteger) {
            if (((BigInteger) value).bitLength() > MAX_NUMBER_BITS) throw new LimitReached('NUMBER', 'A number grew too large.')
        } else if (value instanceof CharSequence) {
            // NOTE: text of any length is parsed: zero padding ('0' * 60 + '1E999999999') makes a huge exponent long text,
            // and parsing returns a compact value (precision 1, scale -999999999), its cost bounded by the text cap;
            // text that is not a number fails at its first stray character
            BigDecimal parsed = null
            try { parsed = new BigDecimal(value.toString().trim()) } catch (NumberFormatException ignored) { }
            if (parsed != null) ScriptBudget.checkDecimal(parsed)
        }
    }

    /** every conversion JEXL makes to a decimal is checked before it is used */
    @Override
    BigDecimal toBigDecimal(Object value) {
        checkNumber(value)
        return ScriptBudget.checkDecimal(super.toBigDecimal(value))
    }

    /** every conversion JEXL makes to an integer is checked first: 1E999999999 as a BigInteger is a billion digits */
    @Override
    BigInteger toBigInteger(Object value) {
        checkNumber(value)
        return super.toBigInteger(value)
    }

    static List arrayItems(Object array) {
        int length = Array.getLength(array)
        List items = new ArrayList(length)
        for (int i = 0; i < length; i++) items.add(Array.get(array, i))
        return items
    }

    /** Caps what one operator may produce: text length, and numbers past 256 bits (x = x * x doubles each pass). */
    static Object guard(Object value) {
        if (value instanceof String) return ScriptBudget.checkString((String) value)
        BigInteger digits = value instanceof BigInteger ? (BigInteger) value : (value instanceof BigDecimal ? ((BigDecimal) value).unscaledValue() : null)
        if (digits != null && digits.bitLength() > MAX_NUMBER_BITS) throw new LimitReached('NUMBER', 'A number grew too large.')
        if (value instanceof BigDecimal) ScriptBudget.checkDecimal((BigDecimal) value)
        return value
    }

    private static boolean isArray(Object value) { value != null && value.getClass().isArray() }

    private static boolean isListLike(Object value) { value instanceof Collection || isArray(value) }

    private static Iterable listItems(Object value) { value instanceof Collection ? (Collection) value : arrayItems(value) }

    /** Appends a list's items, or one item, at a unit and a size check each. */
    private static List appendAll(List list, Object value) {
        for (Object item : (isListLike(value) ? listItems(value) : [value])) {
            ScriptBudget.charge()
            list.add(item)
            ScriptBudget.checkSize(list.size())
        }
        return list
    }

    /** a list position from a number or numeric text, inside 0..size-1 */
    private static int indexIn(Object key, int size) {
        Integer index = key instanceof Number ? ((Number) key).intValue() : (key?.toString()?.isInteger() ? key.toString().toInteger() : null)
        if (index == null) throw new LimitReached('ACCESS', "A list has no field '${key}'; use a number, like items[0].".toString())
        if (index < 0 || index >= size) throw new LimitReached('ACCESS', "There is no item ${index} in a list of ${size}.".toString())
        return index
    }

    private static long scalarLength(Object value) {
        if (value == null) return 4L
        if (value instanceof CharSequence) return ((CharSequence) value).length()
        if (value instanceof BigDecimal) return ScriptBudget.plainLength((BigDecimal) value)
        if (value instanceof BigInteger) return ((BigInteger) value).bitLength().intdiv(3) + 2L
        return 40L
    }
}

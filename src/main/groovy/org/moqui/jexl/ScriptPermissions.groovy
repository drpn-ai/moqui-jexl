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
import org.apache.commons.jexl3.introspection.JexlPermissions

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Everything JEXL's introspection may reach on behalf of a user script:
 * <ul>
 *   <li>the public static functions of {@link ScriptLibrary} (the {@code fn:} namespace);</li>
 *   <li>the operator overloads declared on {@link ScriptArithmetic}. JEXL finds them through the same introspection,
 *   and when they are denied it falls back to its defaults without a word: the meter and the map-only property rule
 *   switch off (tried: a 100M-step empty loop then ran to the end). A script holds no reference to the arithmetic,
 *   so this opens nothing to it.</li>
 * </ul>
 * No constructors, no fields, no other class.
 *
 * <p>Written out instead of {@code JexlPermissions.NONE.compose("org.moqui.jexl.ScriptLibrary")}: in JEXL 3.7
 * composing NONE with a class name answers allow(java.lang.Runtime) == true. Both classes are Groovy, so they carry
 * GroovyObject's methods (invokeMethod, setMetaClass...); those are shut out by name.</p>
 */
@CompileStatic
class ScriptPermissions implements JexlPermissions {

    static final ScriptPermissions INSTANCE = new ScriptPermissions()

    private static final Set<String> GROOVY_OBJECT_METHODS =
            ['getMetaClass', 'setMetaClass', 'invokeMethod', 'getProperty', 'setProperty'] as Set<String>

    @Override boolean allow(Class<?> clazz) { clazz == ScriptLibrary || clazz == ScriptArithmetic }

    @Override boolean allow(Package pack) { pack != null && pack.name == ScriptLibrary.package.name }

    @Override boolean allow(Constructor<?> constructor) { false }

    @Override boolean allow(Field field) { false }

    @Override
    boolean allow(Method method) {
        if (method == null || method.synthetic || method.name.contains('$') || GROOVY_OBJECT_METHODS.contains(method.name)) return false
        int mod = method.modifiers
        if (!Modifier.isPublic(mod)) return false
        if (method.declaringClass == ScriptLibrary) return Modifier.isStatic(mod)
        if (method.declaringClass == ScriptArithmetic) return !Modifier.isStatic(mod)
        return false
    }

    /** Nothing may widen this set at run time. */
    @Override JexlPermissions compose(String... src) { this }
}

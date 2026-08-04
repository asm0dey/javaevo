package evo;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/**
 * A reflection mapper that serializes user objects (records, POJOs, enums)
 * on top of the {@link Evo} codec.
 *
 * <h2>Paradigm</h2>
 * {@code EvoMap} has <b>no wire format of its own</b>. It converts an object
 * into the codec's primitive value tree — a {@code Map<String,Object>} keyed by
 * field name, with nested objects, lists, and maps converted recursively — and
 * hands that to {@link Evo#write}. Reading reverses the process, guided by the
 * declared target type. So a {@code record Person(int age, String name)} is on
 * the wire exactly as the map {@code {"age":…, "name":…}}.
 *
 * <p>Keying by <b>field name</b> (not position) is what gives free schema
 * evolution: adding a field appends a map entry that old readers ignore;
 * dropping a field omits an entry that readers default. No wire-format change.
 *
 * <h2>What it maps</h2>
 * Leaves (primitives, wrappers, {@code String}, {@code byte[]}) pass straight
 * through. Enums are stored by {@code name()} and restored via
 * {@code Enum.valueOf}. Records use their canonical constructor; POJOs need a
 * no-arg constructor and non-final fields. Nested element/value types are
 * discovered from declared generics, so <b>no class names appear on the
 * wire</b> and no {@code Class.forName} is used.
 *
 * <h2>Limits (by design)</h2>
 * No polymorphism (a field decodes as its declared type), no cyclic object
 * graphs, declared fields only (no inherited POJO fields), and raw/wildcard
 * generics decode as plain codec values. See {@code docs/adding-types.md}.
 *
 * <h2>Usage</h2>
 * <pre>
 *   EvoMap.writeObject(out, person);
 *   Person p = EvoMap.readObject(in, Person.class);
 * </pre>
 * All methods are static; the class is not instantiable.
 */
public final class EvoMap {
    private EvoMap() {}

    /**
     * Serialize {@code obj} by converting it to the codec value tree
     * ({@link #toValue}) and writing that with {@link Evo#write}.
     */
    public static void writeObject(OutputStream out, Object obj) throws IOException {
        Evo.write(out, toValue(obj));
    }

    /**
     * Read one value with {@link Evo#read} and reconstruct an instance of
     * {@code type} from it ({@link #fromValue}). The target type drives nested
     * type resolution, so no type information is needed on the wire.
     *
     * @param type the class to reconstruct (record, POJO, enum, or a leaf type)
     * @return the reconstructed object, cast to {@code T} (may be {@code null})
     */
    public static <T> T readObject(InputStream in, Class<T> type) throws IOException {
        Object v = Evo.read(in);
        return type.cast(fromValue(v, type));
    }

    /**
     * Convert an arbitrary object into the codec's value tree (primitives,
     * {@code String}, {@code byte[]}, {@code List}, {@code Map}, or {@code null}).
     * Leaves pass through; enums become their name; lists/maps and record/POJO
     * fields recurse. Records and POJOs become a name-keyed {@code LinkedHashMap}.
     */
    static Object toValue(Object o) {
        if (o == null) return null;
        Class<?> c = o.getClass();
        if (isLeaf(c)) return o;
        if (o instanceof Enum<?> e) return e.name();
        if (o instanceof List<?> l) {
            var out = new ArrayList<>(l.size());
            for (Object e : l) out.add(toValue(e));
            return out;
        }
        if (o instanceof Map<?, ?> mp) {
            var out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : mp.entrySet()) out.put(toValue(e.getKey()), toValue(e.getValue()));
            return out;
        }
        if (c.isRecord()) {
            var out = new LinkedHashMap<String, Object>();
            for (RecordComponent rc : c.getRecordComponents())
                out.put(rc.getName(), toValue(invoke(rc.getAccessor(), o)));
            return out;
        }
        // POJO: declared, non-static, non-transient fields
        var out = new LinkedHashMap<String, Object>();
        for (Field f : c.getDeclaredFields()) {
            int m = f.getModifiers();
            if (Modifier.isStatic(m) || Modifier.isTransient(m)) continue;
            f.setAccessible(true);
            out.put(f.getName(), toValue(get(f, o)));
        }
        return out;
    }

    /**
     * Reconstruct an object of declared type {@code t} from a codec value tree.
     * Handles, in order: null; raw/wildcard generics (returned as-is); leaves;
     * enums; {@code List}/{@code Map} (recursing per declared element/value
     * type); and finally records (via canonical constructor) or POJOs (via
     * no-arg constructor + field injection).
     *
     * @param v the codec value (from {@link Evo#read})
     * @param t the declared target type, carrying generics for element resolution
     */
    static Object fromValue(Object v, Type t) {
        if (v == null) return null;
        Class<?> raw = rawClass(t);
        if (raw == Object.class) return v;               // raw/wildcard generics: leave as codec value
        if (isLeaf(raw)) return v;
        if (raw.isEnum()) {
            try {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object e = Enum.valueOf((Class) raw, (String) v);
                return e;
            } catch (RuntimeException ex) {
                throw new IllegalStateException("cannot decode enum " + raw + " from " + v, ex);
            }
        }
        if (List.class.isAssignableFrom(raw)) {
            Type et = argOf(t, 0);
            var out = new ArrayList<>();
            for (Object e : (List<?>) v) out.add(fromValue(e, et));
            return out;
        }
        if (Map.class.isAssignableFrom(raw)) {
            Type kt = argOf(t, 0), vt = argOf(t, 1);
            var out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet())
                out.put(fromValue(e.getKey(), kt), fromValue(e.getValue(), vt));
            return out;
        }
        Map<?, ?> m = (Map<?, ?>) v;                     // record/POJO wire shape
        return raw.isRecord() ? buildRecord(raw, m) : buildPojo(raw, m);
    }

    /**
     * Build a record from a name-keyed map using its canonical constructor.
     * Each component is resolved from the map by name; a missing component gets
     * {@link #defaultFor} its declared type (primitive zero/false, else null),
     * which is how a record reads back from data that predates one of its fields.
     */
    private static Object buildRecord(Class<?> raw, Map<?, ?> m) {
        RecordComponent[] comps = raw.getRecordComponents();
        Class<?>[] types = new Class<?>[comps.length];
        Object[] args = new Object[comps.length];
        for (int i = 0; i < comps.length; i++) {
            types[i] = comps[i].getType();
            Object cv = m.get(comps[i].getName());
            Object fv = cv == null ? null : fromValue(cv, comps[i].getGenericType());
            args[i] = fv != null ? fv : defaultFor(types[i]);
        }
        try {
            Constructor<?> ctor = raw.getDeclaredConstructor(types);
            ctor.setAccessible(true);
            return ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot build record " + raw, e);
        }
    }

    /**
     * Build a POJO via its no-arg constructor, then inject declared non-static,
     * non-transient fields present in the map. Fields absent from the map keep
     * their constructor default. Requires a no-arg constructor and non-final
     * fields; otherwise throws {@link IllegalStateException}.
     */
    private static Object buildPojo(Class<?> raw, Map<?, ?> m) {
        try {
            Constructor<?> ctor = raw.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object inst = ctor.newInstance();
            for (Field f : raw.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isTransient(mod)) continue;
                Object cv = m.get(f.getName());
                if (cv == null) continue;               // leave field default
                f.setAccessible(true);
                f.set(inst, fromValue(cv, f.getGenericType()));
            }
            return inst;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot build POJO " + raw + " (needs no-arg constructor)", e);
        }
    }

    /**
     * True if {@code c} is a type the codec handles directly (a primitive, a
     * primitive wrapper, {@code String}, or {@code byte[]}). Such values pass
     * straight through instead of being reflected as a record/POJO. Extend this
     * when adding a new codec type that can appear as an object field — see
     * {@code docs/adding-types.md}.
     */
    static boolean isLeaf(Class<?> c) {
        return c.isPrimitive()
            || c == Boolean.class || c == Character.class || c == Byte.class || c == Short.class
            || c == Integer.class || c == Long.class || c == Float.class || c == Double.class
            || c == String.class || c == byte[].class;
    }

    /**
     * The erased {@link Class} of a reflective {@link Type}: the type itself if
     * it is a {@code Class}, the raw type of a {@code ParameterizedType}, else
     * {@code Object.class} (for wildcards, type variables, etc.).
     */
    static Class<?> rawClass(Type t) {
        if (t instanceof Class<?> c) return c;
        if (t instanceof ParameterizedType p) return (Class<?>) p.getRawType();
        return Object.class;
    }

    /**
     * The {@code i}-th generic type argument of {@code t} (e.g. the element type
     * of a {@code List<E>} or key/value type of a {@code Map<K,V>}), or
     * {@code Object.class} when {@code t} is raw or the argument is a wildcard —
     * in which case nested elements decode as plain codec values.
     */
    static Type argOf(Type t, int i) {
        if (t instanceof ParameterizedType p) {
            Type[] a = p.getActualTypeArguments();
            if (i < a.length && a[i] instanceof Class || i < a.length && a[i] instanceof ParameterizedType)
                return a[i];
        }
        return Object.class;
    }

    /**
     * The default value for a missing field of type {@code t}: the zero value
     * for a primitive ({@code 0}/{@code false}/{@code '\0'}), else {@code null}.
     * Lets a record's canonical constructor accept data that omits a field.
     */
    static Object defaultFor(Class<?> t) {
        if (!t.isPrimitive()) return null;
        if (t == boolean.class) return false;
        if (t == char.class)    return '\0';
        if (t == byte.class)    return (byte) 0;
        if (t == short.class)   return (short) 0;
        if (t == int.class)     return 0;
        if (t == long.class)    return 0L;
        if (t == float.class)   return 0f;
        return 0d; // double
    }

    /** Invoke a record accessor, wrapping reflective failure as {@link IllegalStateException}. */
    private static Object invoke(Method mth, Object o) {
        try { return mth.invoke(o); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    /** Read a field's value, wrapping reflective failure as {@link IllegalStateException}. */
    private static Object get(Field f, Object o) {
        try { return f.get(o); }
        catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }
}

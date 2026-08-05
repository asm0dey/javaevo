package evo;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
 * graphs, and raw/wildcard generics decode as plain codec values. POJO fields
 * are collected by walking the superclass chain (subclass-wins on shadowing;
 * see ADR-0007). See {@code docs/adding-types.md}.
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

    // Reflective metadata is cached per class: getRecordComponents() and
    // getDeclaredConstructor() allocate/scan on every call, so caching them is a
    // large speedup (~8x serialize / ~3x deserialize in the JMH suite) with no
    // wire or API change.
    private static final Map<Class<?>, RecordComponent[]> RECORD_COMPONENTS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Field[]> POJO_FIELDS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Constructor<?>> CONSTRUCTORS = new ConcurrentHashMap<>();
    // Cached "has a no-arg constructor" predicate for the POJO write guard: the
    // readability precondition (ADR-0008) is checked once per class, not per
    // write, keeping the serialize hot path off an uncached reflective lookup.
    private static final Map<Class<?>, Boolean> POJO_WRITABLE = new ConcurrentHashMap<>();

    /**
     * Serialize {@code obj} by converting it to the codec value tree
     * ({@link #toValue}) and writing that with {@link Evo#write}.
     *
     * <p><b>Buffering:</b> when {@code out} targets a file or socket, wrap it in
     * a {@link BufferedOutputStream} — the codec writes a byte at a time, so an
     * unbuffered stream is many times slower. In-memory streams
     * ({@link ByteArrayOutputStream}) need no wrapping. See
     * {@link #writeToFile} for a buffered file convenience.
     */
    public static void writeObject(OutputStream out, Object obj) throws IOException {
        Evo.write(out, toValue(obj));
    }

    /**
     * Read one value with {@link Evo#read} and reconstruct an instance of
     * {@code type} from it ({@link #fromValue}). The target type drives nested
     * type resolution, so no type information is needed on the wire.
     *
     * <p><b>Buffering:</b> wrap a file/socket {@code in} in a
     * {@link BufferedInputStream}; see {@link #readFromFile}.
     *
     * @param type the class to reconstruct (record, POJO, enum, or a leaf type)
     * @return the reconstructed object, cast to {@code T} (may be {@code null})
     */
    public static <T> T readObject(InputStream in, Class<T> type) throws IOException {
        Object v = Evo.read(in);
        return type.cast(fromValue(v, type));
    }

    /**
     * Write a single object to a file, buffered. Convenience for the common
     * one-object-per-file case; multi-object streams should manage their own
     * (buffered) stream and call {@link #writeObject} in a loop.
     */
    public static void writeToFile(Path path, Object obj) throws IOException {
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(path))) {
            writeObject(out, obj);
        }
    }

    /** Read a single object of {@code type} from a file, buffered (see {@link #writeToFile}). */
    public static <T> T readFromFile(Path path, Class<T> type) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
            return readObject(in, type);
        }
    }

    /** Cached {@code getRecordComponents()} for a record class. */
    private static RecordComponent[] components(Class<?> c) {
        return RECORD_COMPONENTS.computeIfAbsent(c, Class::getRecordComponents);
    }

    /** Cached, access-enabled instance fields of a POJO class, walking the
     *  superclass chain (subclass-wins on same-name shadowing; static/transient
     *  excluded). See ADR-0007. */
    private static Field[] fields(Class<?> c) {
        return POJO_FIELDS.computeIfAbsent(c, k -> {
            var list = new ArrayList<Field>();
            var seen = new HashSet<String>();
            for (Class<?> t = k; t != null && t != Object.class; t = t.getSuperclass()) {
                for (Field f : t.getDeclaredFields()) {
                    int m = f.getModifiers();
                    if (Modifier.isStatic(m) || Modifier.isTransient(m)) continue;
                    if (!seen.add(f.getName())) continue;   // subclass already claimed this name
                    f.setAccessible(true);
                    list.add(f);
                }
            }
            return list.toArray(new Field[0]);
        });
    }

    /**
     * Convert an arbitrary object into the codec's value tree (primitives,
     * {@code String}, {@code byte[]}, {@code List}, {@code Map}, or {@code null}).
     * Leaves pass through; enums become their name; lists/maps and record/POJO
     * fields recurse. Records and POJOs become a name-keyed {@code LinkedHashMap}.
     *
     * @throws IllegalArgumentException if a POJO type is not round-trippable — it
     *     has no accessible no-arg constructor or has inaccessible fields (e.g.
     *     {@code java.util.UUID}); the message names the offending class. See ADR-0008.
     */
    static Object toValue(Object o) {
        if (o == null) return null;
        Class<?> c = o.getClass();
        if (isLeaf(c)) return o;
        if (o instanceof Enum<?> e) return e.name();
        if (c.isArray() && c != byte[].class) {          // ADR-0005: arrays map to LIST; byte[] stays BYTES
            int n = java.lang.reflect.Array.getLength(o);
            var out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) out.add(toValue(java.lang.reflect.Array.get(o, i)));
            return out;
        }
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
            for (RecordComponent rc : components(c))
                out.put(rc.getName(), toValue(invoke(rc.getAccessor(), o)));
            return out;
        }
        // POJO fallback — must be round-trippable: needs a no-arg constructor to
        // be readable and accessible fields to be writable. Otherwise fail with a
        // clean, named error instead of an opaque reflection exception or a silent
        // partial write. See ADR-0008. The no-arg check is cached (POJO_WRITABLE)
        // so it runs once per class, not per write.
        if (!POJO_WRITABLE.computeIfAbsent(c, EvoMap::hasNoArgCtor))
            throw new IllegalArgumentException("cannot map " + c.getName()
                + "; use a record/List/Map or give it a no-arg constructor with accessible fields");
        try {
            var out = new LinkedHashMap<String, Object>();
            for (Field f : fields(c)) out.put(f.getName(), toValue(get(f, o)));  // fields() setAccessible may throw
            return out;
        } catch (InaccessibleObjectException e) {        // inaccessible field — same clean, named error
            throw new IllegalArgumentException("cannot map " + c.getName()
                + "; use a record/List/Map or give it a no-arg constructor with accessible fields", e);
        }
    }

    /** True if {@code c} has a no-arg constructor (the POJO readability precondition, ADR-0008). */
    private static boolean hasNoArgCtor(Class<?> c) {
        try { c.getDeclaredConstructor(); return true; }
        catch (NoSuchMethodException e) { return false; }
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
        if (raw.isArray() && raw != byte[].class) {      // ADR-0005/0009: rebuild array from the wire LIST
            Type compType = (t instanceof GenericArrayType g)
                ? g.getGenericComponentType()            // List<Person> — keeps generics
                : raw.getComponentType();                // int, String, Person — a Class
            List<?> list = (List<?>) v;
            Object arr = Array.newInstance(rawClass(compType), list.size());
            for (int i = 0; i < list.size(); i++)
                Array.set(arr, i, fromValue(list.get(i), compType));  // pass the generic type, not the erased class
            return arr;
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
        RecordComponent[] comps = components(raw);
        Object[] args = new Object[comps.length];
        for (int i = 0; i < comps.length; i++) {
            Object cv = m.get(comps[i].getName());
            Object fv = cv == null ? null : fromValue(cv, comps[i].getGenericType());
            args[i] = fv != null ? fv : defaultFor(comps[i].getType());
        }
        try {
            return canonicalCtor(raw, comps).newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot build record " + raw, e);
        }
    }

    /** Cached canonical constructor of a record (parameter types = component types). */
    private static Constructor<?> canonicalCtor(Class<?> raw, RecordComponent[] comps) {
        return CONSTRUCTORS.computeIfAbsent(raw, k -> {
            var types = new Class<?>[comps.length];
            for (int i = 0; i < comps.length; i++) types[i] = comps[i].getType();
            try {
                Constructor<?> ctor = k.getDeclaredConstructor(types);
                ctor.setAccessible(true);
                return ctor;
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("no canonical constructor for record " + k, e);
            }
        });
    }

    /**
     * Build a POJO via its no-arg constructor, then inject declared non-static,
     * non-transient fields present in the map. Fields absent from the map keep
     * their constructor default. Requires a no-arg constructor and non-final
     * fields; otherwise throws {@link IllegalStateException}.
     */
    private static Object buildPojo(Class<?> raw, Map<?, ?> m) {
        Constructor<?> ctor = CONSTRUCTORS.computeIfAbsent(raw, k -> {
            try {
                Constructor<?> c = k.getDeclaredConstructor();
                c.setAccessible(true);
                return c;
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("cannot build POJO " + k + " (needs no-arg constructor)", e);
            }
        });
        try {
            Object inst = ctor.newInstance();
            for (Field f : fields(raw)) {
                Object cv = m.get(f.getName());
                if (cv == null) continue;               // leave field default
                f.set(inst, fromValue(cv, f.getGenericType()));
            }
            return inst;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot build POJO " + raw, e);
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
     * it is a {@code Class}, the raw type of a {@code ParameterizedType}, the
     * erased array class for a {@code GenericArrayType}, else {@code Object.class}
     * (for wildcards, type variables, etc.).
     */
    static Class<?> rawClass(Type t) {
        if (t instanceof Class<?> c) return c;
        if (t instanceof ParameterizedType p) return (Class<?>) p.getRawType();
        if (t instanceof GenericArrayType g)         // e.g. List<Person>[] -> List[].class
            return Array.newInstance(rawClass(g.getGenericComponentType()), 0).getClass();
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

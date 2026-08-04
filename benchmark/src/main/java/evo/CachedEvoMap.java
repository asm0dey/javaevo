package evo;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Benchmark variant of {@link EvoMap} that caches reflective metadata
 * (record components, canonical constructors, POJO fields) per class in
 * concurrent maps. Same wire format and same value-tree/boxing approach as
 * {@code EvoMap} — the only difference is that the reflective lookups happen
 * once per class instead of once per call. Isolates the cost of those lookups.
 *
 * <p>Lives in package {@code evo} so it can reuse {@code EvoMap}'s
 * package-private helpers and {@code Evo}'s codec.
 */
public final class CachedEvoMap {
    private CachedEvoMap() {}

    private static final Map<Class<?>, RecordComponent[]> RC = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Field[]> FIELDS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Constructor<?>> CTOR = new ConcurrentHashMap<>();

    public static void writeObject(OutputStream out, Object obj) throws IOException {
        Evo.write(out, toValue(obj));
    }

    public static <T> T readObject(InputStream in, Class<T> type) throws IOException {
        return type.cast(fromValue(Evo.read(in), type));
    }

    static Object toValue(Object o) {
        if (o == null) return null;
        Class<?> c = o.getClass();
        if (EvoMap.isLeaf(c)) return o;
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
            var comps = RC.computeIfAbsent(c, Class::getRecordComponents);
            var out = new LinkedHashMap<String, Object>();
            for (RecordComponent rc : comps) {
                try { out.put(rc.getName(), toValue(rc.getAccessor().invoke(o))); }
                catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
            }
            return out;
        }
        var fs = FIELDS.computeIfAbsent(c, CachedEvoMap::pojoFields);
        var out = new LinkedHashMap<String, Object>();
        for (Field f : fs) {
            try { out.put(f.getName(), toValue(f.get(o))); }
            catch (IllegalAccessException ex) { throw new IllegalStateException(ex); }
        }
        return out;
    }

    static Object fromValue(Object v, Type t) {
        if (v == null) return null;
        Class<?> raw = EvoMap.rawClass(t);
        if (raw == Object.class) return v;
        if (EvoMap.isLeaf(raw)) return v;
        if (raw.isEnum()) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object e = Enum.valueOf((Class) raw, (String) v);
            return e;
        }
        if (List.class.isAssignableFrom(raw)) {
            Type et = EvoMap.argOf(t, 0);
            var out = new ArrayList<>();
            for (Object e : (List<?>) v) out.add(fromValue(e, et));
            return out;
        }
        if (Map.class.isAssignableFrom(raw)) {
            Type kt = EvoMap.argOf(t, 0), vt = EvoMap.argOf(t, 1);
            var out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet())
                out.put(fromValue(e.getKey(), kt), fromValue(e.getValue(), vt));
            return out;
        }
        Map<?, ?> m = (Map<?, ?>) v;
        return raw.isRecord() ? buildRecord(raw, m) : buildPojo(raw, m);
    }

    private static Object buildRecord(Class<?> raw, Map<?, ?> m) {
        RecordComponent[] comps = RC.computeIfAbsent(raw, Class::getRecordComponents);
        Object[] args = new Object[comps.length];
        for (int i = 0; i < comps.length; i++) {
            Object cv = m.get(comps[i].getName());
            Object fv = cv == null ? null : fromValue(cv, comps[i].getGenericType());
            args[i] = fv != null ? fv : EvoMap.defaultFor(comps[i].getType());
        }
        Constructor<?> ctor = CTOR.computeIfAbsent(raw, k -> {
            var types = new Class<?>[comps.length];
            for (int i = 0; i < comps.length; i++) types[i] = comps[i].getType();
            try { var c = k.getDeclaredConstructor(types); c.setAccessible(true); return c; }
            catch (NoSuchMethodException e) { throw new IllegalStateException(e); }
        });
        try { return ctor.newInstance(args); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    private static Object buildPojo(Class<?> raw, Map<?, ?> m) {
        Constructor<?> ctor = CTOR.computeIfAbsent(raw, k -> {
            try { var c = k.getDeclaredConstructor(); c.setAccessible(true); return c; }
            catch (NoSuchMethodException e) { throw new IllegalStateException(e); }
        });
        try {
            Object inst = ctor.newInstance();
            for (Field f : FIELDS.computeIfAbsent(raw, CachedEvoMap::pojoFields)) {
                Object cv = m.get(f.getName());
                if (cv == null) continue;
                f.set(inst, fromValue(cv, f.getGenericType()));
            }
            return inst;
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    private static Field[] pojoFields(Class<?> c) {
        var list = new ArrayList<Field>();
        for (Field f : c.getDeclaredFields()) {
            int m = f.getModifiers();
            if (Modifier.isStatic(m) || Modifier.isTransient(m)) continue;
            f.setAccessible(true);
            list.add(f);
        }
        return list.toArray(new Field[0]);
    }
}

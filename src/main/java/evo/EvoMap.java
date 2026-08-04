package evo;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Reflection mapper: user records/POJOs <-> codec value tree. Zero deps. */
public final class EvoMap {
    private EvoMap() {}

    public static void writeObject(OutputStream out, Object obj) throws IOException {
        Evo.write(out, toValue(obj));
    }

    public static <T> T readObject(InputStream in, Class<T> type) throws IOException {
        Object v = Evo.read(in);
        return type.cast(fromValue(v, type));
    }

    // ---- object -> codec value tree ----
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

    // ---- codec value tree -> object of declared type t ----
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

    // ---- helpers ----
    static boolean isLeaf(Class<?> c) {
        return c.isPrimitive()
            || c == Boolean.class || c == Character.class || c == Byte.class || c == Short.class
            || c == Integer.class || c == Long.class || c == Float.class || c == Double.class
            || c == String.class || c == byte[].class;
    }

    static Class<?> rawClass(Type t) {
        if (t instanceof Class<?> c) return c;
        if (t instanceof ParameterizedType p) return (Class<?>) p.getRawType();
        return Object.class;
    }

    static Type argOf(Type t, int i) {
        if (t instanceof ParameterizedType p) {
            Type[] a = p.getActualTypeArguments();
            if (i < a.length && a[i] instanceof Class || i < a.length && a[i] instanceof ParameterizedType)
                return a[i];
        }
        return Object.class;
    }

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

    private static Object invoke(Method mth, Object o) {
        try { return mth.invoke(o); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    private static Object get(Field f, Object o) {
        try { return f.get(o); }
        catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }
}

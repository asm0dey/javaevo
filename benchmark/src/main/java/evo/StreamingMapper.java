package evo;

import java.io.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prototype: a cached, compiled streaming serializer for records — the
 * optimization candidate for {@code EvoMap}. Per record class it builds a plan
 * once (cached), then streams straight to the output:
 * <ul>
 *   <li><b>no intermediate {@code Map} tree</b> (unlike {@code EvoMap.toValue})</li>
 *   <li><b>cached field-name bytes</b> — the UTF-8 of each field name is encoded
 *       once, not re-encoded per instance</li>
 *   <li><b>no boxing on write</b> — primitive accessors are called via
 *       {@link MethodHandle#invokeExact} returning the primitive directly</li>
 * </ul>
 * Emits the same bytes as {@code EvoMap} (a name-keyed map), so it is
 * wire-compatible. The reader trusts field order (positional) and reconstructs
 * via the cached canonical constructor.
 *
 * <p>Prototype scope: records whose fields are {@code long}, {@code int},
 * {@code String}, {@code byte[]}, a nested record, or {@code List<record>}.
 * Enough for the benchmark payload; other kinds throw.
 *
 * <p>Lives in package {@code evo} to use the codec's package-private internals.
 */
public final class StreamingMapper {
    private StreamingMapper() {}

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.publicLookup();
    private static final Map<Class<?>, Plan> PLANS = new ConcurrentHashMap<>();

    // ---- public API ----
    public static void writeObject(OutputStream out, Object obj) throws IOException {
        writeRecord(out, obj, planFor(obj.getClass()));
    }

    public static <T> T readObject(InputStream in, Class<T> type) throws IOException {
        return type.cast(readRecord(in, planFor(type)));
    }

    // ---- field kinds ----
    private enum Kind { LONG, INT, STRING, BYTES, RECORD, LIST_RECORD }

    private record FieldW(byte[] keyBytes, Kind kind, MethodHandle mh, Class<?> elem) {}
    private record FieldR(Kind kind, Class<?> type, Class<?> elem) {}

    private static final class Plan {
        final FieldW[] writers;
        final FieldR[] readers;
        final Constructor<?> ctor;
        Plan(FieldW[] w, FieldR[] r, Constructor<?> c) { writers = w; readers = r; ctor = c; }
    }

    // ---- plan building (cached) ----
    private static Plan planFor(Class<?> c) {
        return PLANS.computeIfAbsent(c, StreamingMapper::buildPlan);
    }

    private static Plan buildPlan(Class<?> c) {
        if (!c.isRecord()) throw new IllegalArgumentException("streaming prototype handles records only: " + c);
        RecordComponent[] comps = c.getRecordComponents();
        var writers = new FieldW[comps.length];
        var readers = new FieldR[comps.length];
        var types = new Class<?>[comps.length];
        try {
            for (int i = 0; i < comps.length; i++) {
                RecordComponent rc = comps[i];
                Class<?> t = rc.getType();
                types[i] = t;
                Kind kind = kindOf(t, rc.getGenericType());
                Class<?> elem = kind == Kind.LIST_RECORD ? listElement(rc.getGenericType()) : null;
                byte[] key = encodeString(rc.getName());
                MethodHandle mh = LOOKUP.unreflect(rc.getAccessor());
                // adapt to (Object)-> primitive-or-Object so invokeExact has a fixed descriptor
                Class<?> ret = (kind == Kind.LONG) ? long.class : (kind == Kind.INT) ? int.class : Object.class;
                mh = mh.asType(MethodType.methodType(ret, Object.class));
                writers[i] = new FieldW(key, kind, mh, elem);
                readers[i] = new FieldR(kind, t, elem);
            }
            Constructor<?> ctor = c.getDeclaredConstructor(types);
            ctor.setAccessible(true);
            return new Plan(writers, readers, ctor);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot build plan for " + c, e);
        }
    }

    private static Kind kindOf(Class<?> t, Type generic) {
        if (t == long.class) return Kind.LONG;
        if (t == int.class) return Kind.INT;
        if (t == String.class) return Kind.STRING;
        if (t == byte[].class) return Kind.BYTES;
        if (t.isRecord()) return Kind.RECORD;
        if (List.class.isAssignableFrom(t) && listElement(generic) != null) return Kind.LIST_RECORD;
        throw new IllegalArgumentException("streaming prototype: unsupported field type " + t);
    }

    private static Class<?> listElement(Type generic) {
        if (generic instanceof ParameterizedType p) {
            Type[] a = p.getActualTypeArguments();
            if (a.length == 1 && a[0] instanceof Class<?> ec && ec.isRecord()) return ec;
        }
        return null;
    }

    // ---- write ----
    private static void writeRecord(OutputStream out, Object obj, Plan plan) throws IOException {
        out.write(Evo.MAP);
        Evo.writeVarint(out, plan.writers.length);
        try {
            for (FieldW w : plan.writers) {
                out.write(w.keyBytes());                    // cached, pre-encoded field name
                switch (w.kind()) {
                    case LONG -> { long v = (long) w.mh().invokeExact(obj); out.write(Evo.LONG); Evo.writeVarint(out, Evo.zig(v)); }
                    case INT  -> { int v = (int) w.mh().invokeExact(obj);  out.write(Evo.INT);  Evo.writeVarint(out, Evo.zig(v)); }
                    case STRING -> writeString(out, (String) (Object) w.mh().invokeExact(obj));
                    case BYTES  -> writeBytes(out, (byte[]) (Object) w.mh().invokeExact(obj));
                    case RECORD -> {
                        Object v = (Object) w.mh().invokeExact(obj);
                        writeRecord(out, v, planFor(v.getClass()));
                    }
                    case LIST_RECORD -> {
                        List<?> list = (List<?>) (Object) w.mh().invokeExact(obj);
                        out.write(Evo.LIST);
                        Evo.writeVarint(out, list.size());
                        Plan ep = planFor(w.elem());
                        for (Object e : list) writeRecord(out, e, ep);
                    }
                }
            }
        } catch (IOException | RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeString(OutputStream out, String s) throws IOException {
        out.write(Evo.STRING);
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        Evo.writeVarint(out, b.length);
        out.write(b);
    }
    private static void writeBytes(OutputStream out, byte[] b) throws IOException {
        out.write(Evo.BYTES);
        Evo.writeVarint(out, b.length);
        out.write(b);
    }

    private static byte[] encodeString(String s) {
        var b = new ByteArrayOutputStream();
        try { b.write(Evo.STRING); Evo.writeVarint(b, s.getBytes(StandardCharsets.UTF_8).length); b.write(s.getBytes(StandardCharsets.UTF_8)); }
        catch (IOException e) { throw new UncheckedIOException(e); }
        return b.toByteArray();
    }

    // ---- read (streaming, positional, into constructor args) ----
    private static Object readRecord(InputStream in, Plan plan) throws IOException {
        expect(in, Evo.MAP);
        Evo.readVarint(in);                                 // field count (trusted)
        FieldR[] rs = plan.readers;
        Object[] args = new Object[rs.length];
        for (int i = 0; i < rs.length; i++) {
            skipKey(in);
            FieldR r = rs[i];
            args[i] = switch (r.kind()) {
                case LONG -> { expect(in, Evo.LONG); yield Evo.unzig(Evo.readVarint(in)); }
                case INT  -> { expect(in, Evo.INT);  yield (int) Evo.unzig(Evo.readVarint(in)); }
                case STRING -> readString(in);
                case BYTES  -> { expect(in, Evo.BYTES); yield Evo.readN(in, (int) Evo.readVarint(in)); }
                case RECORD -> readRecord(in, planFor(r.type()));
                case LIST_RECORD -> {
                    expect(in, Evo.LIST);
                    int n = (int) Evo.readVarint(in);
                    Plan ep = planFor(r.elem());
                    var list = new ArrayList<Object>(n);
                    for (int j = 0; j < n; j++) list.add(readRecord(in, ep));
                    yield list;
                }
            };
        }
        try { return plan.ctor.newInstance(args); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    private static void expect(InputStream in, int tag) throws IOException {
        int t = Evo.read1(in);
        if (t != tag) throw new IOException("expected tag " + tag + " got " + t);
    }
    private static void skipKey(InputStream in) throws IOException {
        expect(in, Evo.STRING);
        Evo.readN(in, (int) Evo.readVarint(in));
    }
    private static String readString(InputStream in) throws IOException {
        expect(in, Evo.STRING);
        return new String(Evo.readN(in, (int) Evo.readVarint(in)), StandardCharsets.UTF_8);
    }
}

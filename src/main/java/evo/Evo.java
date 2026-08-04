package evo;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * A zero-dependency, self-describing binary codec.
 *
 * <h2>Paradigm</h2>
 * Every value on the wire is one <b>tag byte</b> followed by a payload:
 * {@code [tag][payload]}. The tag is self-describing, so a stream needs no
 * external schema — {@link #read} reconstructs each value from the bytes alone,
 * returning the exact Java type that was written ({@code Short} for a
 * {@code short}, etc.).
 *
 * <p>The tag packs two fields:
 * <pre>
 *   bit:  7 6 5 | 4 3 2 1 0
 *         class |  type id
 * </pre>
 * The top 3 bits are a <b>size class</b> that tells any reader how to
 * <i>skip</i> a value even if it does not recognize the type id in the low 5
 * bits. The eight classes and their skip rules:
 * <pre>
 *   0 empty  : no payload            4 fixed8 : skip 8 bytes
 *   1 fixed1 : skip 1 byte           5 varint : read one varint
 *   2 fixed2 : skip 2 bytes          6 bytes  : read varint len, skip len
 *   3 fixed4 : skip 4 bytes          7 values : read varint count, skip values
 * </pre>
 * This is what delivers <b>schema evolution</b>: a reader that meets an unknown
 * type id derives the skip from the size class, returns an {@link Unknown}
 * placeholder, and stays in sync — so a newer writer can add types without
 * breaking older readers, even when the unknown value is nested in a List/Map.
 *
 * <h2>Encoding</h2>
 * Fixed-width fields are big-endian. Lengths/counts are unsigned LEB128
 * varints. The integer types (byte/short/int/long) are zigzag-then-varint, so
 * small magnitudes cost one byte. Strings are UTF-8. Null is a first-class tag,
 * so every position is nullable.
 *
 * <h2>Usage</h2>
 * <pre>
 *   Evo.write(out, anyValue);          // null, primitives, String, byte[], List, Map
 *   Object v = Evo.read(in);           // exact type back; loop until EOFException
 * </pre>
 * See {@code docs/format-spec.md} for byte-level layouts and
 * {@code docs/adding-types.md} for extending the type set. This class is a pure
 * codec over the primitive data model; object mapping lives in {@link EvoMap}.
 *
 * <p>All methods are static; the class is not instantiable.
 */
public final class Evo {
    private Evo() {}

    // tags: (sizeClass << 5) | typeId  — FROZEN, never renumber
    static final int NULL = 0x00, FALSE = 0x01, TRUE = 0x02;   // class 0 empty
    static final int CHAR = 0x40;                              // class 2 fixed2
    static final int FLOAT = 0x60;                            // class 3 fixed4
    static final int DOUBLE = 0x80;                           // class 4 fixed8
    static final int BYTE = 0xA0, SHORT = 0xA1, INT = 0xA2, LONG = 0xA3; // class 5 varint
    static final int STRING = 0xC0, BYTES = 0xC1;            // class 6 bytes
    static final int LIST = 0xE0, MAP = 0xE1;               // class 7 values

    /**
     * Placeholder returned by {@link #read} for a tag whose type id this reader
     * does not recognize. The value was skipped using its size class, keeping
     * the stream in sync; {@code raw} holds the payload bytes that were consumed
     * (empty for a size-class-7 value, whose nested values are discarded).
     *
     * @param tag the unrecognized tag byte (0–255)
     * @param raw the skipped payload bytes (best-effort; see above)
     */
    public record Unknown(int tag, byte[] raw) {}

    /**
     * Write {@code v} as an unsigned LEB128 varint (7 payload bits per byte, low
     * group first, high bit set while more bytes follow). Used for lengths,
     * counts, and (after zigzag) the integer types.
     *
     * @param o  destination
     * @param v  value, treated as unsigned 64-bit
     */
    static void writeVarint(OutputStream o, long v) throws IOException {
        while ((v & ~0x7FL) != 0) {
            o.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        o.write((int) (v & 0x7F));
    }

    /**
     * Read one unsigned LEB128 varint written by {@link #writeVarint}. Rejects a
     * varint longer than 10 bytes (a corrupt run of continuation bytes) with an
     * {@link IOException}.
     *
     * @return the decoded value as a (possibly unsigned) 64-bit long
     */
    static long readVarint(InputStream in) throws IOException {
        long r = 0;
        int shift = 0, b;
        do {
            if (shift >= 64) throw new IOException("varint too long");
            b = read1(in);
            r |= (long) (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return r;
    }

    /**
     * Read a varint length or count that will size an allocation, rejecting
     * negative or {@code > Integer.MAX_VALUE} values with an {@link IOException}.
     * This is the trust boundary guard: it turns a malformed/truncated stream
     * into a clean exception instead of a {@code NegativeArraySizeException} or
     * an out-of-memory allocation.
     */
    static int readLen(InputStream in) throws IOException {
        long n = readVarint(in);
        if (n < 0 || n > Integer.MAX_VALUE) throw new IOException("bad length/count: " + n);
        return (int) n;
    }

    /**
     * Zigzag-encode a signed value so small magnitudes (positive or negative)
     * map to small unsigned numbers: 0,-1,1,-2,2 → 0,1,2,3,4. Applied before
     * {@link #writeVarint} for the integer types so e.g. {@code -1} costs one
     * byte instead of ten.
     */
    static long zig(long v)   { return (v << 1) ^ (v >> 63); }

    /** Inverse of {@link #zig}: decode a zigzagged unsigned value back to signed. */
    static long unzig(long u) { return (u >>> 1) ^ -(u & 1); }

    /**
     * Read one byte, throwing {@link EOFException} at end of stream (rather than
     * returning -1). The building block for all fixed-width reads.
     */
    static int read1(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException();
        return b;
    }

    /**
     * Read exactly {@code n} bytes, looping over short reads and throwing
     * {@link EOFException} if the stream ends first.
     */
    static byte[] readN(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) throw new EOFException();
            off += r;
        }
        return b;
    }

    /** Write a 32-bit int as 4 big-endian bytes (used for the float bit pattern). */
    static void writeInt32(OutputStream o, int v) throws IOException {
        o.write(v >>> 24); o.write(v >>> 16); o.write(v >>> 8); o.write(v);
    }
    /** Read a big-endian 32-bit int written by {@link #writeInt32}. */
    static int readInt32(InputStream in) throws IOException {
        return (read1(in) << 24) | (read1(in) << 16) | (read1(in) << 8) | read1(in);
    }
    /** Write a 64-bit long as 8 big-endian bytes (used for the double bit pattern). */
    static void writeInt64(OutputStream o, long v) throws IOException {
        for (int s = 56; s >= 0; s -= 8) o.write((int) (v >>> s));
    }
    /** Read a big-endian 64-bit long written by {@link #writeInt64}. */
    static long readInt64(InputStream in) throws IOException {
        long r = 0;
        for (int i = 0; i < 8; i++) r = (r << 8) | read1(in);
        return r;
    }

    /**
     * Write one value: emit its tag byte, then its payload. Dispatches on the
     * runtime type of {@code v}. Containers (List/Map) recurse, so nested
     * structures of any depth are supported. A {@code null} is the NULL tag —
     * every position is nullable.
     *
     * @param out destination
     * @param v   one of: {@code null}, {@code Boolean}, {@code Byte},
     *            {@code Short}, {@code Character}, {@code Integer}, {@code Long},
     *            {@code Float}, {@code Double}, {@code String}, {@code byte[]},
     *            {@code List<?>}, or {@code Map<?,?>}
     * @throws IllegalArgumentException if {@code v} is none of the above
     */
    public static void write(OutputStream out, Object v) throws IOException {
        if (v == null)               { out.write(NULL); return; }
        if (v instanceof Boolean b)  { out.write(b ? TRUE : FALSE); return; }
        if (v instanceof Character c){ out.write(CHAR); out.write(c >>> 8); out.write(c & 0xFF); return; }
        if (v instanceof Byte b)     { out.write(BYTE);  writeVarint(out, zig(b)); return; }
        if (v instanceof Short s)    { out.write(SHORT); writeVarint(out, zig(s)); return; }
        if (v instanceof Integer i)  { out.write(INT);   writeVarint(out, zig(i)); return; }
        if (v instanceof Long l)     { out.write(LONG);  writeVarint(out, zig(l)); return; }
        if (v instanceof Float f)    { out.write(FLOAT);  writeInt32(out, Float.floatToIntBits(f)); return; }
        if (v instanceof Double d)   { out.write(DOUBLE); writeInt64(out, Double.doubleToLongBits(d)); return; }
        if (v instanceof String s) {
            out.write(STRING);
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            writeVarint(out, b.length);
            out.write(b);
            return;
        }
        if (v instanceof byte[] b) {
            out.write(BYTES);
            writeVarint(out, b.length);
            out.write(b);
            return;
        }
        if (v instanceof List<?> list) {
            out.write(LIST);
            writeVarint(out, list.size());
            for (Object e : list) write(out, e);
            return;
        }
        if (v instanceof Map<?, ?> map) {
            out.write(MAP);
            writeVarint(out, map.size());
            for (Map.Entry<?, ?> e : map.entrySet()) {
                write(out, e.getKey());
                write(out, e.getValue());
            }
            return;
        }
        throw new IllegalArgumentException("unsupported: " + v.getClass());
    }

    /**
     * Read one value written by {@link #write}, returning the exact Java type
     * that was written. Call in a loop to read a stream of values; the loop ends
     * when this throws {@link EOFException} at a clean value boundary. A tag
     * whose type id is unrecognized is skipped via its size class and returned
     * as an {@link Unknown} (forward compatibility).
     *
     * @return the decoded value (may be {@code null}, or an {@link Unknown})
     * @throws EOFException at end of stream
     */
    public static Object read(InputStream in) throws IOException {
        int tag = in.read();
        if (tag < 0) throw new EOFException();
        switch (tag) {
            case NULL:   return null;
            case FALSE:  return Boolean.FALSE;
            case TRUE:   return Boolean.TRUE;
            case CHAR:   return (char) ((read1(in) << 8) | read1(in));
            case BYTE:   return (byte)  unzig(readVarint(in));
            case SHORT:  return (short) unzig(readVarint(in));
            case INT:    return (int)   unzig(readVarint(in));
            case LONG:   return         unzig(readVarint(in));
            case FLOAT:  return Float.intBitsToFloat(readInt32(in));
            case DOUBLE: return Double.longBitsToDouble(readInt64(in));
            case STRING: return new String(readN(in, readLen(in)), StandardCharsets.UTF_8);
            case BYTES:  return readN(in, readLen(in));
            case LIST: {
                int n = readLen(in);
                var l = new ArrayList<Object>();
                for (int i = 0; i < n; i++) l.add(read(in));
                return l;
            }
            case MAP: {
                int n = readLen(in);
                var m = new LinkedHashMap<Object, Object>();
                for (int i = 0; i < n; i++) {
                    Object k = read(in);
                    Object val = read(in);
                    m.put(k, val);
                }
                return m;
            }
            default:     return skipUnknown(in, tag);
        }
    }

    /**
     * Skip a value with an unrecognized {@code tag} using only its size class
     * (the top 3 bits), returning an {@link Unknown}. This is the mechanism
     * behind forward compatibility: because every size class has a fixed skip
     * rule, a reader can step over a type it was never taught, staying byte-
     * accurate so following values — including siblings in a List/Map — still
     * parse. For a size-class-7 (values) tag the nested values are read and
     * discarded to consume them, so {@code Unknown.raw} is empty in that case.
     */
    static Unknown skipUnknown(InputStream in, int tag) throws IOException {
        int cls = (tag >> 5) & 7;
        var raw = new ByteArrayOutputStream();
        switch (cls) {
            case 0: break;
            case 1: raw.writeBytes(readN(in, 1)); break;
            case 2: raw.writeBytes(readN(in, 2)); break;
            case 3: raw.writeBytes(readN(in, 4)); break;
            case 4: raw.writeBytes(readN(in, 8)); break;
            case 5: { int x; do { x = read1(in); raw.write(x); } while ((x & 0x80) != 0); } break;
            case 6: { int n = readLen(in); raw.writeBytes(readN(in, n)); } break;
            case 7: { int n = readLen(in); for (long i = 0; i < n; i++) read(in); } break;
            default: throw new IOException("bad size class " + cls);
        }
        return new Unknown(tag, raw.toByteArray());
    }
}

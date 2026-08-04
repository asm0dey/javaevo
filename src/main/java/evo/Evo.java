package evo;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Zero-dependency self-describing binary codec. See design spec. */
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

    /** Returned by read() for a tag whose type id is unknown to this reader. */
    public record Unknown(int tag, byte[] raw) {}

    // ---- varint (unsigned LEB128) ----
    static void writeVarint(OutputStream o, long v) throws IOException {
        while ((v & ~0x7FL) != 0) {
            o.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        o.write((int) (v & 0x7F));
    }

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

    /** Read a varint length/count, rejecting negative or oversized values (trust boundary). */
    static int readLen(InputStream in) throws IOException {
        long n = readVarint(in);
        if (n < 0 || n > Integer.MAX_VALUE) throw new IOException("bad length/count: " + n);
        return (int) n;
    }

    // ---- zigzag ----
    static long zig(long v)   { return (v << 1) ^ (v >> 63); }
    static long unzig(long u) { return (u >>> 1) ^ -(u & 1); }

    // ---- byte-level IO ----
    static int read1(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException();
        return b;
    }

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

    static void writeInt32(OutputStream o, int v) throws IOException {
        o.write(v >>> 24); o.write(v >>> 16); o.write(v >>> 8); o.write(v);
    }
    static int readInt32(InputStream in) throws IOException {
        return (read1(in) << 24) | (read1(in) << 16) | (read1(in) << 8) | read1(in);
    }
    static void writeInt64(OutputStream o, long v) throws IOException {
        for (int s = 56; s >= 0; s -= 8) o.write((int) (v >>> s));
    }
    static long readInt64(InputStream in) throws IOException {
        long r = 0;
        for (int i = 0; i < 8; i++) r = (r << 8) | read1(in);
        return r;
    }

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

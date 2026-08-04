package evo;

import java.io.*;
import java.util.*;

public class EvoTest {
    static int checks = 0;
    static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
        checks++;
    }

    static void testVarint() throws IOException {
        long[] samples = {0, 1, 2, 127, 128, 300, 16384, Long.MAX_VALUE, -1L};
        var b = new ByteArrayOutputStream();
        for (long v : samples) Evo.writeVarint(b, v);
        var in = new ByteArrayInputStream(b.toByteArray());
        for (long v : samples) check(Evo.readVarint(in) == v, "varint " + v);
    }

    static void testZigzag() {
        long[] samples = {0, -1, 1, -2, 2, Long.MIN_VALUE, Long.MAX_VALUE};
        for (long v : samples) check(Evo.unzig(Evo.zig(v)) == v, "zigzag " + v);
        // small magnitudes must stay small after zigzag
        check(Evo.zig(-1) == 1, "zig(-1)==1");
        check(Evo.zig(1) == 2, "zig(1)==2");
    }

    static void testFixed() throws IOException {
        var b = new ByteArrayOutputStream();
        Evo.writeInt32(b, 0x01020304);
        Evo.writeInt64(b, 0x0102030405060708L);
        var in = new ByteArrayInputStream(b.toByteArray());
        check(Evo.readInt32(in) == 0x01020304, "int32");
        check(Evo.readInt64(in) == 0x0102030405060708L, "int64");
    }

    static Object roundtrip(Object v) throws IOException {
        var b = new ByteArrayOutputStream();
        Evo.write(b, v);
        return Evo.read(new ByteArrayInputStream(b.toByteArray()));
    }

    static void testScalars() throws IOException {
        check(roundtrip(null) == null, "null");
        check(roundtrip(true).equals(true), "true");
        check(roundtrip(false).equals(false), "false");
        check(roundtrip('Z').equals('Z'), "char");
        check(roundtrip('€').equals('€'), "char unicode"); // euro sign
        // exact type fidelity
        Object bv = roundtrip((byte) -7);
        check(bv instanceof Byte && bv.equals((byte) -7), "byte type+val");
        Object sv = roundtrip((short) 30000);
        check(sv instanceof Short && sv.equals((short) 30000), "short type+val");
        Object iv = roundtrip(123456);
        check(iv instanceof Integer && iv.equals(123456), "int type+val");
        Object lv = roundtrip(Long.MIN_VALUE);
        check(lv instanceof Long && lv.equals(Long.MIN_VALUE), "long type+val");
        Object fv = roundtrip(3.5f);
        check(fv instanceof Float && fv.equals(3.5f), "float type+val");
        Object dv = roundtrip(-2.25d);
        check(dv instanceof Double && dv.equals(-2.25d), "double type+val");
    }

    static void testStringBytes() throws IOException {
        check(roundtrip("").equals(""), "empty string");
        check(roundtrip("hello").equals("hello"), "ascii string");
        check(roundtrip("héllo €").equals("héllo €"), "utf8 string");
        byte[] raw = {1, 2, 3, -1, 0, 127};
        Object rb = roundtrip(raw);
        check(rb instanceof byte[] && java.util.Arrays.equals((byte[]) rb, raw), "byte[]");
        check(java.util.Arrays.equals((byte[]) roundtrip(new byte[0]), new byte[0]), "empty byte[]");
    }

    @SuppressWarnings("unchecked")
    static void testCollections() throws IOException {
        var list = java.util.List.of(1, "two", 3.0, java.util.List.of((byte) 4));
        Object rl = roundtrip(list);
        check(rl.equals(list), "nested list");

        var map = new java.util.LinkedHashMap<Object, Object>();
        map.put("a", 1);
        map.put("b", java.util.List.of("x", "y"));
        map.put(null, "nullkey");           // null key survives
        map.put("c", null);                 // null value survives
        Object rm = roundtrip(map);
        check(rm instanceof java.util.Map, "map type");
        var m2 = (java.util.Map<Object, Object>) rm;
        check(m2.get("a").equals(1), "map a");
        check(m2.get("b").equals(java.util.List.of("x", "y")), "map b nested");
        check(m2.get(null).equals("nullkey"), "map null key");
        check(m2.containsKey("c") && m2.get("c") == null, "map null value");
    }

    // build a stream: unknown value with given tag+payload, then a known INT(99)
    static ByteArrayInputStream unknownThen(int tag, byte[] payload) throws IOException {
        var b = new ByteArrayOutputStream();
        b.write(tag);
        b.writeBytes(payload);
        Evo.write(b, 99);   // known follow-up value
        return new ByteArrayInputStream(b.toByteArray());
    }

    static byte[] varintBytes(long v) throws IOException {
        var b = new ByteArrayOutputStream();
        Evo.writeVarint(b, v);
        return b.toByteArray();
    }

    static void testUnknownSkip() throws IOException {
        // class 0 empty, unused id 0x1F
        checkSkip(0x1F, new byte[0]);
        // class 1 fixed1, id 0 -> tag 0x20, 1 payload byte
        checkSkip(0x20, new byte[]{7});
        // class 2 fixed2, unused id 0x1F -> tag 0x5F, 2 bytes
        checkSkip(0x5F, new byte[]{1,2});
        // class 3 fixed4, unused id 0x1F -> tag 0x7F, 4 bytes
        checkSkip(0x7F, new byte[]{1,2,3,4});
        // class 4 fixed8, unused id 0x1F -> tag 0x9F, 8 bytes
        checkSkip(0x9F, new byte[]{1,2,3,4,5,6,7,8});
        // class 5 varint, unused id 0x1F -> tag 0xBF
        checkSkip(0xBF, varintBytes(123456));
        // class 6 bytes, unused id 0x1F -> tag 0xDF, len-prefixed
        var p = new ByteArrayOutputStream();
        p.writeBytes(varintBytes(3)); p.writeBytes(new byte[]{9,9,9});
        checkSkip(0xDF, p.toByteArray());
    }

    static void checkSkip(int tag, byte[] payload) throws IOException {
        var in = unknownThen(tag, payload);
        Object u = Evo.read(in);
        check(u instanceof Evo.Unknown && ((Evo.Unknown) u).tag() == tag, "unknown tag " + tag);
        check(Evo.read(in).equals(99), "stream in sync after unknown " + tag);
    }

    @SuppressWarnings("unchecked")
    static void testUnknownNestedInMap() throws IOException {
        // a MAP with one entry: key "k", value = unknown (class 6, tag 0xDF).
        var b = new ByteArrayOutputStream();
        b.write(Evo.MAP);
        Evo.writeVarint(b, 1);          // 1 entry
        Evo.write(b, "k");              // key
        b.write(0xDF);                  // unknown value tag (class 6)
        b.writeBytes(varintBytes(2)); b.writeBytes(new byte[]{5,6});
        Evo.write(b, "after");          // next top-level value
        var in = new ByteArrayInputStream(b.toByteArray());
        var m = (Map<Object,Object>) Evo.read(in);
        check(m.get("k") instanceof Evo.Unknown && ((Evo.Unknown) m.get("k")).tag() == 0xDF, "unknown as map value with correct tag, no desync");
        check(Evo.read(in).equals("after"), "top-level in sync after map with unknown");
    }

    static void testUnknownSkipValuesClass() throws IOException {
        var b = new ByteArrayOutputStream();
        b.write(0xFF);                       // class 7 (values), unused id 0x1F
        Evo.writeVarint(b, 2);               // count = 2 nested values
        Evo.write(b, 7);                     // nested value 1 (int)
        Evo.write(b, "nested");              // nested value 2 (string)
        Evo.write(b, 99);                    // known follow-up top-level value
        var in = new ByteArrayInputStream(b.toByteArray());
        Object u = Evo.read(in);
        check(u instanceof Evo.Unknown && ((Evo.Unknown) u).tag() == 0xFF, "unknown class-7 tag skipped");
        check(Evo.read(in).equals(99), "stream in sync after class-7 unknown (recursive skip)");
    }

    static void testFraming() throws IOException {
        var b = new ByteArrayOutputStream();
        Evo.write(b, 1);
        Evo.write(b, "two");
        Evo.write(b, java.util.List.of(3));
        var in = new ByteArrayInputStream(b.toByteArray());
        check(Evo.read(in).equals(1), "frame 1");
        check(Evo.read(in).equals("two"), "frame 2");
        check(Evo.read(in).equals(java.util.List.of(3)), "frame 3");
        boolean eof = false;
        try { Evo.read(in); } catch (EOFException e) { eof = true; }
        check(eof, "EOFException at end of stream");
    }

    static void testMalformedLengthRejected() throws IOException {
        // BYTES tag with a negative (high-bit) length varint must throw IOException, not crash
        var b = new ByteArrayOutputStream();
        b.write(Evo.BYTES);
        Evo.writeVarint(b, -1L);            // 10-byte varint, > Integer.MAX_VALUE
        var in = new ByteArrayInputStream(b.toByteArray());
        boolean threw = false;
        try { Evo.read(in); } catch (IOException e) { threw = true; }
        check(threw, "malformed length rejected with IOException");
    }

    static void testOversizedVarintRejected() throws IOException {
        // 11 continuation bytes -> varint too long
        var b = new ByteArrayOutputStream();
        for (int i = 0; i < 11; i++) b.write(0x80);
        b.write(0x00);
        var in = new ByteArrayInputStream(b.toByteArray());
        boolean threw = false;
        try { Evo.readVarint(in); } catch (IOException e) { threw = true; }
        check(threw, "oversized varint rejected");
    }

    public static void main(String[] args) throws Exception {
        testVarint();
        testZigzag();
        testFixed();
        testScalars();
        testStringBytes();
        testCollections();
        testUnknownSkip();
        testUnknownNestedInMap();
        testUnknownSkipValuesClass();
        testFraming();
        testMalformedLengthRejected();
        testOversizedVarintRejected();
        System.out.println("EvoTest OK (" + checks + " checks)");
    }
}

package evo;

import java.io.*;
import java.util.*;

public class EvoTest {
    static int checks = 0;
    static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
        checks++;
    }

    static byte[] toBytes(long... vals) throws IOException {
        var b = new ByteArrayOutputStream();
        for (long v : vals) Evo.writeVarint(b, v);
        return b.toByteArray();
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

    public static void main(String[] args) throws Exception {
        testVarint();
        testZigzag();
        testFixed();
        testScalars();
        testStringBytes();
        testCollections();
        System.out.println("EvoTest OK (" + checks + " checks)");
    }
}

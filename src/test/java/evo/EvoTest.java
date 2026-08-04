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

    public static void main(String[] args) throws Exception {
        testVarint();
        testZigzag();
        testFixed();
        System.out.println("EvoTest OK (" + checks + " checks)");
    }
}

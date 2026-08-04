package evo;

import java.io.*;

/** Zero-dependency self-describing binary codec. See design spec. */
public final class Evo {
    private Evo() {}

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
            b = read1(in);
            r |= (long) (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return r;
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
}

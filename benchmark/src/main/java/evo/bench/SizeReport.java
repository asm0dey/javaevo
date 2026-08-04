package evo.bench;

import evo.bench.Payload.Session;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/**
 * Prints the encoded size (raw and gzipped) of the payload in each format, and
 * a quick correctness round-trip. Not a JMH benchmark — run directly:
 * {@code java -cp target/benchmarks.jar evo.bench.SizeReport}
 */
public final class SizeReport {
    public static void main(String[] args) throws Exception {
        int nClasses = 200, probeLen = 64;
        Session s = Payload.sample(nClasses, probeLen);
        System.out.printf("payload: %d classes x %d probe bytes%n%n", nClasses, probeLen);
        System.out.printf("%-8s %10s %10s   %s%n", "format", "bytes", "gzipped", "roundtrip");
        System.out.println("---------------------------------------------");
        for (Formats.Fmt f : Formats.Fmt.values()) {
            byte[] enc = Formats.serialize(f, s);
            Session back = Formats.deserialize(f, enc);
            boolean ok = check(s, back);
            System.out.printf("%-8s %10d %10d   %s%n", f, enc.length, gzip(enc), ok ? "ok" : "MISMATCH");
        }
    }

    private static boolean check(Session a, Session b) {
        if (b == null || a.classes().size() != b.classes().size()) return false;
        var ca = a.classes().get(2);
        var cb = b.classes().get(2);
        return ca.classId() == cb.classId()
            && ca.name().equals(cb.name())
            && ca.hitCount() == cb.hitCount()
            && Arrays.equals(ca.probes(), cb.probes())
            && a.id().equals(b.id()) && a.start() == b.start() && a.dump() == b.dump();
    }

    private static int gzip(byte[] data) throws IOException {
        var b = new ByteArrayOutputStream();
        try (var g = new GZIPOutputStream(b)) { g.write(data); }
        return b.size();
    }
}

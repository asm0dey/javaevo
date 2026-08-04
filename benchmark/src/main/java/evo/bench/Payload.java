package evo.bench;

import java.util.*;

/** JaCoCo-ish coverage payload for the benchmarks. Deterministic (no RNG). */
public final class Payload {
    private Payload() {}

    public record ClassData(long classId, String name, byte[] probes, int hitCount) {}

    public record Session(String id, long start, long dump, List<ClassData> classes) {}

    /** A session with {@code nClasses} classes, each carrying a {@code probeLen}-byte probe array. */
    public static Session sample(int nClasses, int probeLen) {
        var list = new ArrayList<ClassData>(nClasses);
        for (int i = 0; i < nClasses; i++) {
            byte[] p = new byte[probeLen];
            for (int j = 0; j < probeLen; j++) p[j] = (byte) ((i * 31 + j) & 0xFF);
            list.add(new ClassData(1000L + i, "com.example.pkg.Class" + i, p, i * 7));
        }
        return new Session("session-" + nClasses, 1_700_000_000_000L, 1_700_000_100_000L, list);
    }
}

package evo;

import evo.bench.Payload.ClassData;
import evo.bench.Payload.Session;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Hand-written serializer for the benchmark payload — the "specialized" ceiling.
 * No reflection, no boxing, no intermediate value-tree: it writes fields
 * straight to the stream via {@link Evo}'s primitive helpers. Produces the
 * <b>same bytes</b> as {@link EvoMap}/{@link CachedEvoMap} for these types
 * (a name-keyed map), so it is byte-compatible and isolates pure
 * serialization-mechanism cost at equal output size.
 *
 * <p>The reader trusts field order (it wrote the stream), skipping name lookups.
 * Lives in package {@code evo} to use the package-private codec internals.
 */
public final class Specialized {
    private Specialized() {}

    // ---- write ----
    public static void write(OutputStream o, Session s) throws IOException {
        o.write(Evo.MAP); Evo.writeVarint(o, 4);
        str(o, "id");      str(o, s.id());
        str(o, "start");   lng(o, s.start());
        str(o, "dump");    lng(o, s.dump());
        str(o, "classes");
        List<ClassData> cs = s.classes();
        o.write(Evo.LIST); Evo.writeVarint(o, cs.size());
        for (ClassData c : cs) {
            o.write(Evo.MAP); Evo.writeVarint(o, 4);
            str(o, "classId");  lng(o, c.classId());
            str(o, "name");     str(o, c.name());
            str(o, "probes");   bytes(o, c.probes());
            str(o, "hitCount"); intv(o, c.hitCount());
        }
    }

    private static void str(OutputStream o, String s) throws IOException {
        o.write(Evo.STRING);
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        Evo.writeVarint(o, b.length);
        o.write(b);
    }
    private static void lng(OutputStream o, long v) throws IOException {
        o.write(Evo.LONG); Evo.writeVarint(o, Evo.zig(v));
    }
    private static void intv(OutputStream o, int v) throws IOException {
        o.write(Evo.INT); Evo.writeVarint(o, Evo.zig(v));
    }
    private static void bytes(OutputStream o, byte[] b) throws IOException {
        o.write(Evo.BYTES); Evo.writeVarint(o, b.length); o.write(b);
    }

    // ---- read ---- (positional: we wrote it, so trust the field order)
    public static Session read(InputStream in) throws IOException {
        expect(in, Evo.MAP); Evo.readVarint(in);        // map, 4 entries
        skipKey(in); String id = rstr(in);
        skipKey(in); long start = rlong(in);
        skipKey(in); long dump = rlong(in);
        skipKey(in);                                    // "classes"
        expect(in, Evo.LIST); int cn = (int) Evo.readVarint(in);
        var list = new ArrayList<ClassData>(cn);
        for (int i = 0; i < cn; i++) {
            expect(in, Evo.MAP); Evo.readVarint(in);
            skipKey(in); long cid = rlong(in);
            skipKey(in); String name = rstr(in);
            skipKey(in); byte[] probes = rbytes(in);
            skipKey(in); int hc = rint(in);
            list.add(new ClassData(cid, name, probes, hc));
        }
        return new Session(id, start, dump, list);
    }

    private static void expect(InputStream in, int tag) throws IOException {
        int t = Evo.read1(in);
        if (t != tag) throw new IOException("expected tag " + tag + " got " + t);
    }
    private static void skipKey(InputStream in) throws IOException {
        expect(in, Evo.STRING);
        Evo.readN(in, (int) Evo.readVarint(in));        // discard the field name
    }
    private static String rstr(InputStream in) throws IOException {
        expect(in, Evo.STRING);
        return new String(Evo.readN(in, (int) Evo.readVarint(in)), StandardCharsets.UTF_8);
    }
    private static long rlong(InputStream in) throws IOException {
        expect(in, Evo.LONG); return Evo.unzig(Evo.readVarint(in));
    }
    private static int rint(InputStream in) throws IOException {
        expect(in, Evo.INT); return (int) Evo.unzig(Evo.readVarint(in));
    }
    private static byte[] rbytes(InputStream in) throws IOException {
        expect(in, Evo.BYTES); return Evo.readN(in, (int) Evo.readVarint(in));
    }
}

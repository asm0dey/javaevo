package evo.bench;

import evo.CachedEvoMap;
import evo.EvoMap;
import evo.Specialized;
import evo.bench.Payload.Session;

import java.io.*;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.*;

/**
 * Full factorial over real file IO: strategy × buffered × {write, read}.
 * This is the realistic path (the codec does byte-at-a-time IO, so buffering
 * matters here). The fastest cell = the combination worth optimizing toward.
 *
 * <ul>
 *   <li>strategy: baseline ({@link EvoMap}) / cached ({@link CachedEvoMap}) /
 *       specialized ({@link Specialized})</li>
 *   <li>buffered: raw {@link FileOutputStream} vs {@link BufferedOutputStream}</li>
 * </ul>
 * All strategies emit identical bytes, so read cells can share one primed file.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class FileBench {

    @Param({"baseline", "cached", "specialized"})
    public String strategy;

    @Param({"false", "true"})
    public boolean buffered;

    @Param({"200"})
    public int nClasses;

    @Param({"64"})
    public int probeLen;

    private Session session;
    private File file;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        session = Payload.sample(nClasses, probeLen);
        file = File.createTempFile("evo-bench", ".bin");
        file.deleteOnExit();
        // prime the file (canonical bytes) so readFile has content for every cell
        try (OutputStream fo = new FileOutputStream(file)) {
            EvoMap.writeObject(fo, session);
        }
    }

    @TearDown(Level.Trial)
    public void teardown() {
        file.delete();
    }

    @Benchmark
    public void writeFile() throws IOException {
        try (OutputStream fo = new FileOutputStream(file);
             OutputStream o = buffered ? new BufferedOutputStream(fo) : fo) {
            switch (strategy) {
                case "baseline"    -> EvoMap.writeObject(o, session);
                case "cached"      -> CachedEvoMap.writeObject(o, session);
                case "specialized" -> Specialized.write(o, session);
                default -> throw new IllegalStateException(strategy);
            }
        }
    }

    @Benchmark
    public Session readFile() throws IOException {
        try (InputStream fi = new FileInputStream(file);
             InputStream in = buffered ? new BufferedInputStream(fi) : fi) {
            return switch (strategy) {
                case "baseline"    -> EvoMap.readObject(in, Session.class);
                case "cached"      -> CachedEvoMap.readObject(in, Session.class);
                case "specialized" -> Specialized.read(in);
                default -> throw new IllegalStateException(strategy);
            };
        }
    }
}

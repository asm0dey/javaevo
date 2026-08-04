package evo.bench;

import evo.EvoMap;
import evo.bench.Payload.Session;

import java.io.*;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.*;

/**
 * The buffering axis, measured where it actually matters: real file IO.
 * {@link evo.Evo} writes/reads a byte at a time, so an unbuffered
 * {@link FileOutputStream}/{@link FileInputStream} pays a syscall per byte;
 * wrapping in {@link BufferedOutputStream}/{@link BufferedInputStream} batches
 * them. Uses the baseline strategy — buffering is orthogonal to it.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class FileBench {

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
        writeFile();   // prime the file so readFile has content
    }

    @TearDown(Level.Trial)
    public void teardown() {
        file.delete();
    }

    @Benchmark
    public void writeFile() throws IOException {
        try (OutputStream fo = new FileOutputStream(file);
             OutputStream o = buffered ? new BufferedOutputStream(fo) : fo) {
            EvoMap.writeObject(o, session);
        }
    }

    @Benchmark
    public Session readFile() throws IOException {
        try (InputStream fi = new FileInputStream(file);
             InputStream in = buffered ? new BufferedInputStream(fi) : fi) {
            return EvoMap.readObject(in, Session.class);
        }
    }
}

package evo.bench;

import evo.CachedEvoMap;
import evo.EvoMap;
import evo.Specialized;
import evo.bench.Payload.Session;

import java.io.*;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.*;

/**
 * Serialize/deserialize the payload in memory, comparing three strategies:
 * <ul>
 *   <li><b>baseline</b>  — {@link EvoMap}: uncached reflection + value-tree + boxing</li>
 *   <li><b>cached</b>    — {@link CachedEvoMap}: cached reflection, otherwise same</li>
 *   <li><b>specialized</b> — {@link Specialized}: no reflection, no boxing, direct writes</li>
 * </ul>
 * All three emit identical bytes, so size is equal and this measures pure
 * mechanism cost. baseline→cached shows the reflection-lookup win;
 * cached→specialized shows the value-tree + boxing win.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class StrategyBench {

    @Param({"baseline", "cached", "specialized"})
    public String strategy;

    // payload size: classes × probe bytes
    @Param({"200"})
    public int nClasses;

    @Param({"64"})
    public int probeLen;

    private Session session;
    private byte[] encoded;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        session = Payload.sample(nClasses, probeLen);
        var b = new ByteArrayOutputStream();
        EvoMap.writeObject(b, session);   // canonical bytes (all strategies match)
        encoded = b.toByteArray();
    }

    @Benchmark
    public byte[] serialize() throws IOException {
        var b = new ByteArrayOutputStream(encoded.length);
        switch (strategy) {
            case "baseline"    -> EvoMap.writeObject(b, session);
            case "cached"      -> CachedEvoMap.writeObject(b, session);
            case "specialized" -> Specialized.write(b, session);
            default -> throw new IllegalStateException(strategy);
        }
        return b.toByteArray();
    }

    @Benchmark
    public Session deserialize() throws IOException {
        var in = new ByteArrayInputStream(encoded);
        return switch (strategy) {
            case "baseline"    -> EvoMap.readObject(in, Session.class);
            case "cached"      -> CachedEvoMap.readObject(in, Session.class);
            case "specialized" -> Specialized.read(in);
            default -> throw new IllegalStateException(strategy);
        };
    }
}

package evo.bench;

import evo.bench.Payload.Session;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.*;

/**
 * evo vs well-known formats (Java native, Jackson JSON, Jackson CBOR, Kryo)
 * on the same {@link Session} payload. Each format serializes and deserializes
 * its own bytes. Size is reported separately by {@link SizeReport}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class FormatBench {

    @Param({"evo", "java", "json", "cbor", "kryo"})
    public String format;

    @Param({"200"})
    public int nClasses;

    @Param({"64"})
    public int probeLen;

    private Formats.Fmt fmt;
    private Session session;
    private byte[] encoded;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        fmt = Formats.Fmt.valueOf(format);
        session = Payload.sample(nClasses, probeLen);
        encoded = Formats.serialize(fmt, session);
    }

    @Benchmark
    public byte[] serialize() throws Exception {
        return Formats.serialize(fmt, session);
    }

    @Benchmark
    public Session deserialize() throws Exception {
        return Formats.deserialize(fmt, encoded);
    }
}

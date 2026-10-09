package evo.bench;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;

import evo.EvoMap;
import evo.StreamingMapper;
import evo.bench.Payload.Session;

import java.io.*;

/**
 * Serialize/deserialize the {@link Session} payload with several well-known
 * formats, for apples-to-apples comparison against evo. All are reflection /
 * no-codegen so they map the same records without a schema step:
 * <ul>
 *   <li><b>evo</b>    — this project ({@link EvoMap}, self-describing binary)</li>
 *   <li><b>java</b>   — built-in {@link Serializable} (no dependency)</li>
 *   <li><b>json</b>   — Jackson JSON (text, self-describing)</li>
 *   <li><b>cbor</b>   — Jackson CBOR (binary, self-describing)</li>
 *   <li><b>kryo</b>   — Kryo (binary, unregistered — typical lazy usage)</li>
 * </ul>
 * Protobuf/Avro are intentionally excluded: they need a schema + codegen (a
 * different category — denser and faster, but not drop-in reflective).
 */
public final class Formats {
    private Formats() {}

    public enum Fmt { evo, evobytes, evostream, java, json, cbor, kryo }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper CBOR = new ObjectMapper(new CBORFactory());

    // Kryo is not thread-safe: one instance per thread.
    private static final ThreadLocal<Kryo> KRYO = ThreadLocal.withInitial(() -> {
        Kryo k = new Kryo();
        k.setRegistrationRequired(false);
        k.setReferences(false);
        return k;
    });

    public static byte[] serialize(Fmt f, Session s) throws Exception {
        switch (f) {
            case evo: {
                var b = new ByteArrayOutputStream();
                EvoMap.writeObject(b, s);
                return b.toByteArray();
            }
            case evobytes: {
                var b = new ByteArrayOutputStream();
                EvoMap.writeObject(b, s);
                return b.toByteArray();
            }
            case evostream: {
                var b = new ByteArrayOutputStream();
                StreamingMapper.writeObject(b, s);
                return b.toByteArray();
            }
            case java: {
                var b = new ByteArrayOutputStream();
                try (var o = new ObjectOutputStream(b)) { o.writeObject(s); }
                return b.toByteArray();
            }
            case json:  return JSON.writeValueAsBytes(s);
            case cbor:  return CBOR.writeValueAsBytes(s);
            case kryo: {
                var b = new ByteArrayOutputStream();
                var out = new Output(b);
                KRYO.get().writeObject(out, s);
                out.flush();
                return b.toByteArray();
            }
            default: throw new IllegalStateException(f.name());
        }
    }

    public static Session deserialize(Fmt f, byte[] data) throws Exception {
        switch (f) {
            case evo:  return EvoMap.readObject(new ByteArrayInputStream(data), Session.class);
            case evobytes: return EvoMap.readObject(data, Session.class);
            case evostream: return StreamingMapper.readObject(new ByteArrayInputStream(data), Session.class);
            case java: {
                try (var in = new ObjectInputStream(new ByteArrayInputStream(data))) {
                    return (Session) in.readObject();
                }
            }
            case json:  return JSON.readValue(data, Session.class);
            case cbor:  return CBOR.readValue(data, Session.class);
            case kryo:  return KRYO.get().readObject(new Input(data), Session.class);
            default: throw new IllegalStateException(f.name());
        }
    }
}

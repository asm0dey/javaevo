# evo benchmarks (JMH)

Measures the three optimization axes: **buffered streams × cached reflection ×
primitive specialization**. Not shipped with the library — `Evo`/`EvoMap` are
untouched; the variants live here.

## Run

The benchmark depends on the `evo` jar, so install it first:

```bash
mvn -q install -DskipTests            # from repo root: publish dev.evo:evo to ~/.m2
cd benchmark && mvn -q clean package  # builds target/benchmarks.jar
java -jar target/benchmarks.jar       # full run (accurate, ~minutes)
```

Quick indicative run:

```bash
java -jar target/benchmarks.jar -f 1 -wi 2 -i 3 -w 500ms -r 500ms
```

## What is measured

Payload: a JaCoCo-ish `Session` of `nClasses` `ClassData` records, each with a
`probeLen`-byte probe array (`Payload.sample`). Default 200 classes × 64 bytes.

**Strategies** (`StrategyBench`, in-memory, all emit *identical bytes*):

| Strategy | Reflection | Value tree + boxing | Isolates |
|---|---|---|---|
| `baseline` (`EvoMap`) | cached per class | yes | — |
| `cached` (`CachedEvoMap`) | cached per class | yes | (now equivalent to baseline) |
| `specialized` (`Specialized`) | none (hand-written) | no | value-tree + boxing cost |

> **Note:** reflection caching was folded into `EvoMap` after this study
> (it was the big, cheap win). So `baseline` and `cached` now share the same
> approach and score alike; `CachedEvoMap` is kept as the historical A/B point.
> The numbers below are from *before* the fold, when `baseline` was uncached.

**Buffering** (`FileBench`, real temp file): baseline strategy, `@Param buffered`
∈ {false, true}. Reads are byte-at-a-time, so an unbuffered `FileInputStream`
pays a syscall per byte. Writes no longer care: `Evo.write` hands the stream
one bulk write per value.

## Indicative results

JDK 21, single short run (`-f 1 -wi 2 -i 3`), 200 classes × 64 B. **Error bars
are wide at this setting — treat as directional, not precise.** Lower is better.

### Serialization mechanism (in-memory, equal output bytes)

| Benchmark | baseline | cached | specialized |
|---|--:|--:|--:|
| serialize   | 714 µs | 85 µs (**8.4×**) | 63 µs (**11×**) |
| deserialize | 314 µs | 103 µs (**3.1×**) | 83 µs (**3.8×**) |

### Buffering (real file IO)

| Benchmark | unbuffered | buffered |
|---|--:|--:|
| writeFile | 63 µs | 63 µs (no difference since the internal write buffer) |
| readFile  | 1614 µs | 170 µs (**9.5×**) |

JDK 26, 2026-10-09. (`specialized` still writes per byte: 2683 µs unbuffered.)

## Reading of the results

- **Cached reflection is the big, cheap win.** ~8× on serialize, ~3× on
  deserialize, for a small per-class `ConcurrentHashMap` cache and *no wire or
  API change*. `EvoMap.toValue` calls `getRecordComponents()` (which allocates a
  fresh array reflectively) once per record — 201 times per payload here — and
  caching removes that. **Strongest candidate to fold into the library.**
- **Buffering matters for file/socket IO** (2–4×), and nothing for in-memory
  streams. It is a one-line caller-side wrap; the codec need not change. Worth a
  note in the README/Javadoc.
- **Specialization adds only ~1.3–1.4× over cached.** Skipping the value-tree
  and boxing helps, but far less than fixing reflection. The hand-written cost
  (no general mapper, no evolution safety) is usually not worth that last bit.

## vs well-known formats

`FormatBench` + `SizeReport` compare evo against Java native serialization,
Jackson JSON, Jackson CBOR, and Kryo (all reflection / no codegen). Protobuf and
Avro are excluded — they need a schema + codegen (a different, denser, faster
category). Same 200×64 payload, short run, JDK 21.

**Size** (bytes):

| format | raw | gzipped |
|---|--:|--:|
| kryo | 18,556 | 2,665 |
| java native | 23,988 | 3,501 |
| cbor | 25,605 | 3,320 |
| **evo** | **26,538** | **3,325** |
| json | 33,205 | 4,219 |

**Speed** (µs/op, lower is better). JDK 26, `-f 2 -wi 3 -i 5`, 2026-10-09:

| format | serialize | deserialize |
|---|--:|--:|
| kryo | 36 | 20 |
| cbor | 29 | 63 |
| json | 57 | 67 |
| java native | 55 | 89 |
| **evo** (`readObject(byte[])`) | **57** | **65** |
| **evo** (`readObject(InputStream)`) | **57** | **166** |
| evostream (prototype) | 95 | 139 |

**Reading:** evo now matches JSON/Java native on write and CBOR/JSON on read,
still ~2× behind CBOR on write and ~3× behind Kryo on read.

**Where the time went (async-profiler, 2026-10-09):** before the fix, 73% of
serialize was `ByteArrayOutputStream.write(int)` and ~85% of deserialize was
`ByteArrayInputStream.read()`. Both take a lock per call, the codec makes one
call per byte, and `BufferedOutputStream`/`BufferedInputStream` lock the same
way (~25 ns/byte either way). It was not the value tree, and not the wire
format: CBOR repeats field names too. Fixes, with no wire or API break:

- `Evo.write` encodes into a private unsynchronized buffer, then does one bulk
  write: serialize 143 → 57 µs for every caller and every stream.
- `Evo.read(byte[])` / `EvoMap.readObject(byte[], Class)` parse through an
  unsynchronized cursor: deserialize 166 → 65 µs. `readFromFile` uses it.
  `read(InputStream)` cannot read ahead (the bytes after a value belong to the
  caller), so it still pays the lock per byte.

The `evo`/`evobytes` rows in `FormatBench` are the stream and `byte[]` paths.

### Streaming-serializer prototype (`StreamingMapper`)

`evostream` is a prototype cached streaming serializer: per-class compiled plan,
cached field-name bytes, `MethodHandle` no-box field access, **no intermediate
Map tree**. It emits byte-identical output to `EvoMap`.

It was measured before the stream-locking fix above, and now loses to plain
`EvoMap` (it still writes through the locked `ByteArrayOutputStream` per byte).
The earlier claim that the wire format set evo's speed floor was wrong: the
floor was per-byte stream locking.

**Conclusion — do not graduate it into the library.** A general
compiled-serializer engine (POJOs, all types, enums, nested collections) is a
large amount of code that defeats the "~2 files, tiny" goal. The prototype
stays here as evidence.

evo's value is **not** speed or size — it is ~2 files, zero dependencies, schema
evolution, and all Java types in one small package. Choose it when those matter
more than throughput.

Run: `java -jar target/benchmarks.jar FormatBench` and
`java -cp target/benchmarks.jar evo.bench.SizeReport`.

## Byte-layout annotator

`evo.Annotate` serializes a deep nested record and prints an annotated,
byte-by-byte disassembly of the wire format (offsets, tags, decoded values,
nesting). Edit its `sample()` to inspect your own shapes.

```bash
cd benchmark && mvn -q package
java -cp target/benchmarks.jar evo.Annotate
```

## Caveats

- Short-run error bars are large; for real decisions run the full harness
  (`java -jar target/benchmarks.jar`), ideally with `-prof gc` to see allocation.
- All strategies produce byte-identical output, so this isolates CPU/alloc, not
  size. A positional (nameless) format would be smaller *and* faster but is a
  different, non-evolvable wire format — out of scope here.
- Numbers are one machine, one JDK. Re-run on your target.

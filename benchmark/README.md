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
∈ {false, true}. This is where buffering matters — the codec does byte-at-a-time
IO, so an unbuffered `FileOutputStream` pays a syscall per byte.

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
| writeFile | 1670 µs | 731 µs (**2.3×**) |
| readFile  | 1130 µs | 305 µs (**3.7×**) |

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

**Speed** (µs/op, lower is better):

| format | serialize | deserialize |
|---|--:|--:|
| kryo | 23 | 15 |
| cbor | 21 | 38 |
| json | 35 | 41 |
| java native | 38 | 55 |
| **evo** | **93** | **101** |

**Reading:** evo is the *slowest* here — ~3–5× behind Jackson-CBOR/Kryo, and
slower than Java native. Its cost is the intermediate `Map<String,Object>` tree
(`toValue` allocates + boxes, then serializes) plus `instanceof`-chain dispatch
and byte-at-a-time IO; Jackson/Kryo stream object→bytes with cached serializers.
On size, evo ≈ CBOR (both self-describing, field names repeated); Kryo is
smallest, JSON largest; gzip nearly equalizes evo/cbor/java.

evo's value is **not** speed or size — it is ~2 files, zero dependencies, schema
evolution, and all Java types in one small package. Choose it when those matter
more than throughput.

Run: `java -jar target/benchmarks.jar FormatBench` and
`java -cp target/benchmarks.jar evo.bench.SizeReport`.

## Caveats

- Short-run error bars are large; for real decisions run the full harness
  (`java -jar target/benchmarks.jar`), ideally with `-prof gc` to see allocation.
- All strategies produce byte-identical output, so this isolates CPU/alloc, not
  size. A positional (nameless) format would be smaller *and* faster but is a
  different, non-evolvable wire format — out of scope here.
- Numbers are one machine, one JDK. Re-run on your target.

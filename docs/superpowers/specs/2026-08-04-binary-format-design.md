# Binary Data Format — Design Spec

**Date:** 2026-08-04
**Status:** Approved design, ready for implementation plan

## Goal

A binary serialization format for Java, zero external dependencies, small enough
to vendor into JaCoCo (maintainers refuse new deps). General-purpose: callers
serialize their own arbitrary records (a mini-CBOR) and their own Java classes
(records / POJOs), not a fixed set of types.

### Success metrics

1. **Schema evolution** — add/drop fields and add brand-new value types without
   breaking old readers.
2. **Effective storage** — dense on the wire; small integers cost ~1 byte.
3. **Tiny footprint** — 2 Java files (codec + reflection mapper), plus tests.
4. **Coverage + forward-compat** — supports all Java primitives, String, byte[],
   List, Map, null, enums, and user records/POJOs; when new types are added
   later, older readers still parse the stream (they skip what they don't
   understand).

## Architecture

Two files, layered:

- **`Evo.java` — codec.** Self-describing wire format over the primitive data
  model: primitives, String, byte[], List, Map, null. Knows nothing about user
  classes. Dumb, recursive, tiny.
- **`EvoMap.java` — reflection mapper.** Maps user objects (records / POJOs)
  to and from `Map<String,Object>`, then delegates to the codec. Opt-in; the
  codec works standalone without it.

## Part 1 — Codec (`Evo.java`)

### Approach

Self-describing, CBOR-style. Every value is `[tag byte][payload]`. No separate
schema object, no registry, no codegen, no reflection.

- **Self-describing** was chosen over Avro-style schema-separated encoding.
  Schema-separated is ~10% denser but needs a schema representation plus
  reader-side resolution logic — many files, brittle evolution rules. Rejected
  for metric 3.

The single design trick that guarantees forward-compatibility (metric 4): the
tag byte encodes a **size class** in its top 3 bits. A reader that hits a tag it
does not recognize still knows, from the size class alone, how many bytes (or
nested values) to skip. So unknown future types are skippable even when nested
inside a List or Map.

### Tag byte

- **Top 3 bits** = size class (skip rule).
- **Low 5 bits** = type id within that class (32 ids per class, ample).

### Size classes

| Class | Name    | Skip rule                          | Types                        |
|-------|---------|------------------------------------|------------------------------|
| 0     | empty   | 0 payload bytes                    | NULL, FALSE, TRUE            |
| 1     | fixed1  | skip 1 byte                        | (reserved for future)        |
| 2     | fixed2  | skip 2 bytes                       | CHAR                         |
| 3     | fixed4  | skip 4 bytes                       | FLOAT                        |
| 4     | fixed8  | skip 8 bytes                       | DOUBLE                       |
| 5     | varint  | read 1 varint, discard             | BYTE, SHORT, INT, LONG       |
| 6     | bytes   | read varint length, skip that many | STRING (UTF-8), BYTES        |
| 7     | values  | read varint count, recurse count   | LIST, MAP                    |

Concrete type-id assignments (low 5 bits) are fixed by the implementation plan.
Class 1 is intentionally left free for a future 1-byte type.

### Encoding rules

- **Integers (byte/short/int/long):** zigzag + unsigned LEB128 varint. Small
  magnitudes cost 1 byte. The low tag bits record the original Java width so the
  reader restores the exact type (a written `short` reads back as `Short`).
- **char:** fixed 2 bytes, big-endian (16-bit unsigned).
- **float:** fixed 4 bytes, `Float.floatToIntBits`, big-endian.
- **double:** fixed 8 bytes, `Double.doubleToLongBits`, big-endian.
- **boolean:** TRUE / FALSE tags (class 0), no payload. Reads back as `Boolean`.
- **null:** NULL tag (class 0). Nullability is inherent — every value slot is
  nullable by construction. No separate "nullable" marker.
- **String:** class 6, varint UTF-8 byte length then the bytes.
- **byte[] (BYTES):** class 6, varint length then the bytes.
- **List:** class 7, varint element count then that many encoded values.
- **Map:** class 7, varint entry count then `key,value` pairs (2×count values).
  Reader builds a `LinkedHashMap` (preserves order, tolerates one null key).
  **Caveat:** MAP's count is *entries* (so `2×count` nested values follow), which
  does not match the generic class-7 skip rule below (skip = read count, then
  skip that many *values*). MAP is safe only because it is a base type every
  reader knows and always takes the explicit `2×count` read branch — it is a
  grandfathered exception. See rule 3.

### Varint

Unsigned LEB128 for lengths/counts. Signed integers use zigzag then LEB128.

### Framing

No file header, no magic number. A file/stream is simply a sequence of top-level
values written back-to-back; each value is self-delimiting. Read in a loop until
`EOFException`. The stream is append-friendly. Callers that need a magic/version
wrap it themselves (JaCoCo already has its own `.exec` magic). ponytail: add a
header only when a concrete need appears.

### Codec API

```java
static void   write(OutputStream out, Object v) throws IOException
static Object read(InputStream in) throws IOException   // throws EOFException at end of stream
```

Accepts / returns: `null`, `Boolean`, `Byte`, `Short`, `Character`, `Integer`,
`Long`, `Float`, `Double`, `String`, `byte[]`, `List<Object>`,
`Map<Object,Object>`. `write` throws `IllegalArgumentException` on an
unsupported object type. `read` returns the exact corresponding Java type, so
callers cast directly (`Short s = (Short) Evo.read(in);`).

### Unknown types (forward-compat behavior)

When `read` encounters a tag whose type id it does not recognize, it skips the
payload using the size-class rule and returns a small placeholder:

```java
record Unknown(int tag, byte[] raw) {}
```

This preserves structure and key/value pairing (an unknown value inside a Map
does not desync the stream) instead of throwing. That is the concrete meaning of
"old readers don't break on new types." Callers doing forward-compat reads guard
with `if (v instanceof Evo.Unknown u) { ... }` before casting.

## Part 2 — Reflection mapper (`EvoMap.java`)

Maps user objects to `Map<String,Object>` and back, then delegates to the codec.
Values are stored **name-keyed** (not positional) so add/drop-field evolution
stays free.

### Mapper API

```java
static void  writeObject(OutputStream out, Object obj) throws IOException
static <T> T readObject(InputStream in, Class<T> type) throws IOException
```

The target `type` is supplied at read time. **No class names are stored on the
wire** (denser, and avoids `Class.forName` — safe inside JaCoCo's classloaders).

### Per-class mechanism

Branch on the kind of `type`:

- **Record** → `getRecordComponents()` gives names, types, accessors; reconstruct
  via the **canonical constructor**. (Requires Java 16+.)
- **POJO** → all declared **non-static, non-transient fields**
  (`setAccessible(true)`); reconstruct via a **no-arg constructor**, then set
  each field. Consequence: a POJO must have a no-arg constructor and non-final
  fields to be readable.

### Nested types

Discovered from the **declared field type**, so no type info is needed on the
wire:

- Field `Address a` → recurse as `Address`.
- `List<Address>` / `Map<String,Address>` → element/value type read from the
  generic signature → recurse per element.
- **Enum** field → stored as the constant **name** string; read via
  `Enum.valueOf(type, name)`. Appending enum constants is evolution-safe.
- Leaf fields (primitives, String, byte[]) → straight to the codec.

### Read-time field resolution

`readObject` reads the wire Map, then for each field of `type`:

- key present → convert to the field's declared type and set it.
- key missing → leave the field default (0 / null).
- extra keys in the data not on the class → ignored.

### Mapper limits (consequences of the chosen design)

- **No polymorphism** — a field is decoded as its declared type; a
  declared-`Animal` field holding a `Dog` comes back as `Animal`.
- **No cycles** — tree-shaped composition only (not needed).
- **Raw/wildcard generics** (`List` untyped, `List<?>`) — elements decode as
  plain codec types (Map/primitive), not user classes. Use concrete generics.

### Mapper scope cuts (ponytail)

No annotations, no custom field naming, no getters/setters convention (fields
read directly), no `Class.forName`, no reflection auto-recursion into cyclic
graphs.

## Compatibility Guarantees

Terms: **forward** = old reader / newer data. **backward** = new reader / older
data.

### Forward compat (old reader reads newer data) — structural

- **New value types:** old reader hits an unknown type id → derives skip from the
  size class → skips → returns `Unknown`. Stays in sync even nested in a Map. All
  8 size classes have frozen skip rules, so a new type in any class is skippable.
- **New fields:** records are name-keyed Maps → new keys arrive as extra entries
  → old reader / mapper ignores keys it doesn't use.
- **Limit:** old reader *survives* new types, does not *understand* them (gets
  `Unknown`, not the real value).

### Backward compat (new reader reads older data) — under discipline

- New reader knows all old type ids → parses old data natively.
- **Dropped fields:** absent Map key → mapper leaves the field default. Safe.
- Missing new fields in old data → defaults applied.

### Field add / drop

Both safe, because records are Maps, not positional structs:

- **Add** — writer emits a new key; old readers ignore it, new readers use it.
- **Drop** — writer stops emitting the key; readers get the default.
- **Rule:** never *repurpose* a dropped field name for a different meaning/type —
  retire the name, don't recycle it.

### Rules maintainers MUST follow (else guarantees void)

1. **Type ids append-only** — never renumber, reuse, or repurpose a type id.
2. **Encoding frozen** — never change how an existing type id is encoded.
3. **Skip contract frozen** — the 8 size-class skip rules never change. A new
   type MUST fit one of the four disciplines: fixed-width, single-varint,
   length-prefixed-bytes, or count-prefixed-values (class 1 `fixed1` is free for
   a 1-byte type). For class 7, count is the exact number of nested *values* —
   a new class-7 type must prefix that value count, NOT an entry/pair count.
   (MAP prefixes an entry count and is safe only because it is universally
   known; do not mirror its framing for a new type or old readers silently
   desync.)
4. **Field names not recycled** — a retired field name is never reused for a
   different meaning.

### Not covered by the format (caller's job)

- Field add/drop/rename *semantics* — the format carries name-keyed Maps; meaning
  is the caller's schema.
- Type narrowing (old `Unknown` → real value) requires a reader upgrade.
- Polymorphism (see mapper limits).

## Usage examples

### Raw codec

```java
Evo.write(out, 42);
Integer n = (Integer) Evo.read(in);   // exact type back
```

### A class

```java
record Person(int age, String name, Address address) {}
record Address(String city) {}

EvoMap.writeObject(out, new Person(30, "Ada", new Address("London")));
Person p = EvoMap.readObject(in, Person.class);   // nested Address rebuilt automatically
```

Evolution: add `String email` to `Person` later → old data lacks the key →
`email` is null; new data carries it. Drop `age` → readers get 0.

## Scope (ponytail cuts, overall)

- No schema objects, no registry, no reflection in the codec, no codegen.
- No built-in compression — wrap in `GZIPOutputStream`/`GZIPInputStream` if wanted.
- No file header/magic.
- No polymorphism, no cyclic graphs, no `Class.forName`.
- Class 1 reserved but unimplemented until a real 1-byte type is needed.

## Testing

Test files (plain assertions / JUnit if the host project uses it):

**Codec tests:**

1. Round-trip every supported type (all 8 primitives, String, byte[], null).
2. Round-trip nested structures (Map of Lists of Maps).
3. Round-trip null in every position (top-level, list element, map key, map value).
4. **Skip-unknown-type:** hand-craft a stream containing a value with an
   unrecognized type id in each size class; assert `read` returns `Unknown`,
   consumes exactly the right bytes, and the following value parses correctly —
   including when the unknown value is nested inside a Map (no pair desync).
5. Multiple top-level values read in a loop until `EOFException`.
6. Varint boundary values (0, 1, -1, Long.MIN/MAX, ints near 2^7 / 2^14 sizes).

**Mapper tests:**

7. Round-trip a record and a POJO (each with several primitive + String fields).
8. Round-trip nested: record-in-record, POJO-in-POJO, `List<UserType>`,
   `Map<String,UserType>`.
9. Round-trip an enum field.
10. Evolution: read old-shape data (missing a field) into a new class → default;
    read new-shape data (extra field) into an old class → ignored.
11. Null nested object field round-trips as null.

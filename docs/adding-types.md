# Adding a New Type

How to add a value type to the `evo` format without breaking existing readers.
The format is built for this: a new type costs a few small edits and old readers
keep working. This guide walks the full process with a worked example
(`OffsetDateTime`).

Read `format-spec.md` first for the tag/size-class layout.

---

## The three rules (do not violate these)

Every guarantee depends on them:

1. **Type ids are append-only.** Pick an *unused* id. Never renumber, reuse, or
   repurpose an existing id — that silently corrupts old data.
2. **Encodings are frozen.** Once a type ships, never change how its id is
   encoded. Need a different encoding? Add a *new* id.
3. **Fit a size-class skip discipline.** A new type must be skippable by the
   generic rule of one of the size classes (below). This is what lets an old
   reader step over a value it has never heard of.

Break rule 1 or 2 and you break backward compatibility. Break rule 3 and you
break forward compatibility.

---

## Step 1 — Choose a size class

The size class (top 3 bits of the tag) decides how an unknowing reader skips the
value. Pick the class whose shape matches your payload:

| If your value is…                                   | Use class | Skip rule (automatic)          |
|-----------------------------------------------------|:---------:|--------------------------------|
| a flag with no payload                              | 0 empty   | nothing                        |
| exactly 1 / 2 / 4 / 8 fixed bytes                   | 1/2/3/4   | skip that many bytes           |
| a single signed/unsigned integer                    | 5 varint  | read one varint                |
| an opaque blob or composite of known length         | 6 bytes   | read varint len, skip len      |
| a container of nested `evo` values                  | 7 values  | read varint count, skip values |

Composite types (several numbers packed together, like a datetime) fit **class 6**:
encode the parts into a byte blob and length-prefix it. The length prefix is
exactly what class 6's skip rule needs.

> **Class 7 caveat:** the skip count is the number of nested *values*. `MAP`
> stores an *entry* count (2× values) and is a grandfathered exception — do not
> copy its framing. A new class-7 type must prefix the exact value count.

---

## Step 2 — Pick an unused type id

The id is the low 5 bits (0–31) within the class. List what each class already
uses and take the next free number.

Class 6 today: `STRING = 0xC0` (id 0), `BYTES = 0xC1` (id 1). Next free is id 2 →
tag `0xC2`. So:

```java
static final int DATETIME = 0xC2;   // class 6 (bytes), type id 2
```

Tag math: `(6 << 5) | 2 = 0xC2`.

---

## Skip-contract test (required for every new type)

Add your new type's representative value to `EvoTest#testSkipContractConformance`'s
`conforming` array. That test forces an ignorant (size-class-only) skip and
asserts it consumes exactly the right bytes. If your type's framing does not
match its size class's skip rule, the test fails — which is the point: only MAP
is a grandfathered exception (it frames an entry count). See ADR-0006.

---

## Step 3 — Add write + read to `Evo.java`

Add the constant, a `write` branch (before the final `throw`), and a `read`
case (before `default:`). Example for `OffsetDateTime` — instant (epoch-second +
nano) plus timezone offset (seconds), packed as three varints in a
length-prefixed blob:

```java
static final int DATETIME = 0xC2;

// --- in write(), before the throw ---
if (v instanceof java.time.OffsetDateTime dt) {
    out.write(DATETIME);
    var inner = new ByteArrayOutputStream();
    writeVarint(inner, zig(dt.toEpochSecond()));               // signed -> zigzag
    writeVarint(inner, dt.getNano());                          // 0..1e9, unsigned
    writeVarint(inner, zig(dt.getOffset().getTotalSeconds())); // signed -> zigzag
    byte[] b = inner.toByteArray();
    writeVarint(out, b.length);   // length prefix => class-6 skip works
    out.write(b);
    return;
}

// --- in read(), before default: ---
case DATETIME: {
    byte[] b = readN(in, readLen(in));                 // readLen bounds the length
    var in2 = new ByteArrayInputStream(b);
    long sec = unzig(readVarint(in2));
    int  nano = (int) readVarint(in2);
    int  off  = (int) unzig(readVarint(in2));
    return java.time.OffsetDateTime.ofInstant(
        java.time.Instant.ofEpochSecond(sec, nano),
        java.time.ZoneOffset.ofTotalSeconds(off));
}
```

Wire layout: `C2 | <varint len> | <zigzag sec> <varint nano> <zigzag off>`.

Use `readLen` (not a raw `(int) readVarint`) for the length so a malformed
stream throws a clean `IOException` instead of crashing.

---

## Step 4 — Add to `EvoMap.isLeaf` (only if used as an object field)

`EvoMap` reflects over record/POJO fields. Any field type it does not recognize
as a "leaf" gets treated as a nested record/POJO and reflected into a map —
wrong for a type the codec handles directly. Add your type to `isLeaf` so the
mapper passes it straight to `Evo.write`:

```java
static boolean isLeaf(Class<?> c) {
    return c.isPrimitive()
        || c == Boolean.class || c == Character.class || c == Byte.class || c == Short.class
        || c == Integer.class || c == Long.class || c == Float.class || c == Double.class
        || c == String.class || c == byte[].class
        || c == java.time.OffsetDateTime.class;   // <-- new leaf
    }
```

Skip this step if the type is only ever written at the top level via `Evo.write`,
never as a record/POJO field.

---

## Step 5 — Test both directions

Add JUnit tests proving round-trip **and** that old readers skip it.

```java
@Test
void datetimeRoundTrip() throws IOException {
    var now = java.time.OffsetDateTime.of(
        2026, 8, 4, 12, 30, 0, 123_000_000, java.time.ZoneOffset.ofHours(2));
    var b = new ByteArrayOutputStream();
    Evo.write(b, now);
    Object r = Evo.read(new ByteArrayInputStream(b.toByteArray()));
    assertTrue(now.equals(r), "datetime round-trips exactly (instant + offset)");
}

@Test
void oldReaderSkipsDatetime() throws IOException {
    // Simulate an OLD reader: it only knows the size class, not tag 0xC2.
    // A real old build returns Unknown; here we assert the byte accounting by
    // reading a known value written right after the datetime.
    var b = new ByteArrayOutputStream();
    Evo.write(b, java.time.OffsetDateTime.now());
    Evo.write(b, 99);                       // follow-up value
    var in = new ByteArrayInputStream(b.toByteArray());
    Evo.read(in);                           // consume the datetime
    assertTrue(Evo.read(in).equals(99), "stream stays in sync across a datetime");
}
```

For a genuine forward-compat check (reader literally does not know `0xC2`),
hand-craft the bytes with a made-up class-6 tag and assert `read` returns
`Evo.Unknown` and the next value still parses — see the existing
`testUnknownSkip` in `EvoTest`.

---

## Step 6 — Document it

- Add the new tag to the type table in `format-spec.md`.
- If the type touches the compatibility story, note it in the design spec.

---

## What this buys you (compatibility)

| Scenario                          | Result                                                        |
|-----------------------------------|---------------------------------------------------------------|
| New reader, old data              | Fine — you only added an id; existing types untouched.        |
| **Old reader, new data**          | Meets `0xC2`, doesn't know it, but `0xC2 >> 5 == 6` → class-6 skip → returns `Unknown(0xC2, raw)` and **keeps reading in sync**, even nested in a List/Map. No crash, no recompile. |
| Old reader needs the actual value | Not possible without upgrading — it gets `Unknown`, not the datetime. Forward compat means *survival*, not *understanding*. |

---

## Checklist

- [ ] Chose a size class whose skip rule fits the payload.
- [ ] Chose an **unused** type id; did not touch any existing id.
- [ ] Added constant + `write` branch + `read` case in `Evo.java`.
- [ ] Used `readLen` for any length prefix.
- [ ] Added to `EvoMap.isLeaf` if it can be an object field.
- [ ] Wrote round-trip and old-reader-skip tests.
- [ ] Updated `format-spec.md` (and the design spec if relevant).

# Binary Data Format Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A zero-dependency, self-describing binary serialization format for Java with schema evolution, plus a reflection mapper for user records/POJOs.

**Architecture:** Two files. `Evo.java` is a CBOR-style codec over the primitive data model (primitives, String, byte[], List, Map, null) — every value is `[tag][payload]`, the tag's top 3 bits are a size class that makes unknown future types skippable. `EvoMap.java` sits on top, mapping user records/POJOs to name-keyed `Map`s and back via reflection.

**Tech Stack:** Plain Java 17 (records require 16+; 17 is LTS). No build tool, no test framework — tests are `main`-method classes run with `java -ea`. Zero external dependencies (hard requirement: this vendors into JaCoCo).

## Global Constraints

- **Java 17+.** Uses records, `record` patterns, `instanceof` patterns, `var`.
- **Zero dependencies.** No JUnit, no Guava, nothing. Only `java.*`.
- **Two production files only:** `src/main/java/evo/Evo.java`, `src/main/java/evo/EvoMap.java`.
- **Package `evo`** for now; final package chosen at JaCoCo vendoring time (out of scope here).
- **Helpers are package-private** (not `private`) so same-package tests can call them.
- **Type ids are frozen once shipped** — never renumber/reuse (forward/backward compat depends on it).
- **Wire tag = `(sizeClass << 5) | typeId`.** Size class = `(tag >> 5) & 7`, type id = `tag & 0x1F`.

### Frozen tag table (do not change once shipped)

| Constant | Value | Class | Payload |
|---|---|---|---|
| `NULL`   | `0x00` | 0 empty | none |
| `FALSE`  | `0x01` | 0 empty | none |
| `TRUE`   | `0x02` | 0 empty | none |
| `CHAR`   | `0x40` | 2 fixed2 | 2 bytes BE |
| `FLOAT`  | `0x60` | 3 fixed4 | 4 bytes BE |
| `DOUBLE` | `0x80` | 4 fixed8 | 8 bytes BE |
| `BYTE`   | `0xA0` | 5 varint | zigzag varint |
| `SHORT`  | `0xA1` | 5 varint | zigzag varint |
| `INT`    | `0xA2` | 5 varint | zigzag varint |
| `LONG`   | `0xA3` | 5 varint | zigzag varint |
| `STRING` | `0xC0` | 6 bytes | varint len + UTF-8 |
| `BYTES`  | `0xC1` | 6 bytes | varint len + raw |
| `LIST`   | `0xE0` | 7 values | varint count + values |
| `MAP`    | `0xE1` | 7 values | varint count + key,value pairs |

Size class 1 (`fixed1`) is reserved/unused.

### Build & test commands

```bash
# compile everything
javac -d out src/main/java/evo/*.java src/test/java/evo/*.java
# run a test class (asserts enabled)
java -ea -cp out evo.EvoTest
java -ea -cp out evo.EvoMapTest
```

---

## File Structure

- `src/main/java/evo/Evo.java` — codec. Public `write`/`read`, nested `Unknown` record, package-private helpers (varint, zigzag, fixed-width IO, skip).
- `src/main/java/evo/EvoMap.java` — reflection mapper. Public `writeObject`/`readObject`, package-private `toValue`/`fromValue` + reflection helpers.
- `src/test/java/evo/EvoTest.java` — codec tests, `main` + `check` helper.
- `src/test/java/evo/EvoMapTest.java` — mapper tests, `main` + `check` helper.

---

## Task 1: Codec skeleton + varint/zigzag/fixed-width helpers

**Files:**
- Create: `src/main/java/evo/Evo.java`
- Test: `src/test/java/evo/EvoTest.java`

**Interfaces:**
- Produces (package-private, for later tasks + tests):
  - `static void writeVarint(OutputStream o, long v)` — unsigned LEB128
  - `static long readVarint(InputStream in)`
  - `static long zig(long v)` / `static long unzig(long u)` — zigzag
  - `static int read1(InputStream in)` — one byte, `EOFException` at end
  - `static byte[] readN(InputStream in, int n)` — fully read n bytes
  - `static void writeInt32(OutputStream o, int v)` / `static int readInt32(InputStream in)` — big-endian
  - `static void writeInt64(OutputStream o, long v)` / `static long readInt64(InputStream in)` — big-endian

- [ ] **Step 1: Write the failing test**

Create `src/test/java/evo/EvoTest.java`:

```java
package evo;

import java.io.*;
import java.util.*;

public class EvoTest {
    static int checks = 0;
    static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
        checks++;
    }

    static byte[] toBytes(long... vals) throws IOException {
        var b = new ByteArrayOutputStream();
        for (long v : vals) Evo.writeVarint(b, v);
        return b.toByteArray();
    }

    static void testVarint() throws IOException {
        long[] samples = {0, 1, 2, 127, 128, 300, 16384, Long.MAX_VALUE, -1L};
        var b = new ByteArrayOutputStream();
        for (long v : samples) Evo.writeVarint(b, v);
        var in = new ByteArrayInputStream(b.toByteArray());
        for (long v : samples) check(Evo.readVarint(in) == v, "varint " + v);
    }

    static void testZigzag() {
        long[] samples = {0, -1, 1, -2, 2, Long.MIN_VALUE, Long.MAX_VALUE};
        for (long v : samples) check(Evo.unzig(Evo.zig(v)) == v, "zigzag " + v);
        // small magnitudes must stay small after zigzag
        check(Evo.zig(-1) == 1, "zig(-1)==1");
        check(Evo.zig(1) == 2, "zig(1)==2");
    }

    static void testFixed() throws IOException {
        var b = new ByteArrayOutputStream();
        Evo.writeInt32(b, 0x01020304);
        Evo.writeInt64(b, 0x0102030405060708L);
        var in = new ByteArrayInputStream(b.toByteArray());
        check(Evo.readInt32(in) == 0x01020304, "int32");
        check(Evo.readInt64(in) == 0x0102030405060708L, "int64");
    }

    public static void main(String[] args) throws Exception {
        testVarint();
        testZigzag();
        testFixed();
        System.out.println("EvoTest OK (" + checks + " checks)");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
javac -d out src/main/java/evo/*.java src/test/java/evo/*.java 2>&1 | head
```
Expected: FAIL — `error: cannot find symbol ... Evo` / `package evo does not exist` (no `Evo.java` yet).

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/evo/Evo.java`:

```java
package evo;

import java.io.*;

/** Zero-dependency self-describing binary codec. See design spec. */
public final class Evo {
    private Evo() {}

    // ---- varint (unsigned LEB128) ----
    static void writeVarint(OutputStream o, long v) throws IOException {
        while ((v & ~0x7FL) != 0) {
            o.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        o.write((int) (v & 0x7F));
    }

    static long readVarint(InputStream in) throws IOException {
        long r = 0;
        int shift = 0, b;
        do {
            b = read1(in);
            r |= (long) (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return r;
    }

    // ---- zigzag ----
    static long zig(long v)   { return (v << 1) ^ (v >> 63); }
    static long unzig(long u) { return (u >>> 1) ^ -(u & 1); }

    // ---- byte-level IO ----
    static int read1(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException();
        return b;
    }

    static byte[] readN(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) throw new EOFException();
            off += r;
        }
        return b;
    }

    static void writeInt32(OutputStream o, int v) throws IOException {
        o.write(v >>> 24); o.write(v >>> 16); o.write(v >>> 8); o.write(v);
    }
    static int readInt32(InputStream in) throws IOException {
        return (read1(in) << 24) | (read1(in) << 16) | (read1(in) << 8) | read1(in);
    }
    static void writeInt64(OutputStream o, long v) throws IOException {
        for (int s = 56; s >= 0; s -= 8) o.write((int) (v >>> s));
    }
    static long readInt64(InputStream in) throws IOException {
        long r = 0;
        for (int i = 0; i < 8; i++) r = (r << 8) | read1(in);
        return r;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
javac -d out src/main/java/evo/*.java src/test/java/evo/*.java && java -ea -cp out evo.EvoTest
```
Expected: PASS — `EvoTest OK (N checks)`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/evo/Evo.java src/test/java/evo/EvoTest.java
git commit -m "feat: codec varint/zigzag/fixed-width helpers"
```

---

## Task 2: Scalar write/read (null, boolean, char, byte, short, int, long, float, double)

**Files:**
- Modify: `src/main/java/evo/Evo.java` (add tag constants + `write`/`read`)
- Test: `src/test/java/evo/EvoTest.java` (add `testScalars`)

**Interfaces:**
- Produces: `static void write(OutputStream out, Object v)`, `static Object read(InputStream in)`, nested `record Unknown(int tag, byte[] raw)`.
- Consumes: helpers from Task 1.

- [ ] **Step 1: Write the failing test**

Add to `EvoTest.java` and call from `main` (before the print line):

```java
    static Object roundtrip(Object v) throws IOException {
        var b = new ByteArrayOutputStream();
        Evo.write(b, v);
        return Evo.read(new ByteArrayInputStream(b.toByteArray()));
    }

    static void testScalars() throws IOException {
        check(roundtrip(null) == null, "null");
        check(roundtrip(true).equals(true), "true");
        check(roundtrip(false).equals(false), "false");
        check(roundtrip('Z').equals('Z'), "char");
        check(roundtrip('€').equals('€'), "char unicode"); // euro sign
        // exact type fidelity
        Object bv = roundtrip((byte) -7);
        check(bv instanceof Byte && bv.equals((byte) -7), "byte type+val");
        Object sv = roundtrip((short) 30000);
        check(sv instanceof Short && sv.equals((short) 30000), "short type+val");
        Object iv = roundtrip(123456);
        check(iv instanceof Integer && iv.equals(123456), "int type+val");
        Object lv = roundtrip(Long.MIN_VALUE);
        check(lv instanceof Long && lv.equals(Long.MIN_VALUE), "long type+val");
        Object fv = roundtrip(3.5f);
        check(fv instanceof Float && fv.equals(3.5f), "float type+val");
        Object dv = roundtrip(-2.25d);
        check(dv instanceof Double && dv.equals(-2.25d), "double type+val");
    }
```

Add `testScalars();` to `main`.

- [ ] **Step 2: Run test to verify it fails**

```bash
javac -d out src/main/java/evo/*.java src/test/java/evo/*.java 2>&1 | head
```
Expected: FAIL — `cannot find symbol: method write(...)`.

- [ ] **Step 3: Write minimal implementation**

Add tag constants at the top of the `Evo` class body (after `private Evo() {}`):

```java
    // tags: (sizeClass << 5) | typeId  — FROZEN, never renumber
    static final int NULL = 0x00, FALSE = 0x01, TRUE = 0x02;   // class 0 empty
    static final int CHAR = 0x40;                              // class 2 fixed2
    static final int FLOAT = 0x60;                            // class 3 fixed4
    static final int DOUBLE = 0x80;                           // class 4 fixed8
    static final int BYTE = 0xA0, SHORT = 0xA1, INT = 0xA2, LONG = 0xA3; // class 5 varint
    static final int STRING = 0xC0, BYTES = 0xC1;            // class 6 bytes
    static final int LIST = 0xE0, MAP = 0xE1;               // class 7 values

    /** Returned by read() for a tag whose type id is unknown to this reader. */
    public record Unknown(int tag, byte[] raw) {}
```

Add `write` and `read` (scalar cases only for now; String/bytes/List/Map added in later tasks):

```java
    public static void write(OutputStream out, Object v) throws IOException {
        if (v == null)               { out.write(NULL); return; }
        if (v instanceof Boolean b)  { out.write(b ? TRUE : FALSE); return; }
        if (v instanceof Character c){ out.write(CHAR); out.write(c >>> 8); out.write(c & 0xFF); return; }
        if (v instanceof Byte b)     { out.write(BYTE);  writeVarint(out, zig(b)); return; }
        if (v instanceof Short s)    { out.write(SHORT); writeVarint(out, zig(s)); return; }
        if (v instanceof Integer i)  { out.write(INT);   writeVarint(out, zig(i)); return; }
        if (v instanceof Long l)     { out.write(LONG);  writeVarint(out, zig(l)); return; }
        if (v instanceof Float f)    { out.write(FLOAT);  writeInt32(out, Float.floatToIntBits(f)); return; }
        if (v instanceof Double d)   { out.write(DOUBLE); writeInt64(out, Double.doubleToLongBits(d)); return; }
        throw new IllegalArgumentException("unsupported: " + v.getClass());
    }

    public static Object read(InputStream in) throws IOException {
        int tag = in.read();
        if (tag < 0) throw new EOFException();
        switch (tag) {
            case NULL:   return null;
            case FALSE:  return Boolean.FALSE;
            case TRUE:   return Boolean.TRUE;
            case CHAR:   return (char) ((read1(in) << 8) | read1(in));
            case BYTE:   return (byte)  unzig(readVarint(in));
            case SHORT:  return (short) unzig(readVarint(in));
            case INT:    return (int)   unzig(readVarint(in));
            case LONG:   return         unzig(readVarint(in));
            case FLOAT:  return Float.intBitsToFloat(readInt32(in));
            case DOUBLE: return Double.longBitsToDouble(readInt64(in));
            default:     return skipUnknown(in, tag);
        }
    }
```

Add a minimal `skipUnknown` (full version lands in Task 5; this keeps `read` compiling and correct for known-class skips):

```java
    static Unknown skipUnknown(InputStream in, int tag) throws IOException {
        int cls = (tag >> 5) & 7;
        var raw = new ByteArrayOutputStream();
        switch (cls) {
            case 0: break;
            case 1: raw.writeBytes(readN(in, 1)); break;
            case 2: raw.writeBytes(readN(in, 2)); break;
            case 3: raw.writeBytes(readN(in, 4)); break;
            case 4: raw.writeBytes(readN(in, 8)); break;
            case 5: { int x; do { x = read1(in); raw.write(x); } while ((x & 0x80) != 0); } break;
            case 6: { long n = readVarint(in); raw.writeBytes(readN(in, (int) n)); } break;
            case 7: { long n = readVarint(in); for (long i = 0; i < n; i++) read(in); } break;
            default: throw new IOException("bad size class " + cls);
        }
        return new Unknown(tag, raw.toByteArray());
    }
```

- [ ] **Step 4: Run test to verify it passes**

```bash
javac -d out src/main/java/evo/*.java src/test/java/evo/*.java && java -ea -cp out evo.EvoTest
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/evo/Evo.java src/test/java/evo/EvoTest.java
git commit -m "feat: codec scalar types with exact-type fidelity"
```

---

## Task 3: String and byte[]

**Files:**
- Modify: `src/main/java/evo/Evo.java` (`write`/`read`)
- Test: `src/test/java/evo/EvoTest.java` (add `testStringBytes`)

**Interfaces:**
- Consumes: `write`/`read` from Task 2.

- [ ] **Step 1: Write the failing test**

Add to `EvoTest.java`, call from `main`:

```java
    static void testStringBytes() throws IOException {
        check(roundtrip("").equals(""), "empty string");
        check(roundtrip("hello").equals("hello"), "ascii string");
        check(roundtrip("héllo €").equals("héllo €"), "utf8 string");
        byte[] raw = {1, 2, 3, -1, 0, 127};
        Object rb = roundtrip(raw);
        check(rb instanceof byte[] && java.util.Arrays.equals((byte[]) rb, raw), "byte[]");
        check(java.util.Arrays.equals((byte[]) roundtrip(new byte[0]), new byte[0]), "empty byte[]");
    }
```

Add `testStringBytes();` to `main`.

- [ ] **Step 2: Run test to verify it fails**

```bash
javac -d out src/main/java/evo/*.java src/test/java/evo/*.java && java -ea -cp out evo.EvoTest 2>&1 | head
```
Expected: FAIL — `IllegalArgumentException: unsupported: class java.lang.String`.

- [ ] **Step 3: Write minimal implementation**

Add the `import` at top of `Evo.java`:

```java
import java.nio.charset.StandardCharsets;
```

In `write`, before the `throw`:

```java
        if (v instanceof String s) {
            out.write(STRING);
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            writeVarint(out, b.length);
            out.write(b);
            return;
        }
        if (v instanceof byte[] b) {
            out.write(BYTES);
            writeVarint(out, b.length);
            out.write(b);
            return;
        }
```

In `read`, add cases before `default`:

```java
            case STRING: return new String(readN(in, (int) readVarint(in)), StandardCharsets.UTF_8);
            case BYTES:  return readN(in, (int) readVarint(in));
```

- [ ] **Step 4: Run test to verify it passes**

```bash
javac -d out src/main/java/evo/*.java src/test/java/evo/*.java && java -ea -cp out evo.EvoTest
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/evo/Evo.java src/test/java/evo/EvoTest.java
git commit -m "feat: codec String and byte[]"
```

---

## Task 4: List and Map (recursive)

**Files:**
- Modify: `src/main/java/evo/Evo.java` (`write`/`read`)
- Test: `src/test/java/evo/EvoTest.java` (add `testCollections`)

**Interfaces:**
- Consumes: `write`/`read`.

- [ ] **Step 1: Write the failing test**

Add to `EvoTest.java`, call from `main`. Note the `import java.util.*;` at top of the test file if not present.

```java
    @SuppressWarnings("unchecked")
    static void testCollections() throws IOException {
        var list = java.util.List.of(1, "two", 3.0, java.util.List.of((byte) 4));
        Object rl = roundtrip(list);
        check(rl.equals(list), "nested list");

        var map = new java.util.LinkedHashMap<Object, Object>();
        map.put("a", 1);
        map.put("b", java.util.List.of("x", "y"));
        map.put(null, "nullkey");           // null key survives
        map.put("c", null);                 // null value survives
        Object rm = roundtrip(map);
        check(rm instanceof java.util.Map, "map type");
        var m2 = (java.util.Map<Object, Object>) rm;
        check(m2.get("a").equals(1), "map a");
        check(m2.get("b").equals(java.util.List.of("x", "y")), "map b nested");
        check(m2.get(null).equals("nullkey"), "map null key");
        check(m2.containsKey("c") && m2.get("c") == null, "map null value");
    }
```

Add `testCollections();` to `main`.

- [ ] **Step 2: Run test to verify it fails**

```bash
javac -d out src/main/java/evo/*.java src/test/java/evo/*.java && java -ea -cp out evo.EvoTest 2>&1 | head
```
Expected: FAIL — `unsupported: ...ImmutableCollections$ListN`.

- [ ] **Step 3: Write minimal implementation**

Add `import java.util.*;` to `Evo.java`. In `write`, before the `throw`:

```java
        if (v instanceof List<?> list) {
            out.write(LIST);
            writeVarint(out, list.size());
            for (Object e : list) write(out, e);
            return;
        }
        if (v instanceof Map<?, ?> map) {
            out.write(MAP);
            writeVarint(out, map.size());
            for (Map.Entry<?, ?> e : map.entrySet()) {
                write(out, e.getKey());
                write(out, e.getValue());
            }
            return;
        }
```

In `read`, add cases before `default`:

```java
            case LIST: {
                int n = (int) readVarint(in);
                var l = new ArrayList<Object>(n);
                for (int i = 0; i < n; i++) l.add(read(in));
                return l;
            }
            case MAP: {
                int n = (int) readVarint(in);
                var m = new LinkedHashMap<Object, Object>();
                for (int i = 0; i < n; i++) {
                    Object k = read(in);
                    Object val = read(in);
                    m.put(k, val);
                }
                return m;
            }
```

- [ ] **Step 4: Run test to verify it passes**

```bash
javac -d out src/main/java/evo/*.java src/test/java/evo/*.java && java -ea -cp out evo.EvoTest
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/evo/Evo.java src/test/java/evo/EvoTest.java
git commit -m "feat: codec List and Map"
```

---

## Task 5: Forward-compat — skip unknown types + multi-value framing

**Files:**
- Test: `src/test/java/evo/EvoTest.java` (add `testUnknownSkip`, `testFraming`)
- (`skipUnknown` already implemented in Task 2 — this task verifies it, no production change expected. If a bug surfaces, fix in `Evo.java`.)

**Interfaces:**
- Consumes: `write`/`read`, `Unknown`, tag constants.

- [ ] **Step 1: Write the failing test**

Add to `EvoTest.java`, call from `main`. These craft raw streams containing a made-up unknown type id in each size class, then a known value after it, and assert the reader skips correctly (stays in sync) and returns `Unknown`.

```java
    // build a stream: unknown value with given tag+payload, then a known INT(99)
    static ByteArrayInputStream unknownThen(int tag, byte[] payload) throws IOException {
        var b = new ByteArrayOutputStream();
        b.write(tag);
        b.writeBytes(payload);
        Evo.write(b, 99);   // known follow-up value
        return new ByteArrayInputStream(b.toByteArray());
    }

    static byte[] varintBytes(long v) throws IOException {
        var b = new ByteArrayOutputStream();
        Evo.writeVarint(b, v);
        return b.toByteArray();
    }

    static void testUnknownSkip() throws IOException {
        // class 0 empty, unused id 0x1F
        checkSkip(0x1F, new byte[0]);
        // class 1 fixed1, id 0 -> tag 0x20, 1 payload byte
        checkSkip(0x20, new byte[]{7});
        // class 4 fixed8, unused id 0x1F -> tag 0x9F, 8 bytes
        checkSkip(0x9F, new byte[]{1,2,3,4,5,6,7,8});
        // class 5 varint, unused id 0x1F -> tag 0xBF
        checkSkip(0xBF, varintBytes(123456));
        // class 6 bytes, unused id 0x1F -> tag 0xDF, len-prefixed
        var p = new ByteArrayOutputStream();
        p.writeBytes(varintBytes(3)); p.writeBytes(new byte[]{9,9,9});
        checkSkip(0xDF, p.toByteArray());
    }

    static void checkSkip(int tag, byte[] payload) throws IOException {
        var in = unknownThen(tag, payload);
        Object u = Evo.read(in);
        check(u instanceof Evo.Unknown && ((Evo.Unknown) u).tag() == tag, "unknown tag " + tag);
        check(Evo.read(in).equals(99), "stream in sync after unknown " + tag);
    }

    @SuppressWarnings("unchecked")
    static void testUnknownNestedInMap() throws IOException {
        // a MAP with one entry: key "k", value = unknown (class 6, tag 0xDF).
        var b = new ByteArrayOutputStream();
        b.write(Evo.MAP);
        Evo.writeVarint(b, 1);          // 1 entry
        Evo.write(b, "k");              // key
        b.write(0xDF);                  // unknown value tag (class 6)
        b.writeBytes(varintBytes(2)); b.writeBytes(new byte[]{5,6});
        Evo.write(b, "after");          // next top-level value
        var in = new ByteArrayInputStream(b.toByteArray());
        var m = (Map<Object,Object>) Evo.read(in);
        check(m.get("k") instanceof Evo.Unknown, "unknown as map value, no desync");
        check(Evo.read(in).equals("after"), "top-level in sync after map with unknown");
    }

    static void testFraming() throws IOException {
        var b = new ByteArrayOutputStream();
        Evo.write(b, 1);
        Evo.write(b, "two");
        Evo.write(b, java.util.List.of(3));
        var in = new ByteArrayInputStream(b.toByteArray());
        check(Evo.read(in).equals(1), "frame 1");
        check(Evo.read(in).equals("two"), "frame 2");
        check(Evo.read(in).equals(java.util.List.of(3)), "frame 3");
        boolean eof = false;
        try { Evo.read(in); } catch (EOFException e) { eof = true; }
        check(eof, "EOFException at end of stream");
    }
```

Add `testUnknownSkip(); testUnknownNestedInMap(); testFraming();` to `main`.

- [ ] **Step 2: Run test to verify it fails, then passes**

```bash
javac -d out src/main/java/evo/*.java src/test/java/evo/*.java && java -ea -cp out evo.EvoTest
```
Expected: PASS (skipUnknown was implemented in Task 2). If any check fails, fix `skipUnknown` in `Evo.java` until green. This task's value is the regression coverage.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/evo/EvoTest.java src/main/java/evo/Evo.java
git commit -m "test: forward-compat skip-unknown + framing coverage"
```

---

## Task 6: Mapper — leaves, records, enums

**Files:**
- Create: `src/main/java/evo/EvoMap.java`
- Test: `src/test/java/evo/EvoMapTest.java`

**Interfaces:**
- Consumes: `Evo.write`, `Evo.read`.
- Produces:
  - `static void writeObject(OutputStream out, Object obj)`
  - `static <T> T readObject(InputStream in, Class<T> type)`
  - package-private `static Object toValue(Object o)`, `static Object fromValue(Object v, java.lang.reflect.Type t)`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/evo/EvoMapTest.java`:

```java
package evo;

import java.io.*;
import java.util.*;

public class EvoMapTest {
    static int checks = 0;
    static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
        checks++;
    }

    enum Color { RED, GREEN, BLUE }

    record Address(String city, int zip) {}
    record Person(int age, String name, Color color, Address address) {}

    static <T> T roundtrip(Object o, Class<T> type) throws IOException {
        var b = new ByteArrayOutputStream();
        EvoMap.writeObject(b, o);
        return EvoMap.readObject(new ByteArrayInputStream(b.toByteArray()), type);
    }

    static void testScalarPassthrough() throws IOException {
        check(roundtrip("hi", String.class).equals("hi"), "scalar string");
        check(roundtrip(42, Integer.class).equals(42), "scalar int");
    }

    static void testEnum() throws IOException {
        check(roundtrip(Color.GREEN, Color.class) == Color.GREEN, "enum");
    }

    static void testRecord() throws IOException {
        var p = new Person(30, "Ada", Color.BLUE, new Address("London", 12345));
        Person r = roundtrip(p, Person.class);
        check(r.equals(p), "record deep equal");
        check(r.address().city().equals("London"), "nested record field");
    }

    public static void main(String[] args) throws Exception {
        testScalarPassthrough();
        testEnum();
        testRecord();
        System.out.println("EvoMapTest OK (" + checks + " checks)");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
javac -d out src/main/java/evo/*.java src/test/java/evo/*.java 2>&1 | head
```
Expected: FAIL — `cannot find symbol: variable EvoMap`.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/evo/EvoMap.java`:

```java
package evo;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Reflection mapper: user records/POJOs &lt;-&gt; codec value tree. Zero deps. */
public final class EvoMap {
    private EvoMap() {}

    public static void writeObject(OutputStream out, Object obj) throws IOException {
        Evo.write(out, toValue(obj));
    }

    public static <T> T readObject(InputStream in, Class<T> type) throws IOException {
        Object v = Evo.read(in);
        return type.cast(fromValue(v, type));
    }

    // ---- object -> codec value tree ----
    static Object toValue(Object o) {
        if (o == null) return null;
        Class<?> c = o.getClass();
        if (isLeaf(c)) return o;
        if (o instanceof Enum<?> e) return e.name();
        if (o instanceof List<?> l) {
            var out = new ArrayList<>(l.size());
            for (Object e : l) out.add(toValue(e));
            return out;
        }
        if (o instanceof Map<?, ?> mp) {
            var out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : mp.entrySet()) out.put(toValue(e.getKey()), toValue(e.getValue()));
            return out;
        }
        if (c.isRecord()) {
            var out = new LinkedHashMap<String, Object>();
            for (RecordComponent rc : c.getRecordComponents())
                out.put(rc.getName(), toValue(invoke(rc.getAccessor(), o)));
            return out;
        }
        // POJO: declared, non-static, non-transient fields
        var out = new LinkedHashMap<String, Object>();
        for (Field f : c.getDeclaredFields()) {
            int m = f.getModifiers();
            if (Modifier.isStatic(m) || Modifier.isTransient(m)) continue;
            f.setAccessible(true);
            out.put(f.getName(), toValue(get(f, o)));
        }
        return out;
    }

    // ---- codec value tree -> object of declared type t ----
    static Object fromValue(Object v, Type t) {
        if (v == null) return null;
        Class<?> raw = rawClass(t);
        if (raw == Object.class) return v;               // raw/wildcard generics: leave as codec value
        if (isLeaf(raw)) return v;
        if (raw.isEnum()) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object e = Enum.valueOf((Class) raw, (String) v);
            return e;
        }
        if (List.class.isAssignableFrom(raw)) {
            Type et = argOf(t, 0);
            var out = new ArrayList<>();
            for (Object e : (List<?>) v) out.add(fromValue(e, et));
            return out;
        }
        if (Map.class.isAssignableFrom(raw)) {
            Type kt = argOf(t, 0), vt = argOf(t, 1);
            var out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet())
                out.put(fromValue(e.getKey(), kt), fromValue(e.getValue(), vt));
            return out;
        }
        Map<?, ?> m = (Map<?, ?>) v;                     // record/POJO wire shape
        return raw.isRecord() ? buildRecord(raw, m) : buildPojo(raw, m);
    }

    private static Object buildRecord(Class<?> raw, Map<?, ?> m) {
        RecordComponent[] comps = raw.getRecordComponents();
        Class<?>[] types = new Class<?>[comps.length];
        Object[] args = new Object[comps.length];
        for (int i = 0; i < comps.length; i++) {
            types[i] = comps[i].getType();
            Object cv = m.get(comps[i].getName());
            Object fv = cv == null ? null : fromValue(cv, comps[i].getGenericType());
            args[i] = fv != null ? fv : defaultFor(types[i]);
        }
        try {
            Constructor<?> ctor = raw.getDeclaredConstructor(types);
            ctor.setAccessible(true);
            return ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot build record " + raw, e);
        }
    }

    private static Object buildPojo(Class<?> raw, Map<?, ?> m) {
        try {
            Constructor<?> ctor = raw.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object inst = ctor.newInstance();
            for (Field f : raw.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isTransient(mod)) continue;
                Object cv = m.get(f.getName());
                if (cv == null) continue;               // leave field default
                f.setAccessible(true);
                f.set(inst, fromValue(cv, f.getGenericType()));
            }
            return inst;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot build POJO " + raw + " (needs no-arg constructor)", e);
        }
    }

    // ---- helpers ----
    static boolean isLeaf(Class<?> c) {
        return c.isPrimitive()
            || c == Boolean.class || c == Character.class || c == Byte.class || c == Short.class
            || c == Integer.class || c == Long.class || c == Float.class || c == Double.class
            || c == String.class || c == byte[].class;
    }

    static Class<?> rawClass(Type t) {
        if (t instanceof Class<?> c) return c;
        if (t instanceof ParameterizedType p) return (Class<?>) p.getRawType();
        return Object.class;
    }

    static Type argOf(Type t, int i) {
        if (t instanceof ParameterizedType p) {
            Type[] a = p.getActualTypeArguments();
            if (i < a.length && a[i] instanceof Class || i < a.length && a[i] instanceof ParameterizedType)
                return a[i];
        }
        return Object.class;
    }

    static Object defaultFor(Class<?> t) {
        if (!t.isPrimitive()) return null;
        if (t == boolean.class) return false;
        if (t == char.class)    return '\0';
        if (t == byte.class)    return (byte) 0;
        if (t == short.class)   return (short) 0;
        if (t == int.class)     return 0;
        if (t == long.class)    return 0L;
        if (t == float.class)   return 0f;
        return 0d; // double
    }

    private static Object invoke(Method mth, Object o) {
        try { return mth.invoke(o); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    private static Object get(Field f, Object o) {
        try { return f.get(o); }
        catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
javac -d out src/main/java/evo/*.java src/test/java/evo/*.java && java -ea -cp out evo.EvoMapTest
```
Expected: PASS — `EvoMapTest OK (N checks)`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/evo/EvoMap.java src/test/java/evo/EvoMapTest.java
git commit -m "feat: reflection mapper for records, enums, scalars"
```

---

## Task 7: Mapper — POJOs and nested collections of user types

**Files:**
- Test: `src/test/java/evo/EvoMapTest.java` (add `testPojo`, `testNestedCollections`)
- (`EvoMap.java` already handles these — this task verifies. Fix only if red.)

**Interfaces:**
- Consumes: `EvoMap.writeObject`/`readObject`.

- [ ] **Step 1: Write the failing test**

Add to `EvoMapTest.java` — a mutable POJO with a no-arg constructor, and collections of user types. Add the class declarations as static nested classes:

```java
    static class Point {          // POJO: no-arg ctor, non-final fields
        int x;
        int y;
        String label;
        Point() {}
        Point(int x, int y, String label) { this.x = x; this.y = y; this.label = label; }
    }

    record Team(String name, List<Person> members, Map<String, Address> offices) {}

    static void testPojo() throws IOException {
        var p = new Point(3, 4, "corner");
        Point r = roundtrip(p, Point.class);
        check(r.x == 3 && r.y == 4 && "corner".equals(r.label), "pojo fields");
    }

    static void testNestedCollections() throws IOException {
        var team = new Team(
            "core",
            List.of(new Person(1, "A", Color.RED, new Address("NYC", 1)),
                    new Person(2, "B", Color.GREEN, new Address("LA", 2))),
            Map.of("hq", new Address("SF", 3)));
        Team r = roundtrip(team, Team.class);
        check(r.name().equals("core"), "team name");
        check(r.members().size() == 2, "list<record> size");
        check(r.members().get(0).name().equals("A"), "list<record> element field");
        check(r.offices().get("hq").city().equals("SF"), "map<string,record> value");
    }
```

Add `testPojo(); testNestedCollections();` to `main`.

- [ ] **Step 2: Run test to verify it passes**

```bash
javac -d out src/main/java/evo/*.java src/test/java/evo/*.java && java -ea -cp out evo.EvoMapTest
```
Expected: PASS. If red, fix `EvoMap.java` (likely `argOf` generic resolution or `buildPojo`).

- [ ] **Step 3: Commit**

```bash
git add src/test/java/evo/EvoMapTest.java src/main/java/evo/EvoMap.java
git commit -m "test: mapper POJOs and nested collections of user types"
```

---

## Task 8: Mapper — schema evolution (add/drop field, null nested)

**Files:**
- Test: `src/test/java/evo/EvoMapTest.java` (add `testEvolution`, `testNullNested`)
- (Verifies existing behavior. Fix only if red.)

**Interfaces:**
- Consumes: mapper + codec.

- [ ] **Step 1: Write the failing test**

Add to `EvoMapTest.java`. Simulate evolution by writing one shape and reading into a different-shaped record. Add nested static records:

```java
    record V1(int age, String name) {}
    record V2(int age, String name, String email) {}   // added field

    static void testEvolution() throws IOException {
        // OLD data (V1) read by NEW code (V2): missing 'email' -> null
        var b1 = new ByteArrayOutputStream();
        EvoMap.writeObject(b1, new V1(20, "Old"));
        V2 upgraded = EvoMap.readObject(new ByteArrayInputStream(b1.toByteArray()), V2.class);
        check(upgraded.age() == 20 && upgraded.name().equals("Old"), "v1->v2 kept fields");
        check(upgraded.email() == null, "v1->v2 missing field defaults null");

        // NEW data (V2) read by OLD code (V1): extra 'email' ignored
        var b2 = new ByteArrayOutputStream();
        EvoMap.writeObject(b2, new V2(21, "New", "x@y.z"));
        V1 downgraded = EvoMap.readObject(new ByteArrayInputStream(b2.toByteArray()), V1.class);
        check(downgraded.age() == 21 && downgraded.name().equals("New"), "v2->v1 extra field ignored");

        // missing primitive defaults to 0
        var b3 = new ByteArrayOutputStream();
        EvoMap.writeObject(b3, Map.of("name", "NoAge"));     // a map with only 'name'
        V1 partial = EvoMap.readObject(new ByteArrayInputStream(b3.toByteArray()), V1.class);
        check(partial.age() == 0 && partial.name().equals("NoAge"), "missing primitive -> 0");
    }

    static void testNullNested() throws IOException {
        var p = new Person(5, "NoAddr", Color.RED, null);   // null nested record
        Person r = roundtrip(p, Person.class);
        check(r.address() == null, "null nested object round-trips null");
    }
```

Add `testEvolution(); testNullNested();` to `main`.

- [ ] **Step 2: Run test to verify it passes**

```bash
javac -d out src/main/java/evo/*.java src/test/java/evo/*.java && java -ea -cp out evo.EvoMapTest
```
Expected: PASS. If red, fix `buildRecord`/`buildPojo` default handling in `EvoMap.java`.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/evo/EvoMapTest.java
git commit -m "test: mapper schema evolution add/drop field + null nested"
```

---

## Task 9: README + full test run

**Files:**
- Create: `README.md`

**Interfaces:** none.

- [ ] **Step 1: Run both test suites**

```bash
javac -d out src/main/java/evo/*.java src/test/java/evo/*.java && \
  java -ea -cp out evo.EvoTest && java -ea -cp out evo.EvoMapTest
```
Expected: both print `... OK (N checks)`.

- [ ] **Step 2: Write README**

Create `README.md`:

```markdown
# evo — tiny binary format for Java

Zero-dependency, self-describing binary serialization with schema evolution.
Two files: `Evo.java` (codec), `EvoMap.java` (reflection mapper).

## Codec

    Evo.write(out, anyValue);   // null, primitives, String, byte[], List, Map
    Object v = Evo.read(in);    // exact type back; loop until EOFException

## Objects

    EvoMap.writeObject(out, person);
    Person p = EvoMap.readObject(in, Person.class);

Records and POJOs (POJO needs a no-arg constructor, non-final fields). Nested
records/POJOs, `List<T>`, `Map<K,V>`, and enums are handled. Add/drop fields
freely — old readers skip unknown data, missing fields default.

See `docs/superpowers/specs/2026-08-04-binary-format-design.md` for the wire
format and compatibility guarantees.

## Build & test

    javac -d out src/main/java/evo/*.java src/test/java/evo/*.java
    java -ea -cp out evo.EvoTest
    java -ea -cp out evo.EvoMapTest
```

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: README"
```

---

## Self-Review Notes

- **Spec coverage:** codec scalars (T2), String/bytes (T3), List/Map (T4), Unknown+framing (T5), mapper records/enums/leaves (T6), POJOs+nested collections (T7), evolution+null-nested (T8). All spec sections mapped.
- **Compat guarantees:** forward (skip-unknown, nested-in-map) covered in T5; backward/add-drop in T8.
- **Type consistency:** `write`/`read` (Evo), `writeObject`/`readObject`/`toValue`/`fromValue` (EvoMap) used consistently across tasks. Tag constants are package-private and referenced by tests in T5.
- **Known limitations (documented, intentional):** no polymorphism, no cyclic graphs, declared-fields-only (no inherited POJO fields), raw/wildcard generics decode as plain codec values, POJOs need no-arg ctor + non-final fields.
```

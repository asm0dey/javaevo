# evo Format Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix five proven correctness/robustness defects in the evo codec and reflection mapper, each backed by an ADR and a runnable repro.

**Architecture:** Two shipped files change — `src/main/java/evo/Evo.java` (codec) and `src/main/java/evo/EvoMap.java` (reflection mapper). Tests go in the existing `EvoTest` (codec) and `EvoMapTest` (mapper) JUnit 5 suites. No new dependencies, no wire-format change (arrays reuse the existing LIST type).

**Tech Stack:** Java 16+ (records), JUnit 5 (`org.junit.jupiter`), Maven (`mvn test`). Zero runtime dependencies — JUnit is test-scope only.

## Global Constraints

- **Zero runtime dependencies.** `Evo` and `EvoMap` must not import anything outside the JDK. (Copied from `README.md` / design spec metric 3.)
- **Wire format frozen.** Type ids and encodings never change (design spec rule 1–2). Arrays map onto the existing LIST type; no new tag.
- **`byte[]` stays BYTES.** Never route `byte[]` through array-as-LIST handling — it would silently change its wire encoding (ADR-0005, mandatory carve-out).
- **Fail loud, never silent.** Prefer a clean exception over silent data loss (ADR-0002/0003/0008).
- **Test style:** JUnit 5, `import static org.junit.jupiter.api.Assertions.assertTrue;`, one-arg-plus-message `assertTrue(cond, "msg")`. Match the existing suites.
- **Reference ADRs:** `docs/adr/0004`–`0008`. Read the relevant ADR before its task.

---

### Task 1: Depth-nesting crash guard (ADR-0004)

Catch `StackOverflowError` from unbounded LIST/MAP recursion at the public `read` boundary and rethrow as `IOException`, so a corrupt/deep stream fails per the trusted-model promise (clean exception, not an `Error`).

**Files:**
- Modify: `src/main/java/evo/Evo.java` (rename recursive `read` body to `read0`; new public `read` wraps it; internal recursive call sites use `read0`)
- Test: `src/test/java/evo/EvoTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `public static Object read(InputStream in) throws IOException` (unchanged signature, now guarded); `static Object read0(InputStream in) throws IOException` (package-private recursive worker).

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/evo/EvoTest.java`:

```java
    @Test
    void testDeepNestingThrowsIOException() {
        // 100k nested LISTs (E0 01 = LIST, count 1), innermost NULL.
        var b = new ByteArrayOutputStream();
        for (int i = 0; i < 100_000; i++) { b.write(0xE0); b.write(0x01); }
        b.write(0x00);
        var in = new ByteArrayInputStream(b.toByteArray());
        boolean cleanIO = false;
        try { Evo.read(in); }
        catch (IOException e) { cleanIO = true; }
        catch (StackOverflowError e) { cleanIO = false; }
        assertTrue(cleanIO, "deep nesting must throw IOException, not StackOverflowError");
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=EvoTest#testDeepNestingThrowsIOException`
Expected: FAIL — a `StackOverflowError` escapes (not caught as `IOException`), so `cleanIO` stays false.

- [ ] **Step 3: Rename the recursive worker to `read0`**

In `src/main/java/evo/Evo.java`, change the method signature at the current `public static Object read(InputStream in)` to a package-private worker, and repoint its internal recursive calls. Concretely:

1. Change the declaration line from
   `public static Object read(InputStream in) throws IOException {`
   to
   `static Object read0(InputStream in) throws IOException {`
2. Inside that method's `LIST` case, change `l.add(read(in));` to `l.add(read0(in));`
3. Inside the `MAP` case, change both `Object k = read(in);` and `Object val = read(in);` to `read0(in)`.
4. In `skipUnknown`, the class-7 branch `for (long i = 0; i < n; i++) read(in);` becomes `read0(in)`.

- [ ] **Step 4: Add the public guarded `read`**

In `src/main/java/evo/Evo.java`, immediately above `read0`, add the public entry point. Keep the existing Javadoc on the public method:

```java
    /**
     * Read one value written by {@link #write}, returning the exact Java type
     * that was written. Loop until this throws {@link EOFException} at a clean
     * value boundary. A tag whose type id is unrecognized is skipped via its size
     * class and returned as an {@link Unknown} (forward compatibility).
     *
     * <p>A pathologically or maliciously deep nesting of LIST/MAP would exhaust
     * the JVM stack; that is caught here and surfaced as an {@link IOException}
     * ("nesting too deep") rather than a {@link StackOverflowError}, so callers'
     * {@code catch (IOException)} stays in control. See ADR-0004.
     *
     * @return the decoded value (may be {@code null}, or an {@link Unknown})
     * @throws EOFException at end of stream
     */
    public static Object read(InputStream in) throws IOException {
        try {
            return read0(in);
        } catch (StackOverflowError e) {
            throw new IOException("nesting too deep");
        }
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q test -Dtest=EvoTest#testDeepNestingThrowsIOException`
Expected: PASS — the overflow now surfaces as `IOException`.

- [ ] **Step 6: Run the full suite (no regressions)**

Run: `mvn -q test`
Expected: PASS — all existing `EvoTest`/`EvoMapTest` cases still green (the public `read` signature is unchanged).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/evo/Evo.java src/test/java/evo/EvoTest.java
git commit -m "fix: guard codec read against deep-nesting StackOverflowError (ADR-0004)"
```

---

### Task 2: Skip-contract conformance test (ADR-0006)

Enforce the frozen size-class skip rules with a test: an ignorant (size-class-only) skip must be byte-exact for every defined type **except** the single grandfathered exception, MAP. A future type framed against its size class trips this.

**Files:**
- Modify: `src/test/java/evo/EvoTest.java` (add the conformance test)
- Modify: `docs/adding-types.md` (add the maintainer convention: register new types in this test)

**Interfaces:**
- Consumes: `Evo.write(OutputStream, Object)`, `Evo.read(InputStream)`, `Evo.skipUnknown(InputStream, int)` (package-private, callable from package `evo`).
- Produces: nothing (test + doc only).

- [ ] **Step 1: Write the conformance test**

Add to `src/test/java/evo/EvoTest.java`:

```java
    @Test
    void testSkipContractConformance() throws IOException {
        // Every defined type: write [value][sentinel], force the ignorant
        // size-class-only skip, then the sentinel MUST still parse. If the skip
        // over/under-consumes, the stream desyncs and the sentinel check fails.
        String sentinel = "SENTINEL";
        Object[] conforming = {
            null, Boolean.FALSE, Boolean.TRUE, 'Z', 3.5f, 1.0d,
            (byte) 1, (short) 1, 1, 1L, "hi", new byte[]{1, 2, 3}, List.of(1, 2)
        };
        for (Object val : conforming) {
            var b = new ByteArrayOutputStream();
            Evo.write(b, val);
            Evo.write(b, sentinel);
            var in = new ByteArrayInputStream(b.toByteArray());
            int tag = in.read();
            Evo.skipUnknown(in, tag);              // ignorant skip
            Object next = Evo.read(in);
            assertTrue(sentinel.equals(next),
                "skip-contract desync for tag 0x" + Integer.toHexString(tag) + " (" + val + ")");
        }

        // MAP is the ONE grandfathered exception: it frames an entry count, so
        // the generic class-7 skip (count = values) under-consumes and desyncs.
        // Safe only because every real reader knows MAP natively. If this ever
        // starts conforming, MAP framing changed — update ADR-0006.
        var mb = new ByteArrayOutputStream();
        Evo.write(mb, Map.of("a", 1));
        Evo.write(mb, sentinel);
        var min = new ByteArrayInputStream(mb.toByteArray());
        int mtag = min.read();
        Evo.skipUnknown(min, mtag);
        Object mnext = Evo.read(min);
        assertTrue(!sentinel.equals(mnext),
            "MAP must be the sole skip-contract exception; if it now conforms, revisit ADR-0006");
    }
```

- [ ] **Step 2: Run test to verify it passes now**

Run: `mvn -q test -Dtest=EvoTest#testSkipContractConformance`
Expected: PASS against the current codec — this test is a *guard for the future*, not a bug fix, so it is green today.

- [ ] **Step 3: Prove the guard bites (temporary sanity check)**

Temporarily add a bogus entry `Map.of("a", 1)` to the `conforming` array (as if a maintainer wrongly declared an entry-count type as generically-skippable). Run:

Run: `mvn -q test -Dtest=EvoTest#testSkipContractConformance`
Expected: FAIL — the MAP value in the conforming list desyncs the sentinel, proving the test catches a non-conforming type. **Then remove the bogus entry** and re-run to confirm PASS.

- [ ] **Step 4: Document the maintainer convention**

In `docs/adding-types.md`, add a short subsection near the type-id rules:

```markdown
## Skip-contract test (required for every new type)

Add your new type's representative value to `EvoTest#testSkipContractConformance`'s
`conforming` array. That test forces an ignorant (size-class-only) skip and
asserts it consumes exactly the right bytes. If your type's framing does not
match its size class's skip rule, the test fails — which is the point: only MAP
is a grandfathered exception (it frames an entry count). See ADR-0006.
```

- [ ] **Step 5: Run the full suite**

Run: `mvn -q test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/test/java/evo/EvoTest.java docs/adding-types.md
git commit -m "test: enforce frozen skip-contract via conformance test (ADR-0006)"
```

---

### Task 3: Array fields (ADR-0005)

Map non-`byte[]` array fields to the existing LIST type, symmetric with `List<T>`. `byte[]` stays on the BYTES path (mandatory carve-out). Fixes the current silent `{}` data loss on write + crash on read.

**Files:**
- Modify: `src/main/java/evo/EvoMap.java` (`toValue` write branch; `fromValue` read branch)
- Test: `src/test/java/evo/EvoMapTest.java`

**Interfaces:**
- Consumes: `java.lang.reflect.Array` (already covered by `import java.lang.reflect.*;`), existing `toValue(Object)` / `fromValue(Object, Type)`.
- Produces: array-aware `toValue`/`fromValue` (same signatures).

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/evo/EvoMapTest.java` (top-level test records + methods):

```java
    record Arrays1(int[] xs, String[] names, int[][] grid) {}
    record Blob(byte[] data) {}

    @Test
    void testArrayFields() throws IOException {
        var a = new Arrays1(new int[]{1, 2, 3}, new String[]{"a", "b"}, new int[][]{{1, 2}, {3}});
        var r = roundtrip(a, Arrays1.class);
        assertTrue(java.util.Arrays.equals(r.xs(), new int[]{1, 2, 3}), "int[] survives");
        assertTrue(java.util.Arrays.equals(r.names(), new String[]{"a", "b"}), "String[] survives");
        assertTrue(java.util.Arrays.deepEquals(r.grid(), new int[][]{{1, 2}, {3}}), "int[][] survives");
    }

    @Test
    void testByteArrayStaysBytes() throws IOException {
        var b = new ByteArrayOutputStream();
        EvoMap.writeObject(b, new Blob(new byte[]{9, 8, 7}));
        // The wire is a MAP {data: <value>}; the value MUST be byte[] (BYTES),
        // not a List (LIST) — the ADR-0005 carve-out.
        Object wire = Evo.read(new ByteArrayInputStream(b.toByteArray()));
        Object data = ((Map<?, ?>) wire).get("data");
        assertTrue(data instanceof byte[], "byte[] field must stay BYTES, not become a LIST");
        var r = roundtrip(new Blob(new byte[]{9, 8, 7}), Blob.class);
        assertTrue(java.util.Arrays.equals(r.data(), new byte[]{9, 8, 7}), "byte[] round-trips");
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q test -Dtest=EvoMapTest#testArrayFields+testByteArrayStaysBytes`
Expected: FAIL — `testArrayFields` fails (the `int[]` is written as `{}` then read throws / mismatches); `testByteArrayStaysBytes` PASSES already (byte[] is a leaf today) but is kept as the carve-out regression guard.

- [ ] **Step 3: Add the array write branch to `toValue`**

In `src/main/java/evo/EvoMap.java`, inside `toValue`, immediately after the enum line `if (o instanceof Enum<?> e) return e.name();`, add:

```java
        if (c.isArray() && c != byte[].class) {          // ADR-0005: arrays map to LIST; byte[] stays BYTES
            int n = java.lang.reflect.Array.getLength(o);
            var out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) out.add(toValue(java.lang.reflect.Array.get(o, i)));
            return out;
        }
```

(`java.lang.reflect.*` is already imported; the fully-qualified `Array` here just avoids any ambiguity with `java.util.Arrays` used in tests. Plain `Array` also works.)

- [ ] **Step 4: Add the array read branch to `fromValue`**

In `src/main/java/evo/EvoMap.java`, inside `fromValue`, immediately after the enum block (the `if (raw.isEnum()) { ... }` that ends before `if (List.class.isAssignableFrom(raw))`), add:

```java
        if (raw.isArray() && raw != byte[].class) {      // ADR-0005: rebuild array from the wire LIST
            Class<?> comp = raw.getComponentType();
            List<?> list = (List<?>) v;
            Object arr = java.lang.reflect.Array.newInstance(comp, list.size());
            for (int i = 0; i < list.size(); i++)
                java.lang.reflect.Array.set(arr, i, fromValue(list.get(i), comp));  // Array.set unboxes for primitives
            return arr;
        }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -q test -Dtest=EvoMapTest#testArrayFields+testByteArrayStaysBytes`
Expected: PASS — `int[]`, `String[]`, `int[][]` round-trip; `byte[]` stays BYTES.

- [ ] **Step 6: Run the full suite**

Run: `mvn -q test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/evo/EvoMap.java src/test/java/evo/EvoMapTest.java
git commit -m "feat: support array fields as LIST, byte[] stays BYTES (ADR-0005)"
```

---

### Task 4: POJO inheritance — walk the superclass chain (ADR-0007)

Collect POJO fields from the concrete class up through superclasses (stop at `Object`). Subclass-wins on same-name shadowing. Fixes silent loss of inherited fields.

**Files:**
- Modify: `src/main/java/evo/EvoMap.java` (`fields(Class<?>)`, lines ~110–120)
- Test: `src/test/java/evo/EvoMapTest.java`

**Interfaces:**
- Consumes: `java.util.HashSet` (already covered by `import java.util.*;`).
- Produces: `fields(Class<?>)` now returns the flattened, de-duplicated instance-field set (same return type `Field[]`).

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/evo/EvoMapTest.java`:

```java
    static class Base { int a; String b; Base() {} }
    static class Derived extends Base { String c; Derived() {} }

    @Test
    void testPojoInheritance() throws IOException {
        var d = new Derived();
        d.a = 5; d.b = "base"; d.c = "derived";
        var r = roundtrip(d, Derived.class);
        assertTrue(r.a == 5, "inherited int field survives");
        assertTrue("base".equals(r.b), "inherited String field survives");
        assertTrue("derived".equals(r.c), "own field survives");
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=EvoMapTest#testPojoInheritance`
Expected: FAIL — `r.a == 5` and `r.b` fail; the inherited fields are written as `{}` (absent) and read back as defaults (`0` / `null`).

- [ ] **Step 3: Rewrite `fields` to walk the hierarchy**

In `src/main/java/evo/EvoMap.java`, replace the body of `fields(Class<?> c)` (currently a single `getDeclaredFields()` loop) with a superclass walk. The full method becomes:

```java
    /** Cached, access-enabled instance fields of a POJO class, walking the
     *  superclass chain (subclass-wins on same-name shadowing; static/transient
     *  excluded). See ADR-0007. */
    private static Field[] fields(Class<?> c) {
        return POJO_FIELDS.computeIfAbsent(c, k -> {
            var list = new ArrayList<Field>();
            var seen = new HashSet<String>();
            for (Class<?> t = k; t != null && t != Object.class; t = t.getSuperclass()) {
                for (Field f : t.getDeclaredFields()) {
                    int m = f.getModifiers();
                    if (Modifier.isStatic(m) || Modifier.isTransient(m)) continue;
                    if (!seen.add(f.getName())) continue;   // subclass already claimed this name
                    f.setAccessible(true);
                    list.add(f);
                }
            }
            return list.toArray(new Field[0]);
        });
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=EvoMapTest#testPojoInheritance`
Expected: PASS — `a`, `b`, `c` all survive.

- [ ] **Step 5: Run the full suite**

Run: `mvn -q test`
Expected: PASS — `Point` and other single-level POJOs are unaffected (their superclass is `Object`, so the walk stops immediately).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/evo/EvoMap.java src/test/java/evo/EvoMapTest.java
git commit -m "fix: serialize inherited POJO fields, subclass-wins (ADR-0007)"
```

---

### Task 5: Write-side round-trippability guard (ADR-0008)

Reject a class the POJO path cannot round-trip — no accessible no-arg constructor, or inaccessible fields — with a clean `IllegalArgumentException` naming the class, instead of an opaque `InaccessibleObjectException` or a silent partial write.

**Files:**
- Modify: `src/main/java/evo/EvoMap.java` (`toValue` POJO fallback, currently lines ~150–153)
- Test: `src/test/java/evo/EvoMapTest.java`

**Interfaces:**
- Consumes: `java.lang.reflect.InaccessibleObjectException` (covered by `import java.lang.reflect.*;`), existing `fields(Class<?>)`, `get(Field, Object)`.
- Produces: guarded `toValue` POJO branch (same signature).

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/evo/EvoMapTest.java`:

```java
    record HasUuid(java.util.UUID id) {}

    @Test
    void testUnsupportedTypeThrowsCleanly() throws IOException {
        boolean cleanIae = false;
        try {
            var b = new ByteArrayOutputStream();
            EvoMap.writeObject(b, new HasUuid(java.util.UUID.randomUUID()));
        } catch (IllegalArgumentException e) {
            cleanIae = e.getMessage() != null && e.getMessage().contains("UUID");
        }
        assertTrue(cleanIae,
            "unsupported type must throw IllegalArgumentException naming the class, not InaccessibleObjectException");
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=EvoMapTest#testUnsupportedTypeThrowsCleanly`
Expected: FAIL — today `writeObject` throws `InaccessibleObjectException` (or, if `java.util` were open, silently writes a garbage map), so `cleanIae` is false.

- [ ] **Step 3: Guard the POJO fallback in `toValue`**

In `src/main/java/evo/EvoMap.java`, replace the POJO fallback at the end of `toValue` (currently the comment `// POJO: declared, non-static, non-transient fields` plus the three lines building `out`) with a guarded version:

```java
        // POJO fallback — must be round-trippable: needs a no-arg constructor to
        // be readable and accessible fields to be writable. Otherwise fail with a
        // clean, named error instead of an opaque reflection exception or a silent
        // partial write. See ADR-0008.
        try {
            c.getDeclaredConstructor();                  // present? (readability precondition)
            var out = new LinkedHashMap<String, Object>();
            for (Field f : fields(c)) out.put(f.getName(), toValue(get(f, o)));  // fields() setAccessible may throw
            return out;
        } catch (NoSuchMethodException | InaccessibleObjectException e) {
            throw new IllegalArgumentException("cannot map " + c.getName()
                + "; use a record/List/Map or give it a no-arg constructor with accessible fields", e);
        }
```

Note: a nested unmappable field throws the same clean `IllegalArgumentException` from its own inner frame; that is *not* caught here (this catch only takes `NoSuchMethodException`/`InaccessibleObjectException`), so the innermost offending class name propagates unchanged.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=EvoMapTest#testUnsupportedTypeThrowsCleanly`
Expected: PASS — a clean `IllegalArgumentException` mentioning `java.util.UUID`.

- [ ] **Step 5: Run the full suite**

Run: `mvn -q test`
Expected: PASS — `Point`, `Base`/`Derived`, and all records have a no-arg (or canonical) constructor and accessible fields, so the guard is transparent to them.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/evo/EvoMap.java src/test/java/evo/EvoMapTest.java
git commit -m "fix: reject unmappable POJO types with a clean error at write (ADR-0008)"
```

---

## Notes for the implementer

- **Task order matters for EvoMap.** Do Task 3 (arrays) → Task 4 (inheritance) → Task 5 (guard). Task 5's guard wraps the same POJO fallback whose field collection Task 4 changes; doing 4 first keeps the guard reasoning about the flattened field set.
- **Tasks 1 and 2 are independent** of the EvoMap tasks and of each other — they touch only `Evo.java` / `EvoTest.java`. They can be done first.
- **Do not "fix" MAP** to conform to the generic class-7 skip rule — its encoding is frozen (design spec rule 2). The conformance test in Task 2 deliberately asserts MAP is the sole exception.
- **`-Dtest=Class#a+b`** runs two methods in one class with the Surefire plugin; if your Surefire version rejects the `+` form, run the whole class: `mvn -q test -Dtest=EvoMapTest`.

## Self-review notes

- **Spec coverage:** ADR-0004→Task 1, ADR-0005→Task 3, ADR-0006→Task 2, ADR-0007→Task 4, ADR-0008→Task 5. ADR-0001/0002/0003 are doc-only (no code) — already applied as spec edits, no task needed.
- **Type consistency:** `read0` used consistently in Task 1 (worker) with `read` as the public wrapper; `fields(Class<?>)` return type `Field[]` unchanged in Task 4; `toValue`/`fromValue` signatures unchanged in Tasks 3/5.
- **byte[] carve-out** appears in both the write (Task 3 Step 3) and read (Task 3 Step 4) branches and is regression-guarded by `testByteArrayStaysBytes`.

# evo — Glossary

Terms of art for the evo format and mapper. Kept alongside the ADRs so a
decision and its vocabulary travel together.

- **Tag byte** — the single leading byte of every value: `(size class << 5) |
  type id`. Top 3 bits skip rule, low 5 bits concrete type.
- **Size class** — top 3 bits of the tag (0–7). Tells *any* reader how to skip a
  value's payload without knowing its type. The mechanism behind forward
  compatibility. Frozen skip rules; see [format-spec.md](format-spec.md) §2.
- **Type id** — low 5 bits of the tag (0–31 per class). Identifies the concrete
  type. Append-only; never renumbered or reused.
- **Varint / LEB128** — unsigned Little-Endian-Base-128 variable-length integer;
  7 value bits/byte, high bit = continuation. Used for all lengths and counts.
- **Zigzag** — signed→unsigned mapping (`(n<<1)^(n>>63)`) applied before varint
  to the integer types so small negatives cost 1 byte. Lengths/counts are *not*
  zigzagged.
- **Unknown** — placeholder record `Unknown(int tag, byte[] raw)` returned when a
  reader meets a type id it doesn't know; the value was skipped via its size
  class so the stream stays in sync.
- **Forward compat** — old reader, newer data. Delivered by size-class skipping
  (unknown types) and name-keyed maps (unknown fields).
- **Backward compat** — new reader, older data. Delivered by defaulting missing
  fields.
- **Trusted producer model** — the reader assumes evo-written input; it guards
  against corruption cheaply but not against adversarial bytes. See
  [ADR-0001](adr/0001-threat-model.md).
- **Enum forward-break** — appending an enum constant is *not* forward-safe: an
  old reader hits a name its enum lacks, `Enum.valueOf` throws, the whole
  `readObject` aborts. Adding a constant is a breaking change for fields that
  reach old readers. See [ADR-0002](adr/0002-unknown-enum-constant.md).
- **Frozen field type** — a field's declared type may never change; the mapper
  does no numeric coercion, so old data of the former type throws on read. Widen
  by add-new-field + drop-old. See [ADR-0003](adr/0003-field-types-frozen.md).
- **Nesting-depth crash** — `read` recurses per LIST/MAP level; ~10 KB of nested
  LISTs exhausts the stack. Mitigated by catching `StackOverflowError` at the
  public `read` boundary and rethrowing `IOException`. See
  [ADR-0004](adr/0004-nesting-depth-crash.md).
- **Round-trippability guard** — the POJO write path rejects a class that lacks
  an accessible no-arg constructor or accessible fields (`UUID`, `BigDecimal`,
  …) with a clean `IllegalArgumentException`, instead of opaque
  `InaccessibleObjectException` or silent internal-field mangling. Restores
  write/read symmetry. See [ADR-0008](adr/0008-unsupported-type-write-guard.md).
- **Generic-array support** — arrays of parameterized types (`List<Person>[]`,
  `Map<K,V>[]`) round-trip by resolving the `GenericArrayType`'s generic
  component type on read; without it `rawClass` erases to `Object` and read
  fails opaquely. See [ADR-0009](adr/0009-generic-array-fields.md).
- **Superclass field walk** — POJO field collection walks the class hierarchy
  (derived→base, stop at `Object`); subclass-wins on same-name shadowing.
  Without it, inherited fields are silently dropped. See
  [ADR-0007](adr/0007-pojo-inheritance.md).
- **Array-as-LIST** — array fields (except `byte[]`) map to the LIST type,
  symmetric with `List<T>`. `byte[]` stays BYTES (carve-out is mandatory for
  compat). Indistinguishable from a List on the wire; declared type decides.
  See [ADR-0005](adr/0005-array-fields.md).
- **Grandfathered MAP framing** — MAP's class-7 count is an *entry* count
  (2×count nested values follow), which violates the generic class-7 skip rule
  (count = number of nested values). Safe only because every reader knows MAP
  natively. A *new* class-7 type must prefix a value count, never an entry count.
- **Skip-contract conformance test** — mechanical gate: an ignorant
  (size-class-only) skip must be byte-exact for every type *except* the single
  grandfathered exception (MAP). A new type that violates its size class trips
  the "exactly one exception" check and fails CI. See
  [ADR-0006](adr/0006-skip-contract-conformance-test.md).

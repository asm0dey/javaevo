# ADR-0005: Support array fields by mapping T[] to LIST

**Status:** Accepted (2026-08-05, grilling session)

## Context

The codec has no array type; `byte[]` is the only array handled (as the leaf
BYTES type). Any other array field (`int[]`, `String[]`, `Point[]`) falls
through `EvoMap.toValue` to the POJO branch, where an array class has no
declared fields, so it serializes as an **empty map `{}`** — silent total data
loss on write — and then `fromValue` crashes on read (no no-arg constructor for
an array class).

**Proven this session:** `record Nums(int[] xs, String name)` with
`xs={1,2,3}` serialized to the wire shape `{xs={}, name="hi"}` (the array gone),
then `readObject` threw `IllegalStateException: cannot build POJO class [I`.

A write-only caller (append-only log) loses the array with **no error at all**.

## Decision

**Support arrays by mapping `T[]` to the existing LIST type**, symmetric with
how `List<T>` is handled. No new wire type, no format change.

- **`toValue`:** an array whose component type is not `byte` becomes a `List` of
  converted elements (iterate, `toValue` each). Recursion handles object,
  primitive, and multi-dimensional arrays.
- **`fromValue`:** when the declared type `raw.isArray()` (and not `byte[]`),
  read the wire LIST, allocate `Array.newInstance(raw.getComponentType(), n)`,
  and `Array.set(arr, i, fromValue(elem, componentType))` per element.
  `Array.set` unboxes for primitive component types, so `int[]` rebuilds as
  `int[]`, not `Integer[]`.

## Compat-critical carve-out (MANDATORY)

**`byte[]` stays on the BYTES path** and is excluded from the array handling
(`isArray() && componentType != byte.class`). Routing `byte[]` through
array-as-LIST would silently change its wire encoding from BYTES to LIST and
break every existing `byte[]` field. This is not optional.

## What comes free (recursive implementation)

- **Primitive arrays** — `int[]`, `long[]`, `double[]`, … via `Array` reflection.
- **Object arrays** — `String[]`, `Point[]` via per-element `fromValue` on the
  component type.
- **Multi-dimensional** — `int[][]` is an array whose component type is `int[]`;
  recursion nests a LIST of LISTs. No extra code.
- **Empty / null** — empty array → LIST count 0; null array field → NULL (existing
  null path).

## Consequences

- On the wire an array is **indistinguishable from a `List`** (both LIST). Which
  Java type you get back is decided by the declared field type — consistent with
  the existing "declared type drives decoding" model. Changing a field between
  `int[]` and `List<Integer>` is therefore a field-type change and thus breaking
  per [ADR-0003](0003-field-types-frozen.md).
- Update the mapper docs and `docs/adding-types.md`: arrays (except `byte[]`) are
  now supported, mapped to LIST.

## Test to add

Round-trip a record/POJO with `int[]`, `String[]`, and `int[][]` fields; assert
values survive. Assert a `byte[]` field still encodes as BYTES (unchanged wire
bytes) — the carve-out regression guard.

## Rejected alternatives

- **Throw at write** — 2 lines, fail-loud, points callers to `List`. Rejected:
  arrays are common in Java APIs and the boxing/ergonomic cost of forcing `List`
  is real; the feature is worth ~20-30 lines.
- **Leave it / document** — keeps the silent `{}` data-loss mode. Rejected.

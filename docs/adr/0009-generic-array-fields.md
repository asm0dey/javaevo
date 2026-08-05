# ADR-0009: Support arrays of parameterized types (generic-component arrays)

**Status:** Accepted (2026-08-05, grilling session follow-up)
**Extends:** [ADR-0005](0005-array-fields.md)

## Context

ADR-0005 added array-field support by mapping `T[]` to LIST. It covers arrays
whose component is a plain `Class` (`int[]`, `String[]`, `int[][]`, `Person[]`)
because such fields have a reflective `Type` that *is* a `Class`.

But a field typed `List<Person>[]` (or `Map<K,V>[]`) has reflective `Type` =
`java.lang.reflect.GenericArrayType`, and `EvoMap.rawClass` handles only `Class`
and `ParameterizedType` — everything else falls to `Object.class`. So
`fromValue` takes the `raw == Object.class` early-return, hands back the raw
codec value (a `List` of `List`s of `Map`s), the array branch never runs, and
the record/POJO constructor gets an `ArrayList` where a `List[]` is expected.

**Proven this session:** `record Holder(List<Person>[] groups)` →
`readObject` throws `IllegalArgumentException: argument type mismatch` — opaque,
exactly the failure mode ADR-0008 set out to eliminate elsewhere.

Write already works (it dispatches on runtime array classes, no generics
needed); only the read path is broken.

## Decision

Handle `GenericArrayType` on read, preserving the component's generics:

1. **`rawClass`** gains a `GenericArrayType` case returning the erased array
   class:
   ```java
   if (t instanceof GenericArrayType g)
       return Array.newInstance(rawClass(g.getGenericComponentType()), 0).getClass();
   ```
   This makes `raw.isArray()` true for `List<Person>[]`, so the array branch is
   reached.

2. **`fromValue` array branch** resolves the component *Type* (with generics)
   instead of the erased `raw.getComponentType()`:
   ```java
   Type compType = (t instanceof GenericArrayType g)
       ? g.getGenericComponentType()            // List<Person>  — keeps generics
       : raw.getComponentType();                // int, String, Person — a Class
   Object arr = Array.newInstance(rawClass(compType), list.size());
   for (int i = 0; i < list.size(); i++)
       Array.set(arr, i, fromValue(list.get(i), compType));
   return arr;
   ```
   Passing `compType` (not the erased class) lets the inner `List<Person>`
   resolve `Person` per element.

## Why proper support over document-and-fail-loud

- Consistent with ADR-0008's fail-loud-not-opaque intent, but goes further: the
  case now *works* rather than throwing. `List<Person>[]` is a natural Java
  shape.
- Small and recursive: ~4 lines. Multi-dimensional and `Map<K,V>[]` fall out of
  the same recursion (`getGenericComponentType` of `List<Person>[][]` is itself a
  `GenericArrayType`).
- The `byte[]` carve-out and non-generic arrays are unchanged: for a plain
  `Class` array `compType` is a `Class`, so `Array.newInstance(rawClass(compType), n)`
  reduces to the previous behavior. No regression.

## Consequences

- Arrays of parameterized types (`List<X>[]`, `Map<K,V>[]`, and their nestings)
  round-trip element generics correctly.
- Wire format unchanged — this is a read-side type-resolution fix only.
- `Array` (`java.lang.reflect.Array`) and `GenericArrayType` are already covered
  by `import java.lang.reflect.*`.

## Test to add

Round-trip `record Holder(List<Person>[] groups)` and assert an element is a
`Person` with the right values; a `Map<String,Person>[]` case; and confirm the
existing `int[]` / `String[]` / `int[][]` tests still pass (regression guard for
the non-generic path).

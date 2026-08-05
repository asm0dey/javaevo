# ADR-0008: Write-side round-trippability guard for the POJO fallback

**Status:** Accepted (2026-08-05, grilling session)
**Relates to:** [ADR-0005](0005-array-fields.md), [ADR-0007](0007-pojo-inheritance.md)
(both are instances of the same catch-all root cause)

## Context

`EvoMap.toValue`'s final branch reflects **any** class that is not a
leaf/enum/List/Map/record as a POJO. It assumes the class is a well-behaved,
round-trippable data object. Two failure modes result for types that aren't:

1. **Opaque crash.** On a modern (module-system) JDK, `setAccessible` on a JDK
   value type throws `InaccessibleObjectException`. Proven this session: a `UUID`
   field threw `InaccessibleObjectException: Unable to make field
   private final long java.util.UUID.mostSigBits accessible` — an opaque
   reflection error, not a clean contract.
2. **Silent mangling.** A user value type in an open module (or JDK types under
   `--add-opens`) has its private internals reflected into a garbage map, then
   usually crashes on read (`BigDecimal.intVal` etc.).

Separately, `toValue` never checks the POJO has a no-arg constructor, so a caller
can **write** an object that `readObject` can never reconstruct — a write/read
asymmetry that surfaces only on read.

`Evo.write` already guards unknown types with a clean
`IllegalArgumentException("unsupported: ...")`; the mapper had no equivalent.

## Decision

Add a **write-side round-trippability guard** to the POJO path. Before treating a
class as a POJO, require it to be genuinely mappable:

- an accessible **no-arg constructor**, and
- **accessible instance fields** (`setAccessible` succeeds).

If either fails, throw a clean
`IllegalArgumentException("cannot map <Class>; use a record/List/Map or give it
a no-arg constructor with accessible fields")` — at write time, before any bytes
are emitted. Validate once per class and cache the verdict alongside the existing
field/constructor caches.

This is **not** value-type support. `UUID`, `BigDecimal`, etc. still aren't
handled — they now fail with one clear message instead of an opaque reflection
error or silent corruption.

## Why

- Turns two bad failure modes (opaque crash, silent mangling) into one honest
  contract, mirroring `Evo.write`'s existing guard.
- Restores write/read symmetry: the mapper can no longer emit something it cannot
  read back. Fail-fast at write beats fail-late at read.
- Keeps the mapper tiny (metric 3). Value-type support and a converter registry
  were the fuller options and were rejected as too much surface for the goal.

## Consequences

- Records are unaffected (canonical constructor + public accessors; no
  `setAccessible` on user fields, no no-arg requirement).
- Callers who relied on writing a not-actually-round-trippable object now get a
  clear error. This is the intended correction.

## Test to add

Assert that writing a `UUID`/`BigDecimal` field (or a POJO with no no-arg
constructor) throws `IllegalArgumentException` naming the class — not
`InaccessibleObjectException`, not a silent partial write.

## Rejected alternatives

- **Support common value types** — real ergonomics, but which types, user value
  types still fall through, ongoing maintenance. Rejected for footprint.
- **User-provided converter registry** — fully extensible but adds an API surface
  and registry. Over-engineered for the goal.
- **Leave it** — opaque/silent failures persist. Rejected.

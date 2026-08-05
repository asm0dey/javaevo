# ADR-0006: Enforce the frozen skip-contract with a conformance test

**Status:** Accepted (2026-08-05, grilling session)
**Relates to:** design spec rule 3 (frozen skip rules); [format-spec.md](../format-spec.md) §6

## Context

Forward compatibility rests entirely on one invariant: **every type conforms to
the skip rule of its size class**, so an old reader that meets an unknown type id
skips it byte-exactly from the size class alone (`Evo.skipUnknown`) and stays in
sync.

The design spec (rule 3) states this and flags **MAP as a grandfathered
violator** — MAP prefixes an *entry* count, but the generic class-7 skip rule
consumes a *value* count. MAP is safe only because every reader knows it
natively and never takes the ignorant skip path for it. A *new* class-7 type
framed like MAP (entry count) would silently desync old readers.

Nothing enforced this. A maintainer could add such a type, pass their own
round-trip tests (they take the native branch), and break only *old* readers,
silently, in production — the worst bug profile in the format.

## Decision

Add a **conformance test** that mechanically enforces the skip contract for
every registered type id.

### Test design

For each concrete type the codec defines, write a stream of
`[a representative value][a sentinel value]`, then:

1. Read the tag byte and call `Evo.skipUnknown(in, tag)` — forcing the
   **ignorant** (size-class-only) skip path even though the type is known.
2. Read the next value and assert it equals the sentinel.

If the ignorant skip consumed exactly the value's bytes, the sentinel parses; if
it under/over-consumed, the sentinel read desyncs and the assertion fails.

### The MAP exception is encoded, not hidden

MAP **fails** this ignorant skip by construction: `skipUnknown` class 7 reads
`count` values, but a MAP has `2×count` values, so it under-consumes and
desyncs. So the test's real assertion is:

> ignorant-skip is byte-exact for **every** type **except** the one explicitly
> listed grandfathered exception — MAP.

A maintainer who adds a new type whose framing violates its size class trips the
"exactly one exception (MAP)" check and the build fails. The grandfathered
exception is thereby documented in *executable* form.

## Why this over the alternatives

- **vs. runtime assertion/registry** — a registry checked per value adds code and
  per-value overhead, fighting the tiny-codec goal (metric 3). The failure mode
  is a *ship-time* mistake (a new type added wrong), so a *test-time* gate is the
  right altitude; runtime checks pay forever for a one-time error.
- **vs. doc only** — the current state. The one mechanism forward-compat depends
  on had no automated guard; a doc rule is not enforcement.

## Test to add

The conformance test above, plus keep the existing hand-crafted skip-unknown
test (spec test #4) for the nested-in-Map desync case.

## Rejected alternatives

- **Runtime assertion / type-id→size-class registry** — enforces at runtime but
  adds a registry + per-value cost. Rejected for footprint.
- **Doc only** — no automated guard on the format's foundation. Rejected.

# ADR-0007: POJO field collection walks the superclass chain

**Status:** Accepted (2026-08-05, grilling session)

## Context

`EvoMap.fields` collects only `getDeclaredFields()` of the concrete class
(EvoMap.java:113). A POJO that inherits instance fields from a superclass loses
them entirely — they are never written and read back as constructor defaults.

**Proven this session:** `class Manager extends Employee { String dept; }` with
`Employee` declaring `name`, `salary` serialized to the wire shape
`{dept="R&D"}` — `name` and `salary` gone; read back `null` / `0`. Silent data
loss on write. (Records are unaffected: records cannot inherit state.)

The spec said "declared fields," but the mapper-limits section never warned that
inheritance means silent loss.

## Decision

`fields()` walks the class hierarchy: from the concrete class up through
superclasses, stopping at `Object`, collecting each level's non-static,
non-transient instance fields.

**Shadowing:** if a subclass field hides a superclass field of the same name,
the **subclass field wins** (it is encountered first, walking derived→base). The
hidden superclass field is unreachable — consistent with Java field-hiding
semantics, and rare. The wire map is keyed by simple field name, so the two
collapse to one key by construction.

## Why

- The current behavior is silent data loss — the failure mode rejected
  throughout this session (enums, numerics, arrays). Inheritance is a common Java
  pattern; correctness demands the superclass state be carried.
- The fix is ~5 lines and the per-concrete-class result is still cached in
  `POJO_FIELDS`, so there is no per-call cost.

## Consequences

- POJO hierarchies now round-trip fully.
- A hidden (shadowed) superclass field is not serializable under its own
  identity — document this single edge in the mapper limits.
- Evolution rules (ADR-0003 field types frozen, field add/drop) apply across the
  flattened field set, since the wire is a flat name-keyed map regardless of
  which class in the hierarchy declared the field.

## Test to add

Round-trip a two-level POJO hierarchy; assert superclass fields survive. Add a
shadowed-field case asserting subclass-wins.

## Rejected alternatives

- **Declared-only + document** — zero code, but keeps the silent loss. Rejected.
- **Throw on inherited fields** — fail-loud but rejects a common Java pattern
  outright. Rejected: correctness (round-trip it) beats refusal here.

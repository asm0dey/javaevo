# ADR-0003: Field types are frozen — changing a field's type is a breaking change

**Status:** Accepted (2026-08-05, grilling session)
**Relates to:** [ADR-0002](0002-unknown-enum-constant.md) (same fail-loud stance)

## Context

The mapper decodes each field to its **declared type** with no numeric coercion
(`EvoMap.fromValue` returns leaf values as the exact wrapper the codec produced,
EvoMap.java:170). Reconstruction then feeds those wrappers to reflection:

- Record → canonical `Constructor.newInstance(args)`
- POJO → `Field.set(inst, value)`

Java reflection unboxes but does **not widen**. So old data written as `INT`
read into a field that a newer version changed to `long` fails with
`IllegalArgumentException` — `int`→`long`, the most common numeric widening, is
**not** evolution-safe. The design spec documented add/drop as safe but said
nothing about type changes.

## Decision

**A field's declared type is frozen.** Changing it is a breaking change, in the
same tier as recycling a retired field name. No coercion layer is added.

To change a field's type, use the already-safe path: **add a new field of the
new type and drop the old one.** Old readers ignore the new key and default the
dropped one; new readers read the new field.

## Why

- Keeps the mapper tiny (metric 3). A coercion matrix is a permanent liability:
  int→long is lossless, long→int truncates, int→double loses precision at the
  top of the range, String↔number is undefined. Every entry is a rule to
  maintain and a silent-loss risk.
- Consistent with ADR-0002: prefer a loud, obvious break over a silent lossy
  conversion. "Coerce all numerics" was rejected for exactly the truncation
  reason enums were.
- The escape hatch (add new + drop old) already exists and is already safe, so
  freezing costs the user only a field rename, not a capability.

## Consequence

Add to the maintainer rules: **field types are append-only in the same sense as
type ids — never change the type of an existing field; introduce a new field
instead.**

## Rejected alternatives

- **Coerce lossless widenings** (JLS widening set) — ~10-20 lines, makes int→long
  "just work." Rejected: opens the "where does safe stop" matrix; the add/drop
  path already covers the need.
- **Coerce all numerics** — never throws on numbers, but silently truncates
  long→int. Rejected: same silent-data-loss mode ADR-0002 rejected.

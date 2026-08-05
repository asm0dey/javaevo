# ADR-0002: Unknown enum constant on read — fail loud

**Status:** Accepted (2026-08-05, grilling session)
**Relates to:** [ADR-0001](0001-threat-model.md) (forward-compat scope)

## Context

The mapper stores an enum field as its `name()` string and restores it with
`Enum.valueOf(type, name)` (`EvoMap.fromValue`, EvoMap.java:171). If a *newer*
writer added a constant the *older* reader's enum lacks, `Enum.valueOf` fails.

This is a forward-compatibility case: old reader, newer data. The rest of the
format survives newer data (unknown *types* skip to `Unknown`; unknown *fields*
are ignored). The enum path is the exception — an enum is stored as a known
type (String), so there is no size-class skip to save it.

The original design spec claimed "appending enum constants is evolution-safe."
**That was false** for forward reads.

## Decision

**Keep throwing.** An unresolvable enum constant raises `IllegalStateException`
and aborts the whole `readObject`. No silent nulling, no sentinel, no
placeholder.

## Why

- Fail-loud beats silent data loss. A dropped/nulled enum value can propagate a
  wrong-but-plausible state; a thrown exception stops the caller cold and is
  found in testing.
- Consistent with the trusted-producer model (ADR-0001): same-version reads
  never hit this; cross-version reads that *do* hit it are a real schema
  mismatch the operator should know about.

## Consequence (must be documented, now is)

**Adding an enum constant is a breaking change** for any field that can flow to
an older reader. Treat it like a wire-format change: gate behind a version bump
the caller controls, or ensure all readers are upgraded first. This is stricter
than "add/drop struct fields freely" — those stay safe; enum constants do not.

## Rejected alternatives

- **Decode to null** — restores forward-compat, one line, but silently loses the
  field value. Rejected in favor of loud failure.
- **User-declared UNKNOWN sentinel** (protobuf-style) — ceremony per enum,
  requires user cooperation. Rejected: too much machinery for the footprint.
- **Placeholder holder carrying the raw name** — most faithful to "survive now,
  understand later," but the field type stops being the enum and every caller
  must guard. Rejected: pushes complexity onto every reader.

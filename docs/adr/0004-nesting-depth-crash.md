# ADR-0004: Deep-nesting crash — catch StackOverflowError, rethrow IOException

**Status:** Accepted (2026-08-05, grilling session)
**Relates to:** [ADR-0001](0001-threat-model.md) (corrupt → clean exception, not crash)

## Context

`Evo.read` recurses once per LIST/MAP nesting level. Depth is unbounded, so a
corrupt or crafted stream of nested LISTs (`E0 01` repeated) recurses until the
JVM stack is exhausted → `StackOverflowError`.

**Proven, not theoretical:** a **~10 KB** file (5000 × `E0 01`) crashes
`Evo.read` with `StackOverflowError` on the default stack. `StackOverflowError`
is an `Error`, so a caller's `catch (IOException)` does **not** catch it — the
reader goes down. This violates ADR-0001's promise.

## Decision

Wrap the reader so a stack overflow surfaces as an `IOException`, not an
`Error`:

- Rename the recursive body to a private `read0(InputStream)`.
- Public `read(InputStream)` = `try { return read0(in); } catch
  (StackOverflowError e) { throw new IOException("nesting too deep"); }`.
- Internal recursion (LIST/MAP loops, `skipUnknown` class 7) and `EvoMap` call
  `read0` for the hot path; the single catch sits at the public boundary.

A max-depth counter was considered and **not** chosen — see below.

## Why this over a depth counter

- **Empirically reliable on HotSpot.** The catch was tested against 20k and 100k
  nested levels at both default and `-Xss160k` stacks; it returned a clean
  `IOException` every time. HotSpot's reserved stack zone guarantees room for the
  unwind + handler, so the feared "SOE inside the catch" does not occur on a
  standard JDK.
- **Zero tuning.** No magic depth constant to pick, defend, or revisit when
  someone legitimately nests deeper than the guess. The real limit (the JVM
  stack) is exactly what bounds the machine anyway.
- **Smallest change.** One try/catch at the boundary vs. threading a depth `int`
  through every recursive call site.

## Residual costs (accepted)

1. **Fails late.** The full nested structure is allocated before the overflow,
   so a corrupt 10 KB file still burns CPU and transient memory. A depth guard
   would fail earlier. Accepted: no crash, no OOM (allocations are freed on
   unwind), and the trusted model (ADR-0001) does not target adversarial DoS
   amplification.
2. **Broad catch.** A `StackOverflowError` from an unrelated cause is relabeled
   "nesting too deep." Low risk in a pure codec.
3. **HotSpot dependence.** Relies on reserved-stack unwind behavior. The project
   targets standard JDK; acceptable.

## Test to add

A PoC test that feeds N nested LISTs (e.g. 50 000) and asserts `read` throws
`IOException`, not `StackOverflowError`. (Verified by hand this session against
the built classes.)

## Rejected alternatives

- **Max-depth guard** — earlier, deterministic failure, but needs a threaded
  depth param and an arbitrary constant. Rejected: the catch is reliable on the
  target JVM with less code and no magic number.
- **Leave it / amend ADR-0001** — zero code, but a 10 KB file DoSes any reader
  and an `Error` escapes `IOException` handlers. Rejected.

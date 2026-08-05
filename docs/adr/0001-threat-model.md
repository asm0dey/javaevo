# ADR-0001: Robustness / threat model — trusted producer, cheap guards

**Status:** Accepted (2026-08-05, grilling session)
**Context subject:** the wire format itself

## Decision

The reader assumes input was produced by an evo writer. It does **not** defend
against adversarial bytes (no checksum, no magic, no framing). It **does** keep
one-line sanity guards that turn a *corrupt* (not malicious) file into a clean
exception instead of a crash.

## Why

- Matches the embedding target (JaCoCo reads its own `.exec`-adjacent data) and
  success metric 3 (tiny footprint). Full hostile-input hardening — bounded
  proofs, checksums, framing — contradicts it.
- Corrupt files still happen (partial writes, disk errors). Cheap guards cost
  nothing and prevent the worst crashes.

## What this commits us to

1. **Length guard exists and stays.** `Evo.readLen` (Evo.java:130) rejects
   negative and `> Integer.MAX_VALUE` lengths/counts → `IOException`, not
   `NegativeArraySizeException` or a silent huge alloc.
2. **Truncation == clean EOF, by design.** A stream cut mid-value throws the
   same `EOFException` as a clean end. The reader cannot distinguish "done" from
   "damaged." Callers who need that guarantee wrap their own framing/length
   prefix (JaCoCo has its own magic). Documented, not fixed.
3. **No integer-range validation on read.** An `INT` tag whose varint decodes
   beyond `Integer` range is silently `(int)`-truncated. Acceptable: a
   conformant writer never emits it. Not guarded (would cost code for a
   trusted-input-only failure).

## Known residual (accepted, deferrable)

`readLen` caps at `Integer.MAX_VALUE` (2 GB), then `readN` pre-allocates
`new byte[n]`. A tiny corrupt file claiming length ≈2 GB still OOMs before any
bytes are read. Under the trusted model this is accepted. Upgrade path if it
ever bites: read length-prefixed payloads **incrementally** (chunked, growing a
buffer) instead of pre-allocating, so allocation tracks bytes actually present.

## Rejected alternatives

- **Fully trusted, zero guards** — drops `readLen`; a corrupt byte can OOM or
  hang. Rejected: the guard is one line.
- **Hostile-input hardened** — checksums + framing + range validation. Rejected:
  contradicts metric 3; the embedding target doesn't need it.

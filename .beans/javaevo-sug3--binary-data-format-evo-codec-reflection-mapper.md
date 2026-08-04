---
# javaevo-sug3
title: Binary data format (evo) — codec + reflection mapper
status: todo
type: feature
created_at: 2026-08-04T11:38:37Z
updated_at: 2026-08-04T11:38:37Z
---

Zero-dep self-describing binary format for Java with schema evolution. Vendors into JaCoCo (no new deps allowed).

Spec: docs/superpowers/specs/2026-08-04-binary-format-design.md
Plan: docs/superpowers/plans/2026-08-04-binary-format.md

Two files: src/main/java/evo/Evo.java (codec), src/main/java/evo/EvoMap.java (reflection mapper). Java 17, no build tool, tests are main+java -ea.

## Tasks
- [ ] T1 codec skeleton + varint/zigzag/fixed-width helpers
- [ ] T2 scalars (null/bool/char/byte/short/int/long/float/double) exact-type fidelity
- [ ] T3 String + byte[]
- [ ] T4 List + Map (recursive)
- [ ] T5 forward-compat: skip unknown types + framing/EOF
- [ ] T6 mapper: leaves, records, enums
- [ ] T7 mapper: POJOs + nested collections of user types
- [ ] T8 mapper: schema evolution (add/drop field, null nested)
- [ ] T9 README + full test run

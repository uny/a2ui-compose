# Vendored Unicode Character Database

This file is copied verbatim from the Unicode Character Database and is the input to the
`generateXidTables` Gradle task, which derives the `XID_Start` and `XID_Continue` code point ranges
and embeds them as Kotlin source. It is not edited here: to move to a newer Unicode version,
replace the file, update the table below, and update the code point count `Uax31Test` asserts.

A2UI v1.0 requires every catalog entity identifier and every extension key to match
`^[\p{XID_Start}_][\p{XID_Continue}]*$` (`docs/a2ui_protocol.md`). Neither half of that is
answerable from Kotlin common code -- `Regex` does not support the property on Kotlin/Native or
Kotlin/Wasm, and `java.lang.Character` is not reachable -- and the properties are *derived* from
this database rather than definable in terms of anything the standard library exposes. So the
choice is between carrying the derivation's output and approximating the rule; this library carried
an approximation through `0.1.0`'s development and it was wrong in both directions, which is what
`Uax31.isUnicodeIdentifier` documents.

Only `XID_Start` and `XID_Continue` are read. The file also derives `Math`, `Alphabetic`,
`ID_Start`, `Grapheme_Base` and a dozen others; the whole file is vendored rather than an extract
so that what ships can be diffed against Unicode's own, which an extract cannot be.

| File | Source | Version | SHA-256 |
|:--|:--|:--|:--|
| `DerivedCoreProperties.txt` | <https://www.unicode.org/Public/17.0.0/ucd/DerivedCoreProperties.txt> | 17.0.0 | `24c7fed1195c482faaefd5c1e7eb821c5ee1fb6de07ecdbaa64b56a99da22c08` |

The Unicode version is not pinned by the A2UI specification, which names UAX #31 without naming a
revision. `XID_Start` and `XID_Continue` are immutable for characters already assigned, so the only
thing a newer database changes is whether identifiers using newly assigned characters are accepted
-- the reason to track the latest release rather than an older one.

Redistribution terms are in the repository's `NOTICE`.

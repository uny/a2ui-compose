# Vendored Unicode Character Database

This file is copied verbatim from the Unicode Character Database and is the input to the
`generateXidTables` Gradle task, which derives the `XID_Start` and `XID_Continue` code point ranges
and embeds them as Kotlin source. It is not edited here: to move to a newer Unicode version,
replace the file, update the table below, and update the two code point counts `Uax31Test` asserts.

The SHA-256 in that table is **checked by the build**, not merely recorded: `generateXidTables`
hashes the file and refuses to run when the two disagree. Nothing downstream could tell a corrupted
database from a real one -- the parser accepts any well-formed range, and the task's
`XID_Continue ⊇ XID_Start` assertion only catches ranges that went *missing* -- so a file that
gained a line would widen the identifier rule and leave the suite green. Replacing the file without
updating the row is therefore a named build failure rather than a silently different validator.

What that check is *not* is supply-chain security. The digest sits in the same repository as the
file it authenticates, so anyone who can change one can change the other; it is worth nothing
against a hostile pull request or a compromised maintainer, and it establishes no independent root
of trust the first time the file lands. What it does catch is drift a human would not: a partial
download, an editor that rewrote the file, a checkout that changed its line endings. Verify the
digest against `unicode.org` yourself when replacing the file -- that is the step this cannot do
for you.

A2UI v1.0 requires every catalog entity identifier and every extension key to match
`^[\p{XID_Start}_][\p{XID_Continue}]*$` (`docs/a2ui_protocol.md`). That is the specification's
prose; the vendored JSON schemas carry the pattern in exactly one place, the `patternProperties`
key on `extensions` in `common_types.json`, so extension keys are the only names this library
checks today. Component, function and argument names are stated by the prose and by upstream's own
test runner but by no schema, and enforcing them is a behaviour change of its own. Neither half is
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

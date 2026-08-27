# Vendored A2UI specification examples

These files are copied verbatim from the A2UI specification and are the input to the
`generateExampleSources` and `generateGalleryCatalog` Gradle tasks, which embed them as Kotlin
source. They are not edited here: to move to a newer specification revision, replace the files and
record the new commit below.

`examples/` is the specification's own sample corpus. The SDK implementation skill names five of
them as the foundational verification set — `00_simple-text`, `00_row-layout`, `00_complex-layout`,
`00_interactive-button`, `00_simple-login-form` — and requires the Gallery to load all of them
before a renderer is considered complete. They are embedded rather than read from disk because the
Gallery runs on Kotlin/JS and Kotlin/Wasm in a browser, where there is no disk to read.

`basic.json` is here because validating an example means resolving the catalog its `catalogId`
names. It duplicates `a2ui-core/spec/v1_0/catalogs/basic.json`, which is a copy this repository
should not keep: a renderer needs the basic catalog document at runtime to resolve children and
check components, so it belongs in `a2ui-compose`'s published sources rather than in two test
corpora. Removing this copy is a task item on T7.

| File | Source | Revision |
|:--|:--|:--|
| `v1_0/examples/*.json` (43 files) | [`a2ui-project/a2ui`](https://github.com/a2ui-project/a2ui) `specification/v1_0/catalogs/basic/examples/` | `b571daf8` |
| `v1_0/basic.json` | [`a2ui-project/a2ui`](https://github.com/a2ui-project/a2ui) `specification/v1_0/catalogs/basic/catalog.json` | `b571daf8` |

Licensed under the Apache License 2.0. See the repository's `NOTICE`.

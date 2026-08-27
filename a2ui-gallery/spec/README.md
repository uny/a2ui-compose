# Vendored A2UI specification examples

These files are copied verbatim from the A2UI specification and are the input to the
`generateExampleSources` Gradle task, which embeds them as Kotlin source. They are
not edited here: to move to a newer specification revision, replace the files and record the new
commit below.

`examples/` is the specification's own sample corpus. The SDK implementation skill names five of
them as the foundational verification set — `00_simple-text`, `00_row-layout`, `00_complex-layout`,
`00_interactive-button`, `00_simple-login-form` — and requires the Gallery to load all of them
before a renderer is considered complete. They are embedded rather than read from disk because the
Gallery runs on Kotlin/JS and Kotlin/Wasm in a browser, where there is no disk to read.

| File | Source | Revision |
|:--|:--|:--|
| `v1_0/examples/*.json` (43 files) | [`a2ui-project/a2ui`](https://github.com/a2ui-project/a2ui) `specification/v1_0/catalogs/basic/examples/` | `b571daf8` |

Licensed under the Apache License 2.0. See the repository's `NOTICE`.

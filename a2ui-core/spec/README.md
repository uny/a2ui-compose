# Vendored A2UI specification schemas

These files are copied verbatim from the A2UI specification and are the input to the
`generateProtocolSchemas` and `generateCatalogFixtures` Gradle tasks, which embed them as Kotlin
source -- in the library and in its tests respectively. They are
not edited here: to move to a newer specification revision, replace the file and record the new
commit below.

`agent_to_renderer.json` is here for the same reason: its `Component` definition is the entry
point that composes a component's envelope with whatever the catalog says, and it is what carries
the rule that no message may create a component named `Surface`.

A renderer has to resolve the `$ref`s a catalog makes into `common_types.json` — thirty-two of
them are `DynamicString` alone — so the document has to be present at runtime. Embedding it is
what keeps `$ref` resolution from becoming a network fetch, which would let an agent choose what
its own payload is validated against.

| File | Source | Revision |
|:--|:--|:--|
| `v1_0/common_types.json` | [`a2ui-project/a2ui`](https://github.com/a2ui-project/a2ui) `specification/v1_0/json/common_types.json` | `b571daf8` |
| `v1_0/agent_to_renderer.json` | [`a2ui-project/a2ui`](https://github.com/a2ui-project/a2ui) `specification/v1_0/json/agent_to_renderer.json` | `b571daf8` |
| `v1_0/renderer_to_agent.json` | [`a2ui-project/a2ui`](https://github.com/a2ui-project/a2ui) `specification/v1_0/json/renderer_to_agent.json` | `b571daf8` |
| `v1_0/catalog_definition.json` | [`a2ui-project/a2ui`](https://github.com/a2ui-project/a2ui) `specification/v1_0/json/catalog_definition.json` | `b571daf8` |
| `v1_0/catalogs/testing.json` | [`a2ui-project/a2ui`](https://github.com/a2ui-project/a2ui) `specification/v1_0/test/testing_catalog.json` | `b571daf8` |
| `v1_0/catalogs/basic.json` | [`a2ui-project/a2ui`](https://github.com/a2ui-project/a2ui) `specification/v1_0/catalogs/basic/catalog.json` | `b571daf8` |

Licensed under the Apache License 2.0. See the repository's `NOTICE`.

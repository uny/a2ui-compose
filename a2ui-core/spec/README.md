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
| `v1_0/common_types.json` | [`a2ui-project/a2ui`](https://github.com/a2ui-project/a2ui) `specification/v1_0/json/common_types.json` | `c69ed0dc` |
| `v1_0/agent_to_renderer.json` | [`a2ui-project/a2ui`](https://github.com/a2ui-project/a2ui) `specification/v1_0/json/agent_to_renderer.json` | `c69ed0dc` |
| `v1_0/renderer_to_agent.json` | [`a2ui-project/a2ui`](https://github.com/a2ui-project/a2ui) `specification/v1_0/json/renderer_to_agent.json` | `c69ed0dc` |
| `v1_0/catalog_definition.json` | [`a2ui-project/a2ui`](https://github.com/a2ui-project/a2ui) `specification/v1_0/json/catalog_definition.json` | `c69ed0dc` |
| `v1_0/catalogs/testing.json` | [`a2ui-project/a2ui`](https://github.com/a2ui-project/a2ui) `specification/v1_0/test/testing_catalog.json` | `c69ed0dc` |
| `v1_0/catalogs/basic.json` | [`a2ui-project/a2ui`](https://github.com/a2ui-project/a2ui) `catalogs/basic/v1/catalog.json` | `c69ed0dc` |
| `v1_0/cases/*.json` (17 files) | [`a2ui-project/a2ui`](https://github.com/a2ui-project/a2ui) `specification/v1_0/test/cases/*.json` | `c69ed0dc` |
| `v1_0/streams/contact_form_example.json` | [`a2ui-project/a2ui`](https://github.com/a2ui-project/a2ui) `specification/v1_0/test/cases/contact_form_example.jsonl` | `c69ed0dc` |
| `conformance/core/*.json` (3 files) | [`a2ui-project/a2ui`](https://github.com/a2ui-project/a2ui) `conformance/core/{data_model,data_deletion,data_model_pointers}.yaml` | `c69ed0dc` |

Every row was taken at one revision. A later update may move a single row again, as the earlier
revisions of this table did.

`v1_0/cases/` takes every `.json` file in the upstream directory. The one `.jsonl` file there,
`contact_form_example.jsonl`, is a message stream `run_tests.py` validates line by line; it is
vendored as `v1_0/streams/contact_form_example.json`, one JSON array holding the same messages in
the same order, for the same no-parser reason as below.

`conformance/core/` is the one place the content is not byte-for-byte upstream's: those suites are
written in YAML, and nothing that reads them here -- the embedding task, or the tests on JS, wasm and
Native -- has a YAML parser. They are converted with Ruby's standard library and are otherwise
unchanged:

```bash
ruby -ryaml -rjson -e 'puts JSON.pretty_generate(YAML.safe_load(File.read(ARGV[0])))' \
  conformance/core/data_model.yaml > a2ui-core/spec/conformance/core/data_model.json
```

The rest of upstream's `conformance/core/` is not vendored. Most of it drives APIs this library
does not have (the reactive node resolver, agent function routing, the version 0.8/0.9 adapters),
and `DataModelConformanceTest` lists the cases it skips from the three it does run, with reasons.

Licensed under the Apache License 2.0. See the repository's `NOTICE`.

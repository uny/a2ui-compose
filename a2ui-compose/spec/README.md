# Vendored A2UI basic catalog

Copied verbatim from the A2UI specification and embedded as Kotlin source by the
`generateBasicCatalog` Gradle task. It is not edited here: to move to a newer specification
revision, replace the file and record the new commit below.

This is a published source, not a test fixture. A renderer cannot do without it at runtime: the
catalog is what names the property carrying each component's children -- `child`, `children`,
`trigger`, `content`, and one nested inside each element of `Tabs.tabs` -- so `CatalogChildResolver`
reads it to walk the tree, and `CatalogValidator` reads it to check a component against the schema
its catalog declares. An agent sends `catalogId` as a bare string; nothing fetches it, and nothing
should, since a catalog fetched from a URL the agent chose is a catalog the agent chose to be
validated against.

| File | Source | Revision |
|:--|:--|:--|
| `v1_0/basic.json` | [`a2ui-project/a2ui`](https://github.com/a2ui-project/a2ui) `specification/v1_0/catalogs/basic/catalog.json` | `b571daf8` |

Licensed under the Apache License 2.0. See the repository's `NOTICE`.

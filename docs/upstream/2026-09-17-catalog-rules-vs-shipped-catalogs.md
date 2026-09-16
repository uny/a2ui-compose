# Upstream report draft: two places the v1.0 catalog rules and the shipped artifacts disagree

Tracked in uny/a2ui-compose#51. Facts below were read from `a2ui-project/a2ui` at `b571daf8`
(2026-08-25), `specification/v1_0/`. Not yet filed; the destination is the specification
repository's issue tracker. Two issues rather than one, since the fixes are independent.

---

## Issue 1: Rule 3's `$ref` allow-list does not admit the schemas `basic/catalog.json` uses

### Where

`docs/a2ui_protocol.md`, "Catalog Schema Rules and Conventions", rule 3 "Restricted `$ref`
Targets" (line 565 at `b571daf8`):

> External `$ref` targets MUST reference the standard types inside `common_types.json`
> (`https://a2ui.org/specification/v1_0/common_types.json#/$defs/...`), limited to the following
> allowed schemas: `ComponentId`, `ChildList`, `DynamicString`, `DynamicNumber`, `DynamicBoolean`,
> `DynamicStringList`, `DynamicValue`, `AccessibilityAttributes`, `CheckRule`, `Checkable`, `Action`

### What the shipped catalogs do

`catalogs/basic/catalog.json` references four `common_types.json` schemas that are not on the list:

| schema | occurrences | first at |
|:--|--:|:--|
| `Child` | 5 | line 315 |
| `DataBinding` | 2 | line 150 |
| `FunctionCommon` | 14 | line 708 |
| `FunctionCall` | 1 | line 1121 |

`Child` is how every single-child container (`Card`, `Modal`, `Tabs.tabs[].child`, …) names its
child; `FunctionCommon` is how every function definition composes the wire-level call shape rule 6
requires. Neither is optional for a catalog written the way `basic` is.

`test/testing_catalog.json` additionally writes the relative form `common_types.json#/$defs/…`
(lines 14, 24, 37, …) rather than the absolute URL the rule gives. Whether the relative spelling
is permitted is not stated either way.

### Why it matters

An implementer who enforces rule 3 as written cannot load the reference catalogs. An implementer
who loads the reference catalogs is not enforcing rule 3. Either the list is incomplete or
`basic/catalog.json` is non-conformant; whichever it is, the prose and the artifact should agree.

### Suggested resolution

Add `Child`, `DataBinding`, `FunctionCommon` and `FunctionCall` to the list (the reading under
which `basic` is conformant), and state whether the relative `common_types.json#` spelling is
equivalent to the absolute one.

### What this renderer does meanwhile

`uny/a2ui-compose` enforces the *document* -- an external `$ref` must target `common_types.json`
-- but not the list, so the shipped catalogs load. Recorded in `CatalogEntityNames.kt`
(`requirePermittedReference`).

---

## Issue 2: `run_tests.py` applies the naming rule to `metadata.extensions`, which the schema says is opaque

### Where

`test/run_tests.py`, `validate_catalogs_identifiers` → `check_schema_properties` (line 307 at
`b571daf8`). The walk recurses into every `dict` and `list` under a component definition,
`metadata` included, and checks every key under any `properties` object it finds against
`str.isidentifier()`.

### What the schema says

`json/common_types.json` types `metadata.extensions` as `#/$defs/Extensions` (line 65), described
as "Optional extension metadata" whose keys must be UAX #31 identifiers -- but whose *values* are
unconstrained vendor JSON. A vendor block that happens to contain an object with a `properties`
key -- for example an extension that carries its own JSON Schema fragment --

```json
"metadata": {"extensions": {"com_vendor_x": {"properties": {"x-legacy": {}}}}}
```

is refused by the harness with `Invalid argument/property name: 'x-legacy'`, although no rule in
the prose applies the naming rule to vendor data. The naming section's scope is "Catalog Entity
Naming Rules": component, function, argument and property names the catalog declares.

### Why it matters

The harness refuses catalogs that break no stated rule, and only in the vendor-extension case
the schema explicitly leaves open. An implementer following the harness rather than the prose
inherits a rule the prose does not state.

### Suggested resolution

Have `check_schema_properties` descend only into JSON Schema subschema positions (`properties`,
`items`, `allOf`/`anyOf`/`oneOf`, `$defs`, `additionalProperties`, …), not into `metadata` or
other annotation values. Or, if the intent is that vendor extension *values* are also held to
the rule, say so in the prose.

### What this renderer does meanwhile

`uny/a2ui-compose` walks only subschema positions, so `metadata.extensions` is treated as data.
The compensating rule is that a `$ref` may not be aimed into a region the walk did not enter, so
nothing unchecked becomes reachable. Recorded in `CatalogEntityNames.kt` (`checkSchema`).

---

## Not included

- Whether a name a catalog only *references* (`required`, `dependentSchemas` keys) is an entity
  name -- uny/a2ui-compose#49, settled locally as "no" and not a spec bug.
- The Kotlin/Native crash behind uny/a2ui-compose#31 -- a different upstream (JetBrains), and it
  needs a library-independent reduction first.

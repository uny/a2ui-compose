# a2ui-compose

A renderer for the [A2UI protocol](https://a2ui.org/) built on **Compose Multiplatform** — Android, iOS, desktop (JVM), macOS, and web (JS + wasmJs) from a single `commonMain`.

> **Status: pre-alpha.** Nothing is published yet and the API is not stable. See [Roadmap](#roadmap).

A2UI lets an agent describe a user interface as a stream of JSON, which the client renders with its
own native widgets. The agent never ships code — the catalog of renderable components is the trust
boundary, and it lives in your binary.

## Protocol version

**This library targets A2UI v1.0 and carries no v0.8/v0.9 compatibility layer.**

The spec site currently labels v0.9.1 "Current" and v1.0 a "release candidate", so that choice
deserves an explanation. The official renderers already ship v1.0: `@a2ui/react` 0.10.2,
`@a2ui/angular` 0.10.5 and `@a2ui/lit` 0.10.3 are all on the 0.10.x line, which is v1.0 under its
former draft name. The "RC" label lags the reference implementations.

Every other Kotlin renderer is on v0.9. Rather than add a fifth v0.9 implementation and inherit a
compatibility layer on day one, this one starts where the official renderers already are.

If v1.0 takes a breaking change before GA, the fallback is a stable-v0.9.1 / experimental-v1.0 split
rather than a rewrite.

## Targets

| Target | |
|:--|:--|
| Android | `minSdk` 24, `compileSdk` 37 |
| JVM (desktop) | |
| iOS | `iosArm64`, `iosSimulatorArm64` |
| macOS | `macosArm64` |
| Web | `js(browser)`, `wasmJs(browser)` |

Three targets are deliberately absent. `iosX64` (the Intel iOS simulator) is dropped because Compose
Multiplatform 1.12.0 does not publish variants for it. `macosX64` is dropped because Kotlin has
demoted it out of the supported tiers. `linux`/`mingw` are not Compose targets.

The `compileSdk` floor of 37 is not a preference — it is forced by `androidx.compose:1.12.0`, which
Compose Multiplatform pulls in on Android. It becomes `minCompileSdk` in the published AAR metadata,
so every Android consumer must compile against 37 or later.

## Modules

| Artifact | Contents |
|:--|:--|
| `dev.ynagai.a2ui:a2ui-core` | Protocol types, v1.0 message parsing and serialization, data model and JSON Pointer binding, function evaluation, validation. **No Compose dependency.** |
| `dev.ynagai.a2ui:a2ui-compose` | `A2uiSurface`, `A2uiRenderer`, the component registry, and the bounds that keep an agent's payload from outgrowing a composition. Depends on `compose.runtime` and `compose.ui` — **no design system.** |
| `dev.ynagai.a2ui:a2ui-material3` | All eighteen of the catalog's components drawn with Material 3 -- every component the specification's forty-three examples name. `Video` and `AudioPlayer` draw a media component's frame and play nothing: there is no player in Compose Multiplatform, and a host with a media stack registers its own renderer for the two. `Tabs` and `Modal` hold state the agent cannot see or set, which is what the guide asks for; a `Modal` intercepts its trigger's taps, so a trigger carrying an `action` does not dispatch it. `checks`, the catalog's renderer-side validation, is honoured: a `Button` whose check fails is disabled and a failing input is captioned with the message. Almost every string on a surface is the agent's own; the four that cannot be (a picker dialog's confirm and cancel, a filter field's label, a modal's close button) come from `LocalA2uiStrings`, English until a host provides otherwise. `Image` draws through a host-provided `A2uiImageLoader`, and a described placeholder without one -- this library fetches nothing itself, and a loader that does is handed the agent's URL unvetted. Every leaf and framed component carries a uniform 8dp margin (the guide's Leaf-Margin Strategy), which `Text`, `Button` and `TextField` did not have before. |

The split follows the Core SDK / Framework Adapter separation in the A2UI project's own guidance for
new client SDKs. Material 3 is a third artifact rather than part of the adapter because a design
system is a host's choice: a host with its own components takes `a2ui-compose` alone and writes its
own `ComponentRenderer`s, and pays nothing for a Material 3 it does not use.

Transport is deliberately absent: the library stays transport-free, so you can drive it from SSE,
AG-UI, a WebSocket, or a local agent loop without the library taking an opinion.

## Roadmap

| Gate | Contents | Target |
|:--|:--|:--|
| **G0** | Skeleton — build, CI, all targets compiling | 2026-08-31 |
| **G1** | `a2ui-core`: full v1.0 message handling, JSON Pointer binding, function evaluation | 2026-10-31 |
| **G2** | `a2ui-material3`: the v1.0 standard widget catalog on Material 3 | 2026-12-31 |
| **G3** | Publish `0.1.0` to Maven Central | one month after G2 |

## Prior art

Four Kotlin/Compose A2UI renderers exist. All are worth reading, and none is currently a
production-ready Compose Multiplatform option:

- [`Contextable/a2ui-4k`](https://github.com/Contextable/a2ui-4k) — Apache-2.0, the only one published
  to Maven Central. Already Compose Multiplatform despite the "for KMP" description. v0.9, no wasmJs.
- [`mikepenz/A2CUI`](https://github.com/mikepenz/A2CUI) — the best module decomposition of the four,
  with AG-UI transport and codegen. No LICENSE file, unpublished.
- [`coder-brzhang/a2ui-compose`](https://github.com/coder-brzhang/a2ui-compose) — Android only.
- [`NikhilBhutani/compose-genui`](https://github.com/NikhilBhutani/compose-genui).

## Building

```bash
./gradlew build
```

## License

[Apache-2.0](LICENSE), matching the upstream A2UI project.

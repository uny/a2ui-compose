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
| `dev.ynagai.a2ui:a2ui-material3` | All eighteen of the catalog's components drawn with Material 3 -- every component the specification's forty-three examples name. `Video` and `AudioPlayer` draw a media component's frame and play nothing: there is no player in Compose Multiplatform, and a host with a media stack registers its own renderer for the two. `Tabs` and `Modal` hold state the agent cannot see or set, which is what the guide asks for; a `Modal` intercepts its trigger's taps, so a trigger carrying an `action` does not dispatch it -- and only its *taps*: a keyboard or a screen reader activating the trigger still reaches the button underneath, so it dispatches the action and does not open the dialog, which is the component's one known gap. `checks`, the catalog's renderer-side validation, is honoured: a `Button` whose check fails is disabled and a failing input is captioned with the message. Almost every string on a surface is the agent's own; the five that cannot be (a picker dialog's confirm and cancel, a filter field's label, a modal's close button, a video's frame) come from `LocalA2uiStrings`, English until a host provides otherwise. `Image` draws through a host-provided `A2uiImageLoader`, and a described placeholder without one -- this library fetches nothing itself, and a loader that does is handed the agent's URL unvetted. Every leaf and framed component carries a uniform 8dp margin (the guide's Leaf-Margin Strategy), which `Text`, `Button` and `TextField` did not have before. |

The split follows the Core SDK / Framework Adapter separation in the A2UI project's own guidance for
new client SDKs. Material 3 is a third artifact rather than part of the adapter because a design
system is a host's choice: a host with its own components takes `a2ui-compose` alone and writes its
own `ComponentRenderer`s, and pays nothing for a Material 3 it does not use.

Transport is deliberately absent: the library stays transport-free, so you can drive it from SSE,
AG-UI, a WebSocket, or a local agent loop without the library taking an opinion.

## Gallery

`a2ui-gallery` is the reference environment the A2UI framework adapter blueprint asks every renderer
to ship: three columns — the specification's forty-three examples on the left, the live surface with
its JSON message stream and a step-one-message-at-a-time control in the middle, and the data model
and action log on the right. It is a development tool, and it is **not published**.

It is also where the renderer is exercised on Kotlin/JS. Compose's UI test harness cannot boot Skiko
there, so JS has no rendering test — the Gallery is the thing that runs. It is likewise the only
thing that runs the platform locale tables: it passes `systemLocaleFormatter()`, which the library
itself leaves opt-in. `openUrl` is deliberately not wired, so such an action does nothing here.

One limitation worth knowing before reading a layout off it: the preview scrolls, so a surface is
measured with an **unbounded height** and vertical layout that needs a bounded one does not take
effect — a `Column` whose `justify` spreads its children wraps instead. That is the same thing a
host embedding `A2uiSurface` in its own scroll container sees; check a vertical-arrangement question
somewhere bounded.

```bash
./gradlew :a2ui-gallery:run                              # desktop
./gradlew :a2ui-gallery:wasmJsBrowserDevelopmentRun      # browser, Kotlin/Wasm
./gradlew :a2ui-gallery:jsBrowserDevelopmentRun          # browser, Kotlin/JS
./gradlew :a2ui-gallery:runDebugExecutableMacosArm64     # native macOS
```

On iOS the entry point is `MainViewController()`, written for an Xcode project to set as its root
view controller. It is not reachable yet: the Gallery declares no `binaries.framework`, so there is
no framework to link and no Xcode project is checked in — the iOS targets are compiled and tested,
not packaged. **Android is the one target the Gallery does not build for** — the three library
modules do, and only the Gallery does not.

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

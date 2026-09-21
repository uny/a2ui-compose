# a2ui-compose

A renderer for the [A2UI protocol](https://a2ui.org/) built on **Compose Multiplatform** — Android, iOS, desktop (JVM), macOS, and web (JS + wasmJs) from a single `commonMain`.

> **Status: `0.1.0` is on Maven Central, and the API is not stable.** A `0.x` line: expect
> breaking changes between minor versions. See [Installation](#installation) and
> [Roadmap](#roadmap).

A2UI lets an agent describe a user interface as a stream of JSON, which the client renders with its
own native widgets. The agent never ships code — the catalog of renderable components is the trust
boundary, and it lives in your binary.

## Installation

```kotlin
dependencies {
    implementation("dev.ynagai.a2ui:a2ui-material3:0.1.0")
}
```

That is the usual entry point: it brings `a2ui-compose`, which brings `a2ui-core`. Take the lower
two directly when you do not want the Material 3 renderers -- `a2ui-compose` to draw the catalog
with your own design system, `a2ui-core` for the protocol alone, which carries no Compose dependency
at all:

```kotlin
implementation("dev.ynagai.a2ui:a2ui-compose:0.1.0")
implementation("dev.ynagai.a2ui:a2ui-core:0.1.0")
```

And one above, for the Markdown a `Text` draws. `a2ui-material3` renders headings, emphasis and
code spans without a parser, and passes lists, tables, block quotes and code fences through as
their literal characters. `a2ui-material3-markdown` draws those as blocks, and brings the parser
that does it (`org.jetbrains:markdown`) -- which is why it is a separate artifact rather than a
default:

```kotlin
implementation("dev.ynagai.a2ui:a2ui-material3-markdown:0.1.0")
```

```kotlin
CompositionLocalProvider(LocalA2uiMarkdownRenderer provides Material3MarkdownRenderer) {
    A2uiSurface(/* ... */)
}
```

### What `0.1.0` requires of your build

Three floors and one version line, and the one with the clearest error message is the least
binding of them. All come from what the artifacts were built with, not from anything the library
does. `a2ui-core` alone carries the first and the last; the Compose floor arrives with
`a2ui-compose`, and the parser only with `a2ui-material3-markdown`:

| Floor | Where it is declared | How it fails for you |
|:--|:--|:--|
| **Kotlin 2.4.x** on the klib targets (iOS, macOS, JS, wasm); **2.3.x or later** on JVM and Android | The klib manifest (`compiler_version = 2.4.10`, `abi_version = 2.4.0`); on JVM and Android, `@kotlin.Metadata` 2.4.0 in the class files and `requires: 2.4.10` on `kotlin-stdlib` | A 2.3.x compiler refuses a klib whose ABI version is newer than its own, and the Kotlin plugin version is project-wide -- so a KMP project with any of those targets moves its whole toolchain. Class-file metadata is read one language release ahead (Kotlin's stated best effort, not a guarantee), so a 2.3.x JVM or Android project compiles against `0.1.0`, with `kotlin-stdlib` moved to 2.4.10 the same silent way Compose is below. A KSP processor in the build does not move the floor: since KSP2, KSP releases are decoupled from your Kotlin version, and KSP 2.3.10 and later run on Kotlin 2.4 projects. |
| **Compose Multiplatform 1.12.0** | `requires: 1.12.0` on `org.jetbrains.compose.runtime:runtime` / `org.jetbrains.compose.ui:ui` in the Gradle module metadata; `a2ui-material3` adds `requires: 1.9.0` on `org.jetbrains.compose.material3:material3`, which ships on its own version line | **Silently.** Under Gradle's default conflict resolution the highest version wins, so a project on 1.10.0 is moved to 1.12.0 without being told. Holding the older version with `strictly` on your own declaration does not fail either: it downgrades the library's `requires` to 1.10.0, and the code compiled against 1.12.0 breaks at runtime instead. |
| **`org.jetbrains:markdown` 0.7.14** (`a2ui-material3-markdown` only) | `requires: 0.7.14` on `org.jetbrains:markdown` in that module's Gradle module metadata. Declared `implementation`, which keeps it off your *compile* class path on JVM, Android, JS and wasmJs -- and not on iOS or macOS, where the Kotlin/Native plugin publishes it in the `iosArm64ApiElements` / `macosArm64ApiElements` variants -- a consumer's compile class path -- while the JS and wasmJs klibs keep it at runtime only (all measured on the local publish). No type of it appears in the module's API either way | **Silently**, the same way as Compose: a project that already depends on an older `org.jetbrains:markdown` is moved up to 0.7.14. The other three modules carry no parser and are unaffected. |
| **`compileSdk` 37** (Android) | `minCompileSdk=37` in the AAR metadata | AGP fails the build with a clear message. Cheap to fix: `compileSdk` is what you compile against, and `targetSdk` need not move with it. See [Targets](#targets) for why 37. |

`0.1.0` was built with Kotlin 2.4.10 and Compose Multiplatform 1.12.0, the current stable lines
at the time. Whether a later `0.x` lowers the klib floor to 2.3.x is
[#64](https://github.com/uny/a2ui-compose/issues/64); a release that changes any floor will say so
in its notes rather than leave it to be discovered.

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

### UAX #31 is enforced on catalogs and in `formatString`

`a2ui_protocol.md`'s "Catalog Entity Naming Rules" make component, function and argument names
MUST-conform to UAX #31, and this library enforces that where a catalog is built:
`CatalogDefinition` answers `^[\p{XID_Start}_][\p{XID_Continue}]*$` from the Unicode 17.0.0
derived tables, in its constructor, so a catalog assembled in Kotlin is held to it as well as one
decoded off the wire.

`formatString`'s expression parser answers from the same tables. The specification states no
production for a name inside a `${…}` expression, so there is no naming rule there to conform to;
what decides it is that a name a catalog accepts should be a name a format string can call.
`0.1.0` judged those names by a general-category approximation of its own (`isLetter()`,
`isLetterOrDigit()`), which refused `_helper` — a valid entity name — and accepted `ͺ` (U+037A,
`ID_Start` but not `XID_Start`), which a catalog refuses. The two were unified in
[#45](https://github.com/uny/a2ui-compose/issues/45); `@index` remains the one name with a `@` a
template may call.

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

### What is tested where

`commonTest` runs on all six targets, Android included — 492 of the suite's assertions run against
the Android variant on the JVM, which is what `withHostTest {}` buys. Before that they ran on five
targets and the sixth was silent: not one test had ever executed against the variant whose `.aar`
is published.

**The Compose UI tests are the exception, and Android is the target that misses them.** They need
a composition to draw into; Android's host tests have none without Robolectric or an on-device
instrumentation run, and this repository has neither. So the component renderers in
`a2ui-material3` — what a `CheckBox` does with a tap, what a `Modal` intercepts — ship in the
`.aar` with nothing on Android having drawn them. **They are covered on JVM, macOS, iOS and
wasmJs from the same `commonMain`, which is most of the argument but not all of it.** Kotlin/JS is
in the same position for a different reason (its test harness cannot boot Skiko) and is covered by
the Gallery instead; Android has no equivalent, because the Gallery does not build for it.

## Modules

| Artifact | Contents |
|:--|:--|
| `dev.ynagai.a2ui:a2ui-core` | Protocol types, v1.0 message parsing and serialization, data model and JSON Pointer binding, function evaluation, validation. **No Compose dependency.** |
| `dev.ynagai.a2ui:a2ui-compose` | `A2uiSurface`, `A2uiRenderer`, the component registry, and the bounds that keep an agent's payload from outgrowing a composition. Depends on `compose.runtime` and `compose.ui` — **no design system.** |
| `dev.ynagai.a2ui:a2ui-material3` | All eighteen of the catalog's components drawn with Material 3 -- every component the specification's forty-three examples name. `Video` and `AudioPlayer` draw a media component's frame and play nothing: there is no player in Compose Multiplatform, and a host with a media stack registers its own renderer for the two. `Tabs` and `Modal` hold state the agent cannot see or set, which is what the guide asks for; a `Modal` intercepts its trigger's taps, so a trigger carrying an `action` does not dispatch it -- and only its *taps*: a keyboard or a screen reader activating the trigger still reaches the button underneath, so it dispatches the action and does not open the dialog, which is the component's one known gap. `checks`, the catalog's renderer-side validation, is honoured: a `Button` whose check fails is disabled and a failing input is captioned with the message. Almost every string on a surface is the agent's own; the five that cannot be (a picker dialog's confirm and cancel, a filter field's label, a modal's close button, a video's frame) come from `LocalA2uiStrings`, English until a host provides otherwise. `Image` draws through a host-provided `A2uiImageLoader`, and a described placeholder without one -- this library fetches nothing itself, and a loader that does is handed the agent's URL unvetted. `Text` draws its Markdown through `LocalA2uiMarkdownRenderer`: by default a parser-free subset (headings, emphasis, code spans) that passes lists, tables, block quotes and code fences through as their literal characters. `a2ui-material3-markdown` below is the renderer for the rest, and a host with one of its own provides it there and keeps everything else `Text` does -- and is handed the agent's text unvetted and unbounded, links included, which the default reduces to their labels so that nothing can open a URL past `openUrl`. Every leaf and framed component carries a uniform 8dp margin (the guide's Leaf-Margin Strategy), which `Text`, `Button` and `TextField` did not have before. |
| `dev.ynagai.a2ui:a2ui-material3-markdown` | `Material3MarkdownRenderer`, an `A2uiMarkdownRenderer` that draws the blocks the default passes through -- lists (ordered, unordered, nested), block quotes, GFM tables, fenced and indented code, thematic breaks -- on top of `org.jetbrains:markdown`, which is the dependency that keeps it out of `a2ui-material3`. Installed with one `CompositionLocalProvider` line; `Text` keeps the variant's style and the leaf margin, so a caption's list shrinks with the caption. The specification's exclusions hold: links are their labels with no `LinkAnnotation` attached, images are their alt text, raw HTML is not drawn. Bounded the way the default is -- above the same 16,384 characters the text is drawn verbatim -- and separately in nesting depth, since the parser accepts a depth the stack on wasmJs would not. Every block converts to a value the tests compare, so the conversion is pinned on every target and the composition only draws. |

### Locale formatting is opt-in, and the default is a placeholder

`formatNumber`, `formatCurrency`, `formatDate` and `pluralize` run through a `LocaleFormatter`, and
the one they get unless a host chooses otherwise is `FallbackLocaleFormatter` — which opens its own
documentation by saying it is not a locale implementation. It renders `USD 1,234.50` rather than a
symbol, applies the English `n == 1` plural rule whatever the language, and uses English month
names.

**That is the intended default, not an oversight.** A renderer that read the device by default would
make one payload render differently in CI than on a desk. But it is chosen silently, so choose:

```kotlin
val renderer = remember {
    A2uiRenderer(A2uiRendererConfig.Default.withLocale(systemLocaleFormatter()))
}
```

`localeFormatter(tag)` takes a fixed locale instead. The corpus reaches those four functions in
roughly thirty places, so a host that ships without choosing is shipping the placeholder.

Every other renderer setting is on the same object. `A2uiRendererConfig` exists so that the
renderer's constructor is not its compatibility surface: seven defaulted constructor parameters
read well and cannot be extended, because a caller naming one binds to the synthetic defaults
constructor — parameter list plus bitmask — and an eighth setting would break every consumer
compiled against the old one. A new setting adds a `withX` to the config and breaks nothing.

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

| Gate | Contents | Target | Closed |
|:--|:--|:--|:--|
| **G0** | Skeleton — build, CI, all targets compiling | 2026-08-31 | ✅ 2026-08-25 |
| **G1** | `a2ui-core`: full v1.0 message handling, JSON Pointer binding, function evaluation | 2026-10-31 | ✅ 2026-08-27 |
| **G2** | `a2ui-material3`: the v1.0 standard widget catalog on Material 3 | 2026-12-31 | ✅ 2026-09-01 |
| **G3** | Publish `0.1.0` to Maven Central | 2026-12-31 | ✅ 2026-09-08 |

All four are closed, each ahead of its date. That is a different claim from "finished": `0.1.0` is a
`0.x` line whose API is not stable, the release notes list what a consumer should know before
adopting it, and the ✅ on G3 is a publication rather than a claim of full conformance — see
[UAX #31 is enforced on catalogs and in
`formatString`](#uax-31-is-enforced-on-catalogs-and-in-formatstring) for the one rule `0.1.0`
applied unevenly, unified since.

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

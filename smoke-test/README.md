# Consumer smoke test

A separate Gradle build that depends on `dev.ynagai.a2ui` **by coordinate**, from a repository,
the way anyone else would.

## Why it exists

Publishing a Kotlin Multiplatform library has a failure mode that no amount of green in the
producer's own build will show: **the publish succeeds and the consumer cannot resolve.** The
producer's tests run against project dependencies, which resolve through its own configurations
and never read the `.module` metadata, the POM, or the per-target coordinates. A variant that was
published wrong, or not published at all, is invisible until someone else tries to use the release.

Maven Central makes that expensive. A version is never re-uploaded and never deleted, so a broken
publication is not fixed — it is abandoned, and the number is burnt.

So this build resolves the artifacts from a repository and compiles against a symbol from each of
the three, on every target the library publishes. Resolution alone would not be enough: an artifact
can resolve and still be missing the class, because a `.module` file can point a variant at a jar
that does not carry it.

## Running it by hand

From the repository root, with its wrapper -- this build has none of its own, because a second
copy of `gradle-wrapper.jar` is a second thing to keep pinned:

```bash
./gradlew publishToMavenLocal
./gradlew -p smoke-test compileAll
```

Needs an Android SDK (`ANDROID_HOME`, or `sdk.dir` in `smoke-test/local.properties`) and, for the
three Apple targets, macOS.

`-Pa2uiVersion=<version>` selects what to resolve; with no property it reads `VERSION_NAME` from
the producer's `../gradle.properties`, so it cannot go on naming a version the producer has left
behind.

`compileAll` is two things in order. First `checkPublishedSets`, which reads every `.module` file
the publish wrote under `dev/ynagai/a2ui/` at that version, takes as a root each module no other
module's `available-at` points to, and fails if a root has no coordinate line here or one of its
variants names a target this build does not — the direction resolution cannot see, below. A root
is found that way rather than by having `available-at` variants because a single-platform module
has none and is a root all the same. Then one compile per target declared in `build.gradle.kts`'s
`kotlin {}` block, derived from that block rather than listed: today
`compileCommonMainKotlinMetadata`, `compileKotlinJvm`, `compileKotlinJs`, `compileKotlinWasmJs`,
`compileKotlinIosArm64`, `compileKotlinIosSimulatorArm64`, `compileKotlinMacosArm64` and
`compileAndroidMain`, and checked with `--dry-run` that the graph is the same one naming those
eight produced. Any of them can still be run on its own.

The metadata one is `compileCommonMainKotlinMetadata`, not `compileKotlinMetadata`: the latter is
a task that exists but is disabled under the hierarchical source-set model, so naming it compiled
nothing. See the second control below.

`checkPublishedSets` reads the repository `mavenLocal()` resolves from: `-Dmaven.repo.local` when
passed, `~/.m2/repository` otherwise. A `localRepository` in `~/.m2/settings.xml` is honoured by
`mavenLocal()` and not by this task, so on a machine that sets one the task reads a directory
the compiles do not: it fails as "nothing published" when `~/.m2/repository` holds no copy of the
version, and checks whatever stale copy sits there when it does. Pass `-Dmaven.repo.local` on
such a machine; CI never has a `settings.xml`. On a warm `~/.m2`, a
module the producer *stopped* publishing still sits at the same `-SNAPSHOT` version and fails
here as unnamed; the message gives the directory to remove. So does the per-target directory of a
target the producer dropped (`a2ui-core-linuxx64`): once no root points to it, it reads as a
root. All of these are false failures, the safe direction; CI runs against a directory nothing
else has written to.

## What it does not check

Compile classpaths only, on a build with no tests and no `run`. So it does **not** see a defect
confined to a *runtime* variant -- `a2ui-core` declares `implementation(kotlinx-coroutines-core)`,
which reaches a consumer's runtime classpath and not its compile classpath, and an omission there
passes this gate on JVM and Android and fails first in a consumer's `NoClassDefFoundError`. Nor
does it run AGP's `checkAarMetadata`, so a `minCompileSdk` raised past a consumer's `compileSdk`
is not caught here either.

It also does not pin each module's `api` scopes individually. It depends on all three, so a type
reachable through more than one of them stays reachable when one downgrades it -- measured:
moving `a2ui-compose`'s `api(compose.runtime)` / `api(compose.ui)` to `implementation` leaves the
gate green, because `a2ui-material3`'s `api(compose.material3)` supplies both transitively.

The module and target sets are still written by hand in `build.gradle.kts`, but no longer
unchecked: *removing* a published target fails resolution loudly, and *adding* one now fails
`checkPublishedSets`, which compares what the publish wrote against what this build declares.
What remains by hand is `Smoke.kt`: one symbol per module. A new module's coordinate line
satisfies the check, and resolves, with no symbol behind it — so that module is proved to
*resolve*, not that its metadata leads to a class. The failure message says to add the symbol;
nothing enforces it.

## Where it runs

`cd.yml`, between `publishToMavenLocal` and the upload to Central — so a publication that a
consumer cannot resolve fails the release before anything reaches the portal.

`release-dry-run.yml` runs the same pair on demand, against a repository under the runner's temp
rather than `~/.m2`, signed with a key generated in the job. That is where the release path gets
exercised before a tag exists — `cd.yml` itself cannot run until one does, and Central neither
re-uploads nor deletes, so the first release is a poor place for a step's first execution.

`build.yml` runs a reduced pair on every PR, in its `smoke` job: a `-SNAPSHOT` publish, so no
signing key is needed, then `checkPublishedSets` and two of the eight compiles --
`compileCommonMainKotlinMetadata` and `compileKotlinJvm`, one for each half the controls below
name. What a PR checks is that the publish wrote nothing this build does not name, that the three
coordinates resolve, that their metadata jars carry what `Smoke.kt` names, and that one platform
variant leads to real classes -- the rot a tag would otherwise be the first to find. The other
six platform variants are left to the two release workflows, which run `compileAll`.

Both release workflows point the consumer at the repository the publish just wrote, but by
different means, and the difference is what each can promise. `cd.yml` passes no `-Dmaven.repo.local` at all: publish and
consumer both fall through to the default `~/.m2`, so it is the same repository either way — what
a fresh GitHub-hosted runner adds is that the repository holds *nothing else*. On a warm one it
would: a stale `0.1.0` left from an earlier run could answer for a variant this publish failed to
write, and the gate would pass on artifacts this run never produced. `release-dry-run.yml` names a
directory under the runner's temp instead, which nothing else can have written to, so it resolves
what *that run* published wherever it runs. Either way `settings.gradle.kts` binds
`dev.ynagai.a2ui` to `mavenLocal()` exclusively, so nothing else can answer for the group under
test.

## Checking that it still bites

A guard that cannot fail is not a guard. Three controls; the first two re-measured on 2026-09-06, the third on 2026-09-20.

**One published platform variant removed** -- the per-target half:

```bash
mv ~/.m2/repository/dev/ynagai/a2ui/a2ui-core-wasm-js /tmp/
./gradlew -p smoke-test compileKotlinWasmJs --rerun-tasks      # must fail to resolve
mv /tmp/a2ui-core-wasm-js ~/.m2/repository/dev/ynagai/a2ui/
```

`Could not find dev.ynagai.a2ui:a2ui-core-wasm-js:0.1.0-SNAPSHOT`. This keeps working after
`0.1.0` is on Central because `dev.ynagai.a2ui` is bound to `mavenLocal()` by `exclusiveContent`
and is not looked up anywhere else -- otherwise the fallback would answer and the control would
stop biting.

**Only the metadata variant broken**, every platform variant left intact -- the half a
per-target-only gate cannot see, and the reason the task name above changed:

```bash
mv ~/.m2/repository/dev/ynagai/a2ui/a2ui-core/0.1.0-SNAPSHOT/a2ui-core-0.1.0-SNAPSHOT.jar /tmp/
./gradlew -p smoke-test compileCommonMainKotlinMetadata --rerun-tasks   # must fail
./gradlew -p smoke-test compileKotlinMetadata --rerun-tasks             # the old name: SKIPPED, green
mv /tmp/a2ui-core-0.1.0-SNAPSHOT.jar ~/.m2/repository/dev/ynagai/a2ui/a2ui-core/0.1.0-SNAPSHOT/
```

`compileCommonMainKotlinMetadata` fails in `transformCommonMainDependenciesMetadata` with
`Could not find dev.ynagai.a2ui:a2ui-core:0.1.0-SNAPSHOT`; `compileKotlinMetadata` reports
`SKIPPED` and exits 0. A consumer writing `commonMain` against that publication could not have
compiled, and the gate as first written would have passed it.

**A published target this build does not name** -- the addition direction, which resolution
cannot see. Measured on 2026-09-20 by taking a declaration *out* of this build, which is the
same state as the producer adding one:

```bash
sed -i '' 's/^    macosArm64()$/    \/\/ macosArm64()/' smoke-test/build.gradle.kts
./gradlew -p smoke-test checkPublishedSets                            # must fail
git checkout smoke-test/build.gradle.kts
```

```
> The producer published something this build does not name, so no compile here resolves it:
    - published target {org.jetbrains.kotlin.native.target=macos_arm64, org.jetbrains.kotlin.platform.type=native} is declared by no target in this build's `kotlin {}`
```

Deleting the `a2ui-material3` coordinate line instead fails the same way, naming
`dev.ynagai.a2ui:a2ui-material3:<version>` and its directory. So does a jvm-only `a2ui-jvmonly`
placed in the repository -- a root whose one variant has no `available-at` -- which the first
draft of this task filtered out with the per-target modules and reported as fully covered. And
pointed at an empty `-Dmaven.repo.local`, it fails with `No dev.ynagai.a2ui:*:<version> under ...`
rather than reporting an empty publication as fully covered.

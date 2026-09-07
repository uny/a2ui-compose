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
./gradlew -p smoke-test \
  compileCommonMainKotlinMetadata compileKotlinJvm compileKotlinJs compileKotlinWasmJs \
  compileKotlinIosArm64 compileKotlinIosSimulatorArm64 compileKotlinMacosArm64 \
  compileAndroidMain
```

Needs an Android SDK (`ANDROID_HOME`, or `sdk.dir` in `smoke-test/local.properties`) and, for the
three Apple targets, macOS.

`-Pa2uiVersion=<version>` selects what to resolve; with no property it reads `VERSION_NAME` from
the producer's `../gradle.properties`, so it cannot go on naming a version the producer has left
behind.

`compileCommonMainKotlinMetadata`, not `compileKotlinMetadata`: the latter is a task that exists
but is disabled under the hierarchical source-set model, so naming it compiled nothing. See the
second control below.

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
gate green, because `a2ui-material3`'s `api(compose.material3)` supplies both transitively. And the module and target lists are enumerated by hand in this build:
*removing* a published target fails loudly, but *adding* one is simply not covered until someone
adds it here too.

## Where it runs

`cd.yml`, between `publishToMavenLocal` and the upload to Central — so a publication that a
consumer cannot resolve fails the release before anything reaches the portal.

`release-dry-run.yml` runs the same pair on demand, against a repository under the runner's temp
rather than `~/.m2`, signed with a key generated in the job. That is where the release path gets
exercised before a tag exists — `cd.yml` itself cannot run until one does, and Central neither
re-uploads nor deletes, so the first release is a poor place for a step's first execution.

Both point the consumer at the same repository the publish just wrote, with `-Dmaven.repo.local`.
`mavenLocal()` honours that property, and `settings.gradle.kts` binds `dev.ynagai.a2ui` to
`mavenLocal()` exclusively, so nothing else can answer for the group under test.

## Checking that it still bites

A guard that cannot fail is not a guard. Two controls, both re-measured on 2026-09-06.

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

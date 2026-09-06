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
  compileKotlinMetadata compileKotlinJvm compileKotlinJs compileKotlinWasmJs \
  compileKotlinIosArm64 compileKotlinIosSimulatorArm64 compileKotlinMacosArm64 \
  compileAndroidMain
```

`-Pa2uiVersion=<version>` selects what to resolve; it defaults to `0.1.0-SNAPSHOT`.

## Where it runs

`release.yml`, between `publishToMavenLocal` and the upload to Central — so a publication that a
consumer cannot resolve fails the release before anything reaches the portal.

## Checking that it still bites

A guard that cannot fail is not a guard. Remove one published variant and it must stop:

```bash
mv ~/.m2/repository/dev/ynagai/a2ui/a2ui-core-wasm-js /tmp/
./gradlew -p smoke-test compileKotlinWasmJs --rerun-tasks      # must fail to resolve
mv /tmp/a2ui-core-wasm-js ~/.m2/repository/dev/ynagai/a2ui/
```

Measured on 2026-09-06: `Could not find dev.ynagai.a2ui:a2ui-core-wasm-js:0.1.0-SNAPSHOT`.

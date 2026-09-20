/*
 * A consumer, not a subproject.
 *
 * This is a separate Gradle build with its own `settings.gradle.kts`, and it depends on
 * `dev.ynagai.a2ui` by *coordinate* rather than by project. That is the whole point: a project
 * dependency resolves through the producer's own configurations and so never reads the `.module`
 * metadata, the POM, or the per-target coordinates a real consumer resolves. The failure this
 * guards against is the one that does not show up until someone else tries to use the release --
 * publish succeeds, and the consumer cannot resolve.
 *
 * It declares every target the library publishes. Resolution is per-target, so a variant that was
 * published wrong, or not published at all, fails here and nowhere else.
 *
 * The other direction -- a module or target the producer *added* and this build does not name --
 * is what `checkPublishedSets` below is for; resolution cannot see it, because nothing asks for
 * the variant.
 */
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import java.util.Properties

plugins {
    // From the producer's own version catalog, read across the build boundary in
    // `settings.gradle.kts`. A consumer pinned to a different Kotlin than the library was built
    // with is not a consumer this gate should be testing.
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

/**
 * The version under test, passed in by the release workflow as `-Pa2uiVersion`.
 *
 * With no property it falls back to the producer's own `VERSION_NAME`, read across the build
 * boundary rather than copied. A literal default here would keep naming `0.1.0-SNAPSHOT` after the
 * producer moved on, and because `mavenLocal()` still holds that older snapshot the by-hand run
 * would go green against artifacts that are not the ones just published.
 */
val a2uiVersion: String =
    (findProperty("a2uiVersion") as String?)?.takeIf { it.isNotBlank() }
        ?: file("../gradle.properties")
            .takeIf { it.isFile }
            ?.let { properties -> Properties().apply { properties.inputStream().use(::load) } }
            ?.getProperty("VERSION_NAME")
        ?: error(
            "No -Pa2uiVersion, and no VERSION_NAME in ../gradle.properties. This build reads the " +
                "producer's version from the repository it sits in; run it from there, or pass " +
                "-Pa2uiVersion=<version>.",
        )

/** The group under test: the coordinates below, and the directory `checkPublishedSets` reads. */
val a2uiGroup = "dev.ynagai.a2ui"

kotlin {
    android {
        namespace = "dev.ynagai.a2ui.smoketest"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    jvm()

    iosArm64()
    iosSimulatorArm64()
    macosArm64()

    js { browser() }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser() }

    sourceSets {
        commonMain.dependencies {
            implementation("$a2uiGroup:a2ui-core:$a2uiVersion")
            implementation("$a2uiGroup:a2ui-compose:$a2uiVersion")
            implementation("$a2uiGroup:a2ui-material3:$a2uiVersion")
        }
    }
}

/**
 * The repository the consumer resolves from, as `mavenLocal()` in `settings.gradle.kts` locates
 * it: `-Dmaven.repo.local` when passed -- every workflow but `cd.yml` passes it -- and `~/.m2/repository`
 * otherwise. A `localRepository` set in `~/.m2/settings.xml` is not consulted; `mavenLocal()` does
 * honour one, so a machine that relies on it has this task read a different directory than the
 * compiles resolve from: "nothing published" when `~/.m2/repository` holds no copy of the
 * version, and a check of whatever stale copy sits there when it does. Pass `-Dmaven.repo.local`
 * on such a machine; CI runners have no `settings.xml`.
 */
val localRepository: Provider<File> =
    providers.systemProperty("maven.repo.local")
        .map(::File)
        .orElse(File(System.getProperty("user.home"), ".m2/repository"))

/**
 * Everything this build depends on from the group under test, by module name -- read back from
 * the declarations above, so the set this task checks against is the set that resolves.
 */
val declaredModules: Provider<Set<String>> =
    configurations.named("commonMainImplementation").map { configuration ->
        configuration.dependencies.filter { it.group == a2uiGroup }.map { it.name }.toSet()
    }

/**
 * Each declared target's `org.jetbrains.kotlin.*` attributes, from its `apiElements` -- the same
 * attributes the producer's publication stamps on the matching variant, which is what makes them
 * comparable: `platform.type`, plus `native.target` for Kotlin/Native and `wasm.target` for Wasm.
 * The consumer's configurations carry a few more (`klib.packaging`, which publication strips), so
 * the comparison is on the published side's keys only.
 */
val declaredTargetAttributes: Provider<Map<String, Map<String, String>>> = provider {
    kotlin.targets
        .filter { it.platformType != KotlinPlatformType.common }
        .associate { target ->
            val attributes = configurations.getByName(target.apiElementsConfigurationName).attributes
            target.name to attributes.keySet()
                .filter { it.name.startsWith("org.jetbrains.kotlin.") }
                .associate { it.name to attributes.getAttribute(it).toString() }
        }
}

/**
 * Fails when the producer published something this build does not name.
 *
 * Resolution only catches the removal direction: a target this build declares and the producer
 * stopped publishing fails with `Could not find ...`. A target or module the producer *added* is
 * published, asked for by nothing here, and the gate stays green -- exactly the publication a
 * consumer cannot resolve that this build exists to stop (#53).
 *
 * The published set is read from the repository the publish just wrote, not from the producer's
 * build scripts: a root `.module` -- one no other module's `available-at` points to -- lists its
 * variants with the Kotlin attributes a consumer's resolution matches on, one per target behind
 * an `available-at` pointer for a multiplatform module, inline for a single-platform one. Those
 * files are the publication; a set read from anywhere else could disagree with them.
 *
 * Only `<module>/<version>/` for the version under test is read, so a version the producer left
 * behind in `~/.m2` is ignored. A *module* it left behind at the same `-SNAPSHOT` version is not,
 * and fails here as unnamed -- the safe direction, and the message names the directory. That
 * includes a per-target module (`a2ui-core-linuxx64`) of a target the producer dropped: no root
 * points to it any more, so it is read as a root of its own.
 */
val checkPublishedSets = tasks.register("checkPublishedSets") {
    group = "verification"
    description = "Fails if the producer published a module or target this build does not name."

    val repository = localRepository
    val version = a2uiVersion
    val group = a2uiGroup
    val modules = declaredModules
    val targets = declaredTargetAttributes

    doLast {
        val groupDirectory = repository.get().resolve(group.replace('.', '/'))
        val moduleFiles = groupDirectory.listFiles().orEmpty()
            .mapNotNull { directory ->
                directory.resolve("$version/${directory.name}-$version.module").takeIf(File::isFile)
            }
        check(moduleFiles.isNotEmpty()) {
            "No $group:*:$version under $groupDirectory. Run `./gradlew publishToMavenLocal` " +
                "first, with the same -Dmaven.repo.local if any."
        }

        @Suppress("UNCHECKED_CAST")
        val variantsByModule: Map<String, List<Map<String, Any?>>> = moduleFiles.associate { moduleFile ->
            val module = groovy.json.JsonSlurper().parse(moduleFile) as Map<String, Any?>
            moduleFile.parentFile.parentFile.name to module["variants"] as List<Map<String, Any?>>
        }

        // A root is a module no other module's `available-at` points to. Not "a module whose
        // variants carry `available-at`": a single-platform module -- a jvm-only sibling -- is a
        // root whose variants list their files directly, and keyed on `available-at` it would be
        // dropped with the per-target modules (`a2ui-core-jvm`) and never compared.
        @Suppress("UNCHECKED_CAST")
        val perTargetModules = variantsByModule.values.flatten()
            .mapNotNull { (it["available-at"] as Map<String, Any?>?)?.get("module")?.toString() }
            .toSet()
        @Suppress("UNCHECKED_CAST")
        val publishedVariants: Map<String, List<Map<String, String>>> = variantsByModule
            .filterKeys { it !in perTargetModules }
            .mapValues { (_, variants) ->
                variants
                    .map { variant ->
                        (variant["attributes"] as Map<String, Any?>)
                            .filterKeys { it.startsWith("org.jetbrains.kotlin.") }
                            .mapValues { it.value.toString() }
                    }
                    // Every variant of a root, not only the `available-at` ones: a platform
                    // variant listed inline -- the whole of a single-platform root -- is a
                    // target too. The root's own metadata variants are `common`, which no
                    // platform target declares, and a variant with no Kotlin attribute at all
                    // (a javadoc jar) is nothing to match.
                    .filter { it.isNotEmpty() && it["org.jetbrains.kotlin.platform.type"] != "common" }
            }

        val unnamedModules = publishedVariants.keys - modules.get()
        val declared = targets.get()
        val unnamedTargets = publishedVariants.values.flatten().toSet().filter { published ->
            declared.values.none { candidate -> published.all { (key, value) -> candidate[key] == value } }
        }

        val problems = buildList {
            for (module in unnamedModules.sorted()) {
                add("published module $group:$module:$version has no `implementation` line here " +
                    "(${groupDirectory.resolve(module)})")
            }
            for (target in unnamedTargets.sortedBy { it.toString() }) {
                add("published target $target is declared by no target in this build's `kotlin {}`")
            }
        }
        check(problems.isEmpty()) {
            "The producer published something this build does not name, so no compile here " +
                "resolves it:\n" + problems.joinToString("\n") { "  - $it" } +
                "\nAdd it to smoke-test/build.gradle.kts (and a symbol from a new module to Smoke.kt)."
        }
        logger.lifecycle(
            "Published: ${publishedVariants.keys.sorted()} on ${publishedVariants.values.flatten().toSet().size} " +
                "targets; all named here.",
        )
    }
}

/**
 * One compile task per target, derived from `kotlin.targets` rather than listed: the `main`
 * compilation of each platform target, and `commonMain` -- not `main` -- of the metadata target.
 * Its `main` is `compileKotlinMetadata`, the task the README's second control is about: present,
 * disabled under the hierarchical model, and green having compiled nothing. `commonMain` is the
 * compilation that reads the published metadata jar, and the only metadata compilation with
 * sources here; the intermediate ones the default hierarchy adds (`appleMain`, `webMain`, ...)
 * are empty and left out.
 *
 * A provider, because the metadata target's `commonMain` compilation is registered after this
 * script has run; asked for now, it is "not found".
 */
val mainCompileTasks: Provider<List<TaskProvider<*>>> = provider {
    kotlin.targets.map { target ->
        val compilation = if (target.platformType == KotlinPlatformType.common) "commonMain" else "main"
        target.compilations.getByName(compilation).compileTaskProvider
    }
}

/**
 * The gate as one task: `checkPublishedSets`, then `Smoke.kt` compiled on every declared target.
 *
 * So a target added to the `kotlin {}` block above is compiled without a task list changing
 * anywhere. `cd.yml` and `release-dry-run.yml` used to name the eight tasks by hand and call
 * this now; `build.yml` keeps naming its two, which is a deliberate subset rather than a copy.
 */
tasks.register("compileAll") {
    group = "verification"
    description = "Checks the declared sets cover the publication, then compiles Smoke.kt on every target."

    dependsOn(checkPublishedSets)
    dependsOn(mainCompileTasks)
}

// The check first: it is milliseconds, and a failure there is the finding; a compile that fails
// to resolve before it would report the same fact less clearly.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    mustRunAfter(checkPublishedSets)
}

# XDCL conversion report

Status as of 2026-10-12. Phase 1 (this repo's own build) is converted and verified against the
Kotlin DSL baseline; the full integration test matrix has not been run. Phase 2 (an XDCL surface for
the published plugin) has not been started.

## Goal and scope

"Convert this plugin project from Gradle DSL to Gradle XDCL" was scoped as **both** of:

1. **Phase 1 – the repo's build**: replace every Kotlin DSL build script with
   `settings.gradle.xdcl` / `build.gradle.xdcl`.
2. **Phase 2 – the published plugin**: let consumers configure `mavenPublishing { }` from their own
   `build.gradle.xdcl`.

## Decisions

| Question | Decision |
|---|---|
| Scope | Both phases |
| How build files refer to dependencies from `libs.versions.toml` | Catalog aliases, written as symbols (`:okhttp.client`) |
| Where the consumer-facing `mavenPublishing` block attaches | Both an own opt-in trait and the built-in `HasDependencies` trait |
| Gradle wrapper | Replaced with the XDCL snapshot `9.8.0-branch-xdclRelease-20261012070500+0000` |

### What "catalog aliases" means

The XDCL language and the built-in settings schema have no version-catalog concept: there is no
`versionCatalogs` block and no `libs.…` accessor. Gradle's catalog machinery still exists at
runtime, though. A `build.gradle.xdcl` only holds values; a *reaction* (ordinary Kotlin code in
`build-logic`) reads them and configures the real `Project`, and that code can call
`VersionCatalogsExtension`. So the alias is a convention this build defines:

- The schema declares a dependency as `String | Symbol | ProjectDependency`.
- A symbol is looked up in the `libs` catalog; a string is literal coordinates; `{ project ":path" }`
  is a project dependency.

Costs: alias typos are not caught by the XDCL editor tooling (they fail at configuration time with
an error naming the alias), and the extra catalogs (`alpha`, `beta`, `rc`) have to be registered by
a settings reaction.

## The constraint that shapes the design

A `build.gradle.xdcl` can only set what a plugin-contributed template declares. The built-in
`xdclGradlePlugin` / `xdclGradlePluginKotlin` project types expose only `dependencies`. Everything
else the old scripts did had to move into a `build-logic` XDCL plugin: a schema plus Kotlin
reactions.

Likewise the built-in settings schema only has `pluginManagement`, `plugins`,
`dependencyResolutionManagement` (repositories only), `rootProject`, `include`, `defaults` and
`prefabs`. Version catalogs, feature previews and Develocity configuration needed a settings
extension.

## What was done

### Wrapper

`gradle/wrapper/*` and `gradlew.bat` now pin
`gradle-9.8.0-branch-xdclRelease-20261012070500+0000-bin.zip` with its SHA-256. It was generated
with the copy of that distribution already in `~/xdcl`, because the first attempt (through the old
wrapper) stalled downloading stock Gradle 9.8.1.

### build-logic (now an XDCL plugin, id `com.vanniktech.build`)

Removed: `build-logic/settings.gradle.kts`, `build-logic/build.gradle.kts`,
`build-logic/src/main/kotlin/shared.gradle.kts`.

Added:

- `build-logic/settings.gradle.xdcl` – applies the built-in `plugin-development-ecosystem`, puts
  Kotlin 2.4.21 on the classpath, declares repositories.
- `build-logic/build.gradle.xdcl` – `xdclGradlePluginKotlin` with the plugins the reactions apply,
  as literal coordinates.
- `build-logic/src/main/xdcl/build-logic.xdsl` – the schema:
  - `kotlinJvmLibrary` template (used by `central-portal`).
  - `kotlinGradlePlugin` template (used by `plugin`): dependencies, plugin declarations, lint,
    `BuildConfig`, Dokka output directory, projects to publish before integration tests, extra
    `clean` paths.
  - `buildSettings` settings block: extra version catalogs, feature previews, Develocity.
- `build-logic/src/main/xdcl/com.vanniktech.build.xdcl` – plugin descriptor binding the reactions.
- `build-logic/src/main/kotlin/com/vanniktech/buildlogic/`
  - `Conventions.kt` – the old `shared` conventions and the dependency-notation resolver.
  - `KotlinJvmLibraryReaction.kt`
  - `KotlinGradlePluginReaction.kt` – everything imperative from the old `plugin/build.gradle.kts`
    (integration-test source set and task, `apiElements` attribute, `BuildConfig` fields from the
    four catalogs, lint, `validatePlugins`, `clean`).
  - `BuildSettingsReaction.kt` – catalogs, feature previews, Develocity, foojay toolchain resolver.
- `build-logic/settings.gradle` – comment-only marker stub.

### Root and modules

- `settings.gradle.kts` → `settings.gradle.xdcl` (plus a comment-only `settings.gradle` marker).
- Root `build.gradle.kts` deleted; it only held `apply false` aliases.
- `central-portal/build.gradle.kts` → `central-portal/build.gradle.xdcl`.
- `plugin/build.gradle.kts` → `plugin/build.gradle.xdcl`.
- `gradle.properties` files and all `*.versions.toml` files are untouched; publishing and signing
  stay property-driven.

### Supporting changes

- `.github/renovate.json5` – a regex manager for the literal coordinates in
  `build-logic/*.gradle.xdcl`. It has not been run through Renovate and has two known gaps, see
  "Behaviour changes to be aware of".
- `RELEASING.md` step 4 – also update `build-logic/build.gradle.xdcl` for the release candidate.

## Verification so far

| Check | Result |
|---|---|
| `xdcl-gradle-sync sync` (root and build-logic) | Succeeded |
| `xdcl-lsp-client validate` on all six XDCL/XDSL files | Clean, no diagnostics |
| build-logic compiles (schema codegen + Kotlin reactions) | Succeeded |
| `:plugin:tasks --all` vs. Kotlin DSL baseline | Identical except `kotlinDslAccessorsReport` is gone (expected: no Kotlin DSL script left) |
| `publishToMavenLocal` POM and `.module` of both modules vs. baseline | Identical; the plugin jar is also the same size |
| `./gradlew build -PquickTest=true` | Succeeded: 27 unit tests and 27 integration tests, 0 failures |
| Evaluated configuration vs. baseline (init script dumping plugins, extensions, catalogs, configurations and declared dependencies, source sets, `Test`/`JavaCompile`/`KotlinCompile` settings, lint, plugin declarations, Dokka output, `clean` targets, `BuildConfig` fields, publications, tasks), with defaults and with `CI=true -PtestJavaVersion=21 -PquickTest=true -PtestConfigMethod=DSL` | Identical except for the differences listed under "Behaviour changes to be aware of" |
| Dry-run task graphs of `build`, `publishToMavenCentral`, `dokkaGenerate`, `clean`, `:plugin:integrationTest` vs. baseline, configuration cache on | Identical |
| `./kotlinw ktlint.main.kts --fail-on-changes` | No files changed |

The baseline was taken with the Kotlin DSL scripts on the same XDCL Gradle distribution, so the
comparison isolates the script conversion from the Gradle version change.

Not yet run: the full integration test matrix (`-PtestConfigMethod=…`, `-PtestJavaVersion=…`), a
real `dokkaGenerate` (only its task graph and output directory were compared), and `gradlew.bat` on
Windows.

## Behaviour changes to be aware of

- **Google repository filter lost.** The settings schema takes a repository as a symbol or a URL, so
  `google { mavenContent { … } }` became a plain URL without the group filter.
- **build-logic now resolves from the Google repository too**, also unfiltered. It used only Maven
  Central and the plugin portal before; the Android lint plugin it now depends on needs Google.
- **Versions duplicated in build-logic.** The built-in plugin project type accepts only literal
  coordinates, so Kotlin, Dokka, buildconfig, Android lint, Develocity, foojay and the
  self-publishing `gradle-maven-publish-plugin:0.37.0` are now written in
  `build-logic/*.gradle.xdcl` as well as (for most) in `libs.versions.toml`.
- **The `[plugins]` section of `libs.versions.toml` is no longer read.** `buildconfig`,
  `android-lint` and `kotlin-jvm` were only used by the deleted scripts; Renovate will keep bumping
  them without effect.
- **The Renovate rule does not keep build-logic fully in sync.**
  `com.android.lint:com.android.lint.gradle.plugin` is not served by `plugins.gradle.org/m2`, the
  only registry the rule uses, so it will never be updated. The Kotlin version in
  `build-logic/settings.gradle.xdcl` is written as `version "…"`, which the regex does not match, so
  it will fall behind the Kotlin plugin coordinate in `build-logic/build.gradle.xdcl`.
- **Develocity and foojay are applied by the settings reaction**, not listed in the root settings
  `plugins`. This differs from the approved plan and was done so the reactions and those plugins
  share one classloader.
- **`TYPESAFE_PROJECT_ACCESSORS` is no longer enabled**; no script uses `projects.…` any more.
- **CI is not adjusted.** The workflows still call `./gradlew`, which now runs a snapshot
  distribution; `setup-gradle` wrapper validation may reject the snapshot wrapper jar.

## Remaining work

### Finish Phase 1

- Run the full integration test matrix and `dokkaGenerate`.
- Fix the two Renovate gaps and drop or reuse the unused `[plugins]` catalog entries.

### Phase 2 – XDCL surface of the published plugin (not started)

Planned, all inside `plugin/`, leaving the existing plugin ids and Kotlin DSL behaviour untouched:

- Have the `kotlinGradlePlugin` reaction apply `xdcl-gradle-plugin` (schema codegen).
- `plugin/src/main/xdcl/maven-publish.xdsl`: a `MavenPublishable` opt-in trait, the publishing
  settings (Maven Central, signing, coordinates, POM, checksums), and `mavenPublishing` extensions
  for both `MavenPublishable` and the built-in `HasDependencies`.
- A descriptor for a **new** plugin id `com.vanniktech.maven.publish.xdcl`; the existing ids are
  taken by the `Plugin<Project>` classes.
- `MavenPublishingReaction` mapping the block onto `MavenPublishBaseExtension`.
- An `XdclPluginTest` integration test, regenerated ABI dump, docs section and changelog entry.

Not possible yet: this repo's own build adopting `mavenPublishing { }`, because build-logic consumes
the released 0.37.0, which has no schema.

### Open assumptions for Phase 2

| Assumption | Fallback |
|---|---|
| The built-in common schema is importable from the plugin's codegen | Add the distribution's ecosystem jar as `compileOnly`; otherwise ship only the own-trait extension |
| Two extensions may share the rootname `mavenPublishing` on different targets | A distinct rootname for the `HasDependencies` variant |
| Android lint and strict `validatePlugins` accept generated facades | Exclude `build/generated/xdcl` from lint |
| Integration tests on stock Gradle still load the plugin jar once it contains XDCL classes | Move the XDCL code to a separate source set or module |

Phase 1 assumptions that turned out to hold: `gradle/libs.versions.toml` is still auto-imported as
`libs` under XDCL settings, and a settings reaction runs early enough to register catalogs and
feature previews.

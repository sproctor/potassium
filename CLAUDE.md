# Potassium Packager

A single Gradle plugin that packages and distributes Compose / JVM desktop applications on macOS, Windows, and Linux. It extends the official JetBrains Compose Desktop plugin and adds: many installer formats (deb/rpm/AppImage/snap/flatpak, msi/exe/appx, dmg/pkg + archives), code signing & notarization, electron-builder-based auto-update, and GraalVM native-image builds.

Published to **Maven Central** with plugin id **`com.seanproctor.potassium`**. Repo home: **https://github.com/sproctor/potassium-packager**.

> Note: both the Maven coordinates / plugin id and the Kotlin DSL import packages use `com.seanproctor.potassium.*`. (This is a fork of kdroidFilter's Nucleus; the kdroidFilter *runtime* libraries it ships ProGuard rules for — `io.github.kdroidfilter.nucleus.window`, `.darkmodedetector`, etc. — intentionally keep their original `io.github.kdroidfilter` coordinates.)

## Project Structure

This repo is a single Gradle build whose root **is** the plugin build.

- `plugin/` — the Gradle plugin itself (source, DSL, packaging logic, bundled GraalVM metadata)
- `buildSrc/` — shared build logic / conventions for this repo's own build
- `config/` — code-quality config (`config/detekt/detekt.yml`)
- `gradle/` — Gradle wrapper and the version catalog (`gradle/libs.versions.toml`, the source of truth for dependency versions)
- root `build.gradle.kts` / `settings.gradle.kts` — settings includes only `:plugin`
- `docs/` + `mkdocs.yml` — documentation site
- `scripts/generate-llms-docs.py` — generates `docs/llms.txt` / `docs/llms-full.txt` from the docs

## Build & Run

```bash
./gradlew :plugin:check                 # Compile + tests + detekt/ktlint
./gradlew :plugin:validatePlugins       # Validate plugin task/property annotations
./gradlew publishToMavenLocal           # Publish the plugin to ~/.m2 for local testing
./gradlew publishAndReleaseToMavenCentral  # Publish + release to Maven Central
./gradlew reformatAll                   # (ktlint) format all code
```

## Key Technologies

- Kotlin 2.x, targeting Compose Desktop 1.10+ consumers
- Gradle 8+ with a version catalog (`gradle/libs.versions.toml`)
- electron-builder (via Node.js) for most installer formats — consumers need Node.js 18+
- GraalVM native-image (BellSoft Liberica NIK) for native binaries
- Detekt + KtLint for code quality

## Development Notes

- Target consumer runtime: JDK 17+ (JDK 25+ recommended for AOT cache)
- The version is resolved from the `GITHUB_REF` env var in the build (`refs/tags/v1.15.11` → `1.15.11`); without it, defaults to `1.0.0`
- GraalVM reachability metadata ships as static JSON inside the plugin under `plugin/src/main/resources/potassium/graalvm/` (per-library, platform-specific, and generic levels) — consumers do not copy hundreds of entries by hand

## Publishing to Maven Central

The plugin is published to `com.seanproctor` on Maven Central.

**Prerequisites:**
- Use **JDK 21** (`JAVA_HOME=/usr/lib/jvm/java-1.21.0-openjdk-amd64`) — the Kotlin DSL script compiler crashes on JDK 25
- Override the coordinates at publish time: group via `-PGROUP=com.seanproctor`, plugin id via `-PID=com.seanproctor.potassium`
- Signing is required for Maven Central and is conditional on the `signingInMemoryKey` property (also pass `signingInMemoryKeyId` / `signingInMemoryKeyPassword` and Sonatype credentials). When the key is absent (e.g. local publish), signing is skipped.

```bash
# Local install (no signing required)
GITHUB_REF=refs/tags/v1.15.11 JAVA_HOME=/usr/lib/jvm/java-1.21.0-openjdk-amd64 \
  ./gradlew publishToMavenLocal -PGROUP=com.seanproctor -PID=com.seanproctor.potassium

# Publish + release to Maven Central (requires signing + Sonatype credentials)
GITHUB_REF=refs/tags/v1.15.11 JAVA_HOME=/usr/lib/jvm/java-1.21.0-openjdk-amd64 \
  ./gradlew publishAndReleaseToMavenCentral -PGROUP=com.seanproctor -PID=com.seanproctor.potassium \
    -PsigningInMemoryKey=... -PsigningInMemoryKeyPassword=...
```

## GraalVM Native Image

- Reflection / reachability metadata is resolved and merged automatically at build time; users do not copy hundreds of entries:
    - **Per-library conditional metadata** shipped in the plugin (only applied when the matching library is on the classpath)
    - **Oracle GraalVM Reachability Metadata Repository** — auto-resolved for classpath deps (enabled by default, `metadataRepository {}` DSL)
    - **Platform-specific metadata** (macOS/Windows/Linux) shipped in the plugin under `plugin/src/main/resources/potassium/graalvm/`
    - **Static bytecode analysis** of the runtime classpath, plus generic cross-platform metadata
- The tracing agent (`runWithNativeAgent`) is only needed as a final safety net for app-specific reflection
- Only BellSoft Liberica NIK (full) is supported — standard GraalVM CE lacks AWT support
</content>

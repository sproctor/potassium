<p align="center">
  <img src="art/header.png" alt="Potassium" />
</p>

# Potassium Packager

[![Maven Central](https://img.shields.io/maven-central/v/com.seanproctor/potassium-packager?label=Maven%20Central)](https://central.sonatype.com/artifact/com.seanproctor/potassium-packager)
[![License: MIT](https://img.shields.io/github/license/sproctor/potassium-packager)](https://github.com/sproctor/potassium-packager/blob/main/LICENSE)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0%2B-7F52FF?logo=kotlin&logoColor=white)
![Platform](https://img.shields.io/badge/Platform-macOS%20%7C%20Windows%20%7C%20Linux-blue)

**Potassium is a Gradle plugin for packaging and distributing Compose / JVM desktop applications** on macOS, Windows, and Linux. It is a drop-in extension of the official JetBrains Compose Desktop plugin: keep your existing `compose.desktop` configuration and add the capabilities you need.

## What it does

- **Many installer formats** — Linux `deb` / `rpm` / `AppImage` / `snap` / `flatpak`, Windows `msi` / `exe` (NSIS) / `appx`, macOS `dmg` / `pkg`, plus archives (`zip`, `tar`, `7z`)
- **Store-ready builds** — Mac App Store, Microsoft Store, Snapcraft, Flathub
- **Code signing & notarization** — Windows (PFX / Azure) and macOS, built into the build pipeline
- **Auto-update** — electron-builder-based update metadata (`latest-*.yml`) generated alongside your installers, with SHA-512 verification
- **GraalVM Native Image** — standalone native binaries with automatic reachability-metadata resolution (no manual reflection config for most apps)
- **Deep links & file associations** — protocol handlers and file type registration on all platforms
- **AOT cache, ProGuard, trusted CA certificates**, and more — all opt-in via the DSL

## Quick Start

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "..."
    id("org.jetbrains.kotlin.plugin.compose") version "..."
    id("org.jetbrains.compose") version "..."
    id("com.seanproctor.potassium") version "0.1.0"
}

potassium {
    mainClass = "com.example.MainKt"
    packageName = "MyApp"
    packageVersion = "1.0.0"

    macOS { targetFormats(MacOSTargetFormat.Dmg) }
    windows { targetFormats(WindowsTargetFormat.Nsis) }
    linux { targetFormats(LinuxTargetFormat.Deb) }
}
```

```bash
./gradlew run                              # Run locally
./gradlew packageDistributionForCurrentOS  # Build installers for the current OS
```

> Kotlin DSL types live under `com.seanproctor.potassium.*` (for example
> `import com.seanproctor.potassium.dsl.MacOSTargetFormat`).

## Documentation

Full documentation — configuration reference, per-platform targets, code signing, auto-update, GraalVM, and CI/CD — is in the [`docs/`](docs/) directory and published at the project site.

A good starting point is [Getting Started](docs/getting-started.md), followed by [Configuration](docs/configuration.md) and [Migration from org.jetbrains.compose](docs/migration.md).

## Sample

[`sample/`](sample/) is a runnable Compose Multiplatform desktop app packaged by this plugin. It's a
composite build that uses the plugin from source, so `cd sample && ./gradlew run` (or
`packageDistributionForCurrentOS`) works against your local checkout. See [`sample/README.md`](sample/README.md).

## Coordinates

- **Plugin id:** `com.seanproctor.potassium`
- **Latest version:** `0.1.0`
- **Published to:** Maven Central
- **Repository:** https://github.com/sproctor/potassium-packager

## Requirements

| Requirement | Version | Note |
|-------------|---------|------|
| JDK | 17+ (25+ for AOT cache) | |
| Gradle | 8.0+ | |
| Kotlin | 2.0+ | |
| Node.js | 18+ | Required by electron-builder for installer formats |

## License

MIT — See [LICENSE](LICENSE).
</content>
</invoke>

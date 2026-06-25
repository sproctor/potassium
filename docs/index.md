# Potassium Packager

<p align="center">
  <img src="assets/header.png" alt="Potassium" />
</p>

[![Maven Central](https://img.shields.io/maven-central/v/com.seanproctor/potassium-packager?label=Maven%20Central)](https://central.sonatype.com/artifact/com.seanproctor/potassium-packager)
[![License: MIT](https://img.shields.io/github/license/sproctor/potassium-packager)](https://github.com/sproctor/potassium-packager/blob/main/LICENSE)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0%2B-7F52FF?logo=kotlin&logoColor=white)
![Platform](https://img.shields.io/badge/Platform-macOS%20%7C%20Windows%20%7C%20Linux-blue)

**Potassium is a Gradle plugin for packaging and distributing Compose / JVM desktop applications** on macOS, Windows, and Linux.

It is a drop-in extension of the official JetBrains Compose Desktop plugin — keep your existing `compose.desktop` configuration and opt into the capabilities you need. Potassium picks up where jpackage stops: more installer formats, real code signing and notarization, built-in auto-update, and GraalVM Native Image builds, all from a single Gradle DSL.

## What it does

### Ship everywhere

- **Many installer formats** — Linux `deb` / `rpm` / `AppImage` / `snap` / `flatpak`, Windows `msi` / `exe` (NSIS) / `appx` / portable, macOS `dmg` / `pkg`, plus archives (`zip`, `tar`, `7z`)
- **Store-ready** — Mac App Store, Microsoft Store, Snapcraft, Flathub
- **Code signing & notarization** — Windows (PFX / Azure) and macOS, built into the build pipeline
- **Auto-update** — electron-builder-compatible update metadata (`latest-*.yml`) generated alongside your installers, with SHA-512 verification
- **Deep links & file associations** — protocol handlers and file type registration on all platforms

### Go further

- **GraalVM Native Image** — compile your app into a standalone native binary. Potassium resolves the reachability metadata transparently, so most apps build with zero manual reflection config.
- **AOT cache** — enable the JDK 25+ ahead-of-time cache for faster startup with a single flag.
- **ProGuard release builds** — optimization and obfuscation for production.
- **Trusted CA certificates** — import custom root CAs into the bundled JVM's `cacerts` at build time.
- **CI/CD ready** — reusable GitHub Actions for multi-platform matrix builds, universal macOS binaries, and MSIX bundles.

## Quick start

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

!!! note "Kotlin DSL imports"
    The Kotlin DSL types live under `com.seanproctor.potassium.*` (for example
    `import com.seanproctor.potassium.dsl.MacOSTargetFormat`).

## Coordinates

- **Plugin id:** `com.seanproctor.potassium`
- **Latest version:** `0.1.0`
- **Published to:** Maven Central
- **Repository:** [github.com/sproctor/potassium-packager](https://github.com/sproctor/potassium-packager)

## Requirements

| Requirement | Version | Note |
|-------------|---------|------|
| JDK | 17+ (25+ for AOT cache) | |
| Gradle | 8.0+ | |
| Kotlin | 2.0+ | |
| Node.js | 18+ | Required by electron-builder for installer formats |

## Next steps

- [Getting Started](getting-started.md) — install the plugin and build your first installer
- [Configuration](configuration.md) — full DSL reference
- [Migration from org.jetbrains.compose](migration.md) — switch an existing Compose Desktop project

## License

MIT — See [LICENSE](https://github.com/sproctor/potassium-packager/blob/main/LICENSE).
</content>

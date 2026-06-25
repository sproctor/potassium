# Getting Started

## Prerequisites

Potassium uses [electron-builder](https://www.electron.build/) under the hood to produce platform-specific installers (DMG, NSIS, DEB, RPM, AppImage, etc.). This requires **Node.js 18+** and **npm** installed on your build machine.

```bash
# Verify your installation
node --version   # v18.0.0 or later
npm --version
```

!!! tip "CI/CD"
    The `setup-potassium` composite action installs Node.js automatically. See [CI/CD](ci-cd.md) for details.

## Installation

The plugin is published to Maven Central. Add Maven Central to your plugin repositories, then apply the plugin in your build script:

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
    id("com.seanproctor.potassium") version "1.15.11"
}
```

!!! note "Kotlin DSL imports"
    The Kotlin DSL types live under `com.seanproctor.potassium.*` (for example
    `import com.seanproctor.potassium.desktop.application.dsl.TargetFormat`).

## Minimal Configuration

```kotlin
potassium.application {
    mainClass = "com.example.MainKt"

    nativeDistributions {
        targetFormats(TargetFormat.Dmg, TargetFormat.Nsis, TargetFormat.Deb)
        packageName = "MyApp"
        packageVersion = "1.0.0"
    }
}
```

## Gradle Tasks

### Development

| Task | Description |
|------|-------------|
| `run` | Run the application from the IDE/terminal |
| `runDistributable` | Run the packaged application image |

#### Compose Hot Reload

Potassium is fully compatible with [Compose Hot Reload](https://kotlinlang.org/docs/multiplatform/compose-hot-reload.html). Since Potassium extends the Compose plugin (not replaces it), Hot Reload works out of the box.

The `hotRun` task reads `mainClass` from the `compose.desktop.application` block. If you only set it in `potassium.application`, add a minimal Compose block:

```kotlin
compose.desktop.application {
    mainClass = "com.example.MainKt"
}
```

Or pass it via the command line:

```bash
./gradlew hotRun -PmainClass=com.example.MainKt
```

### Packaging

| Task | Description |
|------|-------------|
| `packageDistributionForCurrentOS` | Build all configured formats for the current OS |
| `package<Format>` | Build a specific format (e.g., `packageDmg`, `packageNsis`, `packageDeb`) |
| `packageReleaseDistributionForCurrentOS` | Same as above with ProGuard release build |
| `createDistributable` | Create the application image without an installer |
| `createReleaseDistributable` | Same with ProGuard |

### Utility

| Task | Description |
|------|-------------|
| `suggestModules` | Suggest JDK modules required by your dependencies |
| `packageUberJarForCurrentOS` | Create a single fat JAR with all dependencies |

### Running a Specific Task

```bash
# Build a DMG on macOS
./gradlew packageDmg

# Build NSIS installer on Windows
./gradlew packageNsis

# Build DEB package on Linux
./gradlew packageDeb

# Build all formats for current OS
./gradlew packageDistributionForCurrentOS

# Release build (with ProGuard)
./gradlew packageReleaseDistributionForCurrentOS
```

## Output Location

Build artifacts are generated in:

```
build/compose/binaries/main/<format>/
build/compose/binaries/main-release/<format>/   # Release builds
```

Override with:

```kotlin
nativeDistributions {
    outputBaseDir.set(project.layout.buildDirectory.dir("custom-output"))
}
```

## JDK Modules

The plugin does not automatically detect required JDK modules. Use `suggestModules` to identify them:

```bash
./gradlew suggestModules
```

Then declare them in the DSL:

```kotlin
nativeDistributions {
    modules("java.sql", "java.net.http", "jdk.accessibility")
}
```

Or include everything (larger binary):

```kotlin
nativeDistributions {
    includeAllModules = true
}
```

## Application Icons

Provide platform-specific icon files:

```kotlin
nativeDistributions {
    macOS {
        iconFile.set(project.file("icons/app.icns"))
    }
    windows {
        iconFile.set(project.file("icons/app.ico"))
    }
    linux {
        iconFile.set(project.file("icons/app.png"))
    }
}
```

| Platform | Format | Recommended Size |
|----------|--------|------------------|
| macOS | `.icns` | 1024x1024 |
| Windows | `.ico` | 256x256 |
| Linux | `.png` | 512x512 |

## Application Resources

Include extra files in the installation directory via `appResourcesRootDir`:

```kotlin
nativeDistributions {
    appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
}
```

Resource directory structure:

```
resources/
  common/          # Included on all platforms
  macos/           # macOS only
  macos-arm64/     # macOS Apple Silicon only
  macos-x64/       # macOS Intel only
  windows/         # Windows only
  linux/           # Linux only
```

Access at runtime:

```kotlin
val resourcesDir = File(System.getProperty("compose.application.resources.dir"))
```

## Next Steps

- [Configuration](configuration.md) — Full DSL reference
- [macOS](targets/macos.md) / [Windows](targets/windows.md) / [Linux](targets/linux.md) — Platform-specific options
- [CI/CD](ci-cd.md) — Automate builds with GitHub Actions

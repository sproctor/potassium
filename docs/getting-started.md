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
    id("com.seanproctor.potassium") version "0.1.0"
}
```

!!! note "Kotlin DSL imports"
    The Kotlin DSL types live under `com.seanproctor.potassium.*` (for example
    `import com.seanproctor.potassium.dsl.MacOSTargetFormat`).

## Minimal Configuration

```kotlin
potassium {
    mainClass = "com.example.MainKt"
    packageName = "MyApp"
    packageVersion = "1.0.0"

    macOS { targetFormats(MacOSTargetFormat.Dmg) }
    windows { targetFormats(WindowsTargetFormat.Nsis) }
    linux { targetFormats(LinuxTargetFormat.Deb) }
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

The `hotRun` task reads `mainClass` from the `compose.desktop.application` block. If you only set it in `potassium`, add a minimal Compose block:

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
| `package<OS>` | Build all the current OS's non-store formats in one invocation (`packageMacOS` / `packageWindows` / `packageLinux`) |
| `packagePkg` / `packageAppX` / `packageFlatpak` | Build a store format (each built separately) |
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
# Build all configured macOS formats (e.g. DMG + ZIP) in one invocation
./gradlew packageMacOS

# Build all configured Windows formats (e.g. NSIS + MSI) in one invocation
./gradlew packageWindows

# Build all configured Linux formats (e.g. DEB + RPM + AppImage) in one invocation
./gradlew packageLinux

# Build all formats for current OS (incl. store formats)
./gradlew packageDistributionForCurrentOS

# Release build (with ProGuard)
./gradlew packageReleaseDistributionForCurrentOS
```

## Output Location

Build artifacts are generated in:

```
build/potassium/binaries/main/<format>/
build/potassium/binaries/main-release/<format>/   # Release builds
```

Override with:

```kotlin
potassium {
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
potassium {
    modules("java.sql", "java.net.http", "jdk.accessibility")
}
```

Or include everything (larger binary):

```kotlin
potassium {
    includeAllModules = true
}
```

## Application Icons

Provide platform-specific icon files:

```kotlin
potassium {
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
potassium {
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

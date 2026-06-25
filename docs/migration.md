# Migration from org.jetbrains.compose

Potassium is a drop-in extension of the official JetBrains Compose Desktop plugin. All existing configuration is preserved — Potassium only adds new capabilities.

## Step 1: Add the Plugin

```diff
 plugins {
     id("org.jetbrains.kotlin.jvm") version "2.3.10"
     id("org.jetbrains.kotlin.plugin.compose") version "2.3.10"
     id("org.jetbrains.compose") version "1.10.1"
+    id("com.seanproctor.potassium") version "0.1.0"
 }
```

> The official `org.jetbrains.compose` plugin remains — Potassium extends it, not replaces it.

The plugin is published to Maven Central, so make sure `mavenCentral()` is in your `pluginManagement.repositories`:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

## Step 2: Update Imports

Replace the JetBrains Compose DSL imports with the Potassium equivalents:

```diff
-import org.jetbrains.compose.desktop.application.dsl.TargetFormat
+import com.seanproctor.potassium.dsl.MacOSTargetFormat
+import com.seanproctor.potassium.dsl.WindowsTargetFormat
+import com.seanproctor.potassium.dsl.LinuxTargetFormat
```

This applies to all DSL types used in your `build.gradle.kts` (e.g. `MacOSTargetFormat`, `CompressionLevel`, `SigningAlgorithm`, etc.). Potassium splits the single Compose `TargetFormat` into per-OS enums declared inside each platform block — see [Step 3](#step-3-use-the-potassium-dsl).

## Step 3: Use the Potassium DSL

Replace the `compose.desktop.application` block with `potassium` for packaging and distribution:

```diff
-compose.desktop.application {
+potassium {
     mainClass = "com.example.MainKt"
-    targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
     packageName = "MyApp"
     packageVersion = "1.0.0"

     macOS {
+        targetFormats(MacOSTargetFormat.Dmg)
         bundleID = "com.example.myapp"
         iconFile.set(project.file("icons/app.icns"))
     }

     windows {
+        targetFormats(WindowsTargetFormat.Msi)
         iconFile.set(project.file("icons/app.ico"))
     }

     linux {
+        targetFormats(LinuxTargetFormat.Deb)
         iconFile.set(project.file("icons/app.png"))
     }
 }
```

!!! tip "Using Compose Hot Reload?"
    Some Compose plugin tasks (like `hotRun`) read `mainClass` from the original `compose.desktop.application` block, not from `potassium`. If you use [Compose Hot Reload](https://kotlinlang.org/docs/multiplatform/compose-hot-reload.html), either keep a minimal Compose block alongside Potassium:

    ```kotlin
    compose.desktop.application {
        mainClass = "com.example.MainKt"
    }
    ```

    Or pass the property explicitly when running:

    ```bash
    ./gradlew hotRun -PmainClass=com.example.MainKt
    ```

## Step 4: Add Potassium Features (Optional)

Enable the features you need. All are opt-in:

```kotlin
potassium {
    mainClass = "com.example.MainKt"
    packageName = "MyApp"
    packageVersion = "1.0.0"

    // --- New Potassium features ---
    cleanupNativeLibs = true
    enableAotCache = true
    splashImage = "splash.png"
    compressionLevel = CompressionLevel.Maximum
    artifactName = "${name}-${version}-${os}-${arch}.${ext}"

    // Deep links
    protocol("MyApp", "myapp")

    // File associations
    fileAssociation(
        mimeType = "application/x-myapp",
        extension = "myapp",
        description = "MyApp Document",
    )

    // Target formats — grouped per OS (with new Potassium targets)
    macOS { targetFormats(MacOSTargetFormat.Dmg) }
    windows { targetFormats(WindowsTargetFormat.Nsis) }
    linux {
        targetFormats(
            LinuxTargetFormat.Deb,
            LinuxTargetFormat.AppImage, LinuxTargetFormat.Snap, LinuxTargetFormat.Flatpak, // NEW
        )
    }

    // Publishing
    publish {
        github {
            enabled = true
            owner = "myorg"
            repo = "myapp"
        }
    }

    // NSIS customization
    windows {
        nsis {
            oneClick = false
            allowToChangeInstallationDirectory = true
            createDesktopShortcut = true
        }
    }
}
```

## What Changes

| Feature | Before (compose) | After (potassium)                                                                                                                       |
|---------|-------------------|---------------------------------------------------------------------------------------------------------------------------------------|
| DSL entry point | `compose.desktop.application` | `potassium`                                                                                                                 |
| DSL imports | `org.jetbrains.compose.desktop.application.dsl.*` | `com.seanproctor.potassium.dsl.*`                                                                            |
| Compose dependencies | `compose.desktop.currentOs`, `compose("…")` | Unchanged — keep using the official `org.jetbrains.compose` DSL. Potassium no longer re-exposes a `potassium.*` Compose-dependency accessor.                                          |
| Target formats | DMG, PKG, MSI, EXE, DEB, RPM | + NSIS, AppX, Portable, AppImage, Snap, Flatpak, archives                                                                             |
| Native lib cleanup | Manual | `cleanupNativeLibs = true`                                                                                                            |
| AOT cache | Not available | `enableAotCache = true`                                                                                                               |
| Splash screen | Manual | `splashImage = "splash.png"`                                                                                                          |
| Deep links | Manual (macOS only via Info.plist) | Cross-platform `protocol("name", "scheme")`                                                                                           |
| File associations | Limited | Cross-platform `fileAssociation()`                                                                                                    |
| NSIS config | Not available | Full `nsis { }` DSL                                                                                                                   |
| AppX config | Not available | Full `appx { }` DSL                                                                                                                   |
| Snap config | Not available | Full `snap { }` DSL                                                                                                                   |
| Flatpak config | Not available | Full `flatpak { }` DSL                                                                                                                |
| Store pipeline | Not available | Automatic dual pipeline for store formats (PKG, AppX, Flatpak) with sandboxing for PKG and Flatpak                                    |
| Auto-update | Not available | Built-in with YML metadata                                                                                                            |
| Code signing | macOS only | + Windows PFX / Azure Artifact Signing                                                                                                |
| DMG appearance | Not customizable (jpackage defaults) | Full `dmg { }` DSL: background, icon size, window layout, content positioning, format ([details](targets/macos.md#dmg-customization)) |
| Artifact naming | Fixed | Template with `artifactName`                                                                                                          |

## Important Differences from Compose Desktop

### `homepage` is Required for Linux DEB

Unlike Compose Desktop (which uses jpackage), Potassium uses electron-builder for packaging. Electron-builder **requires** the `homepage` property when building DEB packages. Without it, the build will fail with:

```
Please specify project homepage, see https://electron.build/configuration
```

Make sure to set it in your `potassium` block:

```kotlin
potassium {
    homepage = "https://myapp.example.com"
}
```

This also applies to GraalVM native image packaging (`packageGraalvmLinux`).

## What Stays the Same

Everything from the official plugin works unchanged:

- `mainClass`, `jvmArgs`
- Package metadata, icons, resources (now set directly in the `potassium` block)
- `buildTypes` / ProGuard configuration
- `modules()` / `includeAllModules`
- The standard Gradle tasks (`run`, `packageDistributionForCurrentOS`, the per-platform `packageMacOS` / `packageWindows` / `packageLinux`, etc.)
- `compose.desktop.currentOs` dependency
- Source set configuration
- [Compose Hot Reload](https://kotlinlang.org/docs/multiplatform/compose-hot-reload.html) — works as usual since Potassium extends the Compose plugin. Note: `hotRun` reads `mainClass` from `compose.desktop.application`, so set it there too or pass `-PmainClass=...` (see [Step 3](#step-3-use-the-potassium-dsl))

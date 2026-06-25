# Configuration

All Potassium configuration lives inside the `potassium { }` block in your `build.gradle.kts`.

## Overview

```kotlin
potassium {
    mainClass = "com.example.MainKt"
    jvmArgs += listOf("-Xmx512m")

    buildTypes {
        release {
            proguard {
                isEnabled = true
                optimize = true
                obfuscate.set(false)
                configurationFiles.from(project.file("proguard-rules.pro"))
            }
        }
    }

    // Package metadata
    appName = "My App"     // Display name (installer, .desktop, Start Menu)
    packageName = "MyApp"  // Technical name (executable, package file)
    packageVersion = "1.0.0"
    description = "My awesome desktop app"
    vendor = "My Company"
    copyright = "Copyright 2025 My Company"
    homepage = "https://myapp.example.com"
    licenseFile.set(project.file("LICENSE"))

    // JDK modules
    modules("java.sql", "java.net.http")

    // Potassium features
    cleanupNativeLibs = true
    enableAotCache = true
    splashImage = "splash.png"
    compressionLevel = CompressionLevel.Maximum
    artifactName = "${name}-${version}-${os}-${arch}.${ext}"

    // Deep links & file associations
    protocol("MyApp", "myapp")
    fileAssociation(
        mimeType = "application/x-myapp",
        extension = "myapp",
        description = "MyApp Document",
    )

    // Publishing
    publish { /* ... */ }

    // Platform-specific — target formats are grouped per OS here
    macOS { targetFormats(MacOSTargetFormat.Dmg) /* ... */ }
    windows { targetFormats(WindowsTargetFormat.Nsis) /* ... */ }
    linux { targetFormats(LinuxTargetFormat.Deb) /* ... */ }
}
```

## Target Formats

Formats are grouped per OS and declared inside the matching platform block. All of a platform's
non-store formats build in a **single** electron-builder invocation (`packageMacOS` /
`packageWindows` / `packageLinux`); store formats (PKG, AppX, Flatpak) build separately.

```kotlin
macOS { targetFormats(MacOSTargetFormat.Dmg, MacOSTargetFormat.Pkg) }
windows { targetFormats(WindowsTargetFormat.Nsis, WindowsTargetFormat.Msi) }
linux { targetFormats(LinuxTargetFormat.Deb, LinuxTargetFormat.Rpm, LinuxTargetFormat.AppImage) }
```

| OS | Format | Built by | Notes |
|----|--------|----------|-------|
| macOS | `MacOSTargetFormat.Dmg` | `packageMacOS` | |
| macOS | `MacOSTargetFormat.Pkg` | `packagePkg` | App Store — built separately |
| Windows | `WindowsTargetFormat.Nsis` | `packageWindows` | NSIS installer (`.exe`) |
| Windows | `WindowsTargetFormat.Exe` | `packageWindows` | Alias for `Nsis` — same output |
| Windows | `WindowsTargetFormat.NsisWeb` | `packageWindows` | NSIS web installer |
| Windows | `WindowsTargetFormat.Msi` | `packageWindows` | |
| Windows | `WindowsTargetFormat.Portable` | `packageWindows` | |
| Windows | `WindowsTargetFormat.AppX` | `packageAppX` | MSIX — built separately |
| Linux | `LinuxTargetFormat.Deb` | `packageLinux` | |
| Linux | `LinuxTargetFormat.Rpm` | `packageLinux` | |
| Linux | `LinuxTargetFormat.AppImage` | `packageLinux` | |
| Linux | `LinuxTargetFormat.Snap` | `packageLinux` | |
| Linux | `LinuxTargetFormat.Flatpak` | `packageFlatpak` | Built separately |
| all | `Zip` / `Tar` / `SevenZ` | `package<OS>` | Present in each OS enum (e.g. `MacOSTargetFormat.Zip`) |

!!! note "One invocation per platform"
    Configuring several formats for an OS (e.g. `Deb`, `Rpm`, `AppImage`) makes
    `packageLinux` build them all in one electron-builder run, rather than one run per format.

Target all of an OS's formats at once:

```kotlin
linux { targetFormats(*LinuxTargetFormat.entries.toTypedArray()) }
```

## Package Metadata

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `appName` | `String?` | `null` | Human-readable display name (installer title, `.desktop` Name, Start Menu). Falls back to `packageName` if not set. |
| `packageName` | `String` | Gradle project name | Technical package/executable name. On Linux this should be lowercase (e.g. `zayit`). |
| `packageVersion` | `String` | Gradle project version | Application version |
| `description` | `String?` | `null` | Short application description |
| `vendor` | `String?` | `null` | Publisher / company name |
| `copyright` | `String?` | `null` | Copyright notice |
| `homepage` | `String?` | `null` | Application homepage URL. **Required** for Linux DEB packaging (electron-builder enforces this). |
| `licenseFile` | `RegularFileProperty` | — | Path to the license file |
| `appResourcesRootDir` | `DirectoryProperty` | — | Root directory for bundled resources |
| `outputBaseDir` | `DirectoryProperty` | `build/potassium/binaries` | Output directory for built packages |

### Version

Set a single `packageVersion` (SemVer — it may include a pre-release suffix such as `1.2.3-beta.1`).
There are no per-OS or per-format version overrides: each platform has different version rules, so
Potassium and electron-builder format the one version per target automatically.

| Target | Handling | `1.2.3-beta.1` → |
|--------|----------|------------------|
| Windows (NSIS/MSI) | pre-release suffix stripped to numeric `MAJOR.MINOR.PATCH` | `1.2.3` |
| macOS (DMG/PKG) | pre-release suffix stripped for the app version | `1.2.3` |
| Linux (DEB/RPM) | `-` replaced with `~` (sorts before the final release) | `1.2.3~beta.1` |
| AppImage | full version preserved | `1.2.3-beta.1` |

The pre-release identifier (`beta`, `alpha`, …) also selects the auto-update channel — see
[Auto-Update](auto-update.md).

## Potassium-Specific Features

### Native Library Cleanup

Strips `.dll`, `.so`, `.dylib` files for non-target platforms from dependency JARs, reducing package size significantly.

```kotlin
potassium {
    cleanupNativeLibs = true
}
```

### AOT Cache (JDK 25+)

Generates an ahead-of-time compilation cache for faster startup:

```kotlin
potassium {
    enableAotCache = true
}
```

Requires JDK 25+ and that the application self-terminates during the training run.

### Splash Screen

Displays a splash screen during application startup:

```kotlin
potassium {
    splashImage = "splash.png" // Relative to appResources
}
```

### Artifact Naming

Customize output filenames with template variables:

```kotlin
potassium {
    artifactName = "${name}-${version}-${os}-${arch}.${ext}"
}
```

| Variable | Description | Example |
|----------|-------------|---------|
| `${name}` | Package name | `MyApp` |
| `${version}` | Package version | `1.0.0` |
| `${os}` | Operating system | `macos`, `windows`, `linux` |
| `${arch}` | Architecture | `amd64`, `arm64` |
| `${ext}` | File extension | `dmg`, `exe`, `deb` |

### Compression Level

Controls compression for electron-builder formats:

```kotlin
potassium {
    compressionLevel = CompressionLevel.Maximum
}
```

| Level | Description |
|-------|-------------|
| `CompressionLevel.Store` | No compression |
| `CompressionLevel.Normal` | Balanced (default) |
| `CompressionLevel.Maximum` | Best compression (recommended for most formats) |

!!! warning "AppImage and Maximum Compression"
    Using `CompressionLevel.Maximum` with AppImage causes extremely slow startup times (60s+)
    due to squashfs/FUSE decompression overhead. For AppImage targets, use `Normal` or `Store` instead.
    Other formats (DMG, NSIS, DEB, RPM, etc.) are not affected.
    See [electron-builder#7483](https://github.com/electron-userland/electron-builder/issues/7483) for details.

### Trusted CA Certificates

Import custom CA certificates into the bundled JVM's `cacerts` keystore at build time.
Useful for corporate proxies, VPN gateways, or filtering services that use a private root CA.

```kotlin
potassium {
    trustedCertificates.from(files(
        "certs/company-proxy-ca.pem",
    ))
}
```

Both PEM and DER formats are accepted. See [Trusted CA Certificates](trusted-certificates.md) for full details.

### Deep Links (Protocol Handler)

Register a custom URL protocol across all platforms:

```kotlin
potassium {
    protocol("MyApp", "myapp")
    // Registers myapp:// protocol
}
```

- **macOS**: `CFBundleURLTypes` in `Info.plist`
- **Windows**: Registry entries via NSIS/MSI
- **Linux**: `MimeType` in `.desktop` file

This registers the protocol at the OS level; handling the incoming deep link at runtime is up to your application.

### File Associations

Register file type associations:

```kotlin
potassium {
    fileAssociation(
        mimeType = "application/x-myapp",
        extension = "myapp",
        description = "MyApp Document",
    )
}
```

The cross-platform `fileAssociation()` method also accepts per-platform icon files:

```kotlin
fileAssociation(
    mimeType = "application/x-myapp",
    extension = "myapp",
    description = "MyApp Document",
    linuxIconFile = project.file("icons/file.png"),
    windowsIconFile = project.file("icons/file.ico"),
    macOSIconFile = project.file("icons/file.icns"),
)
```

Multiple associations are supported by calling `fileAssociation()` multiple times.

## ProGuard (Release Builds)

```kotlin
buildTypes {
    release {
        proguard {
            version = "7.8.1"
            isEnabled = true
            optimize = true
            obfuscate.set(false)  // Disabled by default
            joinOutputJars.set(true)
            configurationFiles.from(project.file("proguard-rules.pro"))
        }
    }
}
```

Release build tasks are suffixed with `Release`:

```bash
./gradlew packageReleaseMacOS    # or packageReleaseWindows / packageReleaseLinux
./gradlew packageReleaseDistributionForCurrentOS
```

## Full DSL Tree

```
potassium {
    mainClass
    jvmArgs
    buildTypes {
        release {
            proguard { isEnabled, version, optimize, obfuscate, joinOutputJars, configurationFiles }
        }
    }
    appName, packageName, packageVersion, description, vendor, copyright, homepage
    licenseFile, appResourcesRootDir, outputBaseDir
    modules(...), includeAllModules
    cleanupNativeLibs, enableAotCache, splashImage
    compressionLevel, artifactName
    protocol(name, vararg schemes)
    fileAssociation(mimeType, extension, description, linuxIconFile?, windowsIconFile?, macOSIconFile?)
    publish { github { ... }, s3 { ... }, generic { ... } }
    macOS { targetFormats(...), iconFile, bundleID, dockName, appCategory, layeredIconDir, signing { ... }, notarization { ... }, dmg { ... }, infoPlist { ... } }
    windows { targetFormats(...), iconFile, upgradeUuid, signing { ... }, nsis { ... }, appx { ... } }
    linux { targetFormats(...), iconFile, debMaintainer, debDepends, rpmRequires, appImage { ... }, snap { ... }, flatpak { ... } }
}
```

## Next Steps

- Platform-specific configuration: [macOS](targets/macos.md) · [Windows](targets/windows.md) · [Linux](targets/linux.md)
- [Code Signing](code-signing.md) — Sign and notarize your app
- [Publishing](publishing.md) — Distribute via GitHub Releases, S3, or generic HTTP server

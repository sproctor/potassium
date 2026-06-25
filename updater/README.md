# potassium-updater

The auto-update library for Compose/JVM desktop applications — the **updating** half of
[Potassium](https://github.com/sproctor/potassium), under the `com.seanproctor` namespace. It is
the runtime counterpart to the [`potassium-packager`](../plugin) Gradle plugin in this repo (the
**packaging** half): the plugin generates the `latest-*.yml` manifests, this library consumes them.

Both halves are a focused fork of [Nucleus](https://github.com/kdroidFilter/Nucleus). This module
is a fork/extraction of the monorepo's `updater-runtime`: the full updater, plus the runtime
install-type detection, with the handful of `core-runtime` classes it needs vendored in (so it has
no dependency on the rest of Nucleus).

## What it does

Self-updates desktop apps from an electron-builder-style release manifest (`latest-<os>.yml`):

- **Linux** — AppImage, deb, rpm
- **Windows** — NSIS (`.exe`), MSI
- **macOS** — DMG / ZIP (applies the ZIP, Squirrel-style)

It checks a provider (GitHub Releases or a generic HTTP server) for a newer version, picks the
artifact matching how *this* copy was installed, verifies its SHA-512, downloads it, and runs the
platform-appropriate installer.

## Runtime install-type detection

The headline change vs. upstream: the install format is detected **at runtime** rather than baked
into the app before packaging. This is what lets the packaging plugin build every format of an OS
in a single electron-builder invocation. Detection (`InstallTypeDetector`) mirrors
electron-updater's factory:

- **Linux** — `APPIMAGE` / `SNAP` / `FLATPAK` env, else electron-builder's per-target
  `resources/package-type` (deb/rpm), else fall back to the legacy `nucleus.executable.type` marker.
- **macOS** — always the ZIP.
- **Windows** — a per-target `package-type` (nsis/msi) if present, else the legacy marker, else NSIS.

It is fully unit-tested through an injectable `InstallEnvironment` seam.

## Layout

```
com/seanproctor/potassium/updater/
├── PotassiumUpdater.kt        entry point (checkForUpdates / download / installAndRestart)
├── UpdaterConfig.kt           DSL config (provider, channel, currentVersion, …)
├── Update{Result,Info,Event,Level}.kt · DownloadProgress.kt · Version.kt
├── exception/UpdateException.kt
├── provider/                  UpdateProvider · GitHubProvider · GenericProvider
├── internal/                  InstallTypeDetector · InstallEnvironment · FileSelector ·
│                              PlatformInstaller · PlatformInfo · YamlParser · ChecksumVerifier ·
│                              UpdateMarker
└── runtime/                   vendored from core-runtime, trimmed + rebranded:
    ├── Platform.kt            OS detection
    ├── PotassiumRuntime.kt    the InstallType enum + legacy executable-type marker reader
    └── PotassiumApp.kt        app id/version (trimmed to what the updater uses)
```

### What was vendored from `core-runtime` (and what wasn't)

Only what the updater actually imports: `Platform`, `ExecutableType` (→ `InstallType`),
`ExecutableRuntime` (→ `PotassiumRuntime`), and `NucleusApp` (→ `PotassiumApp`, trimmed to
`appId`/`version`). The rest of `core-runtime` — deep links, native library loading, single-instance,
desktop-environment/Logger tools — is unrelated to updating and was left behind.

## Build

From the repo root:

```bash
./gradlew :updater:test
```

Kotlin 2.3.21 / JVM 17.

## Status & attribution

Draft / work in progress. Forked from kdroidFilter's Nucleus (`updater-runtime` + `core-runtime`);
original copyright headers are retained. Package imports stay under `com.seanproctor.potassium.updater`.

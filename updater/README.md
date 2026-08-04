# potassium-updater

The auto-update library for Compose/JVM desktop applications — the **updating** half of
[Potassium](https://github.com/sproctor/potassium), published to Maven Central as
`com.seanproctor:potassium-updater`. It is the runtime counterpart to the
[`potassium-packager`](../plugin) Gradle plugin in this repo (the **packaging** half): the plugin
generates the `latest-*.yml` manifests, this library consumes them.

Both halves are a focused fork of [Nucleus](https://github.com/kdroidFilter/Nucleus). This module
started as a fork of the monorepo's `updater-runtime`, with the handful of `core-runtime` classes
it needed vendored in, so it depends on nothing else from Nucleus.

## What it does

Self-updates desktop apps from an electron-builder-style release manifest (`latest-<os>.yml`):

| Platform | Self-updates            | Not self-updated                  |
|----------|-------------------------|-----------------------------------|
| Linux    | AppImage, deb, rpm      | snap, flatpak (store-managed)     |
| Windows  | NSIS (`.exe`), NSIS-web | MSI (opt-in), AppX/MSIX, portable |
| macOS    | ZIP, DMG                | PKG (store-managed)               |

It checks a provider (GitHub Releases or a generic HTTP server) for a newer version, picks the
artifact matching how *this* copy was installed, downloads it, verifies its SHA-512, and applies it
with the platform-appropriate installer — then relaunches the app.

MSI is treated as a managed-deployment format: electron-builder publishes no `.msi` entries in the
manifest and per-machine upgrades need elevation, so an MSI install reports
`isUpdateSupported() == false` unless the app opts in with `executableType`.

### Differential (delta) downloads

Updates download differentially where a blockmap exists: the old and new
[blockmaps](https://github.com/electron-userland/electron-builder) are compared and only the
changed chunks are fetched over HTTP range requests. Supported for AppImage (blockmap embedded in
the running file), macOS ZIP, and Windows NSIS (`.blockmap` sidecars). Any failure — a missing
sidecar, a host without range support, a checksum mismatch — falls back to a full download, and the
whole-file SHA-512 is verified either way.

## Runtime install-type detection

The install format is detected **at runtime** rather than baked into the app before packaging,
which lets the packaging plugin build a platform's formats in one electron-builder invocation.
Detection (`InstallTypeDetector`) mirrors electron-updater's factory:

- **Linux** — `APPIMAGE` / `SNAP` / `FLATPAK` env, else electron-builder's per-target
  `resources/package-type` (deb/rpm). Undetermined otherwise.
- **macOS** — always the ZIP.
- **Windows** — `resources/package-type` if present, else `PORTABLE_EXECUTABLE_FILE` (portable) or
  a `WindowsApps` path (AppX/MSIX), else NSIS.

MSI is the exception to the single-invocation rule: nothing in the toolchain marks an MSI install,
and every Windows format installs an identical payload, so the packager builds MSI separately in
order to stamp `package-type = msi` into it. Without that marker an MSI install is
indistinguishable from an NSIS one and would be "updated" with the NSIS installer, landing a second
copy beside itself.

Detection is unit-tested through an injectable `InstallEnvironment` seam.

## Layout

```
com/seanproctor/potassium/updater/
├── PotassiumUpdater.kt        entry point (checkForUpdates / downloadUpdate / installAndRestart)
├── UpdaterConfig.kt           DSL config (provider, channel, currentVersion, …)
├── InstallType.kt             the install formats, and which are self-updatable
├── Update{Result,Info,Event,Level}.kt · DownloadProgress.kt · Version.kt
├── exception/UpdateException.kt
├── provider/                  UpdateProvider · GitHubProvider · GenericProvider
├── internal/
│   ├── check & select         YamlParser · FileSelector · PlatformInfo · UpdaterHttp
│   ├── install-type           InstallTypeDetector · InstallEnvironment · InstallTypePlatform ·
│   │                          AppResources
│   ├── download               UpdateDownloadEngine · ChecksumVerifier
│   ├── differential           BlockMap · BlockMapCodec · DownloadPlan ·
│   │                          DifferentialDownloader · DifferentialUpdatePreparer · UpdateCache
│   └── install                PlatformInstaller · MacInstallScripts · ScriptLiterals ·
│                              UpdateMarker · AppDirs
└── runtime/Platform.kt        OS detection, vendored from Nucleus's core-runtime
```

Only `Platform` remains vendored from `core-runtime`; the rest of that module — deep links, native
library loading, single-instance, desktop-environment and logging tools — is unrelated to updating
and was left behind.

## Build

From the repo root:

```bash
./gradlew :updater:check      # compile + tests + detekt/ktlint
```

## Attribution

Forked from kdroidFilter's Nucleus (`updater-runtime` + `core-runtime`); original copyright headers
are retained. Packages are under `com.seanproctor.potassium.updater`.

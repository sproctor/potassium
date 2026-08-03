# Changelog

## Unreleased

### Bug Fixes

- **Windows update could prompt to uninstall** — after a silent NSIS update, the relaunch step picked the first `.exe` in the install directory, which could be the NSIS uninstaller (showing "Are you sure you want to uninstall?" — and removing the app on OK). The relaunch now uses the exact path of the previously running launcher (resolved from the process itself, never by scanning the install directory), and the NSIS installer is invoked in update mode (`/S --updated`). The relaunched app receives no installer arguments.
- **Failed installs no longer report a phantom update** — the post-update marker is written before the installer runs; if the install fails or is cancelled after the app exits, the next launch used to report `wasJustUpdated() == true` anyway. The marker is now discarded when the app still reports the version it had when the marker was written.

### Behavior Changes

- **Evidence-based Windows install-type detection** — instead of assuming every Windows install is NSIS, the updater now detects portable builds (`PORTABLE_EXECUTABLE_FILE` env), AppX/MSIX (`WindowsApps` install path), and NSIS (its uninstaller present in the install root); anything else is treated as an MSI install.
- **MSI installs no longer self-update** — a detected MSI install reports `isUpdateSupported() == false` instead of silently installing the NSIS build alongside the MSI one. electron-builder publishes no `.msi` entries in `latest.yml` and per-machine MSI upgrades require elevation, so MSI is treated as a managed-deployment format (Intune/GPO/SCCM). Opt in explicitly with `executableType = InstallType.MSI` and a manifest that lists the `.msi`.

## v0.4.0

**Released: 2026-08-02**

### New Features

- **Differential (delta) updates** — `potassium-updater` now downloads updates differentially using electron-builder blockmaps and HTTP range requests, fetching only the chunks that changed. Supported for AppImage (embedded blockmap, diffs against the running AppImage), macOS ZIP, and Windows NSIS (sidecar blockmaps, diffing against the previously downloaded artifact kept in a per-app cache). Any failure falls back automatically to a full download, and every download — differential or full — is still verified against the manifest's whole-file SHA-512. Opt out with `disableDifferentialDownload = true`.
- **Universal macOS ZIP blockmap** — `build-macos-universal` now generates an electron-builder-compatible `.zip.blockmap` for the ditto-built universal ZIP (via `app-builder`), so universal macOS updates can also be downloaded differentially.
- **Differential first updates on Windows** — the packager publishes electron-builder's updater-cache directory name into the app's resources (`updater-cache-dir`), and the updater diffs against the installer copy the NSIS install seeds at `%LOCALAPPDATA%\<name>-updater\installer.exe` when its own cache is empty — so even the very first update on a machine downloads only the changed blocks.

### Bug Fixes

- **Stale DMG blockmap after notarization** — stapling rewrites the DMG, invalidating the `.dmg.blockmap` electron-builder generated before notarization; the stale sidecar is now deleted instead of being published.

## v0.3.1

**Released: 2026-06-28**

### New Features

- **Release-channel auto-detection** — the updater derives the channel from `currentVersion` when none is configured (`alpha`/`beta` pre-release identifiers select those channels, everything else uses `latest`).

### Improvements

- **Notarization diagnostics** — `notarytool` output (and the Apple notarization log, when retrievable) is surfaced directly in the build failure instead of a bare exit code.

## v0.3.0

**Released: 2026-06-26**

### New Features

- **Multi-architecture releases** — CI builds x64 and arm64 for all targets, Linux ships per-arch update manifests (`latest-linux.yml` / `latest-linux-arm64.yml`), and the new `publish-s3-release` action merges per-arch manifests for multi-arch S3 publishing.

### Bug Fixes

- **Linux arch suffix in manifest names** — manifest filename resolution is centralized in `PlatformInfo`, fixing the arm64 suffix.

### Improvements

- Update marker file renamed to `potassium-update-event`.
- New Potassium branding for the documentation site.

## v0.2.1

**Released: 2026-06-25**

First release of the combined repository.

- **`potassium-updater` merged in** as the `:updater` subproject (previously a separate repository), sharing one version with the packager plugin.
- **Single version model** — the release version derives from the git tag, and update manifests are owned by electron-builder.
- **Channel is the single pre-release control** — `allowPrerelease` removed.
- **Per-install app id** — `UpdateMarker` derives a stable per-install default id instead of using a shared constant.

---

Potassium began as a fork of [kdroidFilter's Nucleus](https://github.com/kdroidFilter/Nucleus). Changes from before the fork (the Nucleus v1.x line) are recorded in the upstream repository's changelog and releases.

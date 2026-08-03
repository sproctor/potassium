# Changelog

## Unreleased

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

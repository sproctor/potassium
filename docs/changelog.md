# Changelog

## Unreleased

### Improvements

- **Notarization resumes instead of re-uploading** — a `notarize<Format>` task now checks the submission recorded by an earlier run before uploading anything. `xcrun notarytool submit --wait` polls Apple for minutes and is the step most likely to be lost to a dropped connection or a killed process, and re-running it abandoned a submission that was often about to be accepted. An already-accepted submission is stapled without re-uploading, one still in progress is waited for, and one Apple rejected fails the build with its log rather than silently paying for a second round trip on identical bytes. The record is keyed by the artifact's SHA-512, so a rebuilt artifact is always submitted fresh, and it is deleted once notarization completes.
- **Notarization credentials stay out of build logs** — a failed notarization printed a ready-to-run `xcrun notarytool log` command with the authentication arguments filled in, and a failed log fetch echoed the whole command line, putting the Apple ID, team ID, keychain profile, or API key and issuer identifiers into logs that CI retains. The manual command is now printed with a credentials placeholder, and the log fetch redacts those values. (The app-specific password was never on the command line; it is fed through stdin.)

## v0.4.1

**Released: 2026-08-04**

### Bug Fixes

- **Update scripts now quote every interpolated path** — installer, launcher, and cleanup paths were interpolated into the generated scripts unquoted, so an apostrophe in a path (`C:\Users\O'Brien\...`, an ordinary Windows profile) aborted the whole update after the app had already exited, leaving nothing installed and nothing relaunched. Because the downloaded artifact's file name comes from the update manifest, a hostile manifest could also inject shell or PowerShell statements. Paths are now emitted as properly escaped single-quoted literals on all platforms (`$(…)` in a path is no longer executed by the Linux/macOS scripts either).
- **Windows update could prompt to uninstall** — after a silent NSIS update, the relaunch step picked the first `.exe` in the install directory, which could be the NSIS uninstaller (showing "Are you sure you want to uninstall?" — and removing the app on OK). The relaunch now uses the exact path of the previously running launcher, taken from the running process rather than by picking an executable out of the install directory, and the NSIS installer is invoked in update mode (`/S --updated`). The relaunched app receives no installer arguments. (A directory scan remains as a fallback for launches that report no command path, and it now skips the uninstaller.)
- **Failed installs no longer report a phantom update** — the post-update marker is written before the installer runs; if the install fails or is cancelled after the app exits, the next launch used to report `wasJustUpdated() == true` anyway. An update is now reported only once the running version differs from the one recorded when the marker was written. The marker is preserved in the meantime, so reopening the old app while an install is still in progress no longer discards the event for the update that then lands.
- **AppX/MSIX detection no longer misfires on an ordinary install** — any install path containing a `\WindowsApps\` segment anywhere (for example `D:\WindowsApps\MyApp\`) was reported as a packaged app, which is not self-updatable, silently disabling updates for it. Detection is now anchored to the Program Files package root Windows actually stages packages in, honoring `ProgramFiles` / `ProgramFiles(x86)` / `ProgramW6432`.
- **Whitespace in the app version no longer breaks update-event tracking** — the post-update marker is trimmed when read, so a `currentVersion` carrying stray whitespace never matched it: the stale-marker guard was defeated, and a value ending in a newline split the record across lines and lost the event entirely. Values are now trimmed when written, and a malformed line no longer fails the whole read.
- **macOS DMG updates install silently and relaunch** — a DMG update was handed to Finder with `open`, which only mounts the image and leaves the install to the user dragging the bundle, so `installAndRestart()` never relaunched and the update could be abandoned half-done. The DMG is now mounted, the `.app` copied over the installed bundle, and the image detached, matching how ZIP updates already worked. This also makes DMG-only self-update viable for apps that set `executableType = InstallType.DMG`.
- **`wasJustUpdated()` and `consumeUpdateEvent()` never throw** — both parse the marker file, and a torn write from a crash mid-update (e.g. a truncated `1.`) made `Version.fromString` throw out of a call that apps make during startup, crashing every launch until the file was removed by hand. An unreadable or malformed marker is now reported as "not updated".

### Improvements

- **Update script renamed and moved into a per-app directory** — the script the updater writes to apply an update is now `<temp>/<appId>/updater.sh` (`updater.ps1` on Windows), replacing the inherited `nucleus-update` name at the root of the shared system temp directory. Two apps updating at the same moment no longer overwrite each other's script, which previously let one app's update run the other's installer or silently no-op. The script is created and removed within a single update, so nothing needs migrating.
- **electron-builder 26.15.7** — picks up the upstream NSIS fix that pins the payload archive to an install-time-decodable 7z filter (modern 7za auto-applied `BCJ2`/`ARM64` branch filters the NSIS extractor silently skips, which could drop executables from the installed app), plus snap template extraction and `electronLanguages` locale fixes.

### Behavior Changes

- **`executableType` is validated against the running platform** — setting it to a format that cannot exist on the current OS (for example `InstallType.MSI` in a cross-platform app's shared configuration) now throws `IllegalArgumentException` when the updater is constructed, instead of turning every update check on the other platforms into a `NoMatchingFileException`. Platform-independent formats (ZIP, TAR, 7z) and an unset value are accepted everywhere.

- **MSI installs are identified positively and no longer updated with the NSIS installer** — every Windows format installs an identical payload, so an MSI install was indistinguishable from an NSIS one and would "update" by installing the NSIS build alongside itself: two entries in Apps & Features, and the running app still on the old version. The packager now builds MSI in its own electron-builder invocation so its app image can carry `resources/package-type = msi`, which the updater reads to recognize the install and hold back — MSI reports `isUpdateSupported() == false` and is treated as a managed-deployment format (Intune/GPO/SCCM), since electron-builder publishes no `.msi` entries in `latest.yml` and per-machine MSI upgrades require elevation. Opt in explicitly with `executableType = InstallType.MSI` plus a manifest listing the `.msi`.

    **Two build-side consequences:** the `.msi` is written to its own `msi/` output directory instead of alongside the other Windows artifacts, so publishing workflows that glob the Windows output directory need to pick up both; and requesting MSI adds a second electron-builder invocation. Installs made by earlier versions carry no marker and behave as before.

- **Evidence-based Windows install-type detection** — the updater now recognizes portable builds (`PORTABLE_EXECUTABLE_FILE` env) and AppX/MSIX (`WindowsApps` install path), which are not self-updatable, instead of treating every Windows install as NSIS. Everything else still resolves to NSIS; nothing is inferred from what an install lacks, since MSI was the only format that needed such an inference and now carries a marker.

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

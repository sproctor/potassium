# Changelog

## Unreleased

### New Features

- **`AppImageIntegration`** (potassium-updater) — installs the running AppImage's own `.desktop` entry and icons into the user's XDG data directory, with `Exec=` rewritten to launch the image, so desktops that resolve icons only through installed entries (GNOME ignores per-window icons entirely) show the app's icon instead of a generic one. `status()` reports `NotAppImage` / `NotIntegrated` / `Integrated` / `Stale` / `ExternallyManaged` so the app can ask for consent once, refresh silently when the image moves or updates, and stay out of the way of deb/rpm installs and integration tools like Gear Lever or appimaged. See [Auto Update → AppImage Desktop Integration](auto-update.md#appimage-desktop-integration).

## v0.5.0

**Released: 2026-08-14**

### New Features

- **`macOS.bundleName`** — names the `.app` bundle directory across every macOS artifact. Defaults to `appName`, then `macOS.packageName` / `packageName`. See [macOS → Bundle Name](targets/macos.md#bundle-name).
- **`aotCache { }`** — the AOT cache settings block behind `enableAotCache`, adding a `compatibility` profile and `extraTrainingJvmArgs`. See [Configuration → AOT Cache](configuration.md#aot-cache-jdk-25).

### Bug Fixes

- **Every macOS artifact ships the same `.app` bundle name** — electron-builder stages the bundle inside a DMG under its sanitized product name but archives the prepackaged directory verbatim into the ZIP, so an app with `appName = "My App"` and `packageName = "MyApp"` shipped `My App.app` in the DMG and `MyApp.app` in the ZIP. The ZIP-based updater then removed the installed `My App.app`, extracted `MyApp.app` beside it and failed to relaunch, leaving no application at the installed path. The name is now resolved once and fed to jpackage's output, the GraalVM bundle and electron-builder's `productName`. Only the directory is renamed: the launcher stays at `Contents/MacOS/<packageName>` and `CFBundleName` still uses `appName`.
- **macOS ZIP updates never leave the machine without an application** — the update script removed the installed bundle before extracting, so a truncated download or a failed `ditto` destroyed the install. It now extracts into a staging directory beside the installed app, locates the `.app` inside the archive instead of guessing its name, keeps the installed path when both bundles share a `CFBundleIdentifier` (so Dock tiles, login items and aliases keep resolving), and swaps with rollback. It also refuses to replace a bundle at the adopted name that belongs to a different application.
- **ProGuard resolves the JDK's classes on JMOD-less JDKs** — every `-libraryjars` entry came from `$JAVA_HOME/jmods`, which JDKs built with run-time image linking (JEP 493, JDK 25+) drop entirely; Temurin 25 is one of them. With no library jars ProGuard could not resolve `java.lang.Object` and aborted after several hundred thousand warnings. It now falls back to extracting the run-time image with `jimage`, and a JDK offering neither fails with a one-line diagnostic.
- **Obfuscated builds no longer crash at runtime** — the per-module JNI keep rules left every native module that was not listed unprotected (`UnsatisfiedLinkError` on first call) and did not preserve the Compose and Kotlin(x) names (`ClassNotFoundException: androidx.compose.runtime.Composer` on first recomposition). Generic rules now cover any class declaring native methods, and framework name-preservation rules are injected whenever `proguard { obfuscate = true }`.
- **RPM installs find their launcher configuration** — fpm emits no `%dir` entries for the app's own tree, and the jpackage launcher discovers its directories by scanning `rpm -ql` for paths ending in `/app` and `/runtime`, so the launcher died with `Error opening "<app>.cfg"` on Fedora and RHEL. The generated RPM config now passes `--rpm-auto-add-directories`.
- **Linux app images with many dependencies start reliably** — the jpackage launcher serializes the expanded classpath to its child over a pipe with a single unlooped `read()`, and a large payload short-reads and SIGSEGVs in `setenv()` (JDK-8380085). The classpath is now collapsed into a pathing jar, shrinking the payload to a few KB.
- **Non-ASCII application names on Windows** — the generated `.rc` now declares UTF-8 so `rc.exe` stops turning Hebrew, Arabic and CJK names into mojibake in the version resource, and `FileDescription` / `ProductName` carry the app name rather than the description, which is what Task Manager shows as the process name.
- **Deep links register on Windows and GraalVM macOS** — `protocol(...)` only reached electron-builder, whose `protocols` field is honoured on macOS and Linux but ignored by the NSIS target. URL schemes are now written to the registry by an NSIS include and to `CFBundleURLTypes` in the GraalVM `Info.plist`. Values interpolated into the NSIS script are escaped, so a name containing `$` or `"` registers correctly.
- **`cleanupNativeLibs` works in Kotlin Multiplatform projects** — KMP runtime classpaths resolve project dependencies to their classes/resources directories rather than jars, which made artifact selection ambiguous and failed the build. The jar variant is now requested explicitly.
- **Applying `org.jetbrains.compose` alongside Potassium** — Potassium ships its own fork of the Compose Desktop packaging and registers the same task names, so configuring both failed with `Cannot add task '...' as a task with that name already exists`. The conflict is now reported up front, naming the block to remove; the Compose plugin itself can stay applied.
- **Update manifests cannot direct a download outside its staging directory** — the artifact name comes from the manifest's `url` field, which is remote input, and a value such as `../victim` resolved outside the directory the updater downloads into. Names that are not a single path component are now rejected.

### Improvements

- **Notarization resumes instead of re-uploading** — a `notarize<Format>` task now checks the submission recorded by an earlier run before uploading anything. `xcrun notarytool submit --wait` polls Apple for minutes and is the step most likely to be lost to a dropped connection or a killed process, and re-running it abandoned a submission that was often about to be accepted. An already-accepted submission is stapled without re-uploading, one still in progress is waited for, and one Apple rejected fails the build with its log rather than silently paying for a second round trip on identical bytes. The record is keyed by the artifact's SHA-512, so a rebuilt artifact is always submitted fresh, and it is deleted once notarization completes.
- **Notarization credentials stay out of build logs** — a failed notarization printed a ready-to-run `xcrun notarytool log` command with the authentication arguments filled in, and a failed log fetch echoed the whole command line, putting the Apple ID, team ID, keychain profile, or API key and issuer identifiers into logs that CI retains. The manual command is now printed with a credentials placeholder, and the log fetch redacts those values. (The app-specific password was never on the command line; it is fed through stdin.)
- **The macOS app image no longer rewrites every jar** — jars were re-zipped unconditionally and their entries restamped with the build time, so an untouched dependency came out byte-different on every build and differential updates had to refetch it whole. Only jars carrying a native library are rewritten now, and those keep their entry timestamps.
- **Update downloads are staged in a private directory** — previously a predictable path in the shared system temp directory, which is a pre-created-file and symlink hazard on multi-user machines. Each download now gets a fresh owner-only directory.
- **`UpdaterConfig` is validated and frozen when the updater is constructed** — a missing `provider` failed at the first network call instead of at construction, and mutating the config afterwards silently changed a live updater's behaviour.

### Behavior Changes

- **The `.app` bundle directory may be renamed.** It now follows `macOS.bundleName` > `appName` > `packageName`, where the raw app image previously used `macOS.packageName`. If you set `macOS.packageName` and it differs from `appName`, the build warns and names the `macOS.bundleName` value that restores the previous name. Existing installs are updated in place as long as the `CFBundleIdentifier` is unchanged.
- **AOT caches are CPU-portable by default.** The JDK 25+ cache also stores machine code generated for the training machine's CPU features, and no JDK before 27 validates those features when loading it — so a cache built in CI crashed with an illegal instruction on narrower CPUs. The cache now holds class metadata only, trading roughly 6% of the startup win for a cache that runs anywhere. Restore the previous behaviour with `aotCache { compatibility = AotCacheCompatibility.NATIVE }`.
- **A `compose.desktop.application { }` block alongside `potassium { }` now fails the build.** Configuring it initializes Compose Desktop's own packaging, which registers the same task names Potassium does, and the build failed with `Cannot add task '...' as a task with that name already exists`. That is now reported up front instead. This affects the Compose Hot Reload workaround the docs previously suggested: pass `-PmainClass=...` to `hotRun` rather than declaring a minimal Compose block. The Compose plugin itself stays applied — Potassium extends it.
- **jpackage's `--description` now carries the application name.** On Windows that string becomes the `.exe` version resource's `FileDescription`, which Task Manager shows as the process name; it previously showed the `description` text. This matches what electron-builder and GraalVM native-image already produced.

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

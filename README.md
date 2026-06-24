# nucleus-updater (draft)

A draft of the **new runtime install-type detection** for Nucleus auto-update. It replaces
the current approach of baking a single `nucleus.executable.type` into the prepackaged app
*before* electron-builder runs.

## Why this exists

Today the Gradle plugin stamps the install format into the jpackage app's `.cfg`
(`-Dnucleus.executable.type=…`) before calling electron-builder with `--prepackaged`.
Because `--prepackaged` takes exactly one directory and packages it into **every** target,
each format needs its own prepackaged dir → **one electron-builder invocation per format**.
That single-prepackaged-dir constraint is the only reason the plugin can't build
`--linux AppImage deb rpm` in one invocation.

This module moves detection to **runtime**, mirroring how electron-updater does it
(`electron-updater/src/index.ts`): platform + the `resources/package-type` file that
electron-builder writes **per target** into deb/rpm/pacman packages, plus the
`APPIMAGE`/`SNAP` environment variables. With nothing baked into the shared input, the
plugin can batch all formats of an OS into one invocation and let electron-builder own the
`latest-<os>.yml` manifest.

## Detection — `InstallTypeDetector`

| Platform | Signal (in priority order) | Result |
|---|---|---|
| Linux | `APPIMAGE` env set | `APPIMAGE` |
| Linux | `SNAP` env set | `SNAP` |
| Linux | `FLATPAK_ID` env / `/.flatpak-info` | `FLATPAK` |
| Linux | `resources/package-type` = `deb`/`rpm`/`pacman` | `DEB`/`RPM`/`PACMAN` |
| Linux | none | `UNKNOWN` (selection uses platform fallback) |
| macOS | always | `ZIP` (updater applies the ZIP regardless of DMG/ZIP install) |
| Windows | `resources/package-type` (Nucleus-written, optional) | `NSIS`/`MSI`/… |
| Windows | none | `NSIS` (electron-updater is always NSIS on win32) |

electron-builder writes `package-type` **only** for Linux deb/rpm/pacman (its `FpmTarget`),
not for AppImage (detected via `APPIMAGE`) and not on Windows. Consequences:

- **Linux & macOS** need no baked marker — batching is clean and electron-builder can own
  the manifest.
- **Windows** has no electron-builder marker. To disambiguate `nsis` vs `msi` when a release
  ships both, Nucleus must write its own per-target `package-type`. If a release ships a
  single Windows installer, `UNKNOWN` + platform fallback (see `UpdateArtifactSelector`)
  selects it correctly with no marker.

## Module layout

- `Platform` — OS family detection.
- `InstallType` — the format enum, with `selfUpdatable` and the artifact-matching rule
  (`ArtifactMatch`) used to pick the right file from a manifest.
- `Environment` / `SystemEnvironment` — the host-access seam (env vars, system properties,
  files, process executable path), so detection is fully unit-testable.
- `InstallTypeDetector` — the per-platform detection above.
- `UpdateArtifactSelector` — maps a detected `InstallType` to the matching artifact, with
  platform-default fallback (mirrors the updater-runtime `FileSelector`).

## Open questions (need a real build to confirm)

1. **Where `resources/package-type` lands in Nucleus's jpackage install layout.**
   `resourceDirCandidates()` probes the likely locations relative to `java.home` and the
   launcher path; confirm against a real `.deb`/`.rpm` install.
2. **That electron-builder emits `package-type` for prepackaged jpackage deb/rpm builds**
   (it's gated on publish-config presence, which Nucleus has).
3. **pacman artifact naming** — `PACMAN` assumes a `.pacman` artifact; verify against an
   actual electron-builder pacman build.

## How it plugs into the rest of Nucleus

- **`updater-runtime`** (`../Nucleus`): replace `ExecutableRuntime.type()` / the `format`
  resolution in `NucleusUpdater` with `InstallTypeDetector.detect()`, keeping a fallback to
  the existing `nucleus.executable.type` system property during migration.
- **`nucleus-plugin`**: stop baking the marker for Linux/macOS, batch each OS's formats into
  one electron-builder invocation, and let electron-builder own `latest-<os>.yml`. Keep a
  per-target marker on Windows only if shipping nsis + msi together.

## Build

```bash
./gradlew test
```

Package namespace is `com.seanproctor.nucleus.updater`. If this is folded into the existing
`updater-runtime`, the public types can keep the `io.github.kdroidfilter.nucleus.updater`
package for drop-in source compatibility with current consumers.

# Packaging

!!! info "About this comparison"
    Adapted from the [Nucleus comparison page](https://nucleusframework.dev/en/docs/compare/packaging/) — Nucleus is the project Potassium was forked from — with Potassium added back in. Last updated: August 2026.

This page compares Potassium and Nucleus packaging with the other tools that build native installers for JVM desktop apps: jpackage, Compose Multiplatform, Conveyor, install4j, jDeploy, and JavaPackager. It covers distributable formats, auto-update, code signing, CI/CD, GraalVM Native Image, and store distribution.

Potassium builds 16 distributable formats and packages auto-update, signing, sandboxing, CI actions, and GraalVM Native Image output in one Gradle plugin under an MIT license, sharing its electron-builder-based packaging core with Nucleus (18 formats). jpackage covers six formats and nothing around them, install4j is proprietary, and Conveyor covers a different set but does not produce DMG or PKG.

## Format coverage

| Tool | DMG | PKG | NSIS | MSI | MSIX/AppX | Portable | DEB | RPM | AppImage | Snap | Flatpak | Archives | Total |
|------|:---:|:---:|:----:|:---:|:---------:|:--------:|:---:|:---:|:--------:|:----:|:-------:|:--------:|:-----:|
| **Potassium** | Y | Y | Y | Y | Y | Y | Y | Y | Y | Y | Y | ZIP, TAR, 7Z | **16** |
| **Nucleus** | Y | Y | Y | Y | Y | Y | Y | Y | Y | Y | Y | ZIP, TAR, 7Z | **18** |
| Conveyor | — | — | — | — | Y | — | Y | — | — | — | — | ZIP, TAR + custom EXE | 6 |
| install4j | Y | — | — | Y | — | — | Y | Y | — | — | — | TAR, shell | 7 |
| jpackage | Y | Y | — | Y | — | — | Y | Y | — | — | — | — | 6 |
| jDeploy | — | — | — | — | — | — | Y | — | — | — | — | EXE, TAR | 4 |
| Compose MP | Y | Y | — | Y | — | — | Y | Y | — | — | — | — | 6 |
| JavaPackager | Y | Y | — | Y | — | — | Y | Y | Y | — | — | ZIP | 8 |

Potassium and Nucleus reach the higher format counts by combining two build steps: jpackage builds the app-image, then electron-builder's `--prepackaged` mode produces every installer flavor from it — including the formats jpackage does not support, such as NSIS, MSIX, Snap, Flatpak, and AppImage. This hybrid build path is specific to the two of them in the JVM ecosystem.

Both totals also count formats not broken out as their own columns above. Potassium's 16 includes `Exe` (a jpackage-native Windows installer, distinct from NSIS) and `NsisWeb` (a web-installer variant of NSIS); Nucleus's 18 additionally counts `Pacman` (Arch Linux) and `RawAppImage` (the intermediate jpackage app-image as a selectable target, which Potassium treats as an intermediate only).

## Auto-update

| Tool | Runtime lib | Channels | Verification | Delta updates |
|------|-------------|----------|--------------|:-------------:|
| **Potassium** | `PotassiumUpdater` | 3 (latest/beta/alpha) | SHA-512 | Y (blockmap) |
| **Nucleus** | `NucleusUpdater` | 3 (latest/beta/alpha) | SHA-512 | — |
| Conveyor | OS-native (Sparkle 2 / MSIX / apt) | Yes | Yes | Y (OS-native) |
| install4j | Updater API | Yes | Yes | — |
| jDeploy | Built-in | No | No | — |
| jpackage / Compose MP | None | — | — | — |

Conveyor (about 31 KB on macOS via Sparkle 2) and Potassium are the compared tools with delta updates. Potassium downloads only the changed blocks via electron-builder blockmaps and HTTP range requests — for AppImage (differential from the very first update), Windows NSIS (also from the first update, via the installer copy the NSIS install seeds), and macOS ZIP — falling back to a full download automatically on any failure. Nucleus downloads the full file. Both expose a runtime API for progress flow, channel switching, post-update detection, and restart-on-update.

## Code signing and notarization

Every compared tool signs and notarizes macOS builds. Windows signing differs:

- **Potassium / Nucleus** — `.pfx` and Azure Artifact Signing.
- **Conveyor** — the widest set of signing backends: Azure Key Vault, AWS KMS, SSL.com eSigner, DigiCert ONE, Google Cloud KMS, and SafeNet/YubiKey HSMs.
- **install4j** — `.pfx` only.
- **jpackage** — macOS only.

## CI/CD integration

- **Potassium** — six composite GitHub Actions: `setup-potassium`, `setup-macos-signing`, `build-macos-universal` (which also generates the universal ZIP blockmap for delta updates), `build-windows-appxbundle`, `publish-github-release`, and `publish-s3-release`.
- **Nucleus** — six composite GitHub Actions: `setup-nucleus`, `setup-macos-signing`, `build-macos-universal`, `build-windows-appxbundle`, `generate-update-yml`, and `publish-release`.
- **Conveyor** — examples only; single-machine builds.
- **install4j** — CLI only.
- **jpackage / Compose MP** — none.

## GraalVM Native Image packaging

Potassium and Nucleus package GraalVM Native Image output end to end, producing DMG, NSIS, and DEB for the resulting binary. A packaged binary starts in about 0.2 s, uses about 30 MB of RAM (Nucleus's measurement on Windows 11 with a Hello World build), and is about 40 MB in size as an NSIS installer with maximum compression. See [GraalVM Native Image](../graalvm/index.md). Other tools route Native Image binaries through generic packaging pipelines that do not account for the JDK-less output.

## Store distribution

Potassium, Nucleus, Conveyor, and Compose Multiplatform each produce store-acceptable artifacts. Potassium and Nucleus produce all four store formats from one DSL: PKG (Mac App Store), AppX/MSIX (Microsoft Store), Snap (Snap Store), and Flatpak (Flathub).

## Trade-offs

| Where Potassium and Nucleus are strong | Where competitors win |
|----------------------------------------|----------------------|
| Most formats. Auto-update, signing, and CI in one plugin. Built-in GraalVM Native Image packaging. MIT. Potassium adds blockmap delta updates; Nucleus adds a batteries-included runtime. | Conveyor: OS-native delta updates, broader HSM signing, single-machine builds. install4j: stable, paid, long-running support. jpackage: no new dependency. |

!!! info
    Potassium and Nucleus build each OS on its own CI runner — there is no cross-compilation. Both are Gradle-only and younger than Conveyor and install4j.

## When to pick what

- A small CLI or single-window app that needs OS-native update plumbing and cloud HSM signing — **Conveyor**.
- An enterprise installer with custom dialogs and paid support — **install4j**.
- A DMG, MSI, or DEB straight from jpackage — **Compose Multiplatform** or **jpackage** directly.
- One Kotlin codebase covering every format and store, plus a batteries-included runtime (window decorations, notifications, media integration) — **Nucleus**.
- The same packaging core with a focused packager + updater scope and blockmap delta updates — **Potassium**.

## What's next

- [Auto-update](../auto-update.md) — configure `PotassiumUpdater`, release channels, and delta updates.
- [Code signing](../code-signing.md) — sign and notarize builds per OS.
- [CI/CD](../ci-cd.md) — the composite GitHub Actions for building and publishing.
- [GraalVM Native Image](../graalvm/index.md) — package a JDK-less native binary.

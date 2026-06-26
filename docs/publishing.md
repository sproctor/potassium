# Publishing

Potassium can publish your installers and update metadata to **GitHub Releases**, **Amazon S3**, or a **generic HTTP server**.

!!! note "One provider at a time"
    Exactly one publish provider may be enabled. Enabling more than one of `github`, `s3`, or
    `generic` fails the build (electron-builder writes a single shared update manifest per build).

## Configuration

```kotlin
potassium {
    publish {
        // Publish mode
        publishMode = PublishMode.Auto // Never, Auto, Always

        // Enable exactly one of the following providers.
        github {
            enabled = true
            owner = "myorg"
            repo = "myapp"
            token = System.getenv("GITHUB_TOKEN")
            channel = ReleaseChannel.Latest
            releaseType = ReleaseType.Release
        }

        // Or S3
        s3 {
            enabled = false
            bucket = "my-updates-bucket"
            region = "us-east-1"
            path = "releases"
            acl = "public-read"
        }

        // Or generic HTTP server
        generic {
            enabled = false
            url = "https://updates.example.com/releases/"
            channel = ReleaseChannel.Latest
            useMultipleRangeRequest = true
        }
    }
}
```

## GitHub Releases

The most common publishing target. Installers and YML metadata files are uploaded as release assets.

```kotlin
publish {
    github {
        enabled = true
        owner = "myorg"                      // GitHub org or user
        repo = "myapp"                       // Repository name
        token = System.getenv("GITHUB_TOKEN") // Authentication token
        channel = ReleaseChannel.Latest      // Latest, Beta, Alpha
        releaseType = ReleaseType.Release    // Release, Draft, Prerelease
    }
}
```

### Release Structure

A GitHub Release created by Potassium contains:

```
v1.0.0 (Release)
├── MyApp-1.0.0-macos-universal.dmg   ← one binary runs on both arches
├── MyApp-1.0.0-windows-amd64.exe
├── MyApp-1.0.0-windows-arm64.exe
├── MyApp-1.0.0-windows.msixbundle
├── MyApp-1.0.0-linux-amd64.deb
├── MyApp-1.0.0-linux-arm64.deb
├── MyApp-1.0.0-linux-amd64.rpm
├── MyApp-1.0.0-linux-amd64.AppImage
├── latest-mac.yml             ← Auto-update metadata (macOS, both arches)
├── latest.yml                 ← Auto-update metadata (Windows, both arches)
├── latest-linux.yml           ← Auto-update metadata (Linux x64)
└── latest-linux-arm64.yml     ← Auto-update metadata (Linux arm64)
```

Linux ships a separate manifest per architecture (`latest-linux.yml` / `latest-linux-arm64.yml`) because that's what electron-updater fetches by arch. Windows and macOS use a single manifest each — the [`publish-github-release`](ci-cd.md#multi-architecture-update-manifests) action merges the per-arch outputs into it. Shipping per-arch macOS packages instead of a universal binary? You'll see `MyApp-1.0.0-macos-arm64.dmg` / `-amd64.dmg` in place of the universal one; the single `latest-mac.yml` covers both either way.

### GitHub Token

Use a `GITHUB_TOKEN` with `contents: write` permission:

```yaml
# GitHub Actions — automatic token
permissions:
  contents: write

env:
  GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

## Amazon S3

Publish to an S3 bucket for self-hosted update distribution:

```kotlin
publish {
    s3 {
        enabled = true
        bucket = "my-updates-bucket"
        region = "us-east-1"
        path = "releases/myapp"    // Prefix path in the bucket
        acl = "public-read"        // ACL for uploaded files
    }
}
```

### Upload flow

S3 publishing is done by **electron-builder itself, inline at the end of each `package…` build** — there is no separate publish step or action. When the `s3` provider is enabled and `publishMode` is `Auto` or `Always`, electron-builder PUTs that build's installers plus its update manifest (`latest.yml` / `latest-mac.yml` / `latest-linux.yml`) under the bucket `path`, using the AWS credentials from the environment. With `publishMode = Never`, manifests are written locally and nothing is uploaded.

So the sequence for a single build is:

1. `./gradlew packageReleaseDistributionForCurrentOS` builds the installers and writes the update manifest.
2. electron-builder uploads both to `s3://<bucket>/<path>/`, overwriting any existing object of the same key.
3. The auto-updater fetches `https://<bucket>.s3.<region>.amazonaws.com/<path>/latest-<platform>.yml`.

### S3 Bucket Structure

A complete multi-architecture release looks like:

```
s3://my-updates-bucket/releases/myapp/
├── MyApp-1.0.0-macos-universal.dmg
├── MyApp-1.0.0-windows-amd64.exe
├── MyApp-1.0.0-windows-arm64.exe
├── MyApp-1.0.0-linux-amd64.deb
├── MyApp-1.0.0-linux-arm64.deb
├── latest-mac.yml             # macOS (both arches)
├── latest.yml                 # Windows (both arches)
├── latest-linux.yml           # Linux x64
└── latest-linux-arm64.yml     # Linux arm64
```

### S3 Authentication

Set AWS credentials via environment variables:

```bash
export AWS_ACCESS_KEY_ID=AKIA...
export AWS_SECRET_ACCESS_KEY=...
export AWS_REGION=us-east-1
```

### Multi-architecture S3 releases

!!! warning "Inline S3 upload does not merge manifests"
    electron-builder's inline S3 publish PUTs each build's manifest independently, so two
    runners that share a manifest name **overwrite each other** on S3 — the last upload wins and
    its manifest lists only its own architecture. Unlike GitHub Releases, where the
    [`publish-github-release`](ci-cd.md#multi-architecture-update-manifests) action merges per-arch
    manifests before upload, **there is no S3 publish action that merges**, so inline S3 publish is
    only safe for a single architecture per OS.

    - **Linux** is unaffected — electron-builder names the files per-arch (`latest-linux.yml` /
      `latest-linux-arm64.yml`), so they never collide.
    - **Windows and macOS** share one manifest each (`latest.yml`, `latest-mac.yml`); uploading two
      arches inline leaves the loser's clients unable to update.

For a multi-arch Windows/macOS release on S3, **consolidate before uploading** instead of
publishing inline:

1. Build every `(os, arch)` with `publishMode = Never` — electron-builder writes correct manifests
   locally and uploads nothing. On macOS, assemble the universal binary and its `latest-mac.yml`
   first (see [Universal macOS Binaries](ci-cd.md#universal-macos-binaries)).
2. Collect every runner's `build/potassium/binaries/**` output into one directory.
3. Merge the same-named manifests' `files:` arrays into one — the `publish-github-release` action's
   `merge_update_manifests.py` does exactly this (see
   [Multi-architecture update manifests](ci-cd.md#multi-architecture-update-manifests)).
4. Upload the consolidated tree:

   ```bash
   aws s3 sync ./release s3://my-updates-bucket/releases/myapp/ --acl public-read
   ```

## Generic HTTP Server

For self-hosted update distribution without cloud dependencies. The generic provider generates the `latest-*.yml` metadata files and configures the auto-updater to fetch from a base URL. You are responsible for uploading the output to your server.

```kotlin
publish {
    generic {
        enabled = true
        url = "https://updates.example.com/releases/"
        channel = ReleaseChannel.Latest        // Latest, Beta, Alpha
        useMultipleRangeRequest = true         // Differential downloads
    }
}
```

### Server Structure

Upload the installer and YML files to your server:

```
https://updates.example.com/releases/
├── MyApp-1.0.0-macos-arm64.dmg
├── MyApp-1.0.0-windows-amd64.exe
├── MyApp-1.0.0-linux-amd64.deb
├── latest-mac.yml
├── latest.yml
└── latest-linux.yml
```

Any static file server (Nginx, Caddy, Apache, S3 with public access, Cloudflare R2, etc.) works — the auto-updater fetches `<url>/latest-<platform>.yml` (Linux on arm64 fetches `latest-linux-arm64.yml`) and downloads the installer from the same base URL. For a multi-arch release, upload the arm64 installers and `latest-linux-arm64.yml` too — and, for Windows/macOS, merge the two per-arch `latest.yml` / `latest-mac.yml` into one first (the same consolidation as [Multi-architecture S3 releases](#multi-architecture-s3-releases)).

## Release Channels

Channels allow you to distribute pre-release versions to testers:

| Channel | YML Prefix | Version Pattern | Audience |
|---------|------------|-----------------|----------|
| `ReleaseChannel.Latest` | `latest-` | `1.0.0` | All users |
| `ReleaseChannel.Beta` | `beta-` | `1.0.0-beta.1` | Beta testers |
| `ReleaseChannel.Alpha` | `alpha-` | `1.0.0-alpha.1` | Internal testers |

Users on the `beta` channel receive both `latest` and `beta` updates. Users on the `alpha` channel receive all updates.

Configure the channel in the updater runtime:

```kotlin
PotassiumUpdater {
    provider = GitHubProvider(owner = "myorg", repo = "myapp")
    channel = "beta" // Subscribe to beta updates
}
```

## Release Types

| Type | Description |
|------|-------------|
| `ReleaseType.Release` | Visible on the releases page |
| `ReleaseType.Draft` | Hidden until manually published |
| `ReleaseType.Prerelease` | Marked as pre-release |

## Publish Modes

| Mode | Description |
|------|-------------|
| `PublishMode.Never` | Do not publish (build only) |
| `PublishMode.Auto` | Publish if on CI, skip locally |
| `PublishMode.Always` | Always publish |

## DSL Reference

### `publish { }`

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `publishMode` | `PublishMode` | `Never` | When to publish |

### `publish { github { } }`

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | `Boolean` | `false` | Enable GitHub publishing |
| `owner` | `String` | — | GitHub owner/org |
| `repo` | `String` | — | Repository name |
| `token` | `String?` | `null` | GitHub token |
| `channel` | `ReleaseChannel` | `Latest` | Release channel |
| `releaseType` | `ReleaseType` | `Release` | Release type |

### `publish { s3 { } }`

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | `Boolean` | `false` | Enable S3 publishing |
| `bucket` | `String` | — | S3 bucket name |
| `region` | `String` | — | AWS region |
| `path` | `String?` | `null` | Key prefix |
| `acl` | `String?` | `null` | S3 ACL |

### `publish { generic { } }`

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | `Boolean` | `false` | Enable generic HTTP publishing |
| `url` | `String` | — | Base URL where update files are hosted |
| `channel` | `ReleaseChannel` | `Latest` | Release channel |
| `useMultipleRangeRequest` | `Boolean` | `true` | Use multiple range requests for differential downloads |

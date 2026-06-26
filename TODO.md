# TODO

Tracked follow-up work for Potassium. Each item notes why it matters and where it lives.

## Auto-update

- [ ] **Differential (delta) macOS updates for universal builds.** `build-macos-universal`
  produces the universal `.zip` with `ditto`, which doesn't emit a `.blockmap`, so the
  `latest-mac.yml` it writes has no `blockMapSize` and electron-updater falls back to a full
  `.zip` download instead of a differential one. Updates still work — they're just larger.
  To enable delta updates, generate a blockmap for the universal `.zip` (matching
  electron-builder's format) and add `blockMapSize` to the manifest.
  - Action: `.github/actions/build-macos-universal/build-universal.sh`
    (`write_update_manifest` / the ZIP creation step around `ZIP_OUT`).

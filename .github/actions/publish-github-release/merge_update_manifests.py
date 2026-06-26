#!/usr/bin/env python3
"""Consolidate same-named electron-builder update manifests across per-arch CI artifacts.

A Compose/JVM app cannot cross-compile its bundled runtime image, so every
``(os, arch)`` is built on its own native runner. electron-updater fetches a
*single* channel file regardless of CPU architecture for some platforms, so two
runners can emit the same manifest filename, each listing only its own arch:

* Windows  -> ``latest.yml``      for both x64 and arm64
* macOS    -> ``latest-mac.yml``  for both x64 and arm64 (when not shipping a
              universal binary)

This script unions their ``files:`` arrays into one manifest so the updater sees
every architecture. The merged manifest is written in place over the first
(alphabetically-sorted, i.e. amd64/x64) copy and the duplicates are removed, so
the caller's de-duplication then uploads exactly one file per channel name.

Linux is intentionally untouched: electron-builder already names its Linux
channel files per-arch (``latest-linux.yml`` vs ``latest-linux-arm64.yml``), and
electron-updater fetches the matching name, so they never collide.
"""
from __future__ import annotations

import collections
import glob
import os
import sys

import yaml


def merge(artifacts_dir: str) -> None:
    pattern = os.path.join(artifacts_dir, "release-assets-*", "**", "*.yml")
    by_name: dict[str, list[str]] = collections.defaultdict(list)
    for path in glob.glob(pattern, recursive=True):
        by_name[os.path.basename(path)].append(path)

    for name, paths in sorted(by_name.items()):
        if len(paths) < 2:
            continue
        # Deterministic order; amd64/x64 sorts before arm64 so the merged file
        # and its legacy top-level path/sha512 default to the x64 installer.
        paths.sort()

        docs = []
        for path in paths:
            with open(path, encoding="utf-8") as handle:
                data = yaml.safe_load(handle)
            if isinstance(data, dict) and "files" in data:
                docs.append((path, data))

        if len(docs) < 2:
            continue

        winner_path, merged = docs[0]
        seen: set[str] = set()
        files = []
        for _, data in docs:
            for entry in data.get("files") or []:
                url = entry.get("url")
                if url in seen:
                    continue
                seen.add(url)
                files.append(entry)
        merged["files"] = files

        with open(winner_path, "w", encoding="utf-8") as handle:
            yaml.safe_dump(merged, handle, default_flow_style=False, sort_keys=False)
        for path, _ in docs[1:]:
            os.remove(path)

        members = ", ".join(os.path.relpath(p, artifacts_dir) for p, _ in docs)
        print(f"Merged {len(docs)} '{name}' manifests -> {os.path.relpath(winner_path, artifacts_dir)} "
              f"({len(files)} files): {members}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: merge_update_manifests.py <artifacts-dir>", file=sys.stderr)
        sys.exit(2)
    merge(sys.argv[1])

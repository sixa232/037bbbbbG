"""Verify that CloudStream can find and verify the files we are about to publish."""

import hashlib
import json
import sys
from pathlib import Path


def verify_release(manifest: Path, builds: Path, repository: str) -> None:
    raw = f"https://raw.githubusercontent.com/{repository}/builds"
    plugin_lists = json.loads(manifest.read_text(encoding="utf-8"))["pluginLists"]
    if plugin_lists != [f"{raw}/plugins.json"]:
        raise ValueError(f"{manifest} must point to {raw}/plugins.json")

    entries = json.loads((builds / "plugins.json").read_text(encoding="utf-8"))
    packages = {path.name: path for path in builds.glob("*.cs3")}
    if not entries or not packages:
        raise ValueError("Missing plugin entries or .cs3 files")

    for entry in entries:
        name = entry["internalName"]
        filename = f"{name}.cs3"
        if (entry["repositoryUrl"] != f"https://github.com/{repository}"
                or entry["url"] != f"{raw}/{filename}"):
            raise ValueError(f"{name}: plugin metadata points outside {repository}")
        package = packages.pop(filename, None)
        if package is None:
            raise ValueError(f"{name}: missing or duplicate plugin package")
        digest = hashlib.sha256(package.read_bytes()).hexdigest()
        if entry["fileHash"] != f"sha256-{digest}" or entry["fileSize"] != package.stat().st_size:
            raise ValueError(f"{name}: generated metadata does not match {filename}")
    if packages:
        raise ValueError(f"Packages missing from plugins.json: {', '.join(sorted(packages))}")


if __name__ == "__main__":
    manifest, builds, repository = sys.argv[1:]
    verify_release(Path(manifest), Path(builds), repository)

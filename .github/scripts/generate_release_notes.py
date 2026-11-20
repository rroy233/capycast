#!/usr/bin/env python3
"""Assemble manually written release notes into a GitHub Release body."""

import argparse
import html
import json
import re
import sys
from pathlib import Path

FORMAT_MARKER = "<!-- capycast:release-notes:v1 -->"
MARKER_PREFIX = "<!-- capycast:release-notes:"
VERSION_PATTERN = re.compile(r"\d+\.\d+\.\d+(?:-beta\.\d+)?\Z")
LANGUAGE_PATTERN = re.compile(r"[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*\Z")


def _read_languages(path: Path) -> tuple[str, list[tuple[str, str]]]:
    config = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(config, dict):
        raise ValueError(f"Language configuration must be an object: {path}")
    primary = config.get("primary")
    entries = config.get("languages")
    if not isinstance(primary, str) or not isinstance(entries, list) or not entries:
        raise ValueError(f"Language configuration requires primary and languages: {path}")
    languages = []
    seen = set()
    for entry in entries:
        if not isinstance(entry, dict):
            raise ValueError(f"Invalid language entry in {path}")
        tag, label = entry.get("tag"), entry.get("label")
        if not isinstance(tag, str) or not LANGUAGE_PATTERN.fullmatch(tag):
            raise ValueError(f"Invalid language tag in {path}: {tag}")
        if not isinstance(label, str) or not label.strip() or "\n" in label or "\r" in label:
            raise ValueError(f"Invalid language label in {path}: {tag}")
        if tag.lower() in seen:
            raise ValueError(f"Duplicate language tag in {path}: {tag}")
        seen.add(tag.lower())
        languages.append((tag, label))
    if primary not in [tag for tag, _ in languages]:
        raise ValueError(f"Primary language must match an entry in {path}: {primary}")
    return primary, languages


def _read_notes(path: Path) -> str:
    content = path.read_text(encoding="utf-8").strip("\n")
    if not content.strip():
        raise ValueError(f"Release notes are empty: {path}")
    if MARKER_PREFIX in content:
        raise ValueError(f"Reserved release-notes marker in {path}")
    return content


def _section(language: str, content: str) -> str:
    return (
        f"<!-- capycast:release-notes:{language}:start -->\n"
        f"{content}\n"
        f"<!-- capycast:release-notes:{language}:end -->"
    )


def build_release_notes(version: str, notes_dir: Path) -> str | None:
    """Assemble existing notes, or return None when no custom notes exist."""
    if not VERSION_PATTERN.fullmatch(version):
        raise ValueError(f"Invalid version name: {version}")

    primary_tag, languages = _read_languages(notes_dir / "i18n.json")
    primary = notes_dir / f"{version}.md"
    configured_tags = {tag for tag, _ in languages if tag != primary_tag}
    for path in (notes_dir / "i18n").glob(f"{version}-*.md"):
        tag = path.name[len(version) + 1 : -len(".md")]
        if LANGUAGE_PATTERN.fullmatch(tag) and tag not in configured_tags:
            raise ValueError(f"Release-note language is not configured: {path}")
    translations = [
        (tag, title, notes_dir / "i18n" / f"{version}-{tag}.md")
        for tag, title in languages
        if tag != primary_tag
    ]
    if not primary.is_file():
        if any(path.is_file() for _, _, path in translations):
            raise ValueError(f"Primary release notes are missing: {primary}")
        return None

    parts = [FORMAT_MARKER, _section(primary_tag, _read_notes(primary))]
    for tag, title, path in translations:
        if path.is_file():
            parts.append(
                f"<details>\n<summary>{html.escape(title, quote=False)}</summary>\n\n"
                f"{_section(tag, _read_notes(path))}\n\n</details>"
            )
    return "\n\n".join(parts) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version")
    parser.add_argument("--notes-dir", type=Path, default=Path("release-notes"))
    args = parser.parse_args()
    try:
        result = build_release_notes(args.version, args.notes_dir)
    except (OSError, UnicodeError, ValueError) as error:
        print(error, file=sys.stderr)
        return 1
    if result is not None:
        sys.stdout.write(result)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

"""Builds looping exercise animations and media_manifest.json from licensed sources.

Usage (from the repo root):
    python tools/build_media/build_media.py [--source PATH] [--contact-sheet PATH]

Without --source the script clones free-exercise-db at the pinned commit into
tools/build_media/.cache. Requires Pillow >= 10 (animated WebP support).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "shared" / "src" / "commonMain" / "composeResources" / "files"
CATALOG = ASSETS / "catalog.json"
MAPPING = Path(__file__).with_name("mapping.json")
CACHE = Path(__file__).with_name(".cache")

FEDB_URL = "https://github.com/yuhonas/free-exercise-db.git"
FEDB_COMMIT = "f00c92c7dcf1216a928a52c3706c7ce8e2f71ed5"
FEDB_SOURCE = {
    "id": "free-exercise-db",
    "title": "Free Exercise DB",
    "url": "https://github.com/yuhonas/free-exercise-db",
    "author": "yuhonas and contributors (data restructured from wrkout/exercises.json)",
    "license": "Unlicense",
    "licenseUrl": "https://unlicense.org/",
    "commit": FEDB_COMMIT,
}

MAX_SIDE = 360
FADE_FRAMES = 5
FRAME_MS = 84  # >= 1000/12, keeps the loop at or below 12 fps
HOLD_MS = 900
TARGET_BYTES = 150 * 1024
QUALITIES = (62, 52, 44, 36)


def fetch_source(path: Path | None) -> Path:
    if path:
        return path
    dest = CACHE / "free-exercise-db"
    if not dest.exists():
        CACHE.mkdir(parents=True, exist_ok=True)
        subprocess.run(["git", "clone", "--filter=blob:none", FEDB_URL, str(dest)], check=True)
    subprocess.run(["git", "-C", str(dest), "checkout", "--quiet", FEDB_COMMIT], check=True)
    return dest


def fit(img: Image.Image) -> Image.Image:
    img = img.convert("RGB")
    scale = MAX_SIDE / max(img.size)
    if scale < 1:
        img = img.resize((round(img.width * scale), round(img.height * scale)), Image.LANCZOS)
    return img


def frames_for(a: Image.Image, b: Image.Image) -> tuple[list[Image.Image], list[int]]:
    if b.size != a.size:
        b = b.resize(a.size, Image.LANCZOS)
    frames, durations = [a], [HOLD_MS]
    for i in range(1, FADE_FRAMES + 1):
        frames.append(Image.blend(a, b, i / (FADE_FRAMES + 1)))
        durations.append(FRAME_MS)
    frames.append(b)
    durations.append(HOLD_MS)
    for i in range(1, FADE_FRAMES + 1):
        frames.append(Image.blend(b, a, i / (FADE_FRAMES + 1)))
        durations.append(FRAME_MS)
    return frames, durations


def encode(frames: list[Image.Image], durations: list[int], out: Path) -> int:
    for q in QUALITIES:
        frames[0].save(
            out, "WEBP", save_all=True, append_images=frames[1:], duration=durations,
            loop=0, quality=q, method=4, minimize_size=True,
        )
        size = out.stat().st_size
        if size <= TARGET_BYTES:
            return size
    return out.stat().st_size


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", type=Path, help="local free-exercise-db checkout")
    ap.add_argument("--contact-sheet", type=Path, help="write a JPEG grid of first frames for review")
    args = ap.parse_args()

    catalog_ids = [e["id"] for e in json.loads(CATALOG.read_text(encoding="utf-8"))["exercises"]]
    mapping = json.loads(MAPPING.read_text(encoding="utf-8"))["map"]
    missing = [i for i in catalog_ids if i not in mapping]
    unknown = [i for i in mapping if i not in catalog_ids]
    if missing or unknown:
        print(f"mapping out of sync: missing={missing} unknown={unknown}", file=sys.stderr)
        return 1

    src_root = fetch_source(args.source) / "exercises"
    media_dir = ASSETS / "media"
    media_dir.mkdir(parents=True, exist_ok=True)
    for old in media_dir.glob("*.webp"):
        old.unlink()

    built: dict[str, dict] = {}
    items: dict[str, dict] = {}
    over_budget = []
    for cid in catalog_ids:
        entry = mapping[cid]
        if entry is None:
            continue
        src = entry["src"]
        if src not in built:
            record = json.loads((src_root / f"{src}.json").read_text(encoding="utf-8"))
            images = [src_root / p for p in record.get("images", [])]
            if len(images) < 2 or not all(p.exists() for p in images[:2]):
                print(f"{cid}: {src} has no start/end frames", file=sys.stderr)
                return 1
            a_path, b_path = images[0], images[1]
            name = src.lower().replace("-", "_") + ".webp"
            out = media_dir / name
            frames, durations = frames_for(fit(Image.open(a_path)), fit(Image.open(b_path)))
            size = encode(frames, durations, out)
            if size > TARGET_BYTES:
                over_budget.append((name, size))
            built[src] = {
                "file": f"media/{name}",
                "bytes": size,
                "sha256": hashlib.sha256(out.read_bytes()).hexdigest(),
                "preview": frames[0],
            }
        items[cid] = {
            "file": built[src]["file"],
            "source": FEDB_SOURCE["id"],
            "sourceId": src,
            "match": entry["match"],
            "license": FEDB_SOURCE["license"],
            "author": FEDB_SOURCE["author"],
            "sha256": built[src]["sha256"],
        }

    manifest = {
        "manifestVersion": 1,
        "sources": [FEDB_SOURCE],
        "items": items,
    }
    (ASSETS / "media_manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=1) + "\n", encoding="utf-8",
    )

    total = sum(b["bytes"] for b in built.values())
    covered = len(items)
    print(f"files: {len(built)}, total {total / 1024:.0f} KB, max {max(b['bytes'] for b in built.values()) / 1024:.0f} KB")
    print(f"coverage: {covered}/{len(catalog_ids)} ({100 * covered / len(catalog_ids):.1f}%)")
    print(f"no media: {[i for i in catalog_ids if i not in items]}")
    if over_budget:
        print(f"over {TARGET_BYTES // 1024} KB: {over_budget}")

    if args.contact_sheet:
        cell_w, cell_h, cols = 180, 140, 8
        ids = [i for i in catalog_ids if i in items]
        rows = (len(ids) + cols - 1) // cols
        sheet = Image.new("RGB", (cols * cell_w, rows * cell_h), "white")
        draw = ImageDraw.Draw(sheet)
        for n, cid in enumerate(ids):
            thumb = built[items[cid]["sourceId"]]["preview"].copy()
            thumb.thumbnail((cell_w - 4, cell_h - 22))
            x, y = (n % cols) * cell_w, (n // cols) * cell_h
            sheet.paste(thumb, (x + 2, y + 2))
            label = ("~ " if items[cid]["match"] == "similar" else "") + cid
            draw.text((x + 2, y + cell_h - 18), label[:30], fill="black")
        sheet.save(args.contact_sheet, quality=80)
        print(f"contact sheet: {args.contact_sheet}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

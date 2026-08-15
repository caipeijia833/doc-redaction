#!/usr/bin/env python3
"""Build the offline mainland-China administrative-division lexicon.

The source archive is intentionally downloaded outside this script. This builder
accepts only the hash-pinned 2.7.0 snapshot and emits a deterministic gzip TSV.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
from pathlib import Path


EXPECTED_SHA256 = {
    "provinces.json": "a7e6a230102fee054daa7f63d91ca8da4320c11a2ddd4e5f093999b579fd7d4f",
    "cities.json": "3f569aaa0bfbeba72f1597657511c64f54107baae71710fef7146f390a41af32",
    "areas.json": "fbe1575eecba4ffd4d50c3d2d6887bd873ceca6a203fb2d66698b5007826b6b6",
    "streets.json": "7f5e073a7e543de44a0cb7f05cd9e4bf8ecf7a887be0e36870c0dc1ed4ef066b",
}

PROVINCE_ALIASES = {
    "11": "北京", "12": "天津", "13": "河北", "14": "山西", "15": "内蒙古",
    "21": "辽宁", "22": "吉林", "23": "黑龙江", "31": "上海", "32": "江苏",
    "33": "浙江", "34": "安徽", "35": "福建", "36": "江西", "37": "山东",
    "41": "河南", "42": "湖北", "43": "湖南", "44": "广东", "45": "广西",
    "46": "海南", "50": "重庆", "51": "四川", "52": "贵州", "53": "云南",
    "54": "西藏", "61": "陕西", "62": "甘肃", "63": "青海", "64": "宁夏",
    "65": "新疆",
}

GENERIC_NAMES = {
    "市辖区", "县", "省直辖县级行政区划", "自治区直辖县级行政区划",
    "省直辖行政单位", "自治区直辖行政单位", "街道", "城区", "林场",
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_verified(root: Path, name: str) -> list[dict[str, str]]:
    path = root / name
    actual = sha256(path)
    if actual != EXPECTED_SHA256[name]:
        raise SystemExit(f"hash mismatch for {name}: expected {EXPECTED_SHA256[name]}, got {actual}")
    return json.loads(path.read_text(encoding="utf-8"))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    levels = [
        ("province", load_verified(args.source_root, "provinces.json")),
        ("prefecture", load_verified(args.source_root, "cities.json")),
        ("county", load_verified(args.source_root, "areas.json")),
        ("township", load_verified(args.source_root, "streets.json")),
    ]
    rows: set[tuple[str, str, str]] = set()
    for level, records in levels:
        for record in records:
            code = record["code"].strip()
            name = record["name"].strip()
            if name and name not in GENERIC_NAMES:
                rows.add((level, code, name))
    for code, alias in PROVINCE_ALIASES.items():
        rows.add(("province", code, alias))

    order = {"province": 0, "prefecture": 1, "county": 2, "township": 3}
    sorted_rows = sorted(rows, key=lambda row: (order[row[0]], row[1], row[2]))
    payload = [
        "# source=modood/Administrative-divisions-of-China tag=2.7.0 commit=6fb5380",
        "# baseline=2023-06-30 license=WTFPL runtime_network=false",
        "level\tcode\tname",
    ]
    payload.extend("\t".join(row) for row in sorted_rows)
    encoded = ("\n".join(payload) + "\n").encode("utf-8")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0, compresslevel=9) as zipped:
            zipped.write(encoded)
    print(json.dumps({
        "entries": len(sorted_rows),
        "uncompressedBytes": len(encoded),
        "outputBytes": args.output.stat().st_size,
        "outputSha256": sha256(args.output),
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()

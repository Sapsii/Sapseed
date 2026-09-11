#!/usr/bin/env python3
"""Fail before packaging when Git LFS left model pointer files in the checkout."""

from __future__ import annotations

import argparse
import hashlib
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "sapseed-edge/androidApp/src/main/assets"
MODEL_ASSETS = ("sapseed.onnx", "sapseed.tflite")
LFS_POINTER_PREFIX = b"version https://git-lfs.github.com/spec/"
MINIMUM_MODEL_BYTES = 1_000_000


def validate_model(name: str, data: bytes, source: str) -> None:
    if data.startswith(LFS_POINTER_PREFIX):
        raise SystemExit(f"{source} is a Git LFS pointer, not a model; checkout must enable lfs: true")
    if len(data) < MINIMUM_MODEL_BYTES:
        raise SystemExit(f"{source} is unexpectedly small ({len(data)} bytes)")
    if name.endswith(".tflite") and data[4:8] != b"TFL3":
        raise SystemExit(f"{source} is not a TensorFlow Lite flatbuffer (missing TFL3 identifier)")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, help="Also verify the model bytes packaged in this APK")
    args = parser.parse_args()

    source_models: dict[str, bytes] = {}
    for name in MODEL_ASSETS:
        path = ASSETS / name
        data = path.read_bytes()
        validate_model(name, data, str(path.relative_to(ROOT)))
        source_models[name] = data
        print(f"valid model: {name} ({len(data)} bytes, sha256={hashlib.sha256(data).hexdigest()})")

    labels = [line.strip() for line in (ASSETS / "sapseed.labels").read_text(encoding="utf-8").splitlines() if line.strip()]
    if len(labels) != 21:
        raise SystemExit(f"sapseed.labels must contain 21 classes, found {len(labels)}")

    if args.apk is None:
        return

    with zipfile.ZipFile(args.apk) as apk:
        for name, source_data in source_models.items():
            entry = f"assets/{name}"
            packaged = apk.read(entry)
            validate_model(name, packaged, f"{args.apk}!/{entry}")
            if hashlib.sha256(packaged).digest() != hashlib.sha256(source_data).digest():
                raise SystemExit(f"{entry} in {args.apk} differs from the validated source asset")
    print(f"valid packaged models: {args.apk}")


if __name__ == "__main__":
    main()

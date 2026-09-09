from __future__ import annotations

import argparse
import shutil
import tempfile
import zipfile
from pathlib import Path

from export import export_litert, export_onnx
from ultralytics import YOLO

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_ASSETS = REPOSITORY_ROOT / "sapseed-edge/androidApp/src/main/assets"
LEGACY_ASSETS = ("yolo11n.onnx", "yolo11n.tflite")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Export a trained checkpoint and deploy all Android inference assets."
    )
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--model", type=Path, help="Path to best.pt.")
    source.add_argument("--archive", type=Path, help="Kaggle _output_.zip containing best.pt.")
    parser.add_argument("--assets", type=Path, default=DEFAULT_ASSETS)
    parser.add_argument("--imgsz", type=int, default=640)
    return parser.parse_args()


def extract_checkpoint(archive: Path, destination: Path) -> Path:
    if not archive.is_file():
        raise FileNotFoundError(f"Kaggle output archive not found: {archive}")
    with zipfile.ZipFile(archive) as bundle:
        root = destination.resolve()
        for member in bundle.infolist():
            target = (destination / member.filename).resolve()
            if not target.is_relative_to(root):
                raise ValueError(f"Unsafe archive member: {member.filename}")
        bundle.extractall(destination)

    checkpoints = sorted(destination.rglob("best.pt"))
    if len(checkpoints) != 1:
        found = ", ".join(str(path.relative_to(destination)) for path in checkpoints) or "none"
        raise RuntimeError(f"Expected exactly one best.pt in {archive}; found: {found}")
    return checkpoints[0]


def checkpoint_labels(checkpoint: Path) -> list[str]:
    names = YOLO(str(checkpoint)).names
    if isinstance(names, dict):
        indexes = sorted(names)
        if indexes != list(range(len(indexes))):
            raise ValueError(f"Model class indexes are not contiguous: {indexes}")
        labels = [str(names[index]) for index in indexes]
    else:
        labels = [str(name) for name in names]
    if not labels:
        raise ValueError("Model contains no class labels")
    return labels


def deploy(checkpoint: Path, assets: Path, image_size: int) -> None:
    if not checkpoint.is_file():
        raise FileNotFoundError(f"Model not found: {checkpoint}")

    labels = checkpoint_labels(checkpoint)
    onnx_path = export_onnx(checkpoint, image_size, "fp32")
    litert_path = export_litert(onnx_path, image_size, "fp32")

    assets.mkdir(parents=True, exist_ok=True)
    outputs = {
        assets / "sapseed.onnx": onnx_path,
        assets / "sapseed.tflite": litert_path,
    }
    for destination, source in outputs.items():
        temporary = destination.with_suffix(destination.suffix + ".tmp")
        shutil.copy2(source, temporary)
        temporary.replace(destination)
    (assets / "sapseed.labels").write_text("\n".join(labels) + "\n", encoding="utf-8")

    for name in LEGACY_ASSETS:
        (assets / name).unlink(missing_ok=True)

    print(f"Deployed {len(labels)} classes to {assets}")
    for destination in (*outputs, assets / "sapseed.labels"):
        print(f"  {destination.relative_to(REPOSITORY_ROOT)}")


def main() -> None:
    args = parse_args()
    if args.archive is None:
        deploy(args.model.resolve(), args.assets.resolve(), args.imgsz)
        return

    with tempfile.TemporaryDirectory(prefix="sapseed-kaggle-") as temporary:
        checkpoint = extract_checkpoint(args.archive.resolve(), Path(temporary))
        deploy(checkpoint, args.assets.resolve(), args.imgsz)


if __name__ == "__main__":
    main()

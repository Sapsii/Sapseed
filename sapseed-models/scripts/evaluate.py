from __future__ import annotations

import argparse
from pathlib import Path

from ultralytics import YOLO


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Evaluate a trained road-damage detector.")
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--data", type=Path, default=Path("configs/smart-bus.yaml"))
    parser.add_argument("--split", choices=("val", "test"), default="test")
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--device", default=None)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if not args.model.is_file():
        raise FileNotFoundError(f"Model not found: {args.model}")
    if not args.data.is_file():
        raise FileNotFoundError(f"Dataset configuration not found: {args.data}")

    YOLO(str(args.model)).val(
        data=str(args.data.resolve()),
        split=args.split,
        imgsz=args.imgsz,
        device=args.device,
        project="runs/evaluate",
        name=args.model.stem,
    )


if __name__ == "__main__":
    main()

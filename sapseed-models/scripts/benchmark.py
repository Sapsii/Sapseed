from __future__ import annotations

import argparse
import csv
import json
import logging
import platform
import statistics
import sys
import time
from datetime import UTC, datetime
from pathlib import Path

import cv2
import matplotlib.pyplot as plt
import numpy as np
import psutil
import torch
import ultralytics
from ultralytics import YOLO

IMAGE_SUFFIXES = {".bmp", ".jpeg", ".jpg", ".png", ".tif", ".tiff", ".webp"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Benchmark YOLO inference on this PC.")
    parser.add_argument("--model", default="yolo11n.pt", help="Checkpoint name or path.")
    parser.add_argument(
        "--source",
        type=Path,
        help="Image or directory of images. Omit for a deterministic synthetic image.",
    )
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--iterations", type=int, default=100)
    parser.add_argument("--warmup", type=int, default=10)
    parser.add_argument("--device", default=None, help="For example: 0 or cpu. Auto-selected by default.")
    parser.add_argument(
        "--precision",
        choices=("fp32", "fp16"),
        default="fp32",
        help="FP16 requires a supported GPU.",
    )
    parser.add_argument("--max-images", type=int, default=32)
    parser.add_argument("--output", type=Path, help="Output directory; defaults under runs/benchmark.")
    return parser.parse_args()


def configure_logging(output: Path) -> logging.Logger:
    logger = logging.getLogger("sapseed.benchmark")
    logger.setLevel(logging.INFO)
    logger.handlers.clear()
    formatter = logging.Formatter("%(asctime)s | %(levelname)s | %(message)s")
    for handler in (logging.StreamHandler(), logging.FileHandler(output / "benchmark.log")):
        handler.setFormatter(formatter)
        logger.addHandler(handler)
    return logger


def load_images(source: Path | None, imgsz: int, max_images: int) -> list[np.ndarray]:
    if source is None:
        rng = np.random.default_rng(42)
        return [rng.integers(0, 256, size=(imgsz, imgsz, 3), dtype=np.uint8)]

    if not source.exists():
        raise FileNotFoundError(f"Benchmark source not found: {source}")
    paths = [source] if source.is_file() else sorted(
        path for path in source.rglob("*") if path.suffix.lower() in IMAGE_SUFFIXES
    )
    if not paths:
        raise ValueError(f"No supported images found under: {source}")

    images: list[np.ndarray] = []
    for path in paths[:max_images]:
        image = cv2.imread(str(path))
        if image is None:
            raise ValueError(f"OpenCV could not read: {path}")
        images.append(image)
    return images


def synchronize(device: str) -> None:
    if device != "cpu" and torch.cuda.is_available():
        torch.cuda.synchronize()


def percentile(values: list[float], value: float) -> float:
    return float(np.percentile(np.asarray(values), value))


def system_metadata(device: str, args: argparse.Namespace) -> dict[str, object]:
    gpu = None
    if torch.cuda.is_available():
        gpu = {
            "name": torch.cuda.get_device_name(0),
            "total_memory_mb": round(torch.cuda.get_device_properties(0).total_memory / 2**20, 2),
            "cuda_version": torch.version.cuda,
        }
    return {
        "timestamp_utc": datetime.now(UTC).isoformat(),
        "platform": platform.platform(),
        "python": sys.version,
        "cpu": platform.processor(),
        "logical_cpu_count": psutil.cpu_count(logical=True),
        "ram_mb": round(psutil.virtual_memory().total / 2**20, 2),
        "torch": torch.__version__,
        "ultralytics": ultralytics.__version__,
        "device": device,
        "gpu": gpu,
        "arguments": vars(args) | {"source": str(args.source) if args.source else None},
    }


def save_graph(samples: list[dict[str, float | int]], output: Path) -> None:
    iterations = [int(sample["iteration"]) for sample in samples]
    latencies = [float(sample["wall_ms"]) for sample in samples]
    figure, axes = plt.subplots(1, 2, figsize=(13, 4.5))

    axes[0].plot(iterations, latencies, linewidth=1.2)
    axes[0].axhline(statistics.mean(latencies), color="orange", linestyle="--", label="mean")
    axes[0].set(title="YOLO inference latency", xlabel="Iteration", ylabel="Wall latency (ms)")
    axes[0].grid(alpha=0.25)
    axes[0].legend()

    axes[1].hist(latencies, bins=min(20, max(5, len(latencies) // 5)), edgecolor="black")
    axes[1].set(title="Latency distribution", xlabel="Wall latency (ms)", ylabel="Samples")
    axes[1].grid(axis="y", alpha=0.25)

    figure.tight_layout()
    figure.savefig(output / "latency.png", dpi=160)
    plt.close(figure)


def main() -> None:
    args = parse_args()
    if args.iterations <= 0 or args.warmup < 0 or args.max_images <= 0:
        raise ValueError("Iterations/max-images must be positive and warmup cannot be negative.")

    stamp = datetime.now(UTC).strftime("%Y%m%d-%H%M%S")
    output = args.output or Path("runs/benchmark") / f"{Path(args.model).stem}-{stamp}"
    output.mkdir(parents=True, exist_ok=False)
    logger = configure_logging(output)

    device = args.device or ("0" if torch.cuda.is_available() else "cpu")
    images = load_images(args.source, args.imgsz, args.max_images)
    logger.info("Loading model=%s device=%s images=%d", args.model, device, len(images))
    model = YOLO(args.model)

    predict_options: dict[str, object] = {
        "imgsz": args.imgsz,
        "device": device,
        "verbose": False,
    }
    if args.precision == "fp16":
        predict_options["quantize"] = 16

    for index in range(args.warmup):
        model.predict(images[index % len(images)], **predict_options)
    synchronize(device)
    if torch.cuda.is_available() and device != "cpu":
        torch.cuda.reset_peak_memory_stats()

    process = psutil.Process()
    samples: list[dict[str, float | int]] = []
    logger.info("Warmup complete; collecting %d iterations", args.iterations)
    for index in range(args.iterations):
        image = images[index % len(images)]
        synchronize(device)
        started = time.perf_counter_ns()
        results = model.predict(image, **predict_options)
        synchronize(device)
        wall_ms = (time.perf_counter_ns() - started) / 1_000_000
        speed = results[0].speed
        sample: dict[str, float | int] = {
            "iteration": index + 1,
            "wall_ms": round(wall_ms, 4),
            "preprocess_ms": round(float(speed.get("preprocess", 0.0)), 4),
            "inference_ms": round(float(speed.get("inference", 0.0)), 4),
            "postprocess_ms": round(float(speed.get("postprocess", 0.0)), 4),
            "detections": len(results[0].boxes),
            "rss_mb": round(process.memory_info().rss / 2**20, 2),
        }
        samples.append(sample)
        logger.info(
            "iteration=%d wall_ms=%.2f inference_ms=%.2f detections=%d",
            index + 1,
            wall_ms,
            sample["inference_ms"],
            sample["detections"],
        )

    latencies = [float(sample["wall_ms"]) for sample in samples]
    summary: dict[str, object] = system_metadata(device, args)
    summary["result"] = {
        "samples": len(samples),
        "mean_ms": round(statistics.mean(latencies), 3),
        "median_ms": round(statistics.median(latencies), 3),
        "p95_ms": round(percentile(latencies, 95), 3),
        "p99_ms": round(percentile(latencies, 99), 3),
        "min_ms": round(min(latencies), 3),
        "max_ms": round(max(latencies), 3),
        "mean_fps": round(1000 / statistics.mean(latencies), 2),
        "peak_rss_mb": max(float(sample["rss_mb"]) for sample in samples),
        "peak_gpu_memory_mb": (
            round(torch.cuda.max_memory_allocated() / 2**20, 2)
            if torch.cuda.is_available() and device != "cpu"
            else None
        ),
    }

    with (output / "samples.csv").open("w", newline="", encoding="utf-8") as file:
        writer = csv.DictWriter(file, fieldnames=samples[0].keys())
        writer.writeheader()
        writer.writerows(samples)
    (output / "summary.json").write_text(json.dumps(summary, indent=2, default=str), encoding="utf-8")
    save_graph(samples, output)

    result = summary["result"]
    logger.info("Complete: %s", json.dumps(result, indent=2))
    logger.info("Artifacts: %s", output.resolve())


if __name__ == "__main__":
    main()

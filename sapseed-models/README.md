# Sapseed Models

Local tooling for the model consumed by `sapseed-edge`.

The **dataset preparation and training pipeline is on Kaggle**:
[`tsaty01/sapseed-model`](https://www.kaggle.com/code/tsaty01/sapseed-model)
(Apache-2.0). It merges six public datasets into a single 21-class "smart bus"
detector and fine-tunes YOLO11n. The class taxonomy is in
`configs/smart-bus.yaml`. This folder only holds the tooling that consumes the
resulting model: benchmark, evaluate, and export.

## Environment

```shell
uv sync --extra dev --extra export
```

## Benchmark (PC)

```shell
uv run python scripts/benchmark.py --model yolo11n.pt --device 0
```

Use `--source <image-or-dir> --iterations N --warmup M --precision fp16` for a
realistic road-image run. Writes `runs/benchmark/<model>-<timestamp>/`
(`benchmark.log`, `samples.csv`, `summary.json`, `latency.png`). This compares
models/formats; it does not replace measurement on the phone.

## Evaluate

```shell
uv run python scripts/evaluate.py --model path/to/best.pt --split test
```

## Export

```shell
uv run python scripts/export.py --model path/to/best.pt --format onnx
uv run python scripts/export.py --model path/to/best.pt --format litert --precision fp32
```

LiteRT export converts canonical ONNX under `artifacts/litert/` and replaces
converter-generated dynamic shape signatures with fixed dimensions (required by
the Android GPU delegate). Avoid FP16/INT8 model I/O until the Android detector
supports that tensor type and a representative calibration set has been checked.

## Licensing

Datasets and checkpoints are not committed; only scripts, configs, and docs.
Ship exported models via Git LFS or release artifacts. Respect each source
dataset's licence (e.g. IDD restricts redistribution).

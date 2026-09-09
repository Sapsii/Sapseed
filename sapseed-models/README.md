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

## Deploy the Kaggle model to Android

Download `_output_.zip` from the pinned Kaggle notebook output:

<https://www.kaggle.com/code/tsaty01/sapseed-model/output?scriptVersionId=348090044&select=_output_.zip>

Then run the deployment script from this directory:

```shell
uv run python scripts/deploy_android.py --archive path/to/_output_.zip
```

The script requires exactly one `best.pt`, reads its class names, calls the existing ONNX and
LiteRT exporters, fixes NNAPI and GPU compatibility, and atomically replaces the Android assets
with `sapseed.onnx`, `sapseed.tflite`, and `sapseed.labels`. All four Android runtimes therefore
use exports from the same checkpoint. A local checkpoint can be deployed with `--model
path/to/best.pt`.

For a standalone export without deploying to Android:

```shell
uv run python scripts/export.py --model path/to/best.pt --format onnx
uv run python scripts/export.py --model path/to/best.pt --format litert --precision fp32
```

LiteRT export converts canonical ONNX under `artifacts/litert/` and replaces
converter-generated dynamic shape signatures with fixed dimensions (required by
the Android GPU delegate). Avoid FP16/INT8 model I/O until the Android detector
supports that tensor type and a representative calibration set has been checked.

## Licensing

Datasets and checkpoints are not committed. The deployment script updates the exported Android
assets; larger distribution artifacts should use Git LFS or release assets. Respect each source
dataset's licence (e.g. IDD restricts redistribution).

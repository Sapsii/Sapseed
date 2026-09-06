# Sapseed

Edge sensing and model-development workspace for the Sapseed urban-road intelligence system. Backend and frontend are separate projects and are not part of this repository.

```text
sapseed-edge/       Android/KMP edge application and runtime adapters
sapseed-models/     Python model training, evaluation, and export tooling
```

The folders have separate build environments because model development runs on a training machine while the exported model is consumed by the edge application.

## Edge

```shell
cd sapseed-edge
./gradlew :edge:testAndroidHostTest :androidApp:assembleDebug
```

See [`sapseed-edge/README.md`](sapseed-edge/README.md).

## Models

Dataset preparation and model training run on [Kaggle](https://www.kaggle.com/code/tsaty01/sapseed-model). This repo only holds the tooling that consumes the trained model:

```shell
cd sapseed-models
uv sync --extra dev --extra export
uv run python scripts/export.py --model path/to/best.pt --format litert --precision fp32
```

See [`sapseed-models/README.md`](sapseed-models/README.md).

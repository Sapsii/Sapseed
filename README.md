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
uv run python scripts/deploy_android.py --archive path/to/_output_.zip
```

See [`sapseed-models/README.md`](sapseed-models/README.md).

## Releases

Semantic-release publishes prereleases from `dev` and stable releases from `main`. Each release
includes a signed `arm64-v8a` release APK. GitHub Actions requires these repository secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The keystore secret is the base64 encoding of the release keystore file. Keep the original
keystore and passwords backed up because future APK updates must use the same signing key.

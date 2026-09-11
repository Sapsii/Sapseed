#!/usr/bin/env bash
set -euo pipefail

version="${1:?semantic-release version is required}"
required_variables=(
  ANDROID_KEYSTORE_PATH
  ANDROID_KEYSTORE_PASSWORD
  ANDROID_KEY_ALIAS
  ANDROID_KEY_PASSWORD
  GITHUB_RUN_NUMBER
  SAPSEED_API_URL
  SAPSEED_DEVICE_AUTHORIZATION
)
for variable in "${required_variables[@]}"; do
  if [[ -z "${!variable:-}" ]]; then
    echo "$variable is required to build a signed Android release" >&2
    exit 1
  fi
done
if [[ ! -f "$ANDROID_KEYSTORE_PATH" ]]; then
  echo "Android release keystore does not exist: $ANDROID_KEYSTORE_PATH" >&2
  exit 1
fi

export SAPSEED_VERSION_NAME="$version"
export SAPSEED_VERSION_CODE="$GITHUB_RUN_NUMBER"

python .github/scripts/validate-model-assets.py

pushd sapseed-edge >/dev/null
./gradlew :edge:testAndroidHostTest :androidApp:assembleRelease --console=plain
popd >/dev/null

source_apk="sapseed-edge/androidApp/build/outputs/apk/release/androidApp-release.apk"
if [[ ! -f "$source_apk" ]]; then
  echo "Signed release APK was not produced: $source_apk" >&2
  exit 1
fi
python .github/scripts/validate-model-assets.py --apk "$source_apk"

unexpected_abis=$(unzip -Z1 "$source_apk" | grep '^lib/' | grep -v '^lib/arm64-v8a/' || true)
if [[ -n "$unexpected_abis" ]]; then
  echo "Release APK contains non-arm64 native libraries:" >&2
  echo "$unexpected_abis" >&2
  exit 1
fi

apksigner=$(find "$ANDROID_HOME/build-tools" -type f -name apksigner | sort -V | tail -n 1)
if [[ -z "$apksigner" ]]; then
  echo "apksigner was not found under $ANDROID_HOME/build-tools" >&2
  exit 1
fi
"$apksigner" verify --verbose "$source_apk"

mkdir -p dist
release_apk="dist/sapseed-${version}-arm64-v8a.apk"
cp "$source_apk" "$release_apk"
echo "Prepared $release_apk"

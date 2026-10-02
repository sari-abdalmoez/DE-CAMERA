#!/usr/bin/env bash
set -euo pipefail
LOCK="${1:-models/models.lock}"
set -a
source <(grep -E '^(NCNN_MODEL_|NCNN_(PARAM|BIN)_FILE|NCNN_(PARAM|BIN)_BLOB_SHA1)=' "$LOCK")
set +a

mkdir -p app/src/main/assets/models
BASE="https://raw.githubusercontent.com/genie-design/ESRGAN-ncnn-models/$NCNN_MODEL_COMMIT/$NCNN_MODEL_NAME"

curl -fL --retry 5 --retry-delay 2 --retry-all-errors \
  "$BASE/$NCNN_PARAM_FILE" -o "app/src/main/assets/models/$NCNN_PARAM_FILE"
curl -fL --retry 5 --retry-delay 2 --retry-all-errors \
  "$BASE/$NCNN_BIN_FILE" -o "app/src/main/assets/models/$NCNN_BIN_FILE"

PARAM_SHA=$(git hash-object "app/src/main/assets/models/$NCNN_PARAM_FILE")
BIN_SHA=$(git hash-object "app/src/main/assets/models/$NCNN_BIN_FILE")
test "$PARAM_SHA" = "$NCNN_PARAM_BLOB_SHA1"
test "$BIN_SHA" = "$NCNN_BIN_BLOB_SHA1"

echo "Verified NCNN Real-ESRGAN model commit: $NCNN_MODEL_COMMIT"

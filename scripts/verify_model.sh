#!/usr/bin/env bash
set -euo pipefail
LOCK="${1:-models/models.lock}"
set -a
source <(grep -E '^(ESRGAN|NAFNET)_(FILE|URL|SHA256)=' "$LOCK")
set +a
mkdir -p app/src/main/assets/models
verify() {
  local file="$1" url="$2" sha="$3"
  curl -fL --retry 3 "$url" -o "app/src/main/assets/models/$file"
  echo "$sha  app/src/main/assets/models/$file" | sha256sum -c -
}
verify "$ESRGAN_FILE" "$ESRGAN_URL" "$ESRGAN_SHA256"
verify "$NAFNET_FILE" "$NAFNET_URL" "$NAFNET_SHA256"

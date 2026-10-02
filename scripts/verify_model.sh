#!/usr/bin/env bash
set -euo pipefail
LOCK="${1:-models/models.lock}"
set -a
source <(grep -E '^(ESRGAN|NAFNET)_(FILE|URL|SHA256)=' "$LOCK")
set +a
mkdir -p app/src/main/assets/models

verify_sha256() {
  local file="$1" url="$2" sha="$3"
  echo "Downloading and verifying $file"
  curl -fL --retry 5 --retry-delay 2 --retry-all-errors "$url" -o "app/src/main/assets/models/$file"
  echo "$sha  app/src/main/assets/models/$file" | sha256sum -c -
}

verify_sha256 "$ESRGAN_FILE" "$ESRGAN_URL" "$ESRGAN_SHA256"
verify_sha256 "$NAFNET_FILE" "$NAFNET_URL" "$NAFNET_SHA256"

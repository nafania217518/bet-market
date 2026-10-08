#!/usr/bin/env bash
# Static checks for kubernetes_manifest.yaml (CI and local, Docker only):
# 1. kubeconform validates every object against the Kubernetes schemas.
# 2. The scripts embedded in ConfigMaps are byte-identical to the repo files they copy.
set -euo pipefail
cd "$(dirname "$0")/.."

docker run --rm -v "$PWD:/w" ghcr.io/yannh/kubeconform:v0.8.0 -strict -summary /w/kubernetes_manifest.yaml

same() { # same <configmap> <key> <repo file>
  # yq prints JSON so the string comes back exactly (plain yq output adds a trailing newline).
  docker run --rm -v "$PWD:/w" mikefarah/yq:4.53.6 -o json \
      "select(.kind == \"ConfigMap\" and .metadata.name == \"$1\") | .data[\"$2\"]" /w/kubernetes_manifest.yaml \
    | python3 -c 'import json, sys; sys.stdout.write(json.load(sys.stdin))' \
    | cmp -s - "$3" || { echo "FAIL: ConfigMap $1/$2 differs from $3" >&2; exit 1; }
}
same postgres-init 01-databases.sql infra/postgres/init/01-databases.sql
same kafka-create-topics create-topics.sh infra/kafka/create-topics.sh
echo "OK: manifest is valid and its embedded scripts match the repo"

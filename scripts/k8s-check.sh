#!/usr/bin/env bash
# Static checks for kubernetes_manifest.yaml (CI and local, Docker only):
# 1. kubeconform validates every object against the Kubernetes schemas.
# 2. The scripts embedded in ConfigMaps are byte-identical to the repo files they copy.
# 3. HorizontalPodAutoscalers follow the rules below.
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
same postgres-init 02-debezium.sql infra/postgres/init/02-debezium.sql
same debezium-connector register.sh infra/debezium/register.sh
same debezium-connector outbox-connector.json infra/debezium/outbox-connector.json
# 3. HPA rules (each message is the requirement).
docker run --rm -v "$PWD:/w" mikefarah/yq:4.53.6 ea -o json -I0 '[.]' /w/kubernetes_manifest.yaml | python3 -c '
import json, sys
docs = json.load(sys.stdin)
dep = {d["metadata"]["name"]: d["spec"] for d in docs if d["kind"] == "Deployment"}
hpa = {h["spec"]["scaleTargetRef"]["name"]: h["spec"] for h in docs if h["kind"] == "HorizontalPodAutoscaler"}
def need(ok, msg):
    if not ok: sys.exit("FAIL: " + msg)
need(all(n in dep and all(c.get("resources", {}).get("requests", {}).get("cpu") for c in dep[n]["template"]["spec"]["containers"]) for n in hpa),
     "every HPA targets a Deployment in the manifest that sets a CPU request")
need(not any("replicas" in dep[n] for n in hpa if n in dep), "HPA-managed Deployments do not set spec.replicas")
need(not {"user-service", "notification-service", "load-generator"} & hpa.keys(),
     "user-service, notification-service and load-generator have no HPA")
need({"api-gateway", "wallet-service", "market-service", "bet-service", "query-service"} <= hpa.keys(),
     "api-gateway, wallet-service, market-service, bet-service and query-service each have an HPA")
need(hpa.get("market-service", {}).get("minReplicas", 1) >= 2, "market-service HPA keeps minReplicas >= 2")
def env(c): return {e["name"]: e.get("value") for e in c.get("env", [])}
needed = 15 + 5  # Debezium 5 connectors x 3, plus exporters/psql/superuser reserve
for n, s in dep.items():
    for c in s["template"]["spec"]["containers"]:
        if "DB_URL" in env(c):
            pods = hpa[n]["maxReplicas"] if n in hpa else s.get("replicas", 1)
            needed += (pods + 1) * int(env(c).get("SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE") or 10)
pg = next(d for d in docs if d["kind"] == "StatefulSet" and d["metadata"]["name"] == "postgres")
args = pg["spec"]["template"]["spec"]["containers"][0].get("args", [])
maxc = next((int(a.split("=")[1]) for a in args if a.startswith("max_connections=")), 100)
need(maxc >= needed, f"Postgres max_connections covers every DB pod at max replicas plus rollout surge, plus Debezium (needed {needed}, max_connections {maxc})")
'
echo "OK: manifest is valid and its embedded scripts match the repo"

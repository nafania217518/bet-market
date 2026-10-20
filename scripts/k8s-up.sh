#!/usr/bin/env bash
# Deploys kubernetes_manifest.yaml to a local k3d cluster and runs the smoke test through the ingress.
# Needs: docker, k3d, kubectl, Node >= 22 (smoke step 21).
# 1. create the cluster (Traefik ingress on host port $PORT)  2. build and import the images
# 3. apply the manifest  4. wait until every pod is ready  5. run scripts/smoke.sh against the ingress
set -euo pipefail
cd "$(dirname "$0")/.."

CLUSTER="${CLUSTER:-bet-market}"
PORT="${PORT:-8000}"
NS=bet-market
IMAGES=(user-service wallet-service market-service bet-service settlement-service projection-service
        query-service notification-service api-gateway frontend load-generator)

echo "1. cluster $CLUSTER (ingress on :$PORT)"
k3d cluster list "$CLUSTER" > /dev/null 2>&1 \
  || k3d cluster create "$CLUSTER" --agents 1 -p "$PORT:80@loadbalancer" --wait
kubectl config use-context "k3d-$CLUSTER" > /dev/null

echo "2. build and import images"
docker compose build "${IMAGES[@]}" > /dev/null
k3d image import -c "$CLUSTER" "${IMAGES[@]/#/bet-market-}" > /dev/null

echo "3. apply the manifest"
kubectl apply -f kubernetes_manifest.yaml > /dev/null
# `apply` does not restart pods when only the image content changed (same :latest tag).
for d in "${IMAGES[@]}"; do kubectl -n "$NS" rollout restart "deployment/$d" > /dev/null; done

echo "4. wait for kafka-init, Debezium Connect and every pod"
for s in postgres kafka redis mongodb; do kubectl -n "$NS" rollout status "statefulset/$s" --timeout=300s > /dev/null; done
kubectl -n "$NS" wait --for=condition=complete job/kafka-init --timeout=600s > /dev/null
kubectl -n "$NS" rollout status deployment/connect --timeout=600s > /dev/null
kubectl -n "$NS" wait --for=condition=complete job/connect-init --timeout=600s > /dev/null
for d in "${IMAGES[@]}"; do kubectl -n "$NS" rollout status "deployment/$d" --timeout=600s > /dev/null; done
kubectl -n "$NS" get pods
for h in $(kubectl -n "$NS" get hpa -o name); do # wait until metrics-server feeds every HPA
  for _ in $(seq 36); do
    [ -n "$(kubectl -n "$NS" get "$h" -o jsonpath='{.status.currentMetrics[0].resource.current.averageUtilization}')" ] && break
    sleep 5
  done
  [ -n "$(kubectl -n "$NS" get "$h" -o jsonpath='{.status.currentMetrics[0].resource.current.averageUtilization}')" ] \
    || { echo "FAIL: $h reports no CPU value after 180 s" >&2; exit 1; }
done
kubectl -n "$NS" get hpa

echo "5. smoke test through the ingress"
API="http://localhost:$PORT" WEB="http://localhost:$PORT" K8S_NAMESPACE="$NS" scripts/smoke.sh

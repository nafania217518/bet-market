# Kubernetes (M9) caveats

Checked 2026-10-09.

## ingress-nginx is retired: use the cluster's own controller
- The Kubernetes project retired the ingress-nginx controller in March 2026. It gets no more fixes
  or security patches. Gateway API is the suggested successor.
- The manifest keeps a plain `Ingress` with `ingressClassName: traefik`. k3s, and so k3d, ships Traefik
  as its Ingress controller, so the local cluster needs no extra install.
- On another cluster, change `ingressClassName` to the controller that cluster runs.

Sources:
- https://kubernetes.io/blog/2025/11/11/ingress-nginx-retirement/
- https://blog.nginx.org/blog/the-ingress-nginx-alternative-open-source-nginx-ingress-controller-for-the-long-term
- https://www.eucloudcost.com/blog/ingress-nginx-retirement-2026
- https://github.com/k3d-io/k3d/blob/main/docs/usage/exposing_services.md (Traefik listens on port 80 in k3d)

## Kafka pods need `enableServiceLinks: false`
- Kubernetes injects variables such as `KAFKA_PORT` and `KAFKA_SERVICE_HOST` for a Service named `kafka`.
- The `apache/kafka` image turns every `KAFKA_*` variable into a broker setting. The injected
  variables become bogus settings.
- The `kafka` StatefulSet and the `kafka-init` Job set `enableServiceLinks: false`. Services use DNS names.

Sources:
- https://kubernetes.io/docs/tutorials/services/connect-applications-service/ (disable to avoid clashes)
- https://hub.docker.com/r/apache/kafka/ (every `KAFKA_` variable is broker config)
- https://stackoverflow.com/questions/75364184 (injected variables broke a Spring Boot app)
- https://oneuptime.com/blog/post/2026-02-09-enable-service-links-environment-variables/view

## Local images in k3d
- `docker compose build` tags images `bet-market-<service>:latest`. `k3d image import` copies them
  into the cluster. The manifest sets `imagePullPolicy: IfNotPresent`, because `:latest` defaults
  to `Always` and the pull would fail.
- `kubectl apply` does not restart pods when only the image content changes. `scripts/k8s-up.sh`
  runs `kubectl rollout restart` for each Deployment.

## Startup order
- Kubernetes has no `depends_on`. Services start before Postgres and Kafka are ready, fail, and restart.
  The first deploy shows a few restarts per service; this is expected.
- Spring Boot turns on `/actuator/health/liveness` and `/readiness` by itself on Kubernetes. The
  probes use them, and a `startupProbe` gives the JVM up to 3 minutes.

## HorizontalPodAutoscaler
Checked 2026-10-10.
- An HPA scales on CPU only when metrics-server serves the `metrics.k8s.io` API. k3s, and so k3d,
  ships metrics-server as a packaged component. Other clusters may need to install it.
- CPU utilization is a percentage of the container's CPU request. A container without a CPU request
  gives the HPA no value, and the HPA does not scale on CPU.
- Remove `spec.replicas` from a Deployment that an HPA manages. Otherwise every `kubectl apply`
  resets the replica count to the manifest value, and the HPA must scale it back.
- Removing `spec.replicas` from a live Deployment drops it to 1 replica once, until the HPA reacts.
- In the first 5 minutes of a pod (`--horizontal-pod-autoscaler-cpu-initialization-period`), the HPA
  sets aside only CPU samples taken before the pod was ready. JIT warm-up and the first requests after
  readiness still count. On k3d, api-gateway scaled to 2 during the first smoke run. The default
  5-minute scale-down window removes the extra pod later.
- Every extra replica opens its own Hikari pool (10 connections). At HPA maximum plus 1 rollout-surge pod
  per Deployment, plus Debezium, the stack needs about 190 Postgres connections. The default
  `max_connections=100` failed on k3d with "remaining connection slots are reserved". Postgres runs
  with `max_connections=250`, and `k8s-check.sh` recomputes the budget from the manifest.
- `scripts/k8s-check.sh` checks the rules above. `scripts/k8s-up.sh` waits until every HPA reads a CPU value.

Sources:
- https://kubernetes.io/docs/concepts/workloads/autoscaling/horizontal-pod-autoscale/ (requests, metrics API, `spec.replicas`, initialization period)
- https://docs.k3s.io/installation/packaged-components (metrics-server is a packaged component)
- https://docs.k3s.io/cli/server (`--disable metrics-server`)
- https://oneuptime.com/blog/post/2026-03-20-k3s-disable-metrics-server/view (k3s includes metrics-server by default)
- https://argo-cd.readthedocs.io/en/stable/user-guide/best_practices/ (do not track `replicas` when an HPA manages them)
- https://github.com/kubernetes/kubernetes/issues/66385 (`kubectl apply` overrides the HPA count)
- https://github.com/max0ne/helm-omit-replicas-for-hpa (field ownership between apply and the HPA)
- https://github.com/kubernetes-sigs/metrics-server (Metrics API feeds the HPA)

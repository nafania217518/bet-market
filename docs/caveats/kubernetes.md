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

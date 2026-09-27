# api-gateway-service

`cloud.cholewa:api-gateway-service` — Spring Cloud Gateway at the edge of the cluster: the k8s
ingress forwards all of `/home` here, and static routes fan out to the internal services over
k8s DNS. It is the **only** way into the cluster from outside, and where every external request's
trace starts. Reactive (WebFlux), Java 21, Spring Boot 4.1.0, Maven. Port **6200** (Actuator
**8200**) locally and in the cluster alike. Docker image `magikabdul/api-gateway-service`; the pom
keeps `0.0.1-SNAPSHOT`, the released version comes from the git tag.

Org-wide conventions and working rules (PR flow, branch naming `feature/HAS-<n>`, "user writes
service code, Claude reviews", both reviews before merge, own libraries on their latest release)
live in the workspace `organization.md` — this file only covers what is specific to this repo.
When opened as part of the workspace, those rules apply here too.

## Routes — read before adding or changing one

- **Static, in `RoutesConfig`**, one route per target service; host and port from
  `internal.service.*` (k8s DNS names on 6200 in the cluster, `localhost` with each service's own
  port in the `local` profile). No discovery — Eureka is gone.
- **Predicates are written without `/home`.** `PathRoutePredicateFactory` prepends
  `spring.webflux.base-path` to every pattern and matches the full raw path, so nothing is
  stripped at runtime and the target receives the path unchanged. `RouteToRequestUrlFilter`
  merges **only scheme, host and port** onto the incoming URI — the path part of a route's
  `uri(...)` is discarded, so a target mounted under a different path needs `rewritePath`, never
  a longer URI string.
- **Several paths of one service share its route** (`database`: `/device/configuration/**`,
  `/household`, `/household/**`) — `RoutesConfigTest` asserts exactly one route per service by id.
  Add a path to the existing route rather than a second route to the same service.
- **An unrouted path answers "No static resource …" (404)** — that is the gateway, not the target
  service: WebFlux found no route and fell through to static resources. The first thing to check
  when a new endpoint "does not exist" from outside the cluster.
- `ai` overrides the global `response-timeout` (30 s) with 120 s via route metadata — an OpenAI
  answer is slow; do not drag the global timeout up for everyone.
- `notification-service` is deliberately **not** routed: its Discord endpoint has no external
  consumer. Nothing behind the gateway is authenticated — every routed endpoint, writes and the
  household registry (names, phones, MACs) included, is open to whoever reaches the ingress.
- `RoutesConfigTest` checks both the set of routes and, per service, the external paths the
  ingress forwards (prefix included) plus a near-miss that must not match — extend it with every
  route change.

## Spring Cloud on Boot 4.1 — accepted risk

No Spring Cloud release train targets Boot 4.1, so the BOM is not imported and
`spring-cloud-starter-gateway-server-webflux` is pinned on its own. It works because Boot 4.1.0
and 4.0.7 share Spring Framework 7.0.x, but it sits outside Spring's compatibility matrix —
**re-test the gateway (context up, a route proxies, an unmatched path 404s) after any bump** of
Boot or the gateway starter, and drop the pin the day a Boot 4.1 train ships.

## Errors

`UpstreamUnavailableProcessor` maps every `IOException` from a target (unknown host, refused
connection, premature close) to **502 "Upstream service unavailable"** with no details and logs
only the exception type: the gateway's responses leave the cluster and its logs are stored, so the
internal host, pod IP and port a connection error names must not appear in either.

## Tracing and logging

The trace starts here (Micrometer Tracing with Brave, no exporter — `traceId` lives in the JSON
logs). Logbook's own lines never carry `traceId`, so for plainly proxied traffic the gateway
contributes the id but no log line of its own.

## Build & CI/CD

`mvn verify` (JDK 21). `CI.yml`, `sonar.yml`, `release.yml` (GitHub release → Docker image). The
manifest is `deployment-tools/workshop/api-gateway-service.yaml`. Release flow: the `release`
skill.

# api-gateway-service

`cloud.cholewa:api-gateway-service` — Spring Cloud Gateway at the edge of the cluster: the k8s
ingress forwards all of `/home` here, and static routes fan out to the internal services over
k8s DNS. It is the **only** way into the cluster from outside, and where every external request's
trace starts. Reactive (WebFlux), Java 21, Spring Boot 4.1.1, Maven. Port **6200** (Actuator
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
- **`presence` is an allowlist, unlike the other routes:** exactly
  `GET /presence/residents/presence`, `GET /presence/residents/{name}/report`,
  `GET /presence/residents/{name}/report/daily` and `GET /presence/house/report`.
  `presence-service` also serves `GET /home/presence/clients`, which lists the MAC address of
  every device on the home network and must stay unreachable from outside. A `/**` tail is not
  enough for that: `PathPattern` matches the raw path, so `residents/../clients` would match and
  be forwarded unchanged (harmless only while no hop normalises it), and every endpoint the
  service adds under `/residents` or `/house` would be published silently. `RoutesConfigTest` asserts the
  paths and methods that must match nothing. A new endpoint of that service gets its own entry —
  check first what it exposes.
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
`spring-cloud-starter-gateway-server-webflux` is pinned on its own (**5.0.3**, from the 2025.1.3
train, itself built against Boot 4.0.8). It works because Boot 4.1.1 and 4.0.8 share Spring
Framework 7.0.x, but it sits outside Spring's compatibility matrix —
**re-test the gateway (context up, a route proxies, an unmatched path 404s) after any bump** of
Boot or the gateway starter, and drop the pin the day a Boot 4.1 train ships.

The quickest way to re-test without a cluster: start the jar with `home,local`, put any HTTP stub on a target's local port and call the route
through `localhost:6200` — done that way on 2026-10-04 for Boot 4.1.1 / starter 5.0.3 (HAS-152),
together with logbook 4.2.0, which declares apiguardian 1.1.2 itself, so the pin in
`dependencyManagement` went away. Repeated on 2026-10-11 for HAS-212 (same versions, the pool
limits and the retry added): a stub that can reset a connection on request shows the two answers
that matter there — a `GET` on a reset connection is 200, a `POST` 502 with a body.

## Connections to the targets — the pool (HAS-212)

- **An idle connection must leave the pool long before 24 h.** The node drops a TCP connection
  from its connection-tracking table after 24 h of silence (`nf_conntrack_tcp_timeout_established`
  = 86400, what kube-proxy sets), and with it the address translation of the Service. When the
  target pod is replaced after that, its close cannot be translated back and never reaches the
  gateway; the next request on that connection gets a reset. That was the bare 500 of
  2026-10-08 (`water`, a connection idle for 32 h when the pod was replaced, used 40 h later) and
  of 2026-10-06 (`boiler`, 28 h). A connection idle for 36 minutes at a redeploy was closed
  properly — a close that arrives is handled by the pool on its own.
- The limits are `httpclient.pool.*` in `application.yaml`: `max-idle-time` 2 min, `max-life-time`
  30 min, `eviction-interval` 30 s. **Each defaults to "no limit" when left out**, without a word,
  so `ApiGatewayServiceApplicationTest` pins the three values and `IdleConnectionEvictionTest`
  proves they reach the HTTP client of the hand-pinned starter. Raising the idle time is harmless
  up to hours; it must never get near 24 h.
- **A `GET` whose connection broke is repeated once** (`RoutesConfig.repeatFailedRead`, the
  `Retry` filter on every route — a new route gets it too). `GET` only, one repetition, on
  `IOException` only, `series` emptied on purpose: the filter's defaults would repeat three times
  and on every 5xx. Never widen it to a write — `POST /home/heating` switches the house. Reactor
  Netty repeats on its own only while nothing of the request was sent (its WARN "the request
  cannot be retried as the headers/body were sent" is the other case, and stays in the log also
  when the retry then succeeds).
- **Every `GET` behind the gateway therefore has to stay a read.** A service that puts a side
  effect behind a `GET` gets it twice now and then.

## Errors

`UpstreamUnavailableProcessor` maps every `IOException` from a target (unknown host, refused
connection, premature close, reset) to **502 "Upstream service unavailable"** with no details and
logs only the exception type: the gateway's responses leave the cluster and its logs are stored, so
the internal host, pod IP and port a connection error names must not appear in either.

**An `IOException` alone does not get there** (HAS-212). `AbstractErrorWebExceptionHandler` — the
parent of the `cholewa-commons` handler — refuses to render whatever
`DisconnectedClientHelper.isClientDisconnectedException` takes for a caller that has gone away,
and `HttpWebHandlerAdapter` then answers a bare 500. That helper goes by the innermost message
(`connection reset by peer`, `broken pipe`) and by class names anywhere in the cause chain
(`AbortedException`, `EOFException`, …) — exactly what a target dropping its connection produces.
`UpstreamFailureFilter` (a global filter just outside `NettyWriteResponseFilter`) turns a broken
connection seen before anything was sent to the caller into `UpstreamUnavailableException`. Two
things to keep when touching it:

- **The exception carries neither the original as its cause nor its message** — either brings the
  "lost client" verdict back, and the message names the internal address. Only the simple class
  name of the original survives, for the log line.
- **It maps only while the response is not committed.** After that an error may really be the
  caller hanging up, and Spring has to see the original to keep it quiet. The one place the filter
  cannot tell the sides apart: a caller that hangs up while uploading a request body is logged as
  an unreachable upstream.

## Tests

- `UpstreamConnectionFailureTest` and `IdleConnectionEvictionTest` start the gateway on a real port
  and point the `water` route at `StubUpstream`, a plain `ServerSocket`. Not `mockwebserver3`, the
  org's usual choice: the failure is a TCP reset, which takes `SO_LINGER 0` on the server side, and
  no HTTP mock offers that.
- **The bare 500 shows only on Linux.** There a reset reads "Connection reset by peer"; on Windows
  it reads "Connection reset" and was a 502 all along. The tests pass on both, but only a run on
  Linux (CI, or `mvn verify` in a `maven:3.9-eclipse-temurin-21` container with `~/.m2` mounted)
  can fail for that reason; `UpstreamFailureFilterTest` pins the mechanism with the Linux text.
- A `@SpringBootTest` with a real port sets `management.server.port=0`, or two contexts fight over
  8200.

## Tracing and logging

The trace starts here (Micrometer Tracing with Brave, no exporter — `traceId` lives in the JSON
logs). Logbook's own lines never carry `traceId`, so for plainly proxied traffic the gateway
contributes the id but no log line of its own.

## Build & CI/CD

`mvn verify` (JDK 21). `CI.yml`, `sonar.yml`, `release.yml` (GitHub release → Docker image). The
manifest is `deployment-tools/workshop/api-gateway-service.yaml`. Release flow: the `release`
skill.

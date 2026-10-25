# api-gateway-service

`cloud.cholewa:api-gateway-service` — Spring Cloud Gateway at the edge of the cluster: the k8s
ingress forwards all of `/home` here, and static routes fan out to the internal services over
k8s DNS. It is the **only** way into the cluster from outside, and where every external request's
trace starts. Reactive (WebFlux), Java 21, Spring Boot 4.1.2, Maven. Port **6200** (Actuator
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
  `/device/registry`, `/device/registry/**`, `/household`, `/household/**`, `/calendar/**`) —
  `RoutesConfigTest` asserts exactly one route per service by id. Add a path to the existing
  route rather than a second route to the same service.
- **The `database` route has open tails on purpose** (HAS-225): by path alone, every method, and
  whatever `database-service` adds below a routed path is published with it - that service has
  nothing to keep inside, its endpoints are what the dashboard and Bruno call. `/device` is the
  one place listed by its branches (`configuration`, `registry`) and not as `/device/**`. A
  pattern ending in `/**` matches the bare path too (`/calendar/**` routes `/home/calendar`), and
  a dot segment below a tail is forwarded as it was sent - `RoutesConfigTest` pins both, so a
  bump of the starter that changes the matching shows.
- **An unrouted path answers "No static resource …" (404)** — that is the gateway, not the target
  service: WebFlux found no route and fell through to static resources. The first thing to check
  when a new endpoint "does not exist" from outside the cluster.
- **`presence` is an allowlist (so is `weather`, below), unlike the other routes:** exactly
  `GET /presence/residents/presence`, `GET /presence/residents/{name}/report`,
  `GET /presence/residents/{name}/report/daily` and `GET /presence/house/report`.
  `presence-service` also serves `GET /home/presence/clients`, which lists the MAC address of
  every device on the home network and must stay unreachable from outside. A `/**` tail is not
  enough for that: `PathPattern` matches the raw path, so `residents/../clients` would match and
  be forwarded unchanged (harmless only while no hop normalises it), and every endpoint the
  service adds under `/residents` or `/house` would be published silently. `RoutesConfigTest` asserts the
  paths and methods that must match nothing. A new endpoint of that service gets its own entry —
  check first what it exposes.
- **`weather` is an allowlist too** (HAS-239): `GET /weather/current`, `/weather/status`,
  `/weather/history`. weather-service serves reads only; a new endpoint of it needs an entry here
  (and a release of the gateway), and a dot segment below the service is never forwarded -
  `RoutesConfigTest` pins the near misses (`weather/../presence/clients` among them) and the
  writes. Re-tested on 2026-10-16 with a stub on 6010 (Boot 4.1.1, starter 5.0.3, `cholewa-commons`
  1.9.0): the three reads arrive unchanged, a `POST` and the dot segment are 404 at the gateway.
- **`blinds` is open by path, every method** (HAS-251): `/blinds` and `/blinds/**` - the reads, the
  commands, the schedules and the action URLs of the Gen1 rollers, which reach the gateway from the
  devices on the LAN through the host-less ingress. `GET /home/blinds/events/gen1?device=&event=` is
  **a `GET` with a side effect behind this gateway, by design** (with the one of `light`, below): a
  Shelly Gen1 can only call a URL with `GET`, and what it calls is an idempotent report of a state
  (`open`, `close`, `stop`), so the repetition of a read below is harmless for it - the same report
  twice is the same state. Anything else with a side effect stays off `GET`. Re-tested on 2026-10-16 with a stub on 6011 (Boot 4.1.1, starter 5.0.3,
  `cholewa-commons` 1.9.0): reads, the Gen1 event with its query, a `POST`/`PUT` with its body and a
  `DELETE` arrive unchanged, `/home/blindsx` is 404, a stopped target a 502 on route `[blinds]`.
- **`light` is open by path, every method** (HAS-265), the way `blinds` is: `/light` and `/light/**` -
  the state and occupancy reads, the commands, and `GET /home/light/events/gen1?device=&event=` - the
  action URLs of the Gen1 lights (`on` / `off`) and of a Shelly Motion (`motion` / `motion_end`, with
  an optional `lux`), the second `GET` with a side effect by design: an idempotent report of a
  state, harmless under the repetition of a broken read. Re-tested on 2026-10-19 with a stub on 6012
  (Boot 4.1.1, starter 5.0.3, `cholewa-commons` 1.9.0): a read, the Gen1 event with its query, a
  `POST` with its body and a `DELETE` arrive unchanged, `/home/lightx` is 404, a stopped target a
  502 on route `[light]`.
- **`appliance` is open by path, every method** (HAS-281): `/appliance` and `/appliance/**` - the reads of
  appliance-service (the appliances, their cycles and samples) and `POST /appliance/{id}/acknowledge`, the
  acknowledgement of a finished cycle (HAS-279); that service has nothing to keep inside. Re-tested on
  2026-10-25 with an echo stub on 6013 (Boot 4.1.2, starter 5.0.4, `cholewa-commons` 1.12.0): a read with its
  query, a `POST` with a UTF-8 body and one of 300 KB, a `DELETE` arrive unchanged, `/home/appliance` is
  routed and `/home/appliancex` is 404, a stopped target a 502 with its body on route `[appliance]`.
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
`spring-cloud-starter-gateway-server-webflux` is pinned on its own (**5.0.4**, built against Boot 4.0.9 -
Maven Central, 2026-10-25). It works because Boot 4.1.2 and 4.0.9 share Spring
Framework 7.0.x, but it sits outside Spring's compatibility matrix —
**re-test the gateway (context up, a route proxies, an unmatched path 404s) after any bump** of
Boot or the gateway starter, and drop the pin the day a Boot 4.1 train ships.

The quickest way to re-test without a cluster: start the jar with `home,local`, put any HTTP stub on a target's local port and call the route
through `localhost:6200` — done that way on 2026-10-04 for Boot 4.1.1 / starter 5.0.3 (HAS-152),
together with logbook 4.2.0, which declares apiguardian 1.1.2 itself, so the pin in
`dependencyManagement` went away. Repeated on 2026-10-11 for HAS-212 (same versions, the pool
limits and the repetition of a read added): a stub that can reset a connection on request shows
the two answers that matter there — a `GET` on a reset connection is 200, a `POST` 502 with a
body. And on 2026-10-13 for HAS-225 (same versions, `cholewa-commons` 1.8.0): a stub that
answers what it was asked, on the local port of `database-service`, shows that a new path
arrives with its path, query and body unchanged.

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
- **A `GET` whose connection broke at once is repeated once** — `FailedReadRepeatFilter`, a
  **global** filter, so a new route has it without anything in `RoutesConfig`
  (`UpstreamConnectionFailureTest` runs it through a path of every route). `GET` only, an
  `IOException` only, only when the attempt failed within `QUICK_FAILURE` (1 s), once. Never widen
  it to a write — `POST /home/heating` switches the house. Reactor Netty repeats on its own only
  while nothing of the request was sent (its WARN "the request cannot be retried as the
  headers/body were sent" is the other case, and stays in the log next to the filter's own WARN
  `Repeating GET on route [..] after a broken connection [..]`). That line is written **before**
  the second attempt, so a repetition that fails as well is counted too — logged on success
  only, the count was lowest when things were worst.
- **Only a `GET` that announces no body is repeated** (`Content-Length` at most 0 and no
  `Transfer-Encoding`). The filter keeps nothing, and the body of the caller can be read once: a
  repeated `GET` with a body went out with its `Content-Length` and **without** its body, the
  connection returned to the pool, and the target read the next caller's request as the missing
  bytes — a desync of a shared connection, found by the second review. Anything that would repeat
  a request with a body has to hold that body, which is what the `Retry` filter below does.
- **Do not replace it with the `Retry` filter of Spring Cloud Gateway** — the first version of
  HAS-212 did. `RetryGatewayFilterFactory.apply` calls `enableBodyCaching(routeId)`, and
  `AdaptCachedBodyGlobalFilter` then joins the body of **every** request to that route in memory,
  with no size limit, before routing — writes included, on the one replica that is the only way
  in. The `CircuitBreaker` filter factory does the same. `UpstreamConnectionFailureTest` asserts
  that no request body is cached.
- **Why the bound on time**: the response-timeout (30 s) applies to each attempt, so a read that
  broke 25 s in would be run again in full — the caller waits up to twice the timeout and a
  service with a database pool of 2 runs the statement twice. A stale pooled connection or a
  refused one fails in milliseconds. The bound also has to stay below the connect-timeout (2 s),
  or a target that does not answer the connect is waited for twice;
  `ApiGatewayServiceApplicationTest` holds the constant against the bound `connect-timeout`, so a
  change of either one shows.
- **Three things the filter has to do before the second attempt**, each found by a review:
  `ServerWebExchangeUtils.reset(exchange)` (without it `NettyRoutingFilter` sees the exchange as
  already routed and the second attempt calls nobody), and stopping the client observation of the
  failed attempt — `ObservedRequestHttpHeadersFilter` starts one per call and keeps only the
  latest in `GATEWAY_OBSERVATION_ATTR`, so the first would never be stopped (stopped without a
  look at the "already stopped" mark of the response filter: that is set when the headers of an
  answer arrive, and a failure reaches this filter only before). And it sits *inside*
  `NettyWriteResponseFilter` and `GatewayMetricsFilter`: nothing is repeated once an answer is
  being written, and a repeated request is timed as one — asserted on the beans' orders.
- **Every `GET` behind the gateway therefore has to stay a read.** A service that puts a side
  effect behind a `GET` gets it twice now and then. The exceptions are the action URLs of the Gen1
  devices (`/blinds/events/gen1`, `/light/events/gen1`, see Routes): an idempotent report of a state.

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
  name of the original and the id of the route survive, for the log line
  (`Upstream service unreachable [NativeIoException] on route [water]`).
- **It maps only while the response is not committed.** After that an error may really be the
  caller hanging up, and Spring has to see the original to keep it quiet.
- **It calls `ServerWebExchangeUtils.reset(exchange)` before mapping.** When the connection breaks
  between the headers and the body of an answer, the target's headers are already on the
  response; without the reset the 502 went out with the `Cache-Control` and `ETag` of an answer
  that never came.
- **A caller that hangs up while uploading a request body is logged as an unreachable upstream.**
  Run by hand (2026-10-11, a client announcing 100 kB, sending 10 bytes and closing, against a
  stub that waits for the whole body): the request to the target is aborted, Reactor Netty logs
  its WARN, and the processor logs `Upstream service unreachable [AbortedException] on route
  [water]` (`SocketException` when the caller resets) for a 502 nobody reads. Before an answer the
  gateway cannot tell which side broke the exchange. Not new noise for the alert on errors: by
  the sources the same case was a "500 Server Error" at ERROR before HAS-212 (not run on the old
  build).

## Tests

- `UpstreamConnectionFailureTest` and `IdleConnectionEvictionTest` start the gateway on a real port
  and point its routes at `StubUpstream`, a plain `ServerSocket`. Not `mockwebserver3`, the
  org's usual choice: the failure is a TCP reset, which takes `SO_LINGER 0` on the server side, and
  no HTTP mock offers that.
- **A reset cannot be placed "after the headers" reliably**: it throws away what the other side has
  not read yet, so whether the gateway ever sees the headers is a race (a fixed sleep hid it for
  one review). The stub therefore *closes* the connection there (`CLOSE_AFTER_HEADERS`); that a
  reset text is mapped in that phase too is covered by `UpstreamFailureFilterTest` alone.
- `FailedReadRepeatFilterTest` moves the clock of the filter by hand (a `LongSupplier`), so both
  sides of the 1 s bound are tested without waiting; it and `UpstreamUnavailableProcessorTest`
  assert the **level** of their log lines — WARN for a repetition, ERROR for a 502 — because
  the level is what the alerts see.
- A `GET` with a body is written to a raw socket in `UpstreamConnectionFailureTest`: not every
  HTTP client sends one, and the case is about the bytes on the wire.
- **The bare 500 shows only on Linux.** There a reset reads "Connection reset by peer"; on Windows
  it reads "Connection reset" and was a 502 all along. The tests pass on both, but only a run on
  Linux (CI, or `mvn verify` in a `maven:3.9-eclipse-temurin-21` container with `~/.m2` mounted)
  can fail for that reason; `UpstreamFailureFilterTest` pins the mechanism with the Linux text.
- A `@SpringBootTest` with a real port sets `management.server.port=0`, or two contexts fight over
  8200.

## Tracing and logging

The trace starts here (Micrometer Tracing with Brave, no exporter - `traceId` lives in the JSON
logs). Since `cholewa-commons` 1.10.0 (taken with HAS-281) Logbook runs as a WebFilter in the reactive chain
(`logbook.reactive.server-mode: web-filter`, the default of the library), so its `Incoming Request` /
`Outgoing Response` lines carry the `traceId` of the request; the actuator stays out of the log (1.10.1).
The proxied call itself goes through the own HttpClient of the gateway and is not logged by Logbook.
At the start Hibernate Validator 9.1 warns `HV000271` about `@Valid` on `GatewayProperties.routes` of the
starter - a deprecation in Spring Cloud, not in this code, harmless.

## Build & CI/CD

`mvn verify` (JDK 21). `CI.yml`, `sonar.yml`, `release.yml` (GitHub release → Docker image). The
manifest is `deployment-tools/workshop/api-gateway-service.yaml`. Release flow: the `release`
skill.

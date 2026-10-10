# api-gateway-service

Gateway to other home-automation services.

[![CI](https://github.com/smart-home-automation-system/api-gateway-service/actions/workflows/CI.yml/badge.svg)](https://github.com/smart-home-automation-system/api-gateway-service/actions/workflows/CI.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_api-gateway-service&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_api-gateway-service)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_api-gateway-service&metric=vulnerabilities)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_api-gateway-service)

![GitHub Release Date - Published_At](https://img.shields.io/github/release-date/smart-home-automation-system/api-gateway-service?style=plastic)
![GitHub Release](https://img.shields.io/github/v/release/smart-home-automation-system/api-gateway-service?style=plastic)

---

![GitHub top language](https://img.shields.io/github/languages/top/smart-home-automation-system/api-gateway-service?style=plastic)
![Java](https://img.shields.io/badge/java-21-yellow?style=plastic)
![SpringBoot](https://img.shields.io/badge/SpringBoot-4.1.1-blue?style=plastic)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_api-gateway-service&metric=coverage)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_api-gateway-service)
[![Lines of Code](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_api-gateway-service&metric=ncloc)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_api-gateway-service)

![GitHub issues](https://img.shields.io/github/issues/smart-home-automation-system/api-gateway-service?style=plastic)
![GitHub contributors](https://img.shields.io/github/contributors/smart-home-automation-system/api-gateway-service?style=plastic)
![GitHub pull requests](https://img.shields.io/github/issues-pr-raw/smart-home-automation-system/api-gateway-service?style=plastic)

![GitHub last commit](https://img.shields.io/github/last-commit/smart-home-automation-system/api-gateway-service?style=plastic)
![GitHub commit activity](https://img.shields.io/github/commit-activity/m/smart-home-automation-system/api-gateway-service?style=plastic)

---

# Description

Spring Cloud Gateway sitting at the edge of the cluster: the k8s ingress hands it the external
traffic under `/home` and it forwards to the internal services over k8s DNS. It is also where a
request's trace starts, so one `traceId` follows it through every service it touches.

Routing is **static** — service discovery was retired together with `service-discovery` (Eureka),
and k8s DNS resolves the targets. Each route's host and port come from the `internal.service.*`
configuration group.

## Spring Cloud on Boot 4.1 — accepted risk

No Spring Cloud release train targets Spring Boot 4.1 (2025.1.3, the newest, is built against
Boot 4.0.8), so the BOM is not imported and `spring-cloud-starter-gateway-server-webflux` is
pinned on its own (`spring-cloud-gateway.version`, **5.0.3**). The combination works because
Boot 4.1.1 and 4.0.8 sit on the same Spring Framework 7.0.x line, and it is verified on every
change — but it is outside Spring's compatibility matrix, so **re-test the gateway after any
bump** of either version. Last re-tested on 2026-10-11 with Boot 4.1.1 and the starter 5.0.3
(HAS-212, with the limits of the connection pool and the retry of a read): the context starts, a
route proxies and carries `traceparent`, an unmatched path answers 404, an unreachable target 502,
and a connection reset by the target answers 200 for a `GET` and 502 for a `POST`.

## Run locally

```bash
mvn verify                                  # build and tests
mvn spring-boot:run -Dspring-boot.run.profiles=home,local
```

| | Application | Actuator |
|---|---|---|
| local (`local` profile) | 6200 | 8200 |
| cluster (`home` profile) | 6200 | 8200 |

The `local` profile points the routes at `localhost` instead of the k8s service names; the
Actuator exposes `health`, `info` and `prometheus`.

## Routes

Route predicates are written **without** the `/home` prefix, but nothing is stripped at runtime:
`PathRoutePredicateFactory` prepends `spring.webflux.base-path` to every pattern and matches it
against the full request path. The target therefore receives the path unchanged — `RouteToRequestUrlFilter`
merges only scheme, host and port onto the incoming URI. Every service is mounted under the same
path externally as internally, so no rewrite is needed. A target whose path differs needs a
`rewritePath` filter; a longer `uri(...)` string does nothing, its path part is discarded.

| Path | Target | Endpoints behind it |
|---|---|---|
| `/home/ai` | `ai-service` | `POST /home/ai` (text/plain) |
| `/home/amx` | `amx-service` | `POST /home/amx` |
| `/home/boiler/**` | `boiler-service` | `GET /home/boiler/status` |
| `/home/device/configuration/**` | `database-service` | `GET` and `POST /home/device/configuration/eaton` — the POST writes device configuration and is unauthenticated |
| `/home/household`, `/home/household/**` | `database-service` | the household registry: `GET /home/household` and the member/device CRUD under `/home/household/member/...` — unauthenticated like every route here, and it carries members' names, phone numbers and device MACs |
| `/home/heating/**` | `heating-service` | `GET` and `POST` on `/home/heating` (the `POST` switches the heating of the house), `GET /home/heating/status/active`, `GET /home/heating/temperature/sensors`, `GET /home/heating/rooms`, `GET /home/heating/rooms/{name}`, `GET /home/heating/rooms/{name}/temperature/history`, `GET /home/heating/floor-pump` |
| `GET /home/presence/residents/presence`, `GET /home/presence/residents/{name}/report`, `GET /home/presence/residents/{name}/report/daily`, `GET /home/presence/house/report` | `presence-service` | who is at home now and, each for a range (`?from=&to=`, both required): when one resident was at home, their daily statistics, and when the house as a whole was occupied or empty — unauthenticated like every route here, and it tells when each household member is at home and when nobody is |
| `/home/water/**` | `water-service` | `GET /home/water/status/{active,temperature}`, `GET /home/water/temperature/history` |

Hosts and ports come from the `internal.service.*` group: k8s DNS names on 6200 in the cluster,
`localhost` with each service's own port locally.

The `presence` route is an **allowlist** — exactly those four paths, `GET` only — and deliberately
narrower than its service: `GET /home/presence/clients`, a diagnostic endpoint listing the MAC
address of every device on the home network, is **not** routed and answers 404 here, and so does
anything that service adds later until it is listed. Do not widen the predicate to
`/presence/**`, `/presence/residents/**` or `/presence/house/**`.

`notification-service` is deliberately absent: its `/home/notification/skippy` endpoint has no
external consumer and was never routed. Add a route the day something outside the cluster needs it.

Anything not matched returns 404; a route whose target is unreachable — refused, unresolvable,
reset or dropped mid-response — returns 502 with a fixed message, deliberately without the internal
host and port (see `UpstreamUnavailableProcessor`). The HTTP client connects with a 2 s timeout and
waits 30 s for a response; the `ai` route overrides that to 120 s, because an OpenAI answer
legitimately takes longer than anything else here.

## Connections to the services

The gateway keeps its connections to the services in a pool, and since HAS-212 the pool has limits
(`spring.cloud.gateway.server.webflux.httpclient.pool` in `application.yaml`):

| Setting | Value | Why |
|---|---|---|
| `max-idle-time` | 2 min | a connection nothing has travelled on for 24 h is dead without the gateway knowing (below); 2 minutes is far under that and above the 30 s and 60 s the dashboards poll at |
| `max-life-time` | 30 min | bounds the life of a connection that is in constant use and never becomes idle |
| `eviction-interval` | 30 s | closes an expired or broken connection while nobody is calling, instead of at the next request |

What they are for: the node forgets the address translation of a connection that has been silent
for 24 hours. When the pod of the service is replaced after that, its close never reaches the
gateway, and the next request on that connection is answered with a reset. Without a limit the
gateway kept such connections for days - a call to a service used once and then left alone failed.

Two more things hold whatever still breaks inside the error contract:

- **A `GET` whose connection to the service broke at once is sent once more**, on another
  connection (`FailedReadRepeatFilter`, a global filter - every route has it, also one added
  later). The rule is narrow on purpose:
  - `GET` only. A write is never repeated: it may have been carried out before the connection
    broke. Nor are `HEAD` and `OPTIONS`.
  - Only a `GET` without a body (no `Content-Length` above zero, no `Transfer-Encoding`). The
    gateway passes a body on as it comes and cannot read it a second time; a `GET` that carries
    one is sent once, like a write.
  - Only a broken connection (`IOException`). An error status of a service is its own answer and
    is passed on as it is; a response timeout is the gateway's 504 and is not waited for twice.
  - Only a failure within **1 second** of the attempt starting. A connection that is dead in the
    pool, or refused, fails in milliseconds; a read that broke after seconds of waiting is not run
    again - the response timeout applies to each attempt, and the caller would wait up to twice
    as long. The second also stays below the connect timeout (2 s), so a service that does not
    answer the connect is not waited for twice.
  - Once. A second failure is the 502.
  - Every repetition leaves one WARN in the log, written before the second attempt: `Repeating
    GET on route [water] after a broken connection [...]`. When the second attempt succeeds it is
    the only trace of a failure the caller never saw; when it fails too, the ERROR of the 502
    follows it.

  It is not the `Retry` filter of Spring Cloud Gateway: that one makes the gateway keep the body
  of every request to its route in memory, without a limit, to be able to send it again. Here no
  request that announces a body is sent again, so nothing is kept - a request body is streamed
  to the service.
- **A broken connection is always a 502 with a body**, as long as nothing of an answer has been
  sent to the caller. "Connection reset by peer" is a text Spring takes for *the caller* having
  gone away, and it then answers a bare 500; `UpstreamFailureFilter` hands such a failure to the
  error handler as what it is in a gateway - the target's. A connection that breaks between the
  headers and the body of an answer is a 502 as well, without the headers of the answer that
  never came, and is not repeated. The ERROR line names the kind of failure and the route:
  `Upstream service unreachable [NativeIoException] on route [water]`.

What remains:

- Once the first byte of an answer has gone to the caller, a connection that breaks leaves a
  broken response.
- A caller that hangs up in the middle of uploading a request body is logged as an unreachable
  upstream (ERROR, and a 502 nobody reads): the request to the service is aborted, and before
  an answer the gateway cannot tell which side broke the exchange. Seen in a run by hand.
- A service that disappears without closing its connections - a node losing power - sends no
  reset either. A request on one of the connections left in the pool (for up to two minutes)
  is then not refused but unanswered: it waits for the response timeout and gets the 504, which
  is not repeated. This is reasoning from how TCP behaves; it was not tested.
- With several dead connections to one service in the pool, the repeated `GET` can be handed
  another dead one and answer the 502. Not tested either.

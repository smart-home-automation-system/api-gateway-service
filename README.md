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
![SpringBoot](https://img.shields.io/badge/SpringBoot-4.1.0-blue?style=plastic)
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

No Spring Cloud release train targets Spring Boot 4.1 (2025.1.2, the newest, is built against
Boot 4.0.7), so the BOM is not imported and `spring-cloud-starter-gateway-server-webflux` is
pinned on its own (`spring-cloud-gateway.version`). The combination works because Boot 4.1.0 and
4.0.7 sit on the same Spring Framework 7.0.x line, and it is verified on every change — but it is
outside Spring's compatibility matrix, so **re-test the gateway after any bump** of either version.

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
| `/home/heating/**` | `heating-service` | `GET` and `POST` on `/home/heating`, `GET /home/heating/status/active` |
| `/home/water/**` | `water-service` | `GET /home/water/status/{active,temperature}` |

Hosts and ports come from the `internal.service.*` group: k8s DNS names on 6200 in the cluster,
`localhost` with each service's own port locally.

`notification-service` is deliberately absent: its `/home/notification/skippy` endpoint has no
external consumer and was never routed. Add a route the day something outside the cluster needs it.

Anything not matched returns 404; a route whose target is unreachable — refused, unresolvable or
dropped mid-response — returns 502 with a fixed message, deliberately without the internal host and
port (see `UpstreamUnavailableProcessor`). The HTTP client connects with a 2 s timeout and waits 30 s
for a response; the `ai` route overrides that to 120 s, because an OpenAI answer legitimately takes
longer than anything else here.

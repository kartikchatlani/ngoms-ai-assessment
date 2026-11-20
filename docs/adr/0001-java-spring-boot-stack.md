# ADR-0001: Java 21 + Spring Boot 4 stack

- **Status:** Accepted
- **Created:** 2026-11-18
- **Accepted:** 2026-11-18

## Context
The service needs an HTTP API, a relational store, health checks, metrics and a credible path to
production, all of which can be done in Java. The team uses Java, so I chose it.

## Options considered
| Option | For | Against |
|---|---|---|
| **Spring Boot 4.1 (Java 21), Spring MVC + virtual threads** | Industry default in Java shops; Actuator, validation, Problem Details, graceful shutdown, Testcontainers support built-in; plain blocking code with high concurrency | Heavier startup than micro-frameworks |
| Spring Boot 4.1, WebFlux + Project Reactor (reactive) | High concurrency from a small fixed thread pool; strong fit for streaming and backpressure (SSE, WebSockets) | Every layer must be non-blocking, which means R2DBC instead of JDBC (thinner ecosystem, weaker `ON CONFLICT ... RETURNING` support); harder to read, debug and test (framework-internal stack traces, operator-based error handling); thread-local context such as the logging MDC needs extra propagation plumbing. Its main advantage, cheap concurrency for I/O-bound work, is now provided by virtual threads, and this workload has no streaming needs. Rejected on these merits, not because of project size: the same call holds at production scale, where the DB connection pool rather than threads is the throughput limit |
| Spring Boot 3.5 | Most tutorials target it | Open-source support has ended (Nov 2026); starting a greenfield project on it means a near-term migration |
| Quarkus / Micronaut | Faster startup, native images | Less common in the target environment; startup time does not matter for a long-running service |
| Plain Javalin/Jetty | Minimal | Re-implementing health, config, validation, error handling = more code, more bugs |

## Decision
- **Java 21 (LTS):** long-term support; records for immutable DTOs and domain objects; virtual
  threads for cheap blocking I/O. Also small conveniences such as sequenced collections
  (`List.getLast()`), used for pagination cursors.
- **Spring Boot 4.1.2:** latest patch of the current stable line at the time of writing
  (released 2026-10-22).
- **Maven with the Maven Wrapper (`mvnw`):** reviewers can build with no Maven install; Maven is
  the more common choice in enterprise Java.
- **Virtual threads enabled** (`spring.threads.virtual.enabled`): the workload is I/O-bound
  (DB lookups). Virtual threads give high concurrency with plain blocking code and JDBC,
  without the reactive-programming complexity of WebFlux/Reactor (non-blocking drivers,
  harder debugging, MDC/context propagation).

## Consequences
- Boot 4 split auto-configuration into modules (e.g. `spring-boot-starter-webmvc`,
  `spring-boot-starter-flyway`, `spring-boot-starter-webmvc-test`) and moved to Jackson 3
  (`tools.jackson.*`). Older Boot 3 examples need small adaptations.
- JDK 21 must be installed to build locally (Docker builds need nothing but Docker).

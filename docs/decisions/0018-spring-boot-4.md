# 0018. Spring Boot 4 and Java 25

- Status: accepted
- Date: 2026-10-04
- Spec reference: §3.1
- Supersedes: the backend versions in 0007

## Context

Decision 0007 chose Spring Boot 3.5 because the specification asks for Spring Boot 3. It didn't
check how long 3.5 would be supported: its free support, and that of Spring Framework 6.2 and
Spring Security 6.5, ended on 30 June 2026, before version 1 was built. 3.5.16 is the last
release. From then on its dependency management brought no security fixes: the security review
of 4 October 2026 found Jackson, Tomcat, commons-lang3 and Bouncy Castle with published
advisories, which had to be overridden one by one. Choosing a line that was already out of
support was a mistake, and the maintainer asked for the current versions.

## Decision

- Spring Boot 4.1 (free support until 31 July 2027; 4.0's ends on 31 December 2026), so Spring
  Framework 7 and Spring Security 7.
- Jackson 3 (`tools.jackson`), which Spring Boot 4 uses by default. Its annotations keep their
  package, so the API's JSON doesn't change. Rate visibility (spec §11) now reaches the converter
  as a write hint instead of a wrapper; `RateVisibilityTest` guards it.
- Java 25 (LTS) for building and running; the release image already ran Java 25.
- Gradle 9, jOOQ 3.21, Flyway 12, Testcontainers 2, springdoc 3, db-scheduler's Spring Boot 4
  starter, openhtmltopdf 1.1.87 and kotest 6.
- Tracing uses Spring Boot's OpenTelemetry tracing module, not its OpenTelemetry starter: the
  starter also brings a metrics exporter that pushes to localhost and logs an error every minute.

- PostgreSQL 18. jOOQ's free edition supports only the newest PostgreSQL in each release: 15 for
  jOOQ 3.19, 17 for 3.20 and 18 for 3.21, which Spring Boot 4.1 uses. Running 3.21 on 16 would
  mean SQL nobody tested against it. There's no release and no other self-hoster yet, so this is
  the cheapest time to move. The compose file uses a new volume (PostgreSQL 18 also moved its data
  directory), and `docs/self-host.md` says how to move the data across.

## Consequences

- Dependabot opens a grouped update each month (`.github/dependabot.yml`), so versions don't
  quietly go stale again.
- Before choosing a version of anything long-lived, check its support window and say it in the
  decision record.
- Spring Boot 4.1's free support ends on 31 July 2027: move to the next line before then.

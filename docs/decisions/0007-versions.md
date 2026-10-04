# 0007. Platform versions

- Status: accepted; the backend versions are superseded by 0018
- Date: 2026-09-30
- Spec reference: §3.1

## Decision

Spring Boot 3.5.x (the spec asks for Spring Boot 3; 4.x exists but is out of scope), Kotlin 2.4, JDK 21 via Gradle toolchains, jOOQ 3.19 (the version managed by Boot), Flyway 11, db-scheduler 16, and PostgreSQL 16. Frontend: React 19, Vite 8, TanStack Router/Query, Radix primitives, and TypeScript 5.9 (7.x has no stable compiler API yet, which openapi-typescript needs). Font: Archivo (OFL), self-hosted.

## Consequences

Upgrading to Spring Boot 4 is a separate, deliberate step.

The release image runs the Java 21 build on a Java 25 (LTS) runtime. Requests run on virtual threads, and on Java 21 a virtual thread inside `synchronized` code holds on to its carrier thread; on a two-core machine that stalled the whole app once in CI. Java 24 removed that limitation (JEP 491). Code still targets Java 21, so running the jar on Java 21 works, but 25 is the recommended runtime.

# Changelog

All notable changes to soda-core are documented here.
Versioning follows the Java-baseline scheme: `<Java-major>.<feature>.<patch>`.

## 17.0.1 — 2026-10-29

First release under the Java-baseline versioning scheme.
Minimum Java 17 (recommended 21); supports Spring Boot 3.2.x ~ 4.1.x with a single jar.

### Added
- `EventSerializer` abstraction in soda-core-base, with a reflection-based
  `JacksonEventSerializer` for cross-Jackson compatibility
- Release tooling: `deploy/release.sh` and the release handbook (`deploy/RELEASE_SKILL.md`)

### Changed
- One jar now supports both Spring Boot 3.2.x (Jackson 2) and Spring Boot 4.x
  (Jackson 3) — pick your Spring Boot version, the soda coordinates stay the same
- Internally upgraded to Spring Boot 4.1.0 / Java 21 / Jackson 3
- `soda-event-redis` no longer depends on Jackson directly (serialization goes
  through `EventSerializer`)
- `@AutoConfigureAfter` references adapted for Spring Boot 3 + 4 compatibility

### Removed
- `SmartCircularReferenceSerializerModifier` (no longer needed)

## Earlier versions (historical lines, EOL)

- `17.0.0` — ⚠️ superseded by `17.0.1`: a stale internal build accidentally deployed to the
  Aliyun repo during the version-scheme migration (missing the `EventSerializer` decoupling).
  Never published to Maven Central. Do not use.
- `4.0.0` — Spring Boot 4.x single line (pre-rename snapshot of the current code)
- `2.1.1-boot32` — ⚠️ POM-only on Maven Central, do not use (use `2.1.0-boot32`)
- `2.1.0-boot32` — Spring Boot 3.2.x line (Java 8)
- `2.0.0` / `2.1.0` — Spring Boot 2.x line (Java 8)

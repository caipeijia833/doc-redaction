# Changelog

All notable changes will be documented here. The project follows Semantic Versioning for public releases and uses an explicit pre-release label while production acceptance remains incomplete.

## [Unreleased]

### Added

- Reproducible Gradle Wrapper with distribution checksum validation.
- Deterministic hosted CI for Windows 2025 plus macOS 15 Apple Silicon/Intel, dependency review, and CodeQL workflows; macOS 13 remains a separate real-machine/VM acceptance gate because GitHub retired the hosted `macos-13` label.
- Separate native OCR/media integration-test task.
- Public governance templates, English project documentation, and maintainer workflow boundaries.
- Public-safe synthetic large-file evidence index.
- Bilingual end-user guides, troubleshooting, backup/recovery/uninstall, logging, governance, and release documentation.
- SPDX headers for project Java sources and explicit OFDRW-derived source attribution.
- Fail-closed FFmpeg corresponding-source gate for binary Release generation.

### Changed

- A clean checkout can compile with the hash-verified official OpenCV 4.13.0 Java binding committed under `app/libs/`; native libraries remain release-only.
- Windows source build now discovers an installed Java 21 or bootstraps a pinned project-local Temurin 21 without changing the system PATH.
- Java packages and SBOM coordinates moved from the placeholder `com.company.redaction` namespace to `io.github.caipeijia833.docredaction`.
- GitHub Actions are pinned to immutable commit SHAs; Issue templates no longer depend on an uncreated label.
- Public evidence and security summaries no longer contain workstation paths or links to ignored raw logs.
- Offline packages now include the project `LICENSE`, `NOTICE`, user documentation, and a freshly generated external ZIP checksum.

## [0.1.0-poc] - 2026-08-14

Initial single-machine offline Alpha candidate. See `audit/BUILD_MANIFEST.json` and `design/IMPLEMENTATION_STATUS.md` for verified scope and limitations.

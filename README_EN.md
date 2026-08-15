# Doc Redaction: Single-machine Offline Alpha

[简体中文](./README.md) | English

Doc Redaction is a local tool for redacting Chinese and English office documents, electronic case files, images, and audio/video evidence. The service binds only to `127.0.0.1`; the application runtime does not require a cloud API.

This repository is an **Alpha**, not a production-certified security product. Windows x64 has the strongest local evidence. macOS 13 Intel and Apple Silicon packaging exists as source scripts but still requires real-machine acceptance, signing, and notarization.

## Capabilities

| Area | Current scope |
|---|---|
| Office | Package-level streaming rewrite for DOCX, XLSX, and PPTX, with residual scans and fail-closed handling for unsafe embedded objects |
| Fixed-layout files | PDF and OFD are rendered, OCR-checked, masked, and rebuilt as rasterized output; original signatures and editability are not preserved |
| Images | PNG, JPG/JPEG, and BMP use local OCR coordinates and opaque masks |
| Audio | MP3, WAV, M4A, and FLAC use local whisper.cpp ASR; matched time ranges are muted in a re-encoded output |
| Video | MP4, MOV, and MKV support sampled-frame OCR, face, QR-code, and Chinese license-plate masking, audio ASR, and text-subtitle rewrite |
| Archives | ZIP, TAR, TAR.GZ, and 7Z are listed before processing; unsupported ordinary files are excluded unless the user explicitly confirms original inclusion; RAR is recognized but not extracted |
| Rules | 205 built-in Chinese, English, and international rules; 44,712-entry mainland China administrative-division lexicon; black/white lists; paginated custom-rule management and version rollback |
| Filenames | Filenames and archive-entry paths are redacted with extension preservation and collision handling |
| Restore center | Optional AES-256-GCM encrypted-original storage; PBKDF2-HMAC-SHA-256 with 600,000 iterations; restored bytes must match the original SHA-256 |
| Batch work | Persistent SQLite queue, project grouping, history, cancellation/retry, archive confirmation, and up to 10,000 queued items |
| Local preview | Project-owned preview layer for Office text, rasterized PDF/OFD/images, and loopback range streaming for audio/video |

## Security model

- Redacted output must not depend on a removable visual overlay. PDF/OFD and media results are rebuilt or re-encoded.
- Parse failures, unavailable OCR for a required path, unsafe OOXML objects, and failed residual checks fail closed.
- User-defined regex rules run on RE2/J's linear-time engine.
- Uploads, previews, history, encrypted originals, and rules stay under the local data directory.
- Loopback binding, Host validation, and a random session token reduce ordinary browser-origin attacks, but do not protect against malware running as the same operating-system user.
- Windows Job Objects limit worker lifetime, child count, and committed memory; they are not a filesystem/network permission sandbox.
- Restore means decrypting the separately stored original. A redacted result cannot be reversed by removing masks.

See [the threat model](./design/PRODUCTION_THREAT_MODEL.md), [security policy](./SECURITY.md), [current build manifest](./audit/BUILD_MANIFEST.json), and the [historical production-readiness review](./audit/PRODUCTION_READINESS_REVIEW_2026-08-14_V3.md).

## Clean source build

Requirements:

- 64-bit Windows 10/11 or macOS 13+
- Java 21
- Network access for the first Gradle dependency download; the packaged application runtime remains offline

Windows PowerShell:

```powershell
Set-Location '<repository>'
.\build-windows.ps1
```

The script uses an existing Java 21 installation when available. Otherwise it downloads a pinned Temurin 21 archive into the repository-local ignored `.tools` directory and verifies its SHA-256. It never edits the system PATH, registry, or services. The Gradle Wrapper validates the Gradle distribution checksum.

macOS:

```bash
chmod +x build-macos.sh
./build-macos.sh
```

The default build runs deterministic tests that do not require local OCR/media binaries. On a maintainer Windows machine with the pinned native toolchain and synthetic media fixtures:

```powershell
.\build-windows.ps1 -WithNativeTests
```

The `nativeIntegrationTest` task is intentionally separate. Missing native components must not be reported as passing, and must not make clean-clone unit tests nondeterministic.

## Running the packaged application

The complete offline package contains a Java runtime, Tesseract 5.5.2 (`chi_sim`, `eng`, and `osd`), LGPL-only FFmpeg, whisper.cpp, official OpenCV 4.13.0 JNI components, and an optional local Qwen3-VL-2B-Instruct Q4_K_M setup. It verifies component hashes before startup and repairs only from its packaged, hash-verified recovery archive.

The source-tree launcher is for a prepared maintainer environment. End users should use a verified release package instead of reconstructing `.tools` manually.

## Large-file evidence

The upload limit is 1 GiB per file. Local synthetic evidence includes an exact 1 GiB PDF path, near-limit DOCX/XLSX/PPTX paths, and an exact 1 GiB TAR listing/confirmation path. These results do not prove support for every real 1 GiB document, OFD, or media file.

Public-safe summaries are indexed in [audit/evidence](./audit/evidence/README.md). Real legal files, private user data, restore material, and runtime databases must never be committed or attached to public issues.

## Known gaps

- No authorized real legal/OCR/OFD/audio/video gold corpus or independent accuracy acceptance yet.
- No completed macOS 13 Intel/Apple Silicon real-machine package acceptance.
- No Authenticode signing, Apple signing/notarization, or published release-key custody policy.
- No strong low-privilege filesystem/network sandbox.
- No enterprise RBAC, dual approval, KMS integration, tamper-evident audit store, or multi-user deployment.
- No full 10,000-file real-case endurance test or 1 GiB long-media acceptance.

These gaps block production-readiness claims, but are tracked openly rather than presented as completed work.

## Contributing

Read [CONTRIBUTING.md](./CONTRIBUTING.md), [SECURITY.md](./SECURITY.md), [CODE_OF_CONDUCT.md](./CODE_OF_CONDUCT.md), and [GOVERNANCE.md](./GOVERNANCE.md). Test fixtures must be fully synthetic or have a documented redistribution license and pinned provenance. End-user instructions are in [docs/USER_GUIDE_EN.md](./docs/USER_GUIDE_EN.md); troubleshooting, backup, uninstall, and safe diagnostics are documented under [docs](./docs/).

Do not submit real documents, personal data, credentials, passwords, restore ciphertext, raw local logs, or private vulnerability details to an Issue or pull request.

## Open-source publication status

The public repository is `https://github.com/caipeijia833/doc-redaction`, and this local directory uses the `main` branch with that URL configured as `origin`. The project owner has designated `caipeijia833` as the public copyright-holder identifier, primary maintainer, release signer, and recovery-key custodian. No binary Release is published by this source baseline. macOS 13+ target-machine acceptance, signing/notarization, and any Codex for Open Source application remain future work and must not be represented as complete. See [OPEN_SOURCE_PUBLISHING.md](./OPEN_SOURCE_PUBLISHING.md), [RELEASING.md](./RELEASING.md), and [MAINTAINERS.md](./MAINTAINERS.md).

License: Apache-2.0. The single-license choice and treatment of third-party MIT components are explained in [LICENSE_POLICY.md](./LICENSE_POLICY.md). Third-party attributions and component boundaries are documented in [THIRD_PARTY_NOTICES.md](./THIRD_PARTY_NOTICES.md).

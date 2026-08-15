# Public synthetic acceptance evidence

This directory contains small, public-safe summaries copied from local stress runs. Inputs were generated locally from synthetic content. The multi-hundred-megabyte and 1 GiB source files, working directories, and runtime logs are intentionally excluded from Git.

| Evidence | SHA-256 | Meaning |
|---|---|---|
| `pdf-1gib-jobobject-telemetry-20260814.json` | `c7afb383a852a810e7d415ea71129f1d279f8f13f16200e7d1d13ead5e7d481e` | Exact 1 GiB synthetic PDF, every-page OCR/redaction/residual scan, Windows Job Object telemetry |
| `stream-docx-1gib-final.json` | `20e20691d665d4c95e84725b6071f518b58da6e4e8c00f239128e7765424a510` | Near-limit high-density DOCX package-stream processing |
| `stream-xlsx-1gib-final.json` | `281581f25206953447a4aae6c3d1f76f0c6cb43932b50d45bc8d3b3dfbe9da54` | Near-limit high-density XLSX package-stream processing |
| `stream-pptx-1gib-final.json` | `6c852378d892c0cbd816b282e87eb779572013de7cfd4f223db5eb56fef41b8b` | Near-limit high-density PPTX package-stream processing |
| `archive-1gib-final-20260812.json` | `3e00dc9b474cc465244feeeb178dd6ef4d18055c846271463b8eab0f8d925d05` | Exact 1 GiB TAR upload/listing/confirmation boundary |

These are single-machine results, not universal performance guarantees. They do not establish real legal-document accuracy, 1 GiB OFD/media support, 10,000-file endurance, macOS acceptance, or production readiness. Each JSON records its own environment and scope; summaries must not be generalized beyond those fields.

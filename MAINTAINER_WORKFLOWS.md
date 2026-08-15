# Maintainer workflows and optional Codex/API use

The application itself is designed for local offline document processing. Its runtime does not require OpenAI APIs.

If the project later receives OpenAI API credits, they may be used only for maintenance of the public repository, including:

- pull-request review and change-risk summaries;
- issue classification and duplicate detection;
- synthetic test-case generation and evaluation scaffolding;
- release-diff, changelog, documentation-consistency, and SBOM review;
- security findings triage and remediation suggestions for public source code.

The following content must never be sent to OpenAI or another hosted model through these workflows:

- user documents, legal case files, OCR/ASR output, preview images, or restored originals;
- personal information, secrets, passwords, tokens, private vulnerability details, or encryption material;
- local runtime databases, job history, diagnostic archives, or proprietary customer rules.

Hosted maintenance automation must operate only on public repository content or fully synthetic fixtures. A human maintainer remains responsible for accepting changes and for distinguishing generated suggestions from verified evidence.

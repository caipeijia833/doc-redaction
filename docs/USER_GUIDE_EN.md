# User Guide (English)

## Start and language

Extract the complete offline package. On Windows x64, run `start-windows.bat`. On a verified macOS 13+ package for the correct CPU architecture, run `start-macos.command`. Open the `DOC_REDACTION_URL` printed by the launcher, normally `http://127.0.0.1:8765/`. Use the top-right control to switch between Simplified Chinese and English. Do not expose this loopback service through a LAN bind or reverse proxy.

## Five-step redaction flow

1. **Files**: click or drag multiple files. The per-file limit is 1 GiB. Preflight does not start processing.
2. **Project and mode**: group the batch under one project. Irreversible mode removes the task plaintext after processing. Reversible mode stores an AES-GCM encrypted original and requires a project passphrase of at least 10 characters.
3. **Rules and filenames**: select rule categories and optionally require pre-processing review. The selected scope also applies to filenames and archive paths while preserving extensions.
4. **Confirm**: review files, output names, rules, and restore policy, then acknowledge the local operation.
5. **Process and download**: monitor each file and download completed results. Review or archive-confirmation states must be resolved before processing continues.

ZIP, TAR, TAR.GZ, and 7Z are supported in phase one. RAR is recognized but not extracted. Unsupported archive entries are excluded by default and may be copied unchanged only after explicit secondary confirmation.

The Restore Center can recover only tasks created in reversible mode whose encrypted original still exists. The local service verifies the restored file SHA-256. A redacted output cannot be reversed by removing a mask, and a forgotten passphrase cannot be recovered.

Built-in, custom, blacklist, whitelist, and version-history tabs are available in the Rule Center. Test custom RE2/J-compatible expressions with synthetic text before saving them.

Natural-language entities and visual detection can have false positives or false negatives. The 1 GiB limit and synthetic acceptance results do not guarantee every real 1 GiB file. Preserve an offline source backup, validate on copies, and manually review business-critical output.

See [Troubleshooting](./TROUBLESHOOTING.md) and [Backup, restore, and uninstall](./BACKUP_RESTORE_UNINSTALL.md).

# Local Data Backup and Restore

## Coverage

| Category | Included | Excluded |
|---|---|---|
| Database records | Model profiles, conversation trees and branches, skills, subagents, memory, plans, workspace manifests | Active `harness_operations` (conversation-specific operation state) |
| Attachment files | Chat attachments, large-payload blobs (large Harness messages), skill-resource directories | RootFS, Linux filesystem, cache |
| User preferences | Portable UI, Agent behavior, terminal, and other preferences | API keys, custom request headers, device pairing, storage-mount paths |

## Restore safety guarantees

1. **Restore receipt (`backup_restore_receipt`):** Written in the same database transaction so startup can distinguish a committed restore from a rolled-back transaction.
2. **Atomic log (`AtomicFile`):** After process interruption, startup uses the log to decide whether to continue or roll back preference changes and extracted files.
3. **Append only:** Existing conversations are not overwritten; only records missing from this device are added. Existing model profiles keep their local keys.
4. **Path validation:** Every archive path is checked with `BackupArchive.safePath`, preventing directory traversal and absolute paths.
5. **Hash verification:** SHA-256 digests are checked before each resource file is written.

## Archive structure

```
backup.zip
├── manifest.json          # Backup locations, format version, record-table snapshot, resource manifest
├── preferences.json       # Portable preferences (no credentials)
└── assets/
    ├── attachments/       # Chat attachments
    ├── harness_blobs/     # Large-message payload files
    └── skills/<id>/       # Skill resource directories
```

## Limits

- Maximum backup size: 512 MiB; maximum single file: 50 MiB; maximum number of resource files: 2,000.
- No Agent operation may be in progress during backup or restore; snapshot-consistency checks enforce this.
- Actual workspace files (Linux sandbox contents) are not included and must be managed separately.

## Related files

| File | Purpose |
|---|---|
| `tools/backup/LocalBackupService.kt` | Backup/restore coordinator, atomic log, and startup recovery |
| `tools/backup/BackupArchive.kt` | ZIP archive I/O, path validation, and SHA-256 digests |
| `tools/backup/BackupResources.kt` | Resource collection and path rewriting |
| `core/database/BackupRecordPolicy.kt` | Backup table allowlist, runtime-field clearing, and record diffs |
| `core/database/BackupRestoreReceipt.kt` | Restore-receipt entity and migration 53→54 |
| `core/database/DatabaseBackupRepository.kt` | Room transaction snapshots, validation, and merge |
| `core/datastore/BackupPreferences.kt` | Portable preference snapshots, validation, and atomic rollback |
| `core/model/LocalBackup.kt` | `LocalBackupManifest`, `BackupRecords`, and `BackupPreview` data classes |
| `feature/settings/LocalBackupDialog.kt` | Export/import UI, including preview and checkboxes |
| `feature/settings/LocalBackupViewModel.kt` | Backup UI ViewModel |
| `app/BackupStartupRecovery.kt` | Checks and completes pending restore during application startup |

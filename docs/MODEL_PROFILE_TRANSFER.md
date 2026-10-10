# Model Profile Backup and Restore

Model profiles can be migrated using JSON. Open **Settings → Agent Ecosystem → Model Profiles** and use the Import/Export buttons at the top.

## Import

After pasting JSON or selecting a local file, a read-only preview appears first. It shows the number of profiles, how many IDs already exist on this device, and the targets that will be added or updated. **Create copies** is selected by default; each item receives a new ID, does not overwrite existing models, and does not inherit an existing key. Choose **Update profiles with matching IDs** to update matching items; unmatched items are added. Editing the input restores the default copy mode.

Supported formats include a legacy single profile, arrays, schemaVersion=1 configuration packages, and schemaVersion=2 exports. Future versions, empty packages, duplicate IDs, invalid field types, invalid endpoints, or budget violations cause the entire import batch to fail. Previewing does not write any configuration. The file limit is 2 MiB and each batch is limited to 500 items. File reading and parsing happen off the UI thread. Remote endpoints must use HTTPS; localhost, 127.0.0.0/8, and ::1 may use HTTP.

Version 2 records the version both per profile and in the package, and adds `credentialsIncluded`, `compactionKeepRecentTokens`, and `compactionReserveTokens`. When v1 lacks compaction-override fields, updates preserve local overrides; in v2, null means to clear the override. Model parameters, reasoning/tool modes, vision/image-generation/Responses capabilities, cache toggles, and per-key request rates round-trip through export and import.

An update that omits a key preserves the local key. `credentialsIncluded=false` means the exporter explicitly omitted keys and custom request headers; updates of this kind also preserve local headers. New copies never inherit old credentials. Legacy configurations without this marker restore their original request-header fields.

## Export

API keys and all custom request headers are excluded by default to avoid accidentally exporting custom authentication fields. To migrate them, explicitly select **Include API keys and custom request headers**; these values are written to JSON in plaintext. Other fields remain unchanged, including endpoint URLs. Sensitive content entered by users in free-text fields such as URLs and names is not automatically detected, so review the file before sharing it.

Export one profile or all profiles, and copy to the clipboard, save to a file, or use system sharing. Export uses the same version, parameter, and size validation as import to avoid producing a package that the current version cannot restore. If a declared key cannot be read, exporting with credentials fails instead of silently creating a backup with a missing key. Generation and file writes run in the background; if the destination cannot be accessed, the UI does not report a successful save.

## Write consistency

`AiProfileWriter` first creates new encrypted credential references for each item. After the credentials are prepared, `AiModelRepository.importBatch` commits the full model batch and active state in one Room transaction. During commit it checks whether any model was edited while preparation was underway; if so, it aborts and asks the user to preview again. An existing active model stays active; if none was active, the first imported item is activated.

If credential preparation or database writes fail, staged references are cleaned up and the old configuration and credentials are retained. The database commit uses a non-cancellable section, so cancellation after commit does not delete a key referenced by the new configuration. Old references are cleaned up only when no model uses them, including cases where legacy credential references are shared.

Room and DataStore do not share a cross-database transaction. If the process exits during staging or post-commit cleanup, an unreferenced encrypted slot may remain. Model configuration still points to an old valid slot or a newly prepared slot.

## Code and validation

- `AiProfileTransferFormat`: bounded reads, version parsing, and whole-batch validation; error messages never echo raw credentials.
- `AiProfileBackupCodec`: shared export mapping and import entry point used by Settings and first-run setup.
- `AiProfileImportWriter` / `ModelCredentialStore`: batch commit and encrypted credential staging.
- `AiModelRepository` / `AiModelDao.importBatch`: transaction and concurrent-snapshot validation.
- `ModelImportDialog` / `ModelExportDialog`: preview, conflict policy, background I/O, and error state.

Tests cover legacy formats and new versions, invalid packages and size limits, full parameter round-trips, copy/update behavior, omitted and preserved credentials, write failures, concurrent edits, cancellation, shared references, and full-batch rollback using real Room triggers when a later row fails.

This file backs up model configuration only. It does not include chat history, workspace files, the Linux root filesystem, app preferences, or OAuth sessions. This implementation uses local files and system sharing; cloud backup can be built on a separate archive protocol.

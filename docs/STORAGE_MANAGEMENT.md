# Storage Categories and Cleanup Policy

## Current implementation

The storage screen uses six mutually exclusive categories. Each ordinary file is counted once using the longest-path rule; unknown files remain under “Other files” in their category. The scan covers app-private directories, separately mounted distribution home directories, workspace-root files, databases, caches, configuration, and WebView data. It excludes the APK and external mount directories. Symlinks are not traversed, and hard links with the same file identity are counted once.

| Category | Includes | Current action/recommendation |
|---|---|---|
| Projects and personal files | Workspace projects, exports, distribution home directories | Clean only recognized stale Gradle/Dart project caches; manage source and general output directories through Workspace |
| Linux and development environments | Distribution systems, SDKs, shared runtimes, version managers | Keep; uninstall through environment/tool controls after checking project and plugin references |
| Plugins and model data | Plugin executables, persistent data, bundled models | Keep; uninstall/reset through tool controls rather than deleting files and breaking the registry |
| Sessions and attachments | Database, task attachments, generated files | Delete through the relevant session lifecycle after checking references and unique copies |
| Caches and installation assets | Dependency downloads, offline packages, staging, rollback backups | Some old dependency caches may be cleaned; do not bulk-delete install packages, staging, or backups |
| App and diagnostics | Logs, configuration, skills, encrypted request snapshots, other private data | Old archived logs are candidates; keep current logs, configuration, and request snapshots |

Total usage and potentially cleanable usage are shown separately. Values are logical file sizes and may not match Android Settings' allocated storage or the change in free device space. Scan failures are reported rather than treated as a complete scan.

## Cleanup boundaries

- Recommended cleanup covers only archived logs older than seven days: .log.gz, .log.N, and .log.N.gz. This removes historical diagnostics and is not claimed to be side-effect-free.
- Request diagnostics are stored in noBackupFilesDir/request-diagnostics/requests.enc. The encrypted archive retains at most eight active conversations and eight redacted request previews per conversation, and is cleaned up when the conversation is deleted. It is excluded from system backup and is not removed by log cleanup; see CONTEXT_DIAGNOSTICS.md.
- Dependency-cache cleanup is optional: it may increase download/build time and affect offline use. Only old files in allowlisted paths are eligible. Lock files, partial downloads, recent files, Pub global tools, pnpm store, npm npx environment, Go extracted sources, Gradle Wrapper distributions, and Yarn extracted caches are preserved.
- Project cleanup requires project marker files and currently handles old files in root .gradle and .dart_tool directories. It does not infer deletability from directory names such as build, target, dist, or out. Multi-module and custom output directories do not yet have automatic cleanup.
- Install rollback backups and staging are owned by install transactions. Attachments, skills, plugin data, and source packages do not expose misleading cleanup actions.
- Execution uses the concrete file plan from the last scan. Cleanup for one category does not expand into other categories. New or changed files, hard links, and symlink paths are excluded; only ordinary files are deleted and directory structure is retained.
- Before deletion, recheck file identity, size, modification time, link count, and ancestor paths. Failures and skipped files are reported. Unknown IDs, mismatched categories, or missing cleanup plans fail; coroutine cancellation propagates.
- Cleanup is mutually exclusive with commands started through LinuxRuntime. It is rejected while terminal sessions, background services, initialization, or environment installation are active; new commands are blocked during cleanup. Ordinary commands may still run concurrently with one another.
- Scan/delete is not a filesystem transaction and cannot guarantee safety against concurrent writes that bypass the app runtime. The app does not claim to hold a system-wide process lock. Any future cleanup for downloads, plugin installation assets, or user files must integrate with the owning transaction/reference checks rather than widening the path allowlist.

## More effective future storage management

1. **Manage large files by project:** Show source, dependencies, build artifacts, and exports with size sorting and browse/export actions. Only allow artifact cleanup after the build coordinator records the actual output path; Git ignore rules alone are not enough.
2. **Uninstall development environments by version:** Show SDK, NDK, JDK, models, and Runtime with references and versions. Distinguish uninstall from cache cleanup; age alone cannot prove an asset is unused.
3. **Release attachments by conversation:** Delete through repository-aware lifecycle handling and reference checks.
4. **Improve estimates:** Clearly distinguish logical file size from filesystem block usage and Android's reported storage.

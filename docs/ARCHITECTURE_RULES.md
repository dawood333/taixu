# TaiXu — Architecture Rules and AI Coding Guardrails

## 1. Pure model layer

- core/model must remain pure Kotlin. Do not add Android, AndroidX, or Compose dependencies.
- harness/core is also pure Kotlin. It contains the turn interpreter, execution contracts, and storage-independent session projection; it must not depend on Android, network clients, DI, databases, or runtime implementations.
- Tool side effects happen only after intent submission completes. The next turn starts only after result submission completes.
- architectureCheck runs during app:preBuild and blocks platform dependencies in the model layer, illegal horizontal feature dependencies, and direct DAO access from business layers.
- All architecture rules are recorded in the root architecture-policy.json: module dependency allowlist, cycle checks, import denylist, and file-size ratchet. This is the single source of truth. New modules and dependency changes must update it.
- Existing files over 400 lines are listed in .architecture-baseline.json. Their line counts may only decrease; new files must meet the limit. Run architectureBaselineSync after shrinking existing code.

## 2. Persistence and preferences

- Feature, runtime, and harness code may use only repository interfaces exposed by core/database/PersistenceRepositories.kt. Do not inject or call Room DAOs directly.
- Read and write preferences through domain-specific interfaces in core/datastore/PreferenceFacades.kt; do not invent ad-hoc preference keys.

## 3. Material 3 Expressive and UI rules

1. Use shared design-system components. Containers must use RuntimeCard. Do not scatter raw Material 3 Card components or build containers by combining custom border/background modifiers. This keeps blur, refraction, and fine borders adaptive across Material You and liquid-glass themes.
   - Use RuntimeButton, RuntimeFilledTonalButton, RuntimeOutlinedButton, RuntimeTextButton, and RuntimeIconButton for buttons.
   - Use RuntimeAlertDialog for dialogs.
   - Use RuntimeSwitch and the shared linear/circular progress indicators for switches and progress.
2. Dialog text content containing OutlinedTextField or lists must not use fixed dimensions or hard-coded outer offsets. Wrap it in a full-width vertically scrollable container so forms remain accessible when the mobile keyboard opens.
3. Use RuntimeTopBar for top navigation and RuntimeBottomBar for bottom navigation.
4. Message bubbles should use responsive width constraints, such as max 560dp, so tablet and foldable layouts do not look cramped. Single-line text, paths, and labels must use maxLines=1 and ellipsis overflow so they cannot push action buttons off-screen. Horizontal action rows need compact padding and single-line small-label typography for 360dp-wide screens.
5. Inside RuntimeCard, use a suitable MaterialTheme surface container for logs, terminal code, and secondary items. Nested Cards can cause incorrect liquid-glass and shadow layering.

## 4. Security and network policy

- Local models may use HTTP on 127.0.0.1 or localhost and may omit an API key.
- External model groups such as OFFICIAL, CHINA, and AGGREGATOR must use HTTPS.

## 5. PRoot mounts and command execution

- Normalize mount paths through ProotCommandBuilder to prevent shell injection and dangerous traversal into system-root paths.
- Use base only for bounded foreground commands. The default timeout is 10 minutes; users may configure 1–60 minutes, and per-call timeout_seconds may range from 1–3600 seconds.
- Do not simulate daemons with nohup, setsid, or background ampersands inside base. Long-running work must use process(action="start", ...), then status/logs/list/stop.
- External IDs for process use the agent-process: namespace and must not be mixed with Tool Center service processes.

## 6. Mobile Gradle toolchain

- Sandbox Android/Flutter builds use --no-daemon --max-workers=2. Global settings remain org.gradle.daemon=false, org.gradle.parallel=false, and org.gradle.workers.max=2.
- Inject the NDK only through android.ndkPath from the init policy. Build scripts may remove stale ndk.dir values from local.properties, but must not write ndk.dir back or declare a second NDK locator.

## 7. Workspace and plugin safety

- ZIP project import must use WorkspaceManager.importProjectArchive(); never directly extract an untrusted archive. Validate Git HTTP(S) and SSH URLs according to the transport selected by the user.
- Store the selected import type in .taixu-project.properties; do not infer it again solely from file extensions or directory contents.
- If a plugin with the same ID and version already exists, report it as imported; do not recursively overwrite its payload. The installer must ensure $toolDir/bin exists.

## 8. Wireless ADB and PRoot daemon constraints

- The PRoot sandbox has no persistent init/systemd daemon. A background adb fork-server cannot survive across separate commands and is destroyed after each invocation. Complete connect plus operation in one command (the /opt/taixu/bin/adb wrapper connects automatically), or use the host HostBridge through its persistent Kadb connection.
- Logcat collection uses the built-in wireless ADB protocol and HostBridge; it does not require Shizuku or root. For log collection or crash investigation, use host(action="logcat", ...) or logcat-grabber <package> [-P port]. Do not ask users to enable Shizuku/root or perform unnecessary permission troubleshooting.

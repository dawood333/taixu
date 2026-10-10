# TaiXu — Key File Index

Use this index to find the main implementation entry points. For module boundaries, see Architecture; for guardrails, see Architecture Rules.

## Agent Harness

- Agent loop and turn coordination: harness module, especially HarnessLoop and session input controllers.
- Provider resolution and transport: harness/di/harness/ProviderModule.kt and provider transport implementations.
- Tool execution and approval: harness/di/harness/ToolBackendModule.kt, ToolExecutor, ToolBackend, and ToolCheckpoints.
- Request-context diagnostics: harness/.../diagnostics/RequestDiagnosticsInterceptor.kt, RequestDiagnosticsStore.kt, RequestContextDiff.kt, and RequestDiagnosticsArchive.kt.
- Native Responses replay: AssistantText.responsesTurn, ResponsesApi, ApiMessageProjector, and ResponsesRequestBuilder.

## Core data and persistence

- Pure domain models: core/model.
- Room database and repository interfaces: core/database/PersistenceRepositories.kt and related repositories.
- Preferences: core/datastore/PreferenceFacades.kt and related DataStore facades.
- Credential encryption: core/security/SecretManager.kt and ModelCredentialStore.
- Encrypted diagnostics archive: core/database/EncryptedRequestDiagnosticsRepository.kt.

## Linux runtime and terminal

- PRoot command assembly and environment: ProotCommandBuilder and EnvironmentResolver.
- Runtime lifecycle and RootFS: runtime module and RuntimeManager.
- Process registry and background execution: ProcessRegistry, LinuxRuntime, and process execution adapters.
- Native PTY: app/src/main/cpp and NativePtySession/NativePty implementations.
- Terminal session metadata: TerminalSessionManager and the terminal_sessions Room table.
- Host bridge and log collection: HostBridge, EmbeddedAdbManager, logcat-grabber, and taixu-host.

## Workspace and project templates

- Workspace lifecycle and filesystem access: WorkspaceManager and WorkspaceFileService.
- Background builds: WorkspaceBuildTaskCoordinator and WorkspaceBuildRunner.
- Template manifest and variable protocol: project-template module.
- Template specification: PROJECT_TEMPLATE_SPEC.md.
- Plugin package and installer behavior: tools module and PLUGIN_DEVELOPMENT_GUIDELINES.md.

## Settings and UI

- Main settings assembly: feature/settings and SettingsScreen.
- Model profiles: ModelProfilesScreen, ModelEditorScreen, ModelImportDialog, and ModelExportDialog.
- Local backup: LocalBackupDialog, LocalBackupViewModel, LocalBackupService, and BackupArchive.
- Request-context UI: feature/chat/.../RequestContextDialog.kt.
- Developer diagnostics: feature/developer and DeveloperScreen.
- Shared UI components and theme: feature/components and feature/theme.

## Web interface

- Website pages: website/index.html, website/plugins.html, website/templates.html, and website/script.js.
- Web chat application: webchat/src/App.tsx.
- Web chat composer and tool cards: webchat/src/components/ChatPanel.tsx.
- Web chat workspace pane: webchat/src/components/ContextPane.tsx.
- Conversation list: webchat/src/components/ConversationSidebar.tsx.
- Remote terminal pane: webchat/src/components/TerminalPane.tsx.
- Pairing/login screen: webchat/src/components/LoginView.tsx.

## Build and validation

- Module dependency policy: architecture-policy.json.
- Existing file-size baseline: .architecture-baseline.json.
- Main build configuration: app/build.gradle.kts and root Gradle configuration.
- Build/test commands: COMMANDS.md.
- Current known limitations: KNOWN_ISSUES.md.

# TaiXu — System Architecture and Module Topology

## 1. Product scope and technology stack

TaiXu runs a full Linux userspace on Android without root using PRoot. It integrates an AI Agent Harness, native PTY terminal, workspace file management, and a Material 3 Expressive UI.

- Main Android build: Gradle 9.7.0, AGP 9.3.1, Kotlin 2.4.10, compileSdk 37.1, targetSdk 37, NDK 30.0.15729638.
- UI: Kotlin and Jetpack Compose, with Material 3 Expressive, dynamic surfaces, haptic feedback, and adaptive dual-pane layouts.
- Dependency injection: Koin 4.2.2 using explicit Kotlin DSL.
- Async/state: Kotlin Coroutines, StateFlow, SharedFlow.
- Local storage: Jetpack DataStore Preferences and Room/SQLite.
- Native PTY: JNI C/C++ under app/src/main/cpp, packaged as an ARM64 native library.
- Linux runtime: bundled PRoot starts a Debian root filesystem. Host storage is mapped using PRoot bindings such as Download, Documents, and /sdcard.
- Agent protocols: OpenAI Chat Completions with SSE streaming, reasoning_content, and tool_calls. MCP and subagent tools are extensible.
- Built-in tools: read, write, edit, base, and process. base runs bounded foreground commands; process manages long-running commands through the runtime process registry.
- Local models: llama.cpp and Ollama can run inside the sandbox without an API key.
- Sandbox Android toolchain: Gradle 8.14.2, AGP 8.11.1, Kotlin 2.2.20, compileSdk/targetSdk 34, NDK 29.0.14206865. This is for Android/Flutter APK builds inside the ARM64 sandbox, not for building TaiXu itself.

## 2. Module topology

- app: MainActivity, Koin setup, JNI C code, foreground services.
- core/model: pure Kotlin data models with no Android SDK dependency.
- core/common: coroutine dispatchers, logging, shared utilities.
- core/database: Room persistence for Harness conversations, sessions, and tool history.
- core/datastore: preferences, storage mounts, active model, and command timeouts.
- core/network: OkHttp, SSE parsing, and timeout policies.
- core/security: local API-key encryption.
- runtime: PRoot, Linux sandbox, process registry, PTY, workspace import/export, and builds.
- project-template: template manifests, variable forms, import/export, materialization, and built-in templates.
- harness: Agent loop, streaming reasoning, tools, approvals, and MCP.
- harness/core: pure Kotlin turn engine and model-capability contracts.
- tools: Registry, local plugins, install transactions, toolchains, and provider security.
- feature/components and feature/theme: shared UI components and theme configuration.
- feature/home: Linux status and live memory/disk/process metrics.
- feature/chat: Agent chat, task plans, and wide-screen layout.
- feature/terminal: terminal UI and haptic key row; session and VT100 state lives in runtime.
- feature/workspace: projects, ZIP/GitHub import/export, code browsing, and background builds.
- feature/workflow: workflow catalog, DAG canvas, execution timeline, and approval UI.
- feature/settings: model profiles, timeouts, tools/plugins, mounts, appearance, and diagnostics.
- feature/developer: native sandbox and diagnostics.
- feature/onboarding: first-run setup and RootFS readiness.
- feature/custom_iteration: AI-assisted app modification.
- feature/navigation: top-level routes and NavHost.

## 3. Cross-module rules

1. Dependency flow is one-way: feature modules may depend on core, runtime, harness, and tools. Feature modules must not depend horizontally on each other except for shared components and theme; navigation assembles screens and routes.
2. Feature, runtime, and harness code must use repository interfaces exposed by core/database/PersistenceRepositories.kt. Do not inject Room DAOs directly. Preferences go through core/datastore/PreferenceFacades.kt.
3. core/model and harness/core must remain pure Kotlin. architectureCheck runs during app:preBuild to block platform dependencies, illegal feature dependencies, and direct DAO access.
4. SessionProjectionBuilder handles storage-independent context. Android's SessionContextProjector captures leaves, reads branch windows, and decodes content. CompactionManager.inspect provides source/diagnostic details; project builds context from the same result. Inspection must not create or move a lane.
5. SessionControl and InteractiveSessionControl are implemented by the same HarnessLoop singleton. SessionInputController validates and records work under a session lock before submitting input. Web REST waits for a receipt; results flow through state updates. Subtasks keep independent lane scheduling.
6. Short foreground commands use LinuxRuntime.execute or Harness base. Long-running work uses LinuxRuntime.startBackground or Harness process, managed by ProcessRegistry for PID, lifecycle, and ring-buffer logs.
7. Project lifecycle and filesystem access go through WorkspaceManager and WorkspaceFileService. ZIP imports must defend against Zip Slip and validate file-size and entry-count limits; Git clone runs in the sandbox.
8. Workspace builds are owned by WorkspaceBuildTaskCoordinator so rotation/recomposition does not interrupt them. WorkspaceBuildRunner handles execution and environment preflight.

## 4. Koin dependency wiring

- ProviderModule wires ProviderModelResolver, ProviderTransport, and LlmApiRegistry. ProviderClient remains as a compatibility facade; ModelDescriptor contains no keys or endpoints.
- ToolBackendModule wires the pure-JVM ToolBackend and ToolCheckpoints contracts. WorkspaceToolBackend uses restricted workspace operations and write snapshots. Checkpoints may veto or append redacted explanations, but cannot grant permissions or replace result status.
- Each module declares constructors and bindings in its KoinModule.kt. app/di/TaiXuModules.kt assembles the full graph and registers application Context without allowing overrides.
- Business classes use ordinary constructors. Android-created Application, Activity, Service, Receiver, and Compose ViewModel entry points access Koin. Update registrations when adding constructor dependencies and run app unit tests.
- Use single for process singletons, factory for unscoped objects, and viewModel for ViewModels; preserve Navigation3 ViewModelStoreOwner ownership.
- Kotlin Lazy keeps runtime, repositories, and MCP servers deferred. Subagents use a provider function to break dependency cycles. ToolExecutor preferences are wired explicitly.
- Workflow executors and tool-adapter collections use different qualifiers to avoid Set generic-erasure conflicts.
- WorkManager uses Configuration.Provider for on-demand initialization and KoinWorkerFactory to create Workers.
- Use classic Koin DSL, not the Koin compiler plugin. KSP is reserved for Room. Kotlin compilation alone does not prove the DI graph is valid; graph tests do.

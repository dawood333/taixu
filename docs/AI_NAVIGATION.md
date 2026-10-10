# TaiXu / Linux AI Runtime — AI Navigation Overview

This document is a lightweight navigation hub for AI coding assistants (Antigravity, Cursor, Claude, GPT, Copilot). Detailed architecture, call paths, file indexes, guardrails, and commands are split into focused documents; follow the links below as needed.

## Core documents

- [Architecture and modules](ARCHITECTURE.md): technology stack, module responsibilities, dependency boundaries, and Koin wiring.
- [Architecture rules](ARCHITECTURE_RULES.md): dependency policy, UI design rules, security, PRoot execution, toolchain, workspace, and plugin guardrails.
- [File index](FILE_INDEX.md): quick lookup for important files and classes.
- [Execution traces](EXECUTION_TRACES.md): Agent Harness call flow and tool execution timelines.
- [Commands](COMMANDS.md): build, test, deploy, and debugging commands.
- [Known issues](KNOWN_ISSUES.md): implementation status and remaining acceptance work.
- [Security surface](SECURITY_SURFACE.md): permissions, credential handling, and trust boundaries.
- [Plugin development guidelines](PLUGIN_DEVELOPMENT_GUIDELINES.md): local packages, manifests, install scripts, and release checks.
- [Project template specification](PROJECT_TEMPLATE_SPEC.md): portable template format, variables, hooks, and validation.
- [Roadmap](ROADMAP.md): completed work, next priorities, and current limitations.

## Project at a glance

TaiXu is an Android no-root Linux runtime built on PRoot. It combines a Linux userspace, an Agent Harness, a native PTY terminal, project workspaces, a managed tool ecosystem, and persistent visual workflows.

## Important engineering principles

1. Keep `:core:model` and `:harness:core` pure Kotlin; do not introduce Android, network, DI, database, or runtime dependencies.
2. Keep dependency direction one-way. Feature modules must not depend horizontally on each other.
3. Access Room through repository interfaces; do not inject DAOs directly into feature, runtime, or harness code.
4. Use the shared Material 3 Expressive components and respect responsive/IME-safe layout rules.
5. Use bounded foreground commands for short tasks and the process registry for long-running commands.
6. Treat PRoot as a compatibility runtime, not a security boundary against malicious code.
7. Validate ZIP paths, package sizes, signatures/digests, and plugin scripts; never claim a transaction rolled back global files it does not snapshot.
8. Run architecture checks and tests after changing module dependencies or dependency-injection registrations.

## Quick entry points

- Main app assembly: `app/`
- Shared models and persistence: `core/`
- Linux runtime and PTY: `runtime/`
- Agent orchestration: `harness/`
- Tool registry and installers: `tools/`
- Android UI: `feature/`
- Website and web chat: `website/` and `webchat/`

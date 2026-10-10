# TaiXu — Roadmap

> In brief: use **Debian** as the runtime, manage **OpenClaw/Hermes/Codex** as tools, treat **Android** as the product layer, and let a built-in **Agent** (similar to pi) drive everything through natural language.

## Architecture (multi-module)

```
:app                Assembly layer: Application / MainActivity / DI / foreground services / resources
:feature:nav        Navigation across screens
:feature:{home,chat,terminal,workspace,settings,developer,components,theme}
:harness            Agent: HarnessLoop / ProviderClient (streaming) / ToolExecutor (read/write/edit/base)
:tools              ToolManager / installers (core/tools + runtime/tools)
:runtime            PRoot / PTY / rootfs / shell
:core:{common,model,database,datastore,network,security}
```

## Completed

- **Linux sandbox:** Debian 13 initialized on a physical device; PRoot works and `cat /etc/os-release` has been verified on-device.
- **Terminal:** JNI forkpty (same approach as Termux), multiple sessions, Room-persisted session metadata, session list/rename, and a shortcut-key row.
- **Agent:** Four-tool loop, streaming LLM output, model management via Room, and conversation-to-workspace association.
- **Tool system:** Tool Center install/verify/update/uninstall/rollback, shared dependency reference counting, and an Ed25519-signed registry.
- **Engineering quality:** Multi-module architecture, unit tests, and Room migrations 5→11.

## Next (priority order)

1. **Physical-device acceptance:** Once a device is connected, install the latest APK and complete install → launch → health check for at least one of Codex, OpenClaw, or Hermes.
2. **Terminal polish:** Touch scrolling through history and direct delivery of composed text from Chinese IMEs.
3. **Stability:** Restore sessions and install tasks after process death; support long-running agents through foreground-service notifications.
4. **Compliance:** Review third-party licenses and store-distribution policies, especially the implications of dynamically downloading executable code.

## Current limitations

- The terminal input layer intercepts LazyColumn touch scrolling; composed input from Chinese IMEs is not fully passed through.
- No signed remote Registry address is preconfigured; the app uses the manifest bundled in the APK by default.
- API keys use encrypted storage, and logs/storage are redacted end-to-end.

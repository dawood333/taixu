# Workflow: Multi-Step Planning and Progress Tracking

## When a plan is required

For any of the following task types, the first tool call must create a plan using `plan(action="replace_active", goal=..., steps=[{"id":"1","title":"...","status":"in_progress"}, ...])`:

1. **Complex development and implementation:** implementing a feature, writing a crawler, rewriting a module, or integrating systems.
2. **Multi-step or cross-file work:** expected to require more than 3 tool calls or modify more than 2 files.
3. **Troubleshooting and build investigation:** diagnosing compilation failures, runtime crashes, or reverse-engineering issues.
4. **Service installation and environment setup:** work involving multiple stages of commands and verification.
5. **Host automation sequences:** multi-step Android actions such as opening an app → waiting for it to become foreground → observing/tapping the screen → sending a broadcast or changing settings. Prefer reusable visual workflows or `host` tools, and note privilege prerequisites in the plan.

*Simple one- or two-step queries (such as reading a file to check its version or running one inspection command) do not need a plan.*

## Standard lifecycle of the planning board

```text
[Start] ──► plan(replace_active) ──► Render task card in UI
               │
               ▼
[Execute] ──► Call concrete tools (read/write/base/mcp/host/subagent)
               │
               ├─► Step complete ──► plan(advance, steps=[...])
               ├─► Change approach/add steps ──► plan(replace_active, steps=[...])
               └─► Blocker encountered ──► Pause, reflect, update plan, explore alternatives
               │
               ▼
[Finish] ──► Verify actual results ──► plan(clear_active) ──► Summarize delivery
```

## Host capability notes (Shizuku / Root)

- For opening apps, sending broadcasts, changing system settings, and GUI taps/swipes, prefer `host(action=...)`. When the sequence should be reusable, guide the user to a visual workflow using `HOST_ACTION` / `CONDITION_BRANCH`.
- Before privileged actions, call `host(action="status")` to confirm the mode. If authorization is missing, stop and explain; do not retry blindly.
- Explain risks before destructive operations (clearing data, freezing apps, changing security settings), and ask the user for confirmation when necessary.

## Prohibited behavior

- Do not blindly and chaotically call tools for complex tasks without a plan.
- Do not leave the task board out of sync after completing a step.
- After an error, do not ignore the plan and blindly retry the same call.

# TaiXu Workflows

A workflow is a persistent, pure-Kotlin directed acyclic graph (DAG) scheduled by the Harness. The Compose feature layer provides workflow browsing, launch controls, execution timelines, and human approvals. Definitions do not depend on the Android SDK and can be validated independently.

## Entry points

- Use the Workflow entry in the workspace screen or Run Workflow in a project-card menu.
- Enter /wf in the Agent Hub composer to open the current project's workflow catalog. Enter /wf <workflow-id> to open a specific workflow's confirmation form.
- After a workspace build fails or an APK is produced, event-driven suggestion chips can launch a relevant workflow with the build error or APK path.

## Virtual-screen actions and recording

The catalog includes a virtual-screen action template. HOST_ACTION actions include creating a screen, opening an app, tapping, double-tapping, long-pressing, swiping, appending/replacing text, key presses, waiting, and closing the screen.

- Each action has an after-action wait in milliseconds. Gesture duration_ms and wait_ms are separate. Coordinates use a 0–1000 scale, not screenshot pixels. A DELAY node can use seconds or milliseconds.
- Parameters support ${VAR}. Text input supports multiple lines and preserves surrounding whitespace. Replace-text sends select-all/paste key events; empty text can clear a field. Verify that the target control accepts the operation.
- A user can ask the Agent to operate the phone and record the action sequence as a named workflow. HOST virtual_screen_task saves the sequence only when the model reports completion, no steps failed, and no human intervention occurred.
- Recording should begin by opening the app so replay on a fresh screen has a visible app. Recorded workflows retain fixed steps and after-action waits, not model-request latency. Recorded text becomes editable runtime variables such as TEXT_1.
- Edit and reuse saved workflows under Workspace → Workflows. Steps connect through success ports and stop on failure by default. Replay runs fixed actions without invoking a model or automatically recognizing page changes. Verify the app entry point, login state, and layout before replay; issuing an action does not prove the business operation succeeded.
- If session is empty, each run uses a separate session; steps within one run share a screen. The floating control pauses or stops the whole workflow. After human intervention, inspect the screen before restarting. Steps operating on the same screen cannot run in parallel.
- Phone-model requests use max_tokens=2048. With Agent logs enabled, the PhoneAgent tag records request budget, steps, image dimensions, duration, token usage, and input results. It records input length rather than full input and does not log API keys or full model responses.

## Visual editor

- Create a custom workflow or make an editable copy of a built-in workflow.
- Drag nodes with grid snapping. Nodes and edges share the same canvas transform.
- Add, delete, and select nodes; edit titles, descriptions, timeouts, failure policy, and JSON string key/value configuration. Multiline prompts are stored with newline escapes. Invalid JSON cannot be applied and is not silently discarded.
- Automatic layout uses longest-path layering and barycenter ordering to reduce crossings. Layout changes participate in undo/redo.
- Start an edge from a node and choose its destination. Duplicate, self, and cyclic connections are rejected.
- Edges may use any output, success, or failure port and support conditions such as exitCode == 0, output contains text, or a regular expression. The editor and scheduler share the same matching rules.
- Supports deleting edges, up to 60 undo/redo steps, confirmation before leaving with unsaved changes, and Room persistence.
- Agent reasoning and subagent-delegation nodes support prompt variables, waiting, timeouts, and structured cancellation.

## Scheduling semantics

- All ready nodes in a wave run concurrently. Downstream nodes wait for dependencies to reach a terminal state.
- Conditional edges activate only matching branches. Unselected branches propagate as SKIPPED so merge nodes do not wait forever.
- Nodes support timeouts, one automatic retry, retry after user confirmation, and cancellation.
- Timeout is a failure (exit code 124), not user cancellation. Exhausted retries or rejected retry abort the workflow; only explicit CONTINUE proceeds.
- Parallel approval requests are displayed in arrival order. Closing or cancelling one request does not overwrite another node's approval.
- Workflow definitions are validated before saving, including duplicate IDs, dangling edges, invalid timeouts, and cycles.
- Full run-state history is stored in Room. Editing a definition or reseeding built-ins does not delete history; each history entry retains a snapshot of the definition used. Saving an old run does not overwrite a newer edit.
- The catalog can show the latest five runs and rerun one using its original definition, variables, and workspace. Rerun requires an explicit user action.

## Runtime UI

- The phone timeline shows status, elapsed time, progress, exit code, errors, and artifact paths.
- If display-over-other-apps permission is granted, an overlay shows the current node/step, result, and stop action. It is temporarily hidden before screen sensing and clicking to prevent UIAutomator from reading HUD elements.
- Logs and diffs can be expanded, scrolled, selected, and copied. Command logs refresh about every 300 ms and keep a streaming buffer of roughly two million characters.
- Wide screens show the canvas and a right-side detail panel with node logs and results.
- Before a run, the UI displays required variables and optional defaults. For workflows with Agent nodes, users can choose a configured model for that run via WORKFLOW_MODEL_ID / WORKFLOW_MODEL_VARIANT without changing the global default. Approval dialogs show upstream results.

## Built-in workflows

Examples include release build → confirmation → APK install; confirmation to install a newly generated APK; diagnosis after a failed build; read-only Agent diagnosis followed by an approved minimal fix and rebuild; APK unpack/decompile and evidence-based audit; local Git diff review and commit suggestion without pushing; host automation with settings and screen sensing; broadcast/system-settings demonstrations; and a GUI pilot that checks privilege, requests confirmation, opens QQ, and performs a local sense → model JSON decision → click loop.

## Node types and host actions

Executable node types include triggers, end nodes, Bash, managed processes, Agent reasoning, subagent delegation, TaiXu builds, host actions, condition evaluation, delays, variable assignment, and human approval.

Host actions are grouped in the editor. Items marked privileged require Shizuku or Root to be enabled and authorized:

- Diagnostics: permission state, bridge health, device snapshot, logcat.
- Apps: launch, force-stop, clear data, freeze/unfreeze, grant/revoke permissions, wait for foreground.
- Intents: send broadcasts or start Activity/Service with extras such as key=value, key:int=1, or flag:bool=true.
- System: settings get/put, airplane mode, Wi-Fi, media volume.
- GUI: screen sensing, tap/double-tap/long-press/swipe/scroll/key/paste. HostGuiToolkit prefers accessibility gestures when Shizuku/Root is available, then falls back to cmd input or bin input. Chinese text is entered by clipboard paste.
- Interaction: toast, vibration, clipboard, notifications.
- Advanced: privileged shell and cmd service calls. Unknown actions may fall back to a custom command.

Broadcasts and Activity launches use application Context first. If that fails, or forced-shell mode is selected, the workflow uses am broadcast or am start.

CONDITION_BRANCH evaluates expressions including exitCode equality/inequality, output contains/matches, variable and numeric comparisons, empty/notEmpty, and && / || combinations. A true condition sets exitCode=0 and CONDITION_RESULT=true; false sets exitCode=1. DELAY waits for a configured duration. SET_VARIABLE accepts multiline KEY=value entries.

Agent nodes use a persistent Harness session and lane per run/node and do not switch the Agent Hub's current conversation. Prompts support workspace, variable, node-output, and previous-output substitutions; previous output merges only activated direct predecessors in edge order. Subagent nodes can declare a role or a department plus agentQuery, and write permissions are constrained by comma-separated writePaths.

Proactive suggestions currently handle build-failure and APK-produced events only. They suggest workflows but do not execute them automatically. Git dirty-state and FileWatch integration remain extension points; background listeners are not enabled.

## Background execution and schedules

- WorkflowRunManager is a process-level singleton, so leaving the run screen or destroying its ViewModel does not cancel the workflow. Multiple runs can execute concurrently, and the UI can switch its focus using viewRun(executionId).
- A RUNNING history row is written at start. Progress breadcrumbs are persisted about every three seconds and a final snapshot is saved at termination. History records triggerSource (MANUAL/SCHEDULE) and scheduleId.
- On startup, interrupted non-terminal rows are reconciled as CANCELLED with an explanation that the process was terminated by the system. Node states and logs are retained; users may rerun manually. Checkpoint resume is not supported.
- While workflows run, WorkflowForegroundService uses a dataSync foreground service plus WakeLock/WifiLock and shows per-run progress notifications. On Android 15+, after the dataSync six-hour limit the service detaches from foreground mode while the workflow may continue.
- Pending approvals generate high-importance notifications with Approve/Reject actions. Opening the notification deep-links to the run screen.
- workflow_schedules and WorkManager support daily HH:mm schedules, intervals (minimum 15 minutes), and one-time delayed runs. Scheduled runs can start after process death or device restart. If the sandbox is not ready, the worker waits briefly; it does not automatically download a missing RootFS and records failure instead. Doze may delay daily/interval work by minutes.
- Harness sessions prefixed with workflow:<executionId>:<nodeId> are excluded from generic interrupted-session recovery to avoid replaying side effects. Rejecting a later approval does not automatically roll back earlier changes.

## Validation

Automated tests cover DAG branching/concurrency, timeout recovery, immediate cancellation, retry exhaustion, parallel approvals, direct-upstream parameter passing, stable layout, and Room history retention. A real end-to-end run still requires a working Linux toolchain, model account, and install permissions on the target device. Passing compilation or unit tests does not prove the complete model/build/install workflow works on a physical device.

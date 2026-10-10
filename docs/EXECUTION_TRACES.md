# TaiXu — Core Call Paths and Execution Traces

## 1. Agent request and tool loop

A typical turn follows this sequence:

1. Validate the input and acquire the session lock.
2. Record the task and submit the user input.
3. Build protocol-specific context from conversation history, active workspace, memory, skills, and tool definitions.
4. Send the request through the selected provider transport.
5. Stream text/reasoning updates to the UI and accumulate tool-call arguments by index.
6. If the model requests tools, validate arguments and apply approval policy.
7. Execute approved tools, capture bounded output, and submit tool results to the model.
8. Continue the loop until the model produces a final response, the task is cancelled, or a bounded failure occurs.
9. Persist the final state and notify the caller through the relevant state flow or REST receipt.

The loop must not start the next turn before the previous tool-result submission has completed. Tool side effects must not run before intent submission has been accepted.

## 2. Session control and concurrency

- SessionControl and InteractiveSessionControl provide entry points for interactive and external callers.
- HarnessLoop is the shared process-level implementation.
- SessionInputController validates, records, and queues input under the session lock.
- Web REST callers wait for the acceptance receipt; execution progress and results are reported separately through state updates.
- Startup recovery retains enough concrete task/session information to restore or clean up interrupted work.
- Subtasks and subagents keep independent lane scheduling while sharing the appropriate session context.

## 3. Context projection and compaction

- SessionProjectionBuilder computes storage-independent context contributions.
- The Android SessionContextProjector captures active leaves, reads branch windows, and decodes message content.
- CompactionManager.inspect exposes source and diagnostic details; project derives the model context from the same result.
- Compacted queries should avoid loading blobs for collapsed messages. Inspection must not create or move a lane.
- Context previews and diagnostics are observations of the request being constructed; they do not prove the request was sent or accepted by the remote provider.

## 4. Tool execution boundaries

- Short foreground commands use LinuxRuntime.execute or the Harness base tool and must have bounded execution and a clear exit status.
- Long-running commands use LinuxRuntime.startBackground or the Harness process tool.
- ProcessRegistry owns process IDs, lifecycle, and bounded ring-buffer logs.
- Do not simulate daemons with background shell operators in a bounded foreground command.
- Workspace changes should go through WorkspaceManager and WorkspaceFileService. Archive imports require path-traversal checks, entry-count limits, and file-size limits.

## 5. Provider transport and streaming

- ProviderModelResolver resolves the model profile, credentials, and preferences.
- ProviderTransport applies request policy and handles network transport.
- LlmApiRegistry selects the protocol adapter for the model: Chat Completions, Responses, or Anthropic Messages.
- Streaming SSE parsing accumulates text and reasoning increments. Tool-call argument fragments are combined by tool index before validation.
- Retries create new request diagnostics captures. Request construction is not equivalent to successful network delivery.

## 6. Dependency injection and checkpoints

- Koin modules bind provider, tool, runtime, and feature interfaces.
- ToolBackend and ToolCheckpoints define pure JVM contracts; WorkspaceToolBackend performs restricted workspace operations and tracks write snapshots.
- Checkpoints may reject an operation or append a redacted explanation. They cannot grant permissions or rewrite the result state.
- Keep dependency direction one-way and test the DI graph after changing constructor dependencies.

## 7. Failure and cancellation behavior

- Cancellation must stop future work and clean up resources owned by the operation.
- Install transactions restore the tool directory snapshot when possible; they do not automatically roll back unrelated global paths.
- RootFS update uses staging and a previous-version fallback.
- Diagnostic capture must not block normal network requests. Failures in diagnostics should be reported separately and must not prevent the model request.

# Native Responses History Replay

Manual context for the Responses API must preserve the model's complete output items. Ordinary message text and reasoning summaries cannot replace the original reasoning items, and the `phase` in original messages also affects how the model understands intermediate progress and final answers. Protocol reference: [OpenAI conversation state](https://developers.openai.com/api/docs/guides/conversation-state).

TaiXu stores the complete Responses `output` in `AssistantText.responsesTurn`, including item IDs, function-call IDs, message phase, and opaque `encrypted_content`. It is stored in the existing Room `payloadJson` without a database-schema migration. Older messages without this field are read as ordinary history. Turns containing only tool calls also save an empty-body assistant entry as an anchor for the native output.

`ResponsesApi` obtains output from a non-streaming final response or SSE `response.completed`. If a gateway omits `output` from the completion event, the client can use `output_item.done` events with continuous output indexes. Partial encrypted content from `output_item.added` is not stored. A streaming interruption without a completion event preserves only text already received. Gateways that provide text or tool calls only in the completion event are also supported, and existing text deltas are not sent to the UI twice.

The main conversation and subagents use the same replay validation through `ApiMessageProjector`, then build input through `ResponsesRequestBuilder`. The full native output group is replayed only when all of the following conditions hold:

- Responses mode is enabled and the provider, endpoint, model, and credential scope are the same. Credential scope stores only SHA-256 digests of the API key and custom request headers, never plaintext credentials.
- The saved native text matches the current projected text, function names and JSON arguments match, and function-call IDs are valid and unique.
- Every tool call has a completed tool result. Missing, approval-pending, or context-trimmed calls prevent replay of the entire native output group.
- Output types are reasoning, message, or function_call, with no incomplete output items. Unsupported managed-tool output falls back to ordinary history.
- Native item IDs and call IDs do not repeat IDs from earlier native turns in the same request.

Local tool-call IDs may differ from the provider's `call_id` after normalization. When building a request, the client matches by name and arguments and maps the corresponding tool result back to the original `call_id`. This mapping applies only to the current assistant turn. Tool results may arrive in a different order.

If validation fails, ordinary text, function calls, and results are preserved without fabricating reasoning items. Editing a reply, switching models or credentials, partial tool batches, interruption, and context trimming can trigger this fallback. Chat Completions and other protocols do not send this native supplemental data, and JSON-text tool mode does not use it.

Each native output is limited to 256,000 UTF-8 bytes; larger outputs are not stored. Context estimation includes the native payload in token and request-body budgets. Text and arguments may be estimated more than once to keep the budget conservative. If the request body is still too large, native supplemental data can be omitted while ordinary history is retained; the original database record is unchanged. Encrypted content is never shown in chat or request diagnostics; diagnostic previews continue to omit opaque fields such as `encrypted_content` and `signature`.

Regression coverage includes native fields and output order, normalized IDs and out-of-order tool results, legacy-message compatibility, reopening Room, main/subagent consistency, edit/switch fallback, missing or approval-pending results, completion-event text, discarded partial encrypted content, EOF/index gaps, and payload-size budgets. Tests use a local MockWebServer and Robolectric and do not call a real model service.

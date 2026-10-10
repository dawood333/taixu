# Request Context Diagnostics

In the chat composer, tap the context ring, then select **Recent Request Context** to inspect the last eight requests constructed for the current conversation. Records can persist across app runs. Search fields or content, expand fields, select preview text, and copy it.

## Capture scope

- The context-usage panel remains a live estimate. Request diagnostics capture the JSON request body after protocol conversion and before the HTTP call.
- Diagnostics are connected to model turns in the main-conversation Harness. Supported protocols are Chat Completions, Responses, and Anthropic Messages. Compaction-summary requests and subagent requests are not currently captured.
- The preview includes the system prompt, messages/tool calls and results, tool definitions, and request parameters actually constructed for the protocol. Injected memory and skills appear at their actual positions in the request; separate tracing of injection sources or compaction reasons is not currently provided.
- Every compatibility retry or model retry is captured again. The UI shows the turn, request sequence within that turn, timestamp, protocol entry point, and raw request-body byte count.
- Constructing a request does not mean it was successfully sent or received by the server. Preview byte count is not the same as token count.

## Request differences

- When a request is selected, compare it with the previous capture and show counts of added, removed, changed, and unchanged fields. System prompts, tool definitions, model configuration, and messages are compared using the actual protocol fields.
- Expand a changed field to view both the previous and current previews; either can be selected and copied. Truncated portions are not displayed.
- Messages are matched by field position. Inserting or removing a message can make later positions appear changed; this is not matching by conversation-tree message identity.
- Each field receives a SHA-256 fingerprint after redaction and removal of media/opaque data, but before display truncation. Fields with tail changes or exhausted display budgets can still be identified. Fingerprints do not prove that raw request bytes are identical, that a cache was hit, or that the server received a request.
- At most 128 fields are captured. If this limit is exceeded, the UI explicitly marks the comparison as incomplete. Changes to redacted credentials, media, and opaque data are excluded from comparison.

## Retention and redaction

- Keep at most the eight most recently active conversations, with the last eight requests per conversation across app runs. Redacted previews and field fingerprints are encrypted with AndroidKeyStore and atomically stored in the app-private `noBackupFilesDir/request-diagnostics/requests.enc`, excluded from system backup, then loaded asynchronously after restart.
- Request capture does not wait for disk I/O. The save queue coalesces updates into the latest bounded snapshot. The UI clearly shows loading, saving, success, or error states. If the process is killed before a save completes, the latest request may be missing. Read failures do not expose exceptions or ciphertext to users; a later successful save can rebuild the archive.
- When deleting a conversation, archive deletion for that conversation must finish before conversation data is deleted. Failure reports an error and can be retried. New requests captured during restart loading are merged with history; deletion markers take precedence, and late callbacks for deleted conversations cannot re-add snapshots.
- The archive is versioned. Reads validate conversation, request, field, and preview limits. The plaintext serialization limit is 32 MiB and the encrypted file limit is 44 MiB. There is no plaintext-on-disk fallback, and raw request bodies are not saved; the local archive cannot replay a model request byte-for-byte.
- Each preview is limited to 64,000 characters and 128 fields, with at most 8,000 characters per field. Truncation is explicitly indicated and does not alter the request sent to the model.
- The existing `SensitiveDataRedactor` is used on the full preview before truncation to redact credentials echoed across messages in the same request. Known credentials from sensitive request headers are also removed from body text.
- Request headers are not included in snapshots. Inline media, opaque reasoning data, and signatures are not displayed. Redaction is not anonymization; ordinary conversation and file contents can still appear in previews.
- Capture handles only repeatable request bodies no larger than 24 MB. Diagnostic errors do not block network requests.

## Implementation entry points

- `harness/.../diagnostics/RequestDiagnosticsInterceptor.kt`: reads the final request body without modifying it.
- `harness/.../diagnostics/RequestDiagnosticsStore.kt`: redaction, field projection, preview budgets, and conversation isolation.
- `harness/.../diagnostics/RequestContextDiff.kt`: field-fingerprint comparison and capture completeness.
- `harness/.../diagnostics/RequestDiagnosticsArchive.kt`: single-writer archive, restart merge, versioning, and capacity validation.
- `core/database/.../EncryptedRequestDiagnosticsRepository.kt`: bounded reads, encryption, and atomic archive writes.
- `feature/chat/.../RequestContextDialog.kt`: search, expansion, and text selection.

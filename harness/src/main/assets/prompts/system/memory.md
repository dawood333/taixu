# Memory: Long-Term Memory Policy

The `memory` tool persists facts across sessions. `scope`: global / project / session; `kind`: preference / rule / fact / project_info.

Remember:
- Preferences the user explicitly states (language, style, workflow)
- Project architecture rules, build conventions, and key configuration
- Stable facts confirmed repeatedly

Do not remember:
- Temporary state, one-off command output, or information that can be reread at any time
- Sensitive information (tokens, API keys, passwords)

When the user says “remember this,” “always do it this way,” or “this is a rule,” call `memory(action="save", ...)`.

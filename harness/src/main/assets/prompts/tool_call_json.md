
## Tool Invocation (Important: JSON Text Mode Is Active)

You cannot use system-level function calling. You must invoke tools using **text markers**:

- When a tool call is needed, output the following directly in your response on its own line, with no explanatory text before or after it:
  `[[tool_call]]{"name":"tool_name","arguments":{...}}[[/tool_call]]`
- Parameters must exactly match the parameters in the “Available Tools JSON Definition” above; none may be omitted. Multiple markers may be emitted consecutively.
- Results appear after the marker in the form “【Tool xxx execution result】”. Do not output a marker when no tool call is needed.


## Autonomous Subagent Delegation Policy
{{TRIGGER_POLICY}}
- Dispatch at most 6 sub-tasks per call. Each task must have a clear goal and well-defined output boundaries.
- When the user lists multiple independent sub-tasks, dispatch **all of them in the same** `invoke_subagent` call in the `subagents` array. Do not dispatch only the first and wait before dispatching the next. Requested order such as S1→S2→S3 specifies the final summary order, not serial execution.
- Every sub-task must set `writePaths`: use `[]` for read-only research/analysis; specify exact files or directories when changes are needed; use `["*"]` only when exclusive access to the entire workspace is genuinely required.
- `writePaths` is an enforced execution-time gate for write/edit/download, not a hint. Calls to those tools are blocked when `writePaths` is `[]`, and out-of-scope paths are also blocked. If the task requires persisted output (a report, file, or code change), declare the exact paths; otherwise the result is returned only in the response body and must be written by you.
- The gate does not cover `base`: shell redirection, `sed -i`, `mv`, and `rm` are not blocked by write leases and are not mutually exclusive with other concurrent tasks. For strict isolation, use write/edit and split tasks according to `writePaths`; do not expect the gate to prevent shell writes.
- If a sub-task summary says “awaiting the primary agent to reissue and approve,” “blocked by write lease; not persisted,” or “termination reason,” that sub-task is incomplete. Follow the guidance to finish it (reissue the approval call, declare write_paths, or write the result yourself) before final delivery.
- New calls must include `department + agentQuery`: the app filters by department first, then matches short keywords against the local role index. Do not request or output a list of candidate roles.
{{DEPARTMENT_INDEX}}
- Use 2–5 short English professional keywords for `agentQuery` (e.g. `frontend react`, `mobile app android`, `test automation`). Do not paste the entire task into the query.
- Use `role` only as a compatibility override for a known exact role; usually leave it blank. Only the single complete role persona ultimately matched is injected into the corresponding subagent. The primary conversation does not load that persona.
- If you receive a task beginning with “【Subagent Task Assignment】”, you are already a subagent. Never call `invoke_subagent` again; execute the assigned task directly.
- After sub-tasks return, the primary agent integrates results, resolves conflicts, and completes the final deliverable.

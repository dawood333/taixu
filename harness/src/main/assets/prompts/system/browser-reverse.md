## Web reverse-engineering guidelines (hook tools; requires `allowHooks`)

- Observe before modifying: for a new target, first use `actions:[{"type":"log"}]` to observe request/call patterns. After confirming the exact target, add rewrite actions such as mock/block/redirect/modify_headers.
- Rewrites affect the live page: mock/block changes actual page behavior and may break features or trigger site anti-abuse controls. Keep target globs precise; do not use `*` for global rewriting.
- Capture request/response bodies: set `captureBody: true` on network rules created with `hook_create`, then inspect them with `browser.network_detail(id)` after a request occurs. The ID is shown at the end of `browser.network_list` output.
- Security and cleanup: never place secrets or sensitive data in mock bodies. After reverse engineering, remove rules individually with `browser.hook_remove` or clear them with `browser.hook_reset` to avoid affecting later page behavior.

## CDP live-debugging guidelines (debug tools; requires `allowCdp`)

- Before debugging, establish a CDP session with `browser.debug_attach` (omitting `tab` uses the active tab), then call `browser.debug_set_breakpoint`. URLs support suffix matching (for example, `app.js` matches any script ending with that name); line and column are 0-based.
- A pause freezes the page: after a breakpoint hits, in-page tools such as evaluate/snapshot/click are rejected. Use `browser.debug_state` for the call stack, `browser.debug_scope` for scope variables, `browser.debug_eval` to evaluate in a stack frame (local variables can be read or written), and `browser.debug_step` to step over/into/out.
- **Rule: always call `browser.debug_resume` when debugging ends** (omit `tab` to resume all tabs), or the page remains frozen. Detach automatically resumes, but do not rely on that.
- Worker-level interception: after attach, network rules (log/block/redirect/mock/modify_headers) use engine-level Fetch interception, covering Worker/Service Worker and subresource requests that injected hooks cannot intercept. Breakpoints are replayed automatically after reattaching following a detach; they do not need to be recreated.

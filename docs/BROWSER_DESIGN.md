# TaiXu — Embedded Browser and Harness Integration Design

**Scope:** An in-app WebView controlled by the Agent Harness through MCP, with optional access from a desktop MCP client through adb reverse.  
**Status:** The design describes an MVP slice focused on in-app browser and filesystem operations.  
**Created:** 2026-09-02

## 1. Goals

- **Visible browser:** Users can open a real browser view inside the app, with multiple tabs, a URL bar, and a co-browsing status indicator.
- **AI control:** The Harness can use MCP browser tools such as open, navigate, snapshot, click, type, and screenshot against the same browser instance.
- **Co-browsing:** User and AI operations alternate on the same WebView. The UI makes the latest operator and control handoff visible.
- **Optional external access:** A local endpoint may allow a desktop AI/CLI client to connect over MCP JSON-RPC, with adb reverse where appropriate.
- **Security:** Reuse the credential redactor. Cookies, Authorization headers, and passwords are redacted by default; operations are classified by risk and may require approval.

## 2. Module responsibilities

- feature/browser: Compose browser UI using shared design-system components.
- runtime/browser: browser registry, WebView/session lifecycle, and browser operation implementation.
- harness MCP adapters: expose browser operations as tools and validate arguments.
- core/security: sensitive-data redaction and approval-policy integration.

Keep the UI layer separate from browser execution and MCP transport. The same browser session should be shared by the visible UI and the Harness instead of creating hidden parallel WebViews.

## 3. Browser session and operations

A browser session should track its identifier, active tab, current URL, loading state, navigation history, and the latest operation source (user or Agent). Tool calls must target an explicit session/tab where needed.

Core operations include opening a URL, navigating, taking a snapshot, clicking an element, entering text, capturing a screenshot, and reading bounded page metadata. Tool results should be structured and size-limited. Do not return unbounded page content or raw sensitive browser state by default.

User interactions and AI operations must share one state model so that URL changes, loading indicators, tab selection, and handoff status remain consistent.

## 4. MCP endpoint and network exposure

If external access is enabled, bind to loopback by default and use adb reverse for desktop clients. Binding to 0.0.0.0 exposes the endpoint to the local network and must be an explicit, documented choice. Validate the caller, method, parameters, and message size; reject unknown methods and malformed JSON-RPC requests.

Do not treat an open local port as proof of authorization. Any remote control path must have a clear trust boundary, explicit opt-in, and auditable operation logs.

## 5. Approval and sensitive data

- Low-risk navigation and bounded snapshots may be permitted according to policy.
- Clicking destructive controls, submitting forms, changing account settings, or triggering external side effects may require user approval.
- Redact cookies, Authorization values, passwords, access tokens, and other sensitive values from tool arguments, results, screenshots, and logs where possible.
- Redaction is not anonymization. Page text and user-visible content can still contain private information.
- Opening external URLs should be a deliberate action. Do not automatically launch arbitrary links in the system browser.

## 6. Failure handling and testing

Handle page load failures, renderer termination, timeouts, cancellation, tab closure, and stale element references explicitly. Bound tool execution and returned content. Keep the UI responsive while the Harness operates.

Test browser navigation, tab switching, user/Agent handoff, invalid MCP arguments, oversized responses, cancellation, approval paths, redaction, and external endpoint exposure. Verify behavior on real Android devices; WebView and renderer behavior may vary by Android version.

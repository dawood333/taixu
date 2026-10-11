## Android host privileges (Root active, UID 0)

All host tools are available with root privileges. Note that `base` runs inside the PRoot sandbox and has no Android runtime; `am`, `pm`, `settings`, `input`, and `logcat` work only on the host.

1. **Screen observation and control:** `screen_observe` retrieves the foreground app and screen node tree; `screen_click(x, y)` simulates a tap; `screen_swipe(x1, y1, x2, y2)` swipes; `screen_input_text(text)` types text; `screen_key(key)` sends navigation keys (back/home/recents/enter); `app_launch(package)` launches an app.
2. **System and app management:** Prefer structured `settings_*`, `package_*`, and `app_*` actions for system settings and package management. Use `exec` only when these cannot meet a Root-specific requirement. Prefer reversible operations; clearly explain the risks of irreversible actions such as permanently deleting system-partition content.
3. **Launch apps:** Use `am start -n <package>/<Activity>`. If the Activity is unknown, use `monkey -p <package> -c android.intent.category.LAUNCHER 1`. Use `app_list` to find package names.
4. **Common system intents:** Set an alarm with `am start -a android.intent.action.SET_ALARM -e android.intent.extra.alarm.HOUR <hour> -e android.intent.extra.alarm.MINUTES <minute> --es android.intent.extra.alarm.MESSAGE "<label>" -e android.intent.extra.alarm.SKIP_UI true`. For a countdown, use `SET_TIMER` and pass seconds with `LENGTH`. To share text, use `-a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "<content>"`.
5. **One-tap device overview:** The `device_status` action returns a read-only summary of battery, network, foreground app, and storage without requiring approval. It is useful for understanding the device state at the start of a task.

`status`, `device_status`, `app_list`, `package_list`, `settings_get`, and `logcat` are read-only. `exec` and uninstall operations always require approval.

Root has a broad impact surface: perform only the minimum actions required for the task.

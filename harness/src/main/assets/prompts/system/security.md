# Security: Approval for Risky Operations

Before performing any of the following high-risk operations, explain the impact to the user and obtain explicit consent:

- Irreversible deletion (rm -rf, deleting system partitions, clearing databases or directories)
- Overwriting or modifying important data and configuration
- Uninstalling or freezing system apps or critical components
- Formatting, partitioning, or mounting system directories
- Sending data externally or changing network/proxy/security settings
- Actions that affect the Android host system with real Root privileges

Read-only inspection (`status`, `device_status`, `app_list`, `package_list`, `settings_get`, `logcat`, and `read`) does not require confirmation.

If unsure, explain the impact before proceeding.

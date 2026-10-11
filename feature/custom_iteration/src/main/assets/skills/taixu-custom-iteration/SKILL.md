---
name: taixu-custom-iteration
description: Guidelines for TaiXu custom iteration and TaiXuDev builds, helping the Agent develop safely, submit PRs, and build an APK that coexists with the production app.
---

# TaiXu Custom Iteration Development Guidelines

This Skill guides the AI Agent in helping users iterate on the TaiXu app itself from TaiXu's non-Root Linux sandbox, including development, testing, cloud CI builds, and open-source community PR delivery.

---

## 1. Core Security and Isolation Rules

1. **Workspace isolation:** All changes to TaiXu source code must be made exclusively in the isolated workspace `~/custom_taixu`. Never modify the running host directory or system-level configuration directly.
2. **Credential security**：
   - Guide the user to authenticate with `gh auth login`(device-code flow) or an SSH key；
   - Never print GitHub tokens, private keys, or any sensitive credentials in terminal output, session messages, or commit records.
3. **Dual-flavor coexistence**：
   - APKs built by Custom Iteration must be named `TaiXuDev`；
   - The package name must be changed to `top.wkbin.taixu.dev`；
   - Keep the default storage and configuration directories separate from the production app so the test build can coexist with the installed production TaiXu app without interference.

---

## 2. Iterative Development Workflow

1. **Environment diagnostics**：
   - Check whether `git` and `gh` are installed;
   - Confirm that the current workspace is `~/custom_taixu`;
   - Check the current Git branch and ensure the working tree is clean.
2. **Architecture requirements**：
   - **UI layer:**Use Jetpack Compose + Material3 and follow the `top.wkbin.taixu.ui` package naming convention;
   - **State management:**Inject ViewModels with Koin and expose state using `StateFlow` + `collectAsStateWithLifecycle`;
   - **Linux sandbox:**Follow the standard contracts in the `runtime` module for PRoot system calls and interactions.
3. **Local smoke tests**：
   - Before committing, run `./gradlew testDebugUnitTest` for basic unit tests;
   - After verification passes, create a feature branch such as `feature/xxx` and commit the changes.

---

## 3. Build the TaiXuDev APK (GitHub Actions Preferred)

Because the phone's ARM64 architecture differs from the standard x86_64 Android build toolchain, prefer cloud CI builds through GitHub Actions:

1. Ensure the user's fork contains `.github/workflows/taixudev-build.yml`；
2. Trigger the workflow from the terminal and monitor it live:
   ```bash
   gh workflow run taixudev-build.yml --ref <feature-branch>
   gh run watch <run-id> --exit-status
   ```
3. After a successful build, download the APK artifact:
   ```bash
   gh run download <run-id> -n taixudev-apk -D /storage/emulated/0/Download/
   ```
4. Verify the APK package name (`top.wkbin.taixu.dev`) and signature before prompting the user to install and try it.

---

## 4. Open-Source PR Submission

After the user is satisfied with validation on a real device, help prepare a standard PR template and submit it to the official TaiXu repository:
- **Target repository**：`wkbin/taixu:main`
- **Source branch**：`user-fork:feature/xxx`
- **PR template sections**：Summary, Changes, Testing, Screenshots/Screen recording, and Risks.

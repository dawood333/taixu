# TaiXu Plugin Development Guidelines

This document describes the current behavior of the TaiXu plugin importer, Registry, installer, and PRoot runtime. Unless explicitly marked as a recommendation, examples describe the current implementation.

## 1. Plugin sources

| Source | `source` | Install method | Asset location |
|---|---|---|---|
| Online plugin | `REMOTE` | Usually `SCRIPT` | Built-in or signed Registry |
| Local plugin | `LOCAL` | `LOCAL_PACKAGE` | User-imported `.txplugin` |

When importing a local package, the app forcibly sets:

```json
{
  "source": "LOCAL",
  "offlineOnly": true,
  "installMethod": "LOCAL_PACKAGE"
}
```

If a local plugin and an online Registry entry share the same `id`, the local plugin takes precedence in the UI. The `permissions` field is used for manifest validation and display; it is **not a permission sandbox**. Install scripts still run inside PRoot with the current Linux user's permissions.

## 2. Package format and hard limits

A local plugin is a ZIP container with the `.txplugin` extension:

```text
my-plugin.txplugin
├── manifest.json
└── payload/
    ├── archives/       # SDK, Gradle, Flutter, NDK archives
    ├── scripts/        # Install, verify, and uninstall scripts
    ├── config/         # Configuration templates
    └── bin/            # Deployable ARM64 files
```

- The ZIP root may contain only `manifest.json` and `payload/`.
- Entries must not use absolute paths, backslash traversal, or `..`.
- `manifest.json` is limited to 1 MiB.
- Total uncompressed content is limited to 8 GiB.
- Importing a large package needs space for the app-private copy, the payload copy inside the distribution, extraction staging, and the final installation directory. Reserve substantially more space than the compressed package size.

After import, the package is stored in an app-private version directory. Before installation, `payload/` is copied into the active distribution at:

```text
/opt/taixu/imports/<id>
```

## 3. Manifest

Minimal local-plugin example:

```json
{
  "schemaVersion": 1,
  "id": "hello-arm64",
  "name": "Hello ARM64",
  "description": "TaiXu local plugin example",
  "version": "1.0.0",
  "publisher": "Your Name",
  "category": "DEVELOPER",
  "launchType": "command",
  "architectures": ["ARM64"],
  "permissions": [],
  "updateStrategy": "REINSTALL",
  "source": "LOCAL",
  "offlineOnly": true,
  "installMethod": "LOCAL_PACKAGE",
  "installSteps": ["/bin/sh \"$TAIXU_PLUGIN_PAYLOAD/scripts/install.sh\""],
  "uninstallSteps": ["/bin/sh \"$TAIXU_PLUGIN_PAYLOAD/scripts/uninstall.sh\""],
  "launchCommand": "hello",
  "verifyCommand": "hello",
  "commandLinks": ["hello"]
}
```

| Field | Current constraint |
|---|---|
| `schemaVersion` | Must be `1`. |
| `id` | Matches `[a-z0-9][a-z0-9-]{1,63}`; length 2–64. |
| `version` | Required; `x.y.z` is recommended. |
| `architectures` | Must include `ARM64`, case-insensitive. |
| `launchType` | `one_shot`, `command`, `pty`, `web`, or `service`. |
| `permissions` | Only `NETWORK`, `WORKSPACE_READ`, `WORKSPACE_WRITE`, and `LOCAL_WEB`. |
| `updateStrategy` | `REINSTALL` or `IN_PLACE`. |
| `homepage` | If provided, must use HTTPS. |
| `servicePort` | If provided, must be 1–65535 and launch type must be `web` or `service`. |
| `servicePath` | Must start with `/` and must not contain `..`. |

Online plugins should use `source=REMOTE` and `installMethod=SCRIPT`. The current validator does not enforce the REMOTE/SCRIPT combination, so Registry publishers must follow this convention.

## 4. Installer environment variables

```text
$TAIXU_TOOL_ID         Plugin ID
$TAIXU_TOOL_DIR        /opt/taixu/tools/<id>
$TAIXU_TOOL_DATA       /opt/taixu/data/<id>
$TAIXU_PLUGIN_PAYLOAD  /opt/taixu/imports/<id> (local plugins only)
```

Put executable program files under `$TAIXU_TOOL_DIR`; put user configuration, caches, models, and project data under `$TAIXU_TOOL_DATA`. Data is retained by default during ordinary uninstall.

Each name in `commandLinks` points by default to `$TAIXU_TOOL_DIR/bin/<command>`. The install script must create that file and restore executable permissions.

## 5. Minimal RootFS compatibility

Plugins should depend only on `/bin/sh` and commands known to exist in the target RootFS. Do not assume these optional tools are installed:

```text
unzip  xz  file  readelf  jq  python  node
```

- Scripts should start with `#!/bin/sh`, use POSIX Shell syntax, and use LF line endings.
- Use absolute `/bin/sh` in manifest commands instead of relying on `PATH`.
- Prefer `.tar.gz`. If using `.tar.xz`, bundle a compatible extractor; do not assume `xz` exists.
- ZIP files do not guarantee Unix executable bits; run `chmod` explicitly after extraction.
- Do not use `find -type f` to locate tools that may be symlinks; use `\( -type f -o -type l \)`.
- Do not pass a wildcard directly as a single `test -x` argument. Use `find ... -print -quit` to obtain an explicit path.
- Do not depend on `file` or `readelf` for architecture checks; the ELF header can be read directly.

Example POSIX ELF64 AArch64 check:

```sh
elf_bytes() { od -An -t x1 "$@" 2>/dev/null | tr -d ' \n'; }
is_elf() { test "$(elf_bytes -N 4 "$1")" = "7f454c46"; }
is_aarch64_elf() { test "$(elf_bytes -j 18 -N 2 "$1")" = "b700"; }
```

The ELF `e_machine` field is at offset 18. AArch64 is 183, encoded as little-endian bytes `b7 00`.

## 6. Hello plugin

Directory:

```text
hello-arm64/
├── manifest.json
└── payload/
    └── bin/
        └── hello
```

`payload/bin/hello`:

```sh
#!/bin/sh
echo "hello from TaiXu"
```

The install steps should check the source file, create `$TAIXU_TOOL_DIR/bin`, copy the executable, and run `chmod 755`. The uninstall steps should remove the executable owned by the plugin.

## 7. Large offline dependencies

Put large assets in `payload/archives/`. The install script should verify the digest, extract into staging, validate the result, and then commit it:

```sh
#!/bin/sh
set -eu

archive="$TAIXU_PLUGIN_PAYLOAD/archives/flutter-arm64.tar.gz"
expected_sha256="<SHA256>"

test -s "$archive"
printf '%s  %s\n' "$expected_sha256" "$archive" | sha256sum -c -

rm -rf /opt/flutter.staging
mkdir -p /opt/flutter.staging
tar -xzf "$archive" -C /opt/flutter.staging

test -x /opt/flutter.staging/flutter/bin/flutter
rm -rf /opt/flutter
mv /opt/flutter.staging/flutter /opt/flutter
```

- `offlineOnly=true` skips Runtime dependency fetching for Node/Python/Curl, but it does not prevent a script from connecting to the network. A truly offline plugin must not call the network or a package manager.
- Host tools must run in Linux ARM64 under PRoot. Android ARM64/Bionic and Linux ARM64/glibc binaries are not interchangeable just because they share an architecture.
- Pin SHA-256 for every large archive.
- Keep staging on the same filesystem as the final destination and move it into place only after validation.
- Install scripts time out after 15 minutes by default; each `verifyCommand` times out after 60 seconds by default.

## 8. Live progress and logs

The installer records stage events, stdout, and stderr. Scripts may emit structured relative progress:

```text
[TAIXU_PROGRESS:47] [EXTRACT] Extracting Android NDK r29
```

Progress must increase monotonically. Recommended labels are `[COPY]`, `[EXTRACT]`, `[COMMAND]`, and `[VERIFY]`. Do not use `set -x` in install scripts: it can leak tokens, proxy addresses, or other environment variables into logs. Script progress is relative to the recipe; the app maps it to the overall installation progress bar.

## 9. Packaging, versions, and import

For small packages, create a ZIP containing `manifest.json` and `payload/`, then use the `.txplugin` extension. For large packages, use a Zip64-capable tool. For already-compressed `.zip`, `.tar.gz`, and `.deb` entries, prefer ZIP Store/NoCompression to avoid useless recompression.

When updating only the manifest or scripts, copy the old package and replace a few ZIP entries instead of unpacking and recompressing large archives. Reprocess large files only when replacing archives or changing compression formats.

- An already-imported `id + version` is not unpacked or installed again.
- Different versions of the same ID can currently coexist in app-private directories.
- Version selection currently sorts directory names lexicographically rather than by full SemVer. Until fixed, avoid versions such as `1.0.9` and `1.0.10` together.
- After a new package is confirmed, the UI performs import and installation in sequence.

## 10. Web and service plugins

Declare service ports and paths explicitly:

```json
{
  "launchType": "web",
  "servicePort": 8787,
  "servicePath": "/",
  "permissions": ["LOCAL_WEB"],
  "launchCommand": "dashboard --host 127.0.0.1 --port 8787"
}
```

The current validator checks the port when `servicePort` is present, but does not require every `web/service` manifest to provide one. Authors should declare a port and prefer binding to `127.0.0.1` unless LAN access is required.

## 11. Verification, transactions, and uninstall

`verifyCommand` should return 0 and actually run the core executable. For compatibility with older plugins, the current installer may retain a success state if verification fails but any command entry is executable. Do not rely on this fallback.

Install transactions snapshot and restore only:

```text
/opt/taixu/tools/<id>
```

Global paths such as `/opt/android-sdk`, `/opt/flutter`, and `/root/.gradle` are not included in framework snapshots. Therefore:

- Stage, verify, and commit global assets within the plugin itself.
- Use `trap` to clean staging on failure.
- A `ROLLED_BACK` log entry does not mean every global file has been restored.
- `uninstallSteps` must remove global executable files owned by the plugin, while leaving `$TAIXU_TOOL_DATA` by default.

## 12. Release checklist

- [ ] ZIP root contains only `manifest.json` and `payload/`.
- [ ] Manifest is under 1 MiB and uncompressed content under 8 GiB.
- [ ] ID, version, ARM64, launch type, source, and permission fields are valid.
- [ ] Every large file has a pinned SHA-256.
- [ ] No undeclared optional commands such as unzip, xz, file, or readelf are required.
- [ ] ELF files, symlinks, and ZIP executable bits are handled correctly.
- [ ] Import, install, verification, and launch work offline.
- [ ] Fresh install, duplicate import, upgrade, and uninstall have been tested.
- [ ] Verification command, command links, PTY, and web/service modes work.
- [ ] Progress is monotonic and logs contain no credentials or sensitive environment variables.
- [ ] Staging can be cleaned on failure without falsely claiming global rollback.
- [ ] Required disk space for package copies, payload, staging, and final directories has been calculated.

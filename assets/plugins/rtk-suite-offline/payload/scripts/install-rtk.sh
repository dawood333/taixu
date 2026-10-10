#!/bin/sh
set -eu

PAYLOAD="${TAIXU_PLUGIN_PAYLOAD:?missing TAIXU_PLUGIN_PAYLOAD}"
TOOL_DIR="${TAIXU_TOOL_DIR:?missing TAIXU_TOOL_DIR}"

need() { test -s "$1" || { echo "missing offline resource: ${2:-$1}" >&2; exit 2; }; }
progress() { percent="$1"; shift; printf '[TAIXU_PROGRESS:%s] %s\n' "$percent" "$*"; }

need "$PAYLOAD/checksums/SHA256SUMS"
need "$PAYLOAD/bin/rtk"

progress 20 "[VERIFY] Verify RTK binary checksum"
(cd "$PAYLOAD" && sha256sum -c checksums/SHA256SUMS)

progress 60 "[INSTALL] Install the RTK binary in /opt/taixu/bin and the tool directory"
mkdir -p "$TOOL_DIR/bin" /opt/taixu/bin /opt/taixu/data/rtk/config/rtk /opt/taixu/data/rtk/data
cp "$PAYLOAD/bin/rtk" "$TOOL_DIR/bin/rtk"
chmod 755 "$TOOL_DIR/bin/rtk"
ln -sfn "$TOOL_DIR/bin/rtk" /opt/taixu/bin/rtk

if [ -f "$PAYLOAD/config/rtk/config.toml" ]; then
    cp "$PAYLOAD/config/rtk/config.toml" /opt/taixu/data/rtk/config/rtk/config.toml
    chmod 644 /opt/taixu/data/rtk/config/rtk/config.toml
fi

progress 90 "[VERIFY] Verify the RTK command"
/bin/sh "$PAYLOAD/scripts/verify-rtk.sh"
progress 100 "[VERIFY] RTK terminal command optimization plugin is ready"

#!/usr/bin/env bash
set -euo pipefail
INSTALL_DIR="${TIGERTV_INSTALL_DIR:-${HOME}/.local/bin}"
rm -f "$INSTALL_DIR/tigertv" "$INSTALL_DIR/tigertv-cli.py"
echo "已卸载 TigerTV CLI；缓存与日志保留。"

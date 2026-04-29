#!/usr/bin/env bash
set -euo pipefail

TARGET_PATH="${HOME}/.local/bin/tigertv-cli.py"

if [[ -f "${TARGET_PATH}" ]]; then
  rm "${TARGET_PATH}"
  echo "已移除: ${TARGET_PATH}"
else
  echo "未找到: ${TARGET_PATH}"
fi

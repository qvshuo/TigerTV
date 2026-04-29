#!/usr/bin/env bash
set -euo pipefail

INSTALL_DIR="${HOME}/.local/bin"
TARGET_PATH="${INSTALL_DIR}/tigertv-cli.py"
SCRIPT_URL="https://raw.githubusercontent.com/qvshuo/TigerTV/main/tigertv-cli.py"

mkdir -p "${INSTALL_DIR}"

if command -v curl >/dev/null 2>&1; then
  curl -fsSL "${SCRIPT_URL}" -o "${TARGET_PATH}"
elif command -v wget >/dev/null 2>&1; then
  wget -qO "${TARGET_PATH}" "${SCRIPT_URL}"
else
  echo "错误: 需要 curl 或 wget 其中之一用于下载安装文件" >&2
  exit 1
fi

chmod +x "${TARGET_PATH}"

echo "已安装到: ${TARGET_PATH}"
echo "如果 ${INSTALL_DIR} 尚未加入 PATH，请先按 README.md 配置环境变量。"
echo "安装完成后可执行: tigertv-cli.py --help"

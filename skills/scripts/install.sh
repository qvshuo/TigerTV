#!/usr/bin/env bash
set -euo pipefail
case "$(uname -s)/$(uname -m)" in
  Darwin/arm64) ASSET="TigerTV-CLI-macOS-arm64.tar.gz" ;;
  *) echo "当前仅提供 macOS arm64 原生 CLI。" >&2; exit 1 ;;
esac
INSTALL_DIR="${TIGERTV_INSTALL_DIR:-${HOME}/.local/bin}"
RELEASE="${TIGERTV_VERSION:-latest}"
TIGERTV_USER_AGENT="Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/27.2 Safari/605.1.15"
BASE="https://github.com/qvshuo/TigerTV/releases"
if [ "$RELEASE" = latest ]; then BASE="$BASE/latest/download"; else BASE="$BASE/download/v${RELEASE#v}"; fi
TEMP_DIR=$(mktemp -d)
trap 'rm -rf "$TEMP_DIR"' EXIT
curl -fLsS --user-agent "$TIGERTV_USER_AGENT" --connect-timeout 10 --max-time 120 --retry 2 "$BASE/$ASSET" -o "$TEMP_DIR/$ASSET"
curl -fLsS --user-agent "$TIGERTV_USER_AGENT" --connect-timeout 10 --max-time 120 --retry 2 "$BASE/$ASSET.sha256" -o "$TEMP_DIR/$ASSET.sha256"
(cd "$TEMP_DIR" && shasum -a 256 -c "$ASSET.sha256")
tar -xzf "$TEMP_DIR/$ASSET" -C "$TEMP_DIR"
"$TEMP_DIR/tigertv" --version
mkdir -p "$INSTALL_DIR"
install -m 755 "$TEMP_DIR/tigertv" "$INSTALL_DIR/tigertv.new"
mv -f "$INSTALL_DIR/tigertv.new" "$INSTALL_DIR/tigertv"
echo "已安装到 $INSTALL_DIR/tigertv。请将 $INSTALL_DIR 加入 PATH。"

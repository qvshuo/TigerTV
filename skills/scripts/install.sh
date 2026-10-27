#!/usr/bin/env bash
set -euo pipefail
case "$(uname -s)/$(uname -m)" in
  Darwin/arm64) ASSET="TigerTV-CLI-macOS-arm64.tar.gz" ;;
  *) echo "当前仅提供 macOS arm64 原生 CLI。" >&2; exit 1 ;;
esac
INSTALL_DIR="${TIGERTV_INSTALL_DIR:-${HOME}/.local/bin}"
RELEASE="${TIGERTV_VERSION:-latest}"
BASE="https://github.com/qvshuo/TigerTV/releases"
if [ "$RELEASE" = latest ]; then BASE="$BASE/latest/download"; else BASE="$BASE/download/v${RELEASE#v}"; fi
TEMP_DIR=$(mktemp -d)
trap 'rm -rf "$TEMP_DIR"' EXIT
curl -fLsS "$BASE/$ASSET" -o "$TEMP_DIR/$ASSET"
curl -fLsS "$BASE/$ASSET.sha256" -o "$TEMP_DIR/$ASSET.sha256"
(cd "$TEMP_DIR" && shasum -a 256 -c "$ASSET.sha256")
tar -xzf "$TEMP_DIR/$ASSET" -C "$TEMP_DIR"
"$TEMP_DIR/tigertv" --version
mkdir -p "$INSTALL_DIR"
install -m 755 "$TEMP_DIR/tigertv" "$INSTALL_DIR/tigertv.new"
mv -f "$INSTALL_DIR/tigertv.new" "$INSTALL_DIR/tigertv"
ln -sf tigertv "$INSTALL_DIR/tigertv-cli.py"
echo "已安装到 $INSTALL_DIR/tigertv（无需 Python 或 Java）。请将 $INSTALL_DIR 加入 PATH。"

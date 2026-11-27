from json import loads
from pathlib import Path
from subprocess import check_output

root = Path(__file__).resolve().parents[1]
files = check_output(["git", "ls-files", "-z"], cwd=root).decode().split("\0")
domain = "core/src/commonMain/kotlin/art/anjing/tigertv/domain/"
active = ("AndroidTV/", "core/", "cli/", "web/", ".github/workflows/")
collections = {item["collection"] for item in loads((root / "shared/douban-categories.json").read_text())}
errors = []
for name in files:
    path = Path(name)
    if not name.startswith(active) or path.suffix not in {".kt", ".kts", ".ts", ".svelte", ".mjs", ".yml", ".yaml"}:
        continue
    text = (root / path).read_text()
    if name.startswith(domain) and "art.anjing.tigertv.core." in text:
        errors.append(f"{name}: Domain depends on Core")
    if name.startswith("core/src/") and "art.anjing.tigertv.ui." in text:
        errors.append(f"{name}: Core depends on Android UI")
    if name.startswith("web/src/") and "../server/" in text:
        errors.append(f"{name}: browser code depends on Gateway")
    if any(value in text for value in (":nativeBridge", "macOS/App", "art.anjing.tigertv.bridge")):
        errors.append(f"{name}: active module references the macOS archive")
    if "/test" not in name.lower() and any(
        f'"{collection}"' in text or f"'{collection}'" in text for collection in collections
    ):
        errors.append(f"{name}: use shared/douban-categories.json")

snapshot = loads((root / "web/src/douban-snapshot.json").read_text())
if set(snapshot["categories"]) != collections:
    errors.append("Web recommendation snapshot keys differ from the shared categories")
if errors:
    raise SystemExit("\n".join(errors))

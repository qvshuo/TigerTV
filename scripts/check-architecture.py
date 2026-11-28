from json import loads
from pathlib import Path
import re
from subprocess import check_output

root = Path(__file__).resolve().parents[1]
files = check_output(["git", "ls-files", "-z"], cwd=root).decode().split("\0")
domain = "core/src/commonMain/kotlin/art/anjing/tigertv/domain/"
active = ("AndroidTV/", "core/", "cli/", "web/", ".github/workflows/")
collections = {item["collection"] for item in loads((root / "shared/douban-categories.json").read_text())}
defaults = loads((root / "shared/product-defaults.json").read_text())
infrastructure = (
    "art.anjing.tigertv.core.", "io.ktor.", "kotlinx.coroutines.", "java.", "javax.",
    "android.", "androidx.", "okhttp3.", "kotlinx.io.",
)
default_patterns = {}
for key, value in defaults.items():
    if isinstance(value, bool):
        default_patterns[key] = re.compile(
            rf'(?:getBoolean\(\s*["\']{re.escape(key)}["\']\s*,\s*(?:true|false)\s*\)'
            rf'|\b{re.escape(key)}\s*[:=]\s*(?:true|false)\b)'
        )
    elif key == "searchHistoryLimit":
        default_patterns[key] = re.compile(
            r'(?:\bsearchHistoryLimit\s*[:=]\s*\d+|\bMAX_SEARCH_HISTORY\s*=\s*\d+)'
        )
errors = []

def is_test_file(path):
    module, directory, *rest = path.parts
    if module == "web":
        return directory in {"test", "tests"} or path.stem.endswith((".test", ".spec"))
    if directory != "src" or len(rest) < 2:
        return False
    source_set = rest[0]
    return source_set == "test" or source_set.endswith(("Test", "Tests")) or re.match(r"(?:test|androidTest)[A-Z]", source_set) is not None


for name in files:
    path = Path(name)
    if not name.startswith(active) or path.suffix not in {".kt", ".kts", ".ts", ".svelte", ".mjs", ".yml", ".yaml"}:
        continue
    if is_test_file(path):
        continue
    text = (root / path).read_text()
    if name.startswith(domain) and "art.anjing.tigertv.core." in text:
        errors.append(f"{name}: Domain depends on Core")
    if name.startswith(domain):
        for imported in re.findall(r"^\s*import\s+([\w.]+)", text, re.MULTILINE):
            if imported.startswith(infrastructure):
                errors.append(f"{name}: Domain imports infrastructure package {imported}")
                break
    if name.startswith("core/src/") and "art.anjing.tigertv.ui." in text:
        errors.append(f"{name}: Core depends on Android UI")
    if name.startswith("web/src/") and "../server/" in text:
        errors.append(f"{name}: browser code depends on Gateway")
    if any(value in text for value in (":nativeBridge", "macOS/App", "art.anjing.tigertv.bridge")):
        errors.append(f"{name}: active module references the macOS archive")
    if any(
        f'"{collection}"' in text or f"'{collection}'" in text for collection in collections
    ):
        errors.append(f"{name}: use shared/douban-categories.json")
    if name.startswith(("AndroidTV/src/main/", "core/src/commonMain/", "cli/src/", "web/src/")):
        for key, pattern in default_patterns.items():
            if pattern.search(text):
                errors.append(f"{name}: use shared/product-defaults.json for {key}")
                break

snapshot = loads((root / "web/src/douban-snapshot.json").read_text())
if set(snapshot["categories"]) != collections:
    errors.append("Web recommendation snapshot keys differ from the shared categories")
if errors:
    raise SystemExit("\n".join(errors))

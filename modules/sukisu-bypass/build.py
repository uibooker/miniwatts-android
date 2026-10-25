#!/usr/bin/env python3
"""Build a manager-installable ZIP without bundling simulator tests."""

from pathlib import Path
import hashlib
import stat
import zipfile


root = Path(__file__).resolve().parent
properties = dict(line.split("=", 1) for line in (root / "module.prop").read_text().splitlines() if "=" in line)
version = properties["version"]
if not version or any(char not in "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ.-_" for char in version):
    raise ValueError("module.prop contains an unsafe version")
files = ["module.prop", "customize.sh", "control.sh", "lib.sh", "service.sh", "action.sh", "uninstall.sh", "skip_mount", "README.md", "profiles/mca.sh"]
files += [str(path.relative_to(root)) for path in sorted((root / "webroot").rglob("*")) if path.is_file()]
output = root / "build" / f"miniwatts-bypass-{version}.zip"
output.parent.mkdir(exist_ok=True)
with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
    for name in files:
        entry = zipfile.ZipInfo(name, date_time=(2026, 10, 13, 0, 0, 0))
        entry.create_system = 3
        entry.compress_type = zipfile.ZIP_DEFLATED
        mode = 0o755 if name in {"customize.sh", "control.sh", "service.sh", "action.sh", "uninstall.sh"} else 0o644
        entry.external_attr = (stat.S_IFREG | mode) << 16
        archive.writestr(entry, (root / name).read_bytes())
digest = hashlib.sha256(output.read_bytes()).hexdigest()
output.with_suffix(".zip.sha256").write_text(f"{digest}  {output.name}\n")
print(f"{output}\nSHA-256: {digest}")

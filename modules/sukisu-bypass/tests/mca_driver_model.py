"""A register/command model built from the documented driver wire protocol.

This is deliberately separate from controller code. The shown register is an
aggregate bit mask; a store is a command selecting exactly one feature.
"""

import json
import os
from pathlib import Path
import sys


def apply_command(mask, command):
    if not 0 <= command <= 0x7FFFFFFF:
        raise ValueError("kstrtoint range")
    selector = command & 0xFFFE
    if selector == 0 or selector & (selector - 1):
        raise ValueError("store requires one feature selector")
    return mask | selector if command & 1 else mask & ~selector


def store(root, node, command):
    root = Path(root)
    target = root / node.lstrip("/")
    before = int(target.read_text().strip())
    after = apply_command(before, command)
    fault_file = root / "driver-fault"
    fault = fault_file.read_text().strip() if fault_file.exists() else os.environ.get("DRIVER_FAULT", "")
    is_on = command == 1025
    rejected = fault == "before" and is_on or fault == "rollback" and not is_on or fault == "restore" and not is_on
    remaining = root / "remaining-restore-failures"
    if not is_on and remaining.exists():
        count = int(remaining.read_text())
        if count > 0:
            remaining.write_text(str(count - 1))
            rejected = True
    with (root / "driver-writes.jsonl").open("a") as log:
        log.write(json.dumps({"command": command, "before": before, "after": before if rejected else after}) + "\n")
    if rejected:
        return 1
    target.write_text(str(after) + "\n")
    if is_on and fault == "readback":
        (root / "fail-next-read").touch()
    return int(is_on and fault in ("after", "rollback"))


if __name__ == "__main__":
    try:
        sys.exit(store(sys.argv[1], sys.argv[2], int(sys.argv[3], 0)))
    except (ValueError, OSError):
        sys.exit(1)

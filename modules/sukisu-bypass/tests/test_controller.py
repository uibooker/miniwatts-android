"""Exercise writes and recovery on an isolated fake sysfs, never on a device."""

import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest


MODULE = Path(__file__).resolve().parents[1]
NODE = "/sys/class/power_supply/battery/bypass_charging"
ONLINE = "/sys/class/power_supply/usb/online"
HARNESS = r'''
umask 077
MODDIR=$1
SYSROOT=$2
STATE=$3
shift 3
. "$MODDIR/lib.sh"
kernel_flock() { python3 "$MODDIR/flock_model.py" "$@" 9>&9; }
init || exit 1
if [ "${NO_WATCHDOG:-0}" != 1 ]; then heartbeat; fi
if [ -n "${FAULT:-}" ]; then
    write_node() {
        case "$FAULT:$2" in
            before:1) return 1 ;;
            after:1|rollback:1) printf '%s\n' "$2" > "$SYSROOT$1"; return 1 ;;
            rollback:0) return 1 ;;
        esac
        printf '%s\n' "$2" > "$SYSROOT$1" && [ "$(value "$1")" = "$2" ]
    }
fi
if [ "$1" = test_tick ]; then
    lock || exit 1
    tick
    TEST_RESULT=$?
    unlock
    exit "$TEST_RESULT"
fi
main "$@"
'''


class ControllerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.sysroot = self.root / "device"
        self.state = self.root / "state"
        self.moddir = self.root / "module"
        self.moddir.mkdir()
        (self.moddir / "lib.sh").symlink_to(MODULE / "lib.sh")
        (self.moddir / "profiles").symlink_to(MODULE / "profiles", target_is_directory=True)
        (self.moddir / "flock_model.py").symlink_to(MODULE / "tests/flock_model.py")
        self.put("/proc/sys/kernel/random/boot_id", "test-boot")
        self.put("/proc/uptime", "1234.00 100.00")
        self.put(NODE, "0")
        self.put(ONLINE, "1")
        self.put("/sys/class/power_supply/battery/capacity", "60")
        self.put("/sys/class/power_supply/battery/temp", "350")

    def tearDown(self):
        self.temp.cleanup()

    def put(self, path, value):
        target = self.sysroot / path.lstrip("/")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(value + "\n")

    def node(self):
        return (self.sysroot / NODE.lstrip("/")).read_text().strip()

    def command(self, *args, ok=True, **env):
        result = subprocess.run(
            ["/bin/sh", "-c", HARNESS, "test", str(self.moddir), str(self.sysroot), str(self.state), *args],
            text=True, capture_output=True, env={**os.environ, **env}, timeout=10,
        )
        self.assertEqual(result.returncode == 0, ok, result.stdout + result.stderr)
        return result

    def configure(self):
        self.command("configure", NODE, "1", "0", ONLINE)

    def test_unconfigured_and_diagnosis_never_write(self):
        self.command("on", ok=False)
        result = self.command("diagnose")
        self.assertIn(NODE + "=0", result.stdout)
        self.assertEqual(self.node(), "0")
        self.assertFalse((self.state / "journal").exists())

    def test_configure_enable_repeat_and_restore(self):
        self.configure()
        self.assertEqual(self.node(), "0")
        self.command("on")
        original = (self.state / "journal").read_text()
        self.command("on")
        self.assertEqual((self.state / "journal").read_text(), original)
        self.assertEqual(self.node(), "1")
        self.command("off")
        self.assertEqual(self.node(), "0")
        self.assertFalse((self.state / "journal").exists())

    def test_reject_stop_charge_and_path_traversal(self):
        for node in ["/sys/class/power_supply/battery/input_suspend", "/sys/class/power_supply/battery/charging_enabled", "/sys/class/power_supply/../battery/bypass_charging", "/tmp/bypass_charging"]:
            with self.subTest(node=node):
                self.command("configure", node, "1", "0", ONLINE, ok=False)
        self.assertEqual(self.node(), "0")

    def test_inverted_driver_polarity(self):
        self.put(NODE, "1")
        self.command("configure", NODE, "0", "1", ONLINE)
        self.command("on")
        self.assertEqual(self.node(), "0")
        self.command("off")
        self.assertEqual(self.node(), "1")

    def test_guards_reject_enable_and_restore_active_session(self):
        scenarios = [(ONLINE, "0"), ("/sys/class/power_supply/battery/capacity", "29"), ("/sys/class/power_supply/battery/capacity", "unknown"), ("/sys/class/power_supply/battery/temp", "450"), ("/sys/class/power_supply/battery/temp", "")]
        for path, invalid in scenarios:
            with self.subTest(path=path, invalid=invalid):
                self.configure()
                previous = (self.sysroot / path.lstrip("/")).read_text().strip()
                self.put(path, invalid)
                self.command("on", ok=False)
                self.assertEqual(self.node(), "0")
                self.put(path, previous)
                self.command("on")
                self.put(path, invalid)
                self.command("test_tick")
                self.assertEqual(self.node(), "0")
                self.assertFalse((self.state / "journal").exists())
                self.put(path, previous)

    def test_write_failure_before_and_after_mutation_rolls_back(self):
        self.configure()
        for fault in ["before", "after"]:
            with self.subTest(fault=fault):
                self.command("on", FAULT=fault, ok=False)
                self.assertEqual(self.node(), "0")
                self.assertFalse((self.state / "journal").exists())

    def test_failed_rollback_retains_journal_for_retry(self):
        self.configure()
        self.command("on", FAULT="rollback", ok=False)
        self.assertEqual(self.node(), "1")
        self.assertTrue((self.state / "journal").exists())
        self.command("off")
        self.assertEqual(self.node(), "0")

    def test_missing_node_and_unexpected_value_retain_recovery(self):
        self.configure()
        self.command("on")
        (self.sysroot / NODE.lstrip("/")).unlink()
        self.command("off", ok=False)
        self.assertFalse((self.sysroot / NODE.lstrip("/")).exists())
        self.assertTrue((self.state / "journal").exists())
        self.put(NODE, "2")
        self.command("off", ok=False)
        self.assertEqual(self.node(), "2")
        self.put(NODE, "1")
        self.command("off")

    def test_no_watchdog_or_stale_watchdog_rejects_enable(self):
        self.configure()
        self.command("on", NO_WATCHDOG="1", ok=False)
        (self.state / "watchdog").write_text(f"boot=test-boot\npid={os.getpid()}\ntime=1000\n")
        self.command("on", NO_WATCHDOG="1", ok=False)
        self.assertEqual(self.node(), "0")

    def test_other_controller_and_active_profile_changes_are_rejected(self):
        self.configure()
        self.put(NODE, "1")
        self.command("on", ok=False)
        self.put(NODE, "0")
        self.command("on")
        self.command("configure", NODE, "1", "0", ONLINE, ok=False)
        self.command("limits", "40", "400", ok=False)
        self.command("off")
        self.command("limits", "40", "400")
        self.command("limits", "5", "900", ok=False)

    def test_disable_and_removal_restore(self):
        self.configure()
        for marker in ["disable", "remove"]:
            self.command("on")
            (self.moddir / marker).touch()
            self.command("test_tick")
            self.assertEqual(self.node(), "0")
            self.command("on", ok=False)
            (self.moddir / marker).unlink()

    def test_previous_boot_journal_never_writes_to_new_driver(self):
        self.configure()
        self.command("on")
        self.put("/proc/sys/kernel/random/boot_id", "new-boot")
        self.command("off")
        self.assertEqual(self.node(), "1")
        self.assertFalse((self.state / "journal").exists())

    def test_config_is_data_never_executed(self):
        self.configure()
        sentinel = self.root / "executed"
        with (self.state / "config").open("a") as config:
            config.write(f"node=$(touch {sentinel})\n")
        self.command("on", ok=False)
        self.assertFalse(sentinel.exists())
        self.assertEqual(self.node(), "0")

    def test_daemon_lifecycle_and_signal_restore(self):
        self.configure()
        process = subprocess.Popen(
            ["/bin/sh", "-c", HARNESS, "test", str(self.moddir), str(self.sysroot), str(self.state), "run"],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
            env={**os.environ, "NO_WATCHDOG": "1"},
        )
        try:
            deadline = time.monotonic() + 4
            watchdog = self.state / "watchdog"
            while not watchdog.exists() or f"pid={process.pid}\n" not in watchdog.read_text():
                self.assertIsNone(process.poll(), "Daemon exited during startup")
                self.assertLess(time.monotonic(), deadline, "No daemon heartbeat")
                time.sleep(0.05)
            self.command("run", NO_WATCHDOG="1", ok=False)
            self.command("on", NO_WATCHDOG="1")
            self.assertEqual(self.node(), "1")
            process.terminate()
            stdout, stderr = process.communicate(timeout=8)
            self.assertEqual(process.returncode, 0, stdout + stderr)
            self.assertEqual(self.node(), "0")
            self.assertFalse((self.state / "journal").exists())
            self.assertFalse((self.state / "watchdog").exists())
        finally:
            if process.poll() is None:
                process.terminate()
                process.communicate(timeout=8)

    def test_mismatched_profile_restores_original_journal_node(self):
        self.configure()
        self.command("on")
        other = "/sys/class/power_supply/battery/bypass_mode"
        self.put(other, "1")
        config = self.state / "config"
        config.write_text(config.read_text().replace(NODE, other))
        self.command("test_tick")
        self.assertEqual(self.node(), "0")
        self.assertEqual((self.sysroot / other.lstrip("/")).read_text().strip(), "1")

    def test_legacy_ownerless_directory_does_not_block_kernel_lock(self):
        self.configure()
        lock = self.state / "lock"
        lock.mkdir()
        self.command("on")
        self.assertEqual(self.node(), "1")
        self.assertTrue(lock.exists())
        self.assertTrue((self.state / "controller.lock").exists())
        self.assertFalse((self.state / "reap").exists())
        self.command("off")

    def test_legacy_native_config_and_journal_restore_without_backend_field(self):
        self.configure()
        config = self.state / "config"
        config.write_text("\n".join(line for line in config.read_text().splitlines() if not line.startswith("backend=")) + "\n")
        self.command("on")
        journal = self.state / "journal"
        journal.write_text("\n".join(line for line in journal.read_text().splitlines() if not line.startswith("backend=")) + "\n")
        self.command("off")
        self.assertEqual(self.node(), "0")
        self.assertFalse(journal.exists())

    def diagnose_preview(self):
        return subprocess.run(
            ["/bin/sh", "-c", 'MODDIR=$1; SYSROOT=$2; STATE=$3; . "$MODDIR/lib.sh"; main diagnose', "test", str(self.moddir), str(self.sysroot), str(self.state)],
            text=True, capture_output=True, timeout=10,
        )

    def test_diagnose_preview_creates_no_state(self):
        result = self.diagnose_preview()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(NODE + "=0", result.stdout)
        self.assertFalse(self.state.exists())
        self.assertEqual(self.node(), "0")

    def test_mca_diagnose_does_not_treat_aggregate_status_as_a_switch(self):
        smart = "/sys/devices/platform/soc/soc:smart_charge"
        self.put(smart + "/smart_chg", "80")
        self.put(smart + "/scene", "0")
        result = self.diagnose_preview()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(smart + "/smart_chg=80", result.stdout)
        self.assertIn("not a boolean bypass node", result.stdout)
        self.assertFalse(self.state.exists())
        self.command("configure", smart + "/smart_chg", "1", "0", ONLINE, ok=False)
        self.assertEqual((self.sysroot / (smart + "/smart_chg").lstrip("/")).read_text().strip(), "80")


if __name__ == "__main__":
    unittest.main(verbosity=2)

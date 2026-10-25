"""Black-box controller checks against a simulated vendor command parser."""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest

from mca_driver_model import apply_command


MODULE = Path(__file__).resolve().parents[1]
TESTS = Path(__file__).resolve().parent
SMART = "/sys/devices/platform/soc/soc:smart_charge"
NODE = SMART + "/smart_chg"
HASH = "9c85ed0c761e597df3a549ed25b9a9cfbbc9414fb7b38d2b6a6043bc787510af"
KERNEL = "6.6.77-android15-8-gf9a1d4bd8353-abogki440974771-4k"


class DriverProtocolTest(unittest.TestCase):
    def test_flock_mock_rejects_flags_absent_from_installed_busybox(self):
        result = subprocess.run([sys.executable, str(TESTS / "flock_model.py"), "-x", "-w", "3", "9"], capture_output=True)
        self.assertEqual(result.returncode, 2)

    def test_aggregate_mask_cannot_be_written_back(self):
        with self.assertRaises(ValueError):
            apply_command(80, 80)
        with self.assertRaises(ValueError):
            apply_command(80, 1)

    def test_commands_target_one_feature_and_preserve_other_bits(self):
        self.assertEqual(apply_command(80, 1025), 1104)
        self.assertEqual(apply_command(1104, 1024), 80)
        self.assertEqual(apply_command(1108, 1024), 84)
        self.assertEqual(apply_command(80, 9), 88)
        self.assertEqual(apply_command(88, 8), 80)
        with self.assertRaises(ValueError):
            apply_command(80, 1025 | 16)


class MCAPolicyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.sysroot = self.root / "device"
        self.state = self.root / "state"
        self.moddir = self.root / "module"
        self.moddir.mkdir()
        for name in ("lib.sh", "profiles"):
            (self.moddir / name).symlink_to(MODULE / name, target_is_directory=name == "profiles")
        for path, value in {
            "/proc/sys/kernel/random/boot_id": "test-boot",
            "/proc/uptime": "1234.00 100.00",
            "/sys/class/power_supply/usb/online": "1",
            "/sys/class/power_supply/wireless/online": "0",
            "/sys/class/power_supply/battery/capacity": "60",
            "/sys/class/power_supply/battery/temp": "350",
            NODE: "80",
            SMART + "/board_temp": "350",
            "/mock-props/ro.product.model": "24129PN74C",
            "/mock-props/ro.product.device": "dada",
            "/mock-props/ro.build.version.incremental": "OS3.0.303.0.WOCCNXM",
            "/mock-kernel": KERNEL,
            "/mock-hash": HASH,
            "/vendor_dlkm/lib/modules/mca_smart_charge.ko": "fake ELF; hash command mocked only in harness",
        }.items():
            self.put(path, value)
        dt = self.path(SMART + "/of_node/smart_bypass_support")
        dt.parent.mkdir(parents=True)
        dt.write_bytes(b"\0\0\0\1")
        driver = self.path("/sys/bus/platform/drivers/smart_charge")
        driver.mkdir(parents=True)
        (self.path(SMART) / "driver").symlink_to(driver, target_is_directory=True)
        (driver / "soc:smart_charge").symlink_to(self.path(SMART), target_is_directory=True)

    def tearDown(self):
        self.temp.cleanup()

    def path(self, path):
        return self.sysroot / path.lstrip("/")

    def put(self, path, value):
        target = self.path(path)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(value + "\n")

    def mask(self):
        return int(self.path(NODE).read_text().strip())

    def writes(self):
        log = self.sysroot / "driver-writes.jsonl"
        return [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []

    def spawn(self, *args, **env):
        return subprocess.Popen(
            ["/bin/sh", str(TESTS / "mca_harness.sh"), str(self.moddir), str(self.sysroot),
             str(self.state), str(TESTS / "mca_driver_model.py"), *args],
            text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            env={**os.environ, "TEST_PYTHON": sys.executable, "TEST_FLOCK": str(TESTS / "flock_model.py"), **env},
        )

    def command(self, *args, ok=True, **env):
        process = self.spawn(*args, **env)
        stdout, stderr = process.communicate(timeout=12)
        self.assertEqual(process.returncode == 0, ok, stdout + stderr)
        return stdout

    def configure(self):
        self.command("configure-mca")
        self.assertEqual(self.mask(), 80)
        self.assertEqual(self.writes(), [])

    def test_probe_and_status_are_readonly_and_honest(self):
        self.assertIn("mca_supported=yes", self.command("probe", TEST_PREVIEW="1"))
        self.assertEqual(self.writes(), [])
        self.assertFalse(self.state.exists())
        self.assertIn("configured=no", self.command("status", TEST_PREVIEW="1"))
        self.assertFalse(self.state.exists())
        self.configure()
        status = self.command("status")
        self.assertIn("configured=yes", status)
        self.assertIn("session_active=no", status)
        self.assertIn("power_path=unverified", status)
        self.assertIn("mode=xiaomi-mca-policy", status)
        self.assertIn("policy=fcc_limit", status)
        self.command("on")
        status = self.command("status")
        self.assertIn("request_state=on", status)
        self.assertIn("session_active=yes", status)
        self.assertIn("raw_node_value=1104", status)
        self.command("off")

    def test_enable_idempotence_and_restore_preserve_mask_changes(self):
        self.configure()
        self.command("on")
        journal = (self.state / "journal").read_text()
        self.command("on")
        self.assertEqual((self.state / "journal").read_text(), journal)
        self.assertEqual([write["command"] for write in self.writes()], [1025])
        self.put(NODE, "1108")  # Another vendor feature was enabled during this session.
        self.command("off")
        self.assertEqual(self.mask(), 84)
        self.assertEqual([write["command"] for write in self.writes()], [1025, 1024])
        self.command("on")
        self.put(NODE, "1032")  # Existing features can also be removed externally.
        self.command("off")
        self.assertEqual(self.mask(), 8)
        self.assertFalse((self.state / "journal").exists())

    def test_preexisting_bit_is_not_claimed_or_cleared(self):
        self.configure()
        self.put(NODE, "1104")
        self.command("on", ok=False)
        self.command("off")
        self.assertEqual(self.mask(), 1104)
        self.assertEqual(self.writes(), [])
        self.assertFalse((self.state / "journal").exists())

    def test_identity_and_dt_mismatch_refuse_configuration_and_enable(self):
        scenarios = {
            "/mock-props/ro.product.model": "other-model",
            "/mock-props/ro.product.device": "other-device",
            "/mock-props/ro.build.version.incremental": "OS3.other",
            "/mock-kernel": "6.6.other",
            "/mock-hash": "0" * 64,
            SMART + "/of_node/smart_bypass_support": "bad",
        }
        for path, bad in scenarios.items():
            with self.subTest(path=path):
                original = self.path(path).read_bytes()
                self.put(path, bad)
                self.assertIn("mca_supported=no", self.command("probe"))
                self.command("configure-mca", ok=False)
                self.assertEqual(self.writes(), [])
                self.path(path).write_bytes(original)
        self.configure()
        for path, bad in scenarios.items():
            with self.subTest(active_profile_path=path):
                original = self.path(path).read_bytes()
                self.put(path, bad)
                self.command("on", ok=False)
                self.assertEqual(self.writes(), [])
                self.path(path).write_bytes(original)

    def test_missing_and_unbound_driver_refuse_configuration(self):
        link = self.path(SMART + "/driver")
        target = link.resolve()
        link.unlink()
        self.command("configure-mca", ok=False)
        link.symlink_to(target, target_is_directory=True)
        (target / "soc:smart_charge").unlink()
        self.command("configure-mca", ok=False)
        self.assertEqual(self.writes(), [])

    def test_wrong_bound_driver_and_wrong_reciprocal_target_refuse_writes(self):
        link = self.path(SMART + "/driver")
        target = link.resolve()
        wrong = self.path("/sys/bus/platform/drivers/other_driver")
        wrong.mkdir()
        (wrong / "soc:smart_charge").symlink_to(self.path(SMART), target_is_directory=True)
        link.unlink()
        link.symlink_to(wrong, target_is_directory=True)
        self.command("configure-mca", ok=False)
        link.unlink()
        link.symlink_to(target, target_is_directory=True)
        back_link = target / "soc:smart_charge"
        back_link.unlink()
        back_link.symlink_to(wrong, target_is_directory=True)
        self.command("configure-mca", ok=False)
        self.assertEqual(self.writes(), [])

    def test_on_failures_before_after_and_readback_restore_other_bits(self):
        self.configure()
        for fault in ("before", "after", "readback"):
            with self.subTest(fault=fault):
                self.command("on", DRIVER_FAULT=fault, ok=False)
                self.assertEqual(self.mask(), 80)
                self.assertFalse((self.state / "journal").exists())
                self.assertTrue(all(write["command"] in (1025, 1024) for write in self.writes()))

    def test_failed_rollback_and_restore_keep_recovery_until_success(self):
        self.configure()
        self.command("on", DRIVER_FAULT="rollback", ok=False)
        self.assertEqual(self.mask(), 1104)
        self.assertTrue((self.state / "journal").exists())
        self.put(NODE, "1108")
        self.command("off", DRIVER_FAULT="restore", ok=False)
        self.assertEqual(self.mask(), 1108)
        self.assertTrue((self.state / "journal").exists())
        self.command("on", DRIVER_FAULT="restore", ok=False)
        self.assertEqual(self.mask(), 1108)
        self.assertEqual(sum(write["command"] == 1025 for write in self.writes()), 1)
        self.command("off")
        self.assertEqual(self.mask(), 84)
        self.assertFalse((self.state / "journal").exists())

    def test_changed_identity_retains_journal_without_restore_write(self):
        self.configure()
        self.command("on")
        original = self.path("/mock-hash").read_text()
        self.put("/mock-hash", "0" * 64)
        before = self.writes()
        self.command("off", ok=False)
        self.assertEqual(self.writes(), before)
        self.assertTrue((self.state / "journal").exists())
        self.assertEqual(self.mask(), 1104)
        self.path("/mock-hash").write_text(original)
        self.command("off")

    def test_unreadable_or_invalid_mask_keeps_pending_recovery(self):
        self.configure()
        self.command("on")
        for invalid in (None, "", "-1", "65536", "00080", "unknown"):
            with self.subTest(raw_mask=invalid):
                if invalid is None:
                    self.path(NODE).unlink()
                else:
                    self.put(NODE, invalid)
                before = self.writes()
                self.command("off", ok=False)
                self.assertEqual(self.writes(), before)
                self.assertTrue((self.state / "journal").exists())
                status = self.command("status", TEST_PREVIEW="1")
                self.assertIn("session_active=unknown", status)
                self.assertIn("request_state=unknown", status)
                self.assertIn("recovery_pending=yes", status)
                self.put(NODE, "1104")
        self.command("off")

    def test_recovery_uses_journal_after_config_is_lost(self):
        self.configure()
        self.command("on")
        (self.state / "config").unlink()
        self.put(NODE, "1108")
        self.command("off")
        self.assertEqual(self.mask(), 84)
        self.assertFalse((self.state / "journal").exists())

    def test_previous_boot_never_restores_to_new_driver(self):
        self.configure()
        self.command("on")
        self.put("/proc/sys/kernel/random/boot_id", "new-boot")
        self.put("/mock-hash", "0" * 64)
        before = self.writes()
        self.command("off")
        self.assertEqual(self.writes(), before)
        self.assertEqual(self.mask(), 1104)
        self.assertFalse((self.state / "journal").exists())

    def test_invalid_config_status_reports_unknown_with_success_exit(self):
        self.configure()
        (self.state / "config").write_text("backend=unknown-driver\n")
        status = self.command("status", TEST_PREVIEW="1")
        self.assertIn("configured=unknown", status)
        self.assertIn("request_state=unknown", status)
        self.assertIn("error=config_unreadable_or_invalid", status)
        self.assertEqual(self.writes(), [])

    def test_guards_reject_on_and_restore_active_request(self):
        self.configure()
        scenarios = [
            ("/sys/class/power_supply/usb/online", "0"),
            ("/sys/class/power_supply/wireless/online", "1"),
            ("/sys/class/power_supply/wireless/online", None),
            (SMART + "/board_temp", None),
            (SMART + "/board_temp", "450"),
            (SMART + "/board_temp", "unknown"),
            (SMART + "/board_temp", "-1"),
            ("/sys/class/power_supply/battery/capacity", "29"),
            ("/sys/class/power_supply/battery/temp", "450"),
        ]
        for path, bad in scenarios:
            with self.subTest(path=path, value=bad):
                original = self.path(path).read_bytes()
                if bad is None:
                    self.path(path).unlink()
                else:
                    self.put(path, bad)
                before = self.writes()
                self.command("on", ok=False)
                self.assertEqual(self.writes(), before)
                self.path(path).write_bytes(original)
                self.command("on")
                if bad is None:
                    self.path(path).unlink()
                else:
                    self.put(path, bad)
                self.command("test_tick")
                self.assertEqual(self.mask(), 80)
                self.assertFalse((self.state / "journal").exists())
                self.path(path).write_bytes(original)

    def test_external_bit_clear_ends_session_without_reenabling(self):
        self.configure()
        self.command("on")
        self.put(NODE, "84")
        before = self.writes()
        self.command("test_tick")
        self.assertEqual(self.writes(), before)
        self.assertEqual(self.mask(), 84)
        self.assertFalse((self.state / "journal").exists())

    def test_parallel_on_is_single_owned_request_and_off_is_serialized(self):
        self.configure()
        (self.state / "watchdog").write_text(f"boot=test-boot\npid={os.getpid()}\ntime=1234\n")
        processes = [self.spawn("on", NO_WATCHDOG="1") for _ in range(3)]
        for process in processes:
            stdout, stderr = process.communicate(timeout=12)
            self.assertEqual(process.returncode, 0, stdout + stderr)
        self.assertEqual([write["command"] for write in self.writes()], [1025])
        self.assertTrue((self.state / "journal").exists())
        processes = [self.spawn("off", NO_WATCHDOG="1") for _ in range(3)]
        for process in processes:
            stdout, stderr = process.communicate(timeout=12)
            self.assertEqual(process.returncode, 0, stdout + stderr)
        self.assertEqual([write["command"] for write in self.writes()], [1025, 1024])
        self.assertEqual(self.mask(), 80)
        self.assertFalse((self.state / "journal").exists())

    def test_parallel_enable_off_and_profile_change_preserve_ownership(self):
        self.configure()
        (self.state / "watchdog").write_text(f"boot=test-boot\npid={os.getpid()}\ntime=1234\n")
        processes = [self.spawn(command, NO_WATCHDOG="1") for command in ("on", "off", "configure-mca")]
        for process in processes:
            stdout, stderr = process.communicate(timeout=12)
            if process.returncode:
                self.assertIn("before changing", stdout + stderr)
        self.assertIn(self.mask(), (80, 1104))
        self.assertEqual((self.state / "journal").exists(), self.mask() == 1104)
        self.command("off")
        self.assertEqual(self.mask(), 80)
        self.assertFalse((self.state / "journal").exists())
        self.assertTrue(all(write["command"] in (1025, 1024) for write in self.writes()))

    def test_invalid_journal_is_not_accepted_as_idempotent_enable(self):
        self.configure()
        self.command("on")
        journal = self.state / "journal"
        original = journal.read_text()
        for key, invalid in (("original", "1"), ("on", "0"), ("backend", "unknown-driver")):
            with self.subTest(key=key):
                lines = original.splitlines()
                self.assertTrue(any(line.startswith(key + "=") for line in lines), original)
                journal.write_text("\n".join(key + "=" + invalid if line.startswith(key + "=") else line for line in lines) + "\n")
                before = self.writes()
                self.command("on", ok=False)
                self.assertEqual(self.writes(), before)
                self.assertEqual(self.mask(), 1104)
                self.assertTrue(journal.exists())
                journal.write_text(original)
        self.command("off")

    def test_missing_or_changed_profile_identity_never_restores(self):
        self.configure()
        self.command("on")
        journal = self.state / "journal"
        original = journal.read_text()
        self.assertIn("profile_id=", original)
        for replacement in (None, "other-profile"):
            with self.subTest(profile_id=replacement):
                lines = [line for line in original.splitlines() if not line.startswith("profile_id=")]
                if replacement is not None:
                    lines.append("profile_id=" + replacement)
                journal.write_text("\n".join(lines) + "\n")
                before = self.writes()
                self.command("off", ok=False)
                self.assertEqual(self.writes(), before)
                self.assertEqual(self.mask(), 1104)
                self.assertTrue(journal.exists())
                journal.write_text(original)
        self.command("off")

    def test_sigkill_releases_kernel_lock_without_removing_lock_inode(self):
        self.configure()
        self.command("on")
        inode = (self.state / "controller.lock").stat().st_ino
        holder = self.spawn("test_hold_lock", NO_WATCHDOG="1")
        try:
            deadline = time.monotonic() + 5
            while not (self.state / "lock-held").exists():
                self.assertIsNone(holder.poll())
                self.assertLess(time.monotonic(), deadline, "lock holder did not start")
                time.sleep(0.05)
            before = self.writes()
            self.command("off", ok=False)
            self.assertEqual(self.writes(), before)
            holder.kill()
            holder.communicate(timeout=5)
            self.command("off")
            self.assertEqual(self.mask(), 80)
            self.assertFalse((self.state / "journal").exists())
            self.assertEqual((self.state / "controller.lock").stat().st_ino, inode)
        finally:
            if holder.poll() is None:
                holder.kill()
                holder.communicate(timeout=5)

    def test_daemon_preloaded_backend_restores_after_module_directory_removed(self):
        self.configure()
        daemon = self.spawn("run", NO_WATCHDOG="1")
        try:
            self.wait_watchdog(daemon)
            self.command("on", NO_WATCHDOG="1")
            self.put(NODE, "1108")
            self.moddir.rename(self.root / "removed-module")
            stdout, stderr = daemon.communicate(timeout=10)
            self.assertEqual(daemon.returncode, 0, stdout + stderr)
            self.assertEqual(self.mask(), 84)
            self.assertFalse((self.state / "journal").exists())
            self.assertFalse((self.state / "watchdog").exists())
        finally:
            if daemon.poll() is None:
                daemon.terminate()
                daemon.communicate(timeout=10)

    def wait_watchdog(self, process):
        deadline = time.monotonic() + 5
        watchdog = self.state / "watchdog"
        while not watchdog.exists() or f"pid={process.pid}\n" not in watchdog.read_text():
            self.assertIsNone(process.poll(), "daemon stopped")
            self.assertLess(time.monotonic(), deadline, "daemon never heartbeats")
            time.sleep(0.05)

    def test_abrupt_daemon_death_restart_restores_owned_request(self):
        self.configure()
        daemon = self.spawn("run", NO_WATCHDOG="1")
        restarted = None
        try:
            self.wait_watchdog(daemon)
            self.command("on", NO_WATCHDOG="1")
            daemon.kill()
            daemon.communicate(timeout=8)
            self.assertEqual(self.mask(), 1104)
            self.assertTrue((self.state / "journal").exists())
            self.put(NODE, "1108")
            (self.state / "watchdog").unlink(missing_ok=True)
            restarted = self.spawn("run", NO_WATCHDOG="1")
            self.wait_watchdog(restarted)
            self.assertEqual(self.mask(), 84)
            self.assertFalse((self.state / "journal").exists())
        finally:
            for process in (daemon, restarted):
                if process is not None and process.poll() is None:
                    process.terminate()
                    process.communicate(timeout=8)

    def test_dead_watchdog_enable_rejects_and_restores_existing_session(self):
        self.configure()
        self.command("on")
        self.put(NODE, "1108")
        # The heartbeat belongs to the previous, exited command harness.
        self.command("on", NO_WATCHDOG="1", ok=False)
        self.assertEqual(self.mask(), 84)
        self.assertFalse((self.state / "journal").exists())

    def test_startup_pending_recovery_retries_without_claiming_session(self):
        self.configure()
        self.command("on")
        self.put(NODE, "1108")
        fault = self.sysroot / "driver-fault"
        fault.write_text("restore")
        (self.state / "watchdog").unlink(missing_ok=True)
        daemon = self.spawn("run", NO_WATCHDOG="1")
        try:
            self.wait_watchdog(daemon)
            self.assertTrue((self.state / "recovery_pending").exists())
            status = self.command("status", TEST_PREVIEW="1")
            self.assertIn("recovery_pending=yes", status)
            self.assertIn("watchdog=unavailable", status)
            self.command("run", NO_WATCHDOG="1", ok=False)
            self.command("on", NO_WATCHDOG="1", ok=False)
            self.assertEqual(self.mask(), 1108)
            self.assertTrue((self.state / "journal").exists())
            deadline = time.monotonic() + 7
            attempts = len(self.writes())
            while len(self.writes()) <= attempts:
                self.assertIsNone(daemon.poll())
                self.assertLess(time.monotonic(), deadline, "no autonomous recovery retry")
                time.sleep(0.1)
            fault.unlink()
            deadline = time.monotonic() + 7
            while (self.state / "journal").exists() or (self.state / "recovery_pending").exists():
                self.assertIsNone(daemon.poll())
                self.assertLess(time.monotonic(), deadline, "recovery did not complete after fault cleared")
                time.sleep(0.1)
            self.assertEqual(self.mask(), 84)
            status = self.command("status", TEST_PREVIEW="1")
            self.assertIn("watchdog=running", status)
            self.assertIn("recovery_pending=no", status)
        finally:
            fault.unlink(missing_ok=True)
            if daemon.poll() is None:
                daemon.terminate()
                stdout, stderr = daemon.communicate(timeout=10)
                self.assertEqual(daemon.returncode, 0, stdout + stderr)

    def test_daemon_shutdown_retry_success_returns_success(self):
        self.configure()
        (self.state / "watchdog").unlink(missing_ok=True)
        daemon = self.spawn("run", NO_WATCHDOG="1")
        try:
            self.wait_watchdog(daemon)
            self.command("on", NO_WATCHDOG="1")
            self.put(NODE, "1108")
            (self.sysroot / "remaining-restore-failures").write_text("2")
            daemon.terminate()
            stdout, stderr = daemon.communicate(timeout=12)
            self.assertEqual(daemon.returncode, 0, stdout + stderr)
            self.assertEqual(self.mask(), 84)
            self.assertFalse((self.state / "journal").exists())
            self.assertFalse((self.state / "watchdog").exists())
            self.assertFalse((self.state / "recovery_pending").exists())
            self.assertEqual([write["command"] for write in self.writes()][-3:], [1024, 1024, 1024])
        finally:
            if daemon.poll() is None:
                daemon.terminate()
                daemon.communicate(timeout=12)

    def test_production_entrypoints_reset_root_and_state(self):
        source = (MODULE / "control.sh").read_text()
        self.assertIn("SYSROOT=\n", source)
        self.assertIn("STATE=/data/adb/miniwatts-bypass\n", source)
        for name in ("service.sh", "action.sh", "uninstall.sh"):
            source = (MODULE / name).read_text()
            self.assertIn('/system/bin/sh "$MODDIR/control.sh"', source, name)
        self.put(NODE, "314159")
        result = subprocess.run(
            ["/bin/sh", str(MODULE / "control.sh"), "diagnose"],
            text=True, capture_output=True, timeout=8,
            env={**os.environ, "SYSROOT": str(self.sysroot), "STATE": str(self.root / "attack-state")},
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn(NODE + "=314159", result.stdout)
        self.assertFalse((self.root / "attack-state").exists())
        self.assertEqual(self.mask(), 314159)

    @unittest.skipIf(Path("/data/adb/ksu/bin/busybox").exists(), "host has the production BusyBox")
    def test_missing_production_lock_transport_refuses_hardware_write(self):
        self.configure()
        self.command("on", TEST_REAL_FLOCK="1", ok=False)
        self.assertEqual(self.mask(), 80)
        self.assertFalse((self.state / "journal").exists())
        self.assertEqual(self.writes(), [])


if __name__ == "__main__":
    unittest.main(verbosity=2)

"""Test-only BSD flock transport for the Android BusyBox CLI contract."""

import fcntl
import sys


def main(args):
    # The installed BusyBox supports [-sxun], without util-linux's -w option.
    if not args or args[-1] != "9" or any(arg not in ("-x", "-s", "-u", "-n") for arg in args[:-1]):
        return 2
    descriptor = int(args[-1])
    if "-u" in args:
        fcntl.flock(descriptor, fcntl.LOCK_UN)
        return 0
    operation = fcntl.LOCK_SH if "-s" in args else fcntl.LOCK_EX
    if "-n" in args:
        operation |= fcntl.LOCK_NB
    try:
        fcntl.flock(descriptor, operation)
        return 0
    except BlockingIOError:
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

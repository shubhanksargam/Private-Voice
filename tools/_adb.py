"""Shared adb path resolution and app-storage staging for tools/*.py.

PATH is set persistently for new terminals (see docs/SETUP.md), but tools
invoked from other contexts (an IDE run configuration, a differently-scoped
shell) shouldn't have to rely on that. Fall back to the known SDK location.
"""

from __future__ import annotations

import subprocess
import sys
import uuid
from pathlib import Path

SDK_ROOT = Path.home() / "AppData" / "Local" / "Android" / "Sdk"
_ADB_NAME = "adb.exe" if sys.platform == "win32" else "adb"
_CANDIDATE = SDK_ROOT / "platform-tools" / _ADB_NAME

ADB = str(_CANDIDATE) if _CANDIDATE.exists() else "adb"


def push_dir_to_app_storage(app_id: str, local_dir: Path, remote_subpath: str) -> None:
    """Copy local_dir's contents into the app's INTERNAL storage at
    files/<remote_subpath>/, ending up owned by the app's own UID.

    Plain `adb push` into the app's external files dir (Android/data/<pkg>)
    was tried first and doesn't work on this device: files land there fine
    from shell's point of view, but the app's own File.listFiles() can't see
    them — confirmed by writing there and having the running app report the
    directory as empty. Cross-UID reads into the public Download/ folder are
    blocked the same way. Internal storage doesn't go through that FUSE layer
    at all, so instead this stages through /data/local/tmp (plain,
    unmediated, shell-writable) and has `run-as` — running as the app's own
    UID — perform the actual copy into files/.
    """
    staging = f"/data/local/tmp/pv_stage_{uuid.uuid4().hex[:8]}"
    try:
        r = subprocess.run([ADB, "push", str(local_dir), staging], capture_output=True, text=True)
        if r.returncode != 0:
            raise SystemExit(f"adb push to {staging} failed:\n{r.stderr}")

        remote = f"files/{remote_subpath}"
        cmd = f"mkdir -p {remote} && cp -r {staging}/. {remote}/"
        r = subprocess.run([ADB, "shell", "run-as", app_id, "sh", "-c", cmd],
                           capture_output=True, text=True)
        if r.returncode != 0:
            raise SystemExit(f"run-as copy into {remote} failed:\n{r.stderr}")
    finally:
        subprocess.run([ADB, "shell", "rm", "-rf", staging], capture_output=True)

"""Shared adb path resolution for tools/*.py.

PATH is set persistently for new terminals (see docs/SETUP.md), but tools
invoked from other contexts (an IDE run configuration, a differently-scoped
shell) shouldn't have to rely on that. Fall back to the known SDK location.
"""

from __future__ import annotations

import sys
from pathlib import Path

SDK_ROOT = Path.home() / "AppData" / "Local" / "Android" / "Sdk"
_ADB_NAME = "adb.exe" if sys.platform == "win32" else "adb"
_CANDIDATE = SDK_ROOT / "platform-tools" / _ADB_NAME

ADB = str(_CANDIDATE) if _CANDIDATE.exists() else "adb"

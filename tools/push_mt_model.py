#!/usr/bin/env python3
"""Push the quantized opus-mt-en-hi ONNX model into the app's internal
storage at files/mt/, parallel to fetch_ggml_models.py's files/ggml/ push.

No download step here (unlike fetch_ggml_models.py) -- these files were
built locally by tools/build_onnx_tokenizer3.py + quantize_encoder_with_tokenizer.py,
not fetched from a remote registry.

    python tools/push_mt_model.py
"""
from __future__ import annotations

import sys
from pathlib import Path

from _adb import push_dir_to_app_storage
from fetch_ggml_models import ensure_device

APP_ID = "dev.privatevoice.app"
ROOT = Path(__file__).resolve().parent.parent
MT_DIR = ROOT / "models_mt" / "opus-mt-en-hi-onnx-int8"
REQUIRED = ["encoder_with_tokenizer.onnx", "decoder_model.onnx", "decoder_with_past_model.onnx", "vocab.json"]


def main() -> int:
    missing = [f for f in REQUIRED if not (MT_DIR / f).is_file()]
    if missing:
        raise SystemExit(f"Missing from {MT_DIR}: {missing}")

    total = sum((MT_DIR / f).stat().st_size for f in REQUIRED)
    print(f"{MT_DIR}  ({total/1e6:.0f} MB)")
    for f in REQUIRED:
        print(f"    {f}  {(MT_DIR / f).stat().st_size/1e6:.1f} MB")

    ensure_device()
    push_dir_to_app_storage(APP_ID, MT_DIR, "mt")
    print(f"Pushed to {APP_ID}'s internal files/mt/")
    return 0


if __name__ == "__main__":
    sys.exit(main())

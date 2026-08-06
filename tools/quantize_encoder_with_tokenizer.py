"""
build_onnx_tokenizer3.py fuses the tokenizer onto the FP32 encoder_model.onnx
(from the un-quantized export dir) and saves it straight to
models_mt/opus-mt-en-hi-onnx-int8/encoder_with_tokenizer.onnx without ever
quantizing it -- that file is still ~204MB on disk, not the ~49MB the plan
called for. Quantize it in place here, same op_types_to_quantize=['MatMul',
'Gather'] as the decoder graphs (default quantize_dynamic only touches
MatMul and misses the large embedding Gather table).

The custom SentencepieceTokenizer node (domain com.microsoft.extensions)
lives entirely in the pre-processing subgraph ahead of the real transformer
weights and isn't a quantizable type, so it's left untouched.
"""
from pathlib import Path
from onnxruntime.quantization import quantize_dynamic, QuantType

OUT_DIR = Path("models_mt/opus-mt-en-hi-onnx-int8")
SRC = OUT_DIR / "encoder_with_tokenizer.onnx"
TMP = OUT_DIR / "encoder_with_tokenizer.fp32.onnx"

if __name__ == "__main__":
    if not TMP.exists():
        SRC.rename(TMP)
    quantize_dynamic(
        str(TMP),
        str(SRC),
        weight_type=QuantType.QInt8,
        op_types_to_quantize=["MatMul", "Gather"],
    )
    before = TMP.stat().st_size / 1e6
    after = SRC.stat().st_size / 1e6
    print(f"{TMP.name}: {before:.1f}MB -> {SRC.name}: {after:.1f}MB")

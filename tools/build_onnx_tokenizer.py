"""
Bakes the opus-mt-en-hi tokenizer (SentencePiece, separate source/target
vocabs) into standalone ONNX graphs via onnxruntime_extensions, so Android
can tokenize/detokenize through plain ONNX Runtime + the extensions custom-op
library instead of needing a native SentencePiece binding.

Run: python tools/build_onnx_tokenizer.py
"""
from transformers import AutoTokenizer
from onnxruntime_extensions import gen_processing_models, OrtPyFunction
import numpy as np

MODEL_DIR = "models_mt/opus-mt-en-hi-onnx"
OUT_DIR = "models_mt/opus-mt-en-hi-onnx-int8"

tokenizer = AutoTokenizer.from_pretrained(MODEL_DIR)

pre, post = gen_processing_models(tokenizer, pre_kwargs={"CAST_TOKEN_ID": True}, post_kwargs={})

with open(f"{OUT_DIR}/tokenizer_encode.onnx", "wb") as f:
    f.write(pre.SerializeToString())
with open(f"{OUT_DIR}/tokenizer_decode.onnx", "wb") as f:
    f.write(post.SerializeToString())

print("Wrote tokenizer_encode.onnx and tokenizer_decode.onnx")

# Sanity check: encode "How are you?" and compare against the real tokenizer.
encode_fn = OrtPyFunction.from_model(pre)
ids = encode_fn(["How are you?"])
print("ONNX-encoded ids:", ids)
print("HF-encoded ids:  ", tokenizer("How are you?")["input_ids"])

decode_fn = OrtPyFunction.from_model(post)
real_ids = np.array(tokenizer("How are you?")["input_ids"], dtype=np.int64)
text = decode_fn(real_ids)
print("ONNX-decoded text of EN ids (sanity, using source vocab):", text)

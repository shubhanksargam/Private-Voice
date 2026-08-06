"""
Attempt 2: fuse SentencePieceTokenizer (source.spm) onto the encoder model
as a pre-processing step (Step-based PrePostProcessor pipeline, confirmed
working API), and hand-build a minimal standalone SentencepieceDecoder graph
(target.spm) for final detokenization.
"""
import onnx
import numpy as np
from pathlib import Path
from onnxruntime_extensions.tools.pre_post_processing import PrePostProcessor
from onnxruntime_extensions.tools.pre_post_processing.utils import create_named_value
from onnxruntime_extensions.tools.pre_post_processing.steps.nlp import SentencePieceTokenizer, TokenizerParam

MODEL_DIR = Path("models_mt/opus-mt-en-hi-onnx")
OUT_DIR = Path("models_mt/opus-mt-en-hi-onnx-int8")

# --- Fuse tokenizer onto the encoder ---
encoder_model = onnx.load(str(MODEL_DIR / "encoder_model.onnx"))
print("encoder original inputs:", [i.name for i in encoder_model.graph.input])

inputs = [create_named_value("input_text", onnx.TensorProto.STRING, [1, "num_sentences"])]
pipeline = PrePostProcessor(inputs, 18)
tok_param = TokenizerParam(vocab_or_file=str(MODEL_DIR / "source.spm"))
pipeline.add_pre_processing([SentencePieceTokenizer(tok_param, add_eos=True)])
fused_encoder = pipeline.run(encoder_model)
fused_encoder.ir_version = 10  # conservative, matches what onnxruntime-android is likely to support
print("fused encoder inputs:", [i.name for i in fused_encoder.graph.input])
print("fused encoder outputs:", [o.name for o in fused_encoder.graph.output])
onnx.save_model(fused_encoder, str(OUT_DIR / "encoder_with_tokenizer.onnx"))
print("saved encoder_with_tokenizer.onnx")

# --- Standalone detokenizer graph (hand-built, target.spm) ---
with open(MODEL_DIR / "target.spm", "rb") as f:
    target_spm_bytes = f.read()

ids_input = onnx.helper.make_tensor_value_info("ids", onnx.TensorProto.INT64, [None])
str_output = onnx.helper.make_tensor_value_info("str", onnx.TensorProto.STRING, [None])
node = onnx.helper.make_node(
    "SentencepieceDecoder",
    inputs=["ids"],
    outputs=["str"],
    domain="ai.onnx.contrib",
    model=target_spm_bytes,
)
graph = onnx.helper.make_graph([node], "detokenizer", [ids_input], [str_output])
model = onnx.helper.make_model(graph, opset_imports=[
    onnx.helper.make_opsetid("", 18),
    onnx.helper.make_opsetid("ai.onnx.contrib", 1),
])
model.ir_version = 10  # match what the installed onnxruntime (1.23.2) actually supports
onnx.save_model(model, str(OUT_DIR / "tokenizer_decode.onnx"))
print("saved tokenizer_decode.onnx")

# --- Test both ---
import onnxruntime as ort
from onnxruntime_extensions import get_library_path

so = ort.SessionOptions()
so.register_custom_ops_library(get_library_path())

enc_sess = ort.InferenceSession(str(OUT_DIR / "encoder_with_tokenizer.onnx"), so)
out = enc_sess.run(None, {"input_text": np.array([["How are you?"]], dtype=object)})
print("fused encoder ran ok, output shapes:", [o.shape for o in out])

dec_sess = ort.InferenceSession(str(OUT_DIR / "tokenizer_decode.onnx"), so)
from transformers import AutoTokenizer
tokenizer = AutoTokenizer.from_pretrained(str(MODEL_DIR))
real_ids = np.array(tokenizer("How are you?")["input_ids"], dtype=np.int64)
text_out = dec_sess.run(None, {"ids": real_ids})
print("decoder test (using EN ids against... need target vocab test):", text_out)

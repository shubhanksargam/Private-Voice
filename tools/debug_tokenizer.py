import onnx
import numpy as np
from pathlib import Path
from onnxruntime_extensions.tools.pre_post_processing import PrePostProcessor
from onnxruntime_extensions.tools.pre_post_processing.utils import create_named_value
from onnxruntime_extensions.tools.pre_post_processing.steps.nlp import SentencePieceTokenizer, TokenizerParam
import onnxruntime as ort
from onnxruntime_extensions import get_library_path
from transformers import AutoTokenizer

MODEL_DIR = Path("models_mt/opus-mt-en-hi-onnx")

# Minimal "model": Identity on input_ids and attention_mask, so we can see
# exactly what the fused tokenizer step actually produces.
ids_in = onnx.helper.make_tensor_value_info("input_ids", onnx.TensorProto.INT64, ["batch", "seq"])
mask_in = onnx.helper.make_tensor_value_info("attention_mask", onnx.TensorProto.INT64, ["batch", "seq"])
ids_out = onnx.helper.make_tensor_value_info("input_ids", onnx.TensorProto.INT64, ["batch", "seq"])
mask_out = onnx.helper.make_tensor_value_info("attention_mask", onnx.TensorProto.INT64, ["batch", "seq"])
n1 = onnx.helper.make_node("Identity", ["input_ids"], ["input_ids_out"])
n2 = onnx.helper.make_node("Identity", ["attention_mask"], ["attention_mask_out"])
graph = onnx.helper.make_graph(
    [n1, n2], "dummy",
    [ids_in, mask_in],
    [onnx.helper.make_tensor_value_info("input_ids_out", onnx.TensorProto.INT64, ["batch", "seq"]),
     onnx.helper.make_tensor_value_info("attention_mask_out", onnx.TensorProto.INT64, ["batch", "seq"])],
)
dummy_model = onnx.helper.make_model(graph, opset_imports=[onnx.helper.make_opsetid("", 18)])
dummy_model.ir_version = 10

inputs = [create_named_value("input_text", onnx.TensorProto.STRING, [1, "num_sentences"])]
pipeline = PrePostProcessor(inputs, 18)
tok_param = TokenizerParam(vocab_or_file=str(MODEL_DIR / "source.spm"))
pipeline.add_pre_processing([SentencePieceTokenizer(tok_param, add_eos=True)])
fused = pipeline.run(dummy_model)
fused.ir_version = 10

so = ort.SessionOptions()
so.register_custom_ops_library(get_library_path())
sess = ort.InferenceSession(fused.SerializeToString(), so)
print("dummy graph outputs:", [o.name for o in sess.get_outputs()])
out = sess.run(None, {"input_text": np.array([["How are you?"]], dtype=object)})
for name, val in zip([o.name for o in sess.get_outputs()], out):
    print(name, val)

tokenizer = AutoTokenizer.from_pretrained(str(MODEL_DIR))
print("HF reference:", tokenizer("How are you?"))

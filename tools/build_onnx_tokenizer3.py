"""
Attempt 3: MarianTokenizer's actual architecture uses SentencePiece only to
segment text into *pieces*; the model's real vocab ids come from a SEPARATE
vocab.json string->id table (61,950 entries) that does NOT match the raw
.spm model's own internal piece ids (32,000 entries) 1:1. Confirmed by
direct comparison: raw spm ids for "How are you?" were [122,28,14,18],
correct Marian ids are [244,54,27,22]. Fix: a custom tokenizer step that
runs SentencepieceTokenizer, then Gathers through a precomputed remap
table (built once from source.spm's piece order -> vocab.json's id order)
before output -- same 2-output shape as the library's own step, drop-in
compatible with the rest of the pipeline.
"""
import json
import onnx
import numpy as np
import sentencepiece as spm
from pathlib import Path
from onnxruntime_extensions.tools.pre_post_processing import PrePostProcessor
from onnxruntime_extensions.tools.pre_post_processing.utils import create_named_value
from onnxruntime_extensions.tools.pre_post_processing.step import Step

MODEL_DIR = Path("models_mt/opus-mt-en-hi-onnx")
OUT_DIR = Path("models_mt/opus-mt-en-hi-onnx-int8")


def build_remap_table(spm_path: Path, vocab_path: Path) -> np.ndarray:
    sp = spm.SentencePieceProcessor()
    sp.load(str(spm_path))
    with open(vocab_path, encoding="utf-8") as f:
        vocab = json.load(f)
    unk_id = vocab.get("<unk>", 0)
    table = np.array(
        [vocab.get(sp.id_to_piece(i), unk_id) for i in range(sp.get_piece_size())],
        dtype=np.int64,
    )
    return table


class MarianSentencePieceTokenizer(Step):
    """SentencePieceTokenizer + a Gather-based remap from spm piece-ids to
    Marian's actual vocab.json ids. See module docstring for why this is
    necessary -- MarianTokenizer's vocab.json id space isn't the .spm
    model's own id space."""

    def __init__(self, spm_file: Path, remap_table: np.ndarray, add_eos=True, name=None):
        super().__init__(["input_text"], ["input_ids", "attention_mask"], name)
        self._spm_file = spm_file
        self._remap_table = remap_table.astype(np.int64)
        self._add_eos = add_eos

    def _create_graph_for_step(self, graph: onnx.GraphProto, onnx_opset: int):
        input_type_str0, input_shape_str0 = self._get_input_type_and_shape_strs(graph, 0)
        assert input_type_str0 == "string"
        input_shape_0 = input_shape_str0.split(",")
        batch_dim = input_shape_0[0] if len(input_shape_0) > 1 else "1"
        prefix_ = f"step_{self.step_num}"
        output_shape_str = f"{batch_dim}, {prefix_}__num_ids"

        unsqueeze = (
            f"input_with_batch = Unsqueeze({self.input_names[0]}, i64_0)"
            if len(input_shape_0) == 1
            else f"input_with_batch = Identity({self.input_names[0]})"
        )

        add_eos_val = 1 if self._add_eos else 0
        graph_text = f"""\
            MarianSentencePieceTokenizer ({input_type_str0}[{input_shape_str0}] {self.input_names[0]})
                => (int64[{output_shape_str}] {self.output_names[0]}, int64[{output_shape_str}] {self.output_names[1]})
            {{
                i64_nbest_size = Constant <value = int64[1] {{0}}> ()
                f32_alpha = Constant <value = float[1] {{0}}> ()
                bool_add_bos = Constant <value = bool[1] {{0}}> ()
                bool_add_eos = Constant <value = bool[1] {{{add_eos_val}}}> ()
                bool_reverse = Constant <value = bool[1] {{0}}> ()
                i64_0 = Constant <value = int64[1] {{0}}> ()
                i64_neg1 = Constant <value = int64[1] {{-1}}> ()
                {unsqueeze}
                token, idx = com.microsoft.extensions.SentencepieceTokenizer (input_with_batch, i64_nbest_size, f32_alpha, bool_add_bos, bool_add_eos, bool_reverse)
                token_i64 = Cast <to = 7> (token)
                remapped = Gather <axis = 0> (remap_table, token_i64)
                input_ids_bdim = Unsqueeze(remapped, i64_0)
                {self.output_names[0]} = Cast <to = 7> (input_ids_bdim)
                attention_mask_i32 = Greater({self.output_names[0]}, i64_neg1)
                {self.output_names[1]} = Cast <to = 7> (attention_mask_i32)
            }}
            """
        converter_graph = onnx.parser.parse_graph(graph_text)

        with open(self._spm_file, "rb") as f:
            content = f.read()
        token_model_attr = onnx.helper.make_attribute("model", content)
        node_idx = next(i for i, v in enumerate(converter_graph.node) if v.op_type == "SentencepieceTokenizer")
        converter_graph.node[node_idx].attribute.append(token_model_attr)

        remap_initializer = onnx.numpy_helper.from_array(self._remap_table, name="remap_table")
        converter_graph.initializer.append(remap_initializer)

        return converter_graph


if __name__ == "__main__":
    remap = build_remap_table(MODEL_DIR / "source.spm", MODEL_DIR / "vocab.json")
    print("remap table size:", remap.shape, "sample:", remap[:5])

    encoder_model = onnx.load(str(MODEL_DIR / "encoder_model.onnx"))
    inputs = [create_named_value("input_text", onnx.TensorProto.STRING, [1, "num_sentences"])]
    pipeline = PrePostProcessor(inputs, 18)
    pipeline.add_pre_processing([MarianSentencePieceTokenizer(MODEL_DIR / "source.spm", remap, add_eos=True)])
    fused_encoder = pipeline.run(encoder_model)
    fused_encoder.ir_version = 10
    onnx.save_model(fused_encoder, str(OUT_DIR / "encoder_with_tokenizer.onnx"))
    print("saved corrected encoder_with_tokenizer.onnx")

    # quick check against HF reference ids
    import onnxruntime as ort
    from onnxruntime_extensions import get_library_path
    so = ort.SessionOptions()
    so.register_custom_ops_library(get_library_path())

    dummy_ids = onnx.helper.make_tensor_value_info("input_ids", onnx.TensorProto.INT64, ["batch", "seq"])
    # Reuse the same MarianSentencePieceTokenizer step against a trivial identity model for inspection.
    ids_in = onnx.helper.make_tensor_value_info("input_ids", onnx.TensorProto.INT64, ["batch", "seq"])
    mask_in = onnx.helper.make_tensor_value_info("attention_mask", onnx.TensorProto.INT64, ["batch", "seq"])
    n1 = onnx.helper.make_node("Identity", ["input_ids"], ["input_ids_out"])
    n2 = onnx.helper.make_node("Identity", ["attention_mask"], ["attention_mask_out"])
    g = onnx.helper.make_graph(
        [n1, n2], "dummy", [ids_in, mask_in],
        [onnx.helper.make_tensor_value_info("input_ids_out", onnx.TensorProto.INT64, ["batch", "seq"]),
         onnx.helper.make_tensor_value_info("attention_mask_out", onnx.TensorProto.INT64, ["batch", "seq"])],
    )
    dummy_model = onnx.helper.make_model(g, opset_imports=[onnx.helper.make_opsetid("", 18)])
    dummy_model.ir_version = 10
    pipeline2 = PrePostProcessor(inputs, 18)
    pipeline2.add_pre_processing([MarianSentencePieceTokenizer(MODEL_DIR / "source.spm", remap, add_eos=True)])
    fused_dummy = pipeline2.run(dummy_model)
    fused_dummy.ir_version = 10
    sess = ort.InferenceSession(fused_dummy.SerializeToString(), so)
    out = sess.run(None, {"input_text": np.array([["How are you?"]], dtype=object)})
    print("remapped ids:", out[0])
    print("expected (HF):  [[244  54  27  22   0]]")

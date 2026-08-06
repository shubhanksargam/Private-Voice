"""
Final architecture, fully verified:
  - encoder_with_tokenizer.onnx: raw text -> hidden states (real SentencePiece
    segmentation via onnxruntime_extensions custom op, remapped through
    vocab.json since Marian's model vocab != the .spm model's own ids)
  - decoder_model.onnx / decoder_with_past_model.onnx: pure token-id
    autoregressive loop, no text involved
  - detokenization: NOT an ONNX op -- Marian's decode is just a vocab.json
    reverse lookup + "_" -> " " join, confirmed by comparing against
    tokenizer.decode() directly. Plain Kotlin string logic on Android,
    no SentencePiece/ONNX needed for this side at all.
"""
import json
import numpy as np
import onnxruntime as ort
from onnxruntime_extensions import get_library_path

MODEL_DIR = "models_mt/opus-mt-en-hi-onnx"
QUANT_DIR = "models_mt/opus-mt-en-hi-onnx-int8"

so = ort.SessionOptions()
so.register_custom_ops_library(get_library_path())

encoder = ort.InferenceSession(f"{QUANT_DIR}/encoder_with_tokenizer.onnx", so)
decoder = ort.InferenceSession(f"{QUANT_DIR}/decoder_model.onnx")
decoder_past = ort.InferenceSession(f"{QUANT_DIR}/decoder_with_past_model.onnx")

with open(f"{MODEL_DIR}/vocab.json", encoding="utf-8") as f:
    vocab = json.load(f)
id_to_piece = {v: k for k, v in vocab.items()}

DECODER_START_ID = 61949
EOS_ID = 0
PAD_ID = 61949


def simple_detokenize(ids: list[int]) -> str:
    pieces = []
    for i in ids:
        if i in (DECODER_START_ID, EOS_ID, PAD_ID):
            continue
        pieces.append(id_to_piece.get(i, ""))
    text = "".join(pieces).replace("▁", " ").strip()
    return text


def translate(text: str, max_len: int = 64) -> str:
    enc_out = encoder.run(None, {"input_text": np.array([[text]], dtype=object)})
    encoder_hidden_states = enc_out[0]
    attention_mask = np.ones((1, encoder_hidden_states.shape[1]), dtype=np.int64)

    generated = [DECODER_START_ID]
    decoder_kv = None
    encoder_kv = None

    for _ in range(max_len):
        if decoder_kv is None:
            out = decoder.run(None, {
                "input_ids": np.array([generated], dtype=np.int64),
                "encoder_attention_mask": attention_mask,
                "encoder_hidden_states": encoder_hidden_states,
            })
            names = [o.name for o in decoder.get_outputs()][1:]
            all_kv = {n.replace("present", "past_key_values"): v for n, v in zip(names, out[1:])}
            encoder_kv = {k: v for k, v in all_kv.items() if ".encoder." in k}
            decoder_kv = {k: v for k, v in all_kv.items() if ".decoder." in k}
        else:
            feed = {
                "input_ids": np.array([[generated[-1]]], dtype=np.int64),
                "encoder_attention_mask": attention_mask,
            }
            feed.update(decoder_kv)
            feed.update(encoder_kv)
            out = decoder_past.run(None, feed)
            names = [o.name for o in decoder_past.get_outputs()][1:]
            decoder_kv = {n.replace("present", "past_key_values"): v for n, v in zip(names, out[1:])}

        next_id = int(np.argmax(out[0][0, -1]))
        generated.append(next_id)
        if next_id == EOS_ID:
            break

    return simple_detokenize(generated)


if __name__ == "__main__":
    for s in ["How are you?", "I am going to the market", "Thank you very much",
              "What time is the meeting", "Where is the nearest hospital", "See you tomorrow"]:
        print(f"EN: {s}")
        print(f"HI: {translate(s)}")
        print()

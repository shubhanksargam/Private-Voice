"""
Full end-to-end validation using ONLY the ONNX artifacts Android will
actually use: encoder_with_tokenizer.onnx (raw text -> hidden states),
decoder_model.onnx / decoder_with_past_model.onnx (token id loop, from
verify_opus_mt_onnx.py's already-proven logic), tokenizer_decode.onnx
(token ids -> raw text). No `transformers` tokenizer involved anywhere
in this script -- if this works, the Kotlin port has nothing left to
guess about correctness, only about API translation.
"""
import numpy as np
import onnxruntime as ort
from onnxruntime_extensions import get_library_path

QUANT_DIR = "models_mt/opus-mt-en-hi-onnx-int8"

so = ort.SessionOptions()
so.register_custom_ops_library(get_library_path())

encoder = ort.InferenceSession(f"{QUANT_DIR}/encoder_with_tokenizer.onnx", so)
decoder = ort.InferenceSession(f"{QUANT_DIR}/decoder_model.onnx")
decoder_past = ort.InferenceSession(f"{QUANT_DIR}/decoder_with_past_model.onnx")
detok = ort.InferenceSession(f"{QUANT_DIR}/tokenizer_decode.onnx", so)

DECODER_START_ID = 61949
EOS_ID = 0


def translate(text: str, max_len: int = 64) -> str:
    enc_out = encoder.run(None, {"input_text": np.array([[text]], dtype=object)})
    encoder_hidden_states = enc_out[0]
    # attention mask: all-ones, since the fused tokenizer graph doesn't expose it
    # separately -- fine for single, unpadded sentences (our actual use case).
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

    # Strip the decoder-start seed token and a trailing EOS -- the raw
    # SentencepieceDecoder op has no "skip_special_tokens" concept the way
    # HF's tokenizer.decode() does, and errors on ids outside its vocab
    # rather than silently ignoring them.
    content_ids = generated[1:]
    if content_ids and content_ids[-1] == EOS_ID:
        content_ids = content_ids[:-1]
    ids = np.array(content_ids, dtype=np.int64)
    text_out = detok.run(None, {"ids": ids})
    return text_out[0]


if __name__ == "__main__":
    for s in ["How are you?", "I am going to the market", "Thank you very much", "What time is the meeting"]:
        print(f"EN: {s}")
        print(f"HI: {translate(s)}")
        print()

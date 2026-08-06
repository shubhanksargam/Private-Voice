"""
Sanity check for the quantized opus-mt-en-hi ONNX export before porting the
inference loop to Kotlin. Mirrors exactly what the Android code will need to
do: tokenize, run the encoder once, run the decoder autoregressively
(no-past for step 0, with-past for subsequent steps), greedy-decode, detokenize.

Run: python tools/verify_opus_mt_onnx.py
"""
import numpy as np
import onnxruntime as ort
from transformers import AutoTokenizer

MODEL_DIR = "models_mt/opus-mt-en-hi-onnx"
QUANT_DIR = "models_mt/opus-mt-en-hi-onnx-int8"

tokenizer = AutoTokenizer.from_pretrained(MODEL_DIR)

encoder = ort.InferenceSession(f"{QUANT_DIR}/encoder_model.onnx")
decoder = ort.InferenceSession(f"{QUANT_DIR}/decoder_model.onnx")
decoder_past = ort.InferenceSession(f"{QUANT_DIR}/decoder_with_past_model.onnx")

print("encoder inputs:", [i.name for i in encoder.get_inputs()])
print("decoder (no past) inputs:", [i.name for i in decoder.get_inputs()])
print("decoder (with past) inputs:", [i.name for i in decoder_past.get_inputs()])
print("decoder (no past) outputs:", [o.name for o in decoder.get_outputs()])


def translate(text: str, max_len: int = 64) -> str:
    enc_inputs = tokenizer(text, return_tensors="np")
    enc_out = encoder.run(None, {
        "input_ids": enc_inputs["input_ids"].astype(np.int64),
        "attention_mask": enc_inputs["attention_mask"].astype(np.int64),
    })
    encoder_hidden_states = enc_out[0]

    decoder_start_token_id = 61949  # from generation_config.json / config.json (== pad_token_id)
    generated = [decoder_start_token_id]
    decoder_kv = None  # self-attention cache: updates every step
    encoder_kv = None  # cross-attention cache: fixed after step 0, with-past decoder doesn't re-emit it

    for step in range(max_len):
        if decoder_kv is None:
            out = decoder.run(None, {
                "input_ids": np.array([generated], dtype=np.int64),
                "encoder_attention_mask": enc_inputs["attention_mask"].astype(np.int64),
                "encoder_hidden_states": encoder_hidden_states,
            })
            names = [o.name for o in decoder.get_outputs()][1:]
            all_kv = {n.replace("present", "past_key_values"): v for n, v in zip(names, out[1:])}
            encoder_kv = {k: v for k, v in all_kv.items() if ".encoder." in k}
            decoder_kv = {k: v for k, v in all_kv.items() if ".decoder." in k}
        else:
            feed = {
                "input_ids": np.array([[generated[-1]]], dtype=np.int64),
                "encoder_attention_mask": enc_inputs["attention_mask"].astype(np.int64),
            }
            feed.update(decoder_kv)
            feed.update(encoder_kv)
            out = decoder_past.run(None, feed)
            names = [o.name for o in decoder_past.get_outputs()][1:]
            decoder_kv = {n.replace("present", "past_key_values"): v for n, v in zip(names, out[1:])}

        logits = out[0]
        next_id = int(np.argmax(logits[0, -1]))
        generated.append(next_id)

        if next_id == tokenizer.eos_token_id:
            break

    return tokenizer.decode(generated, skip_special_tokens=True)


if __name__ == "__main__":
    for s in ["How are you?", "What is the vacation going", "I am going to the market", "Thank you very much"]:
        print(f"EN: {s}")
        print(f"HI: {translate(s)}")
        print()

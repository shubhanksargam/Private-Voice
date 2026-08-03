# Reference transcripts

One `.txt` per recording, named to match the WAV: `eval/audio/mix_001.wav` pairs with
`eval/refs/mix_001.txt`.

Splits, taken from the filename prefix:

- `en_` — Indian English
- `hi_` — Hindi
- `mix_` — code-switched Hinglish

Write references the way you would want the keyboard to type them: real punctuation,
real casing, digits as digits. `tools/eval_wer.py` scores with punctuation stripped
*and* included, so you get the cost of punctuation errors as a separate column.

Aim for ~100 utterances, weighted toward `mix_` — that split is what decides whether
this project beats Google. Use realistic dictation content: messages, addresses,
names, amounts. Not read-aloud news copy, which flatters every ASR model.

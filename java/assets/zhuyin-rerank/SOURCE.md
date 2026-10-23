# Zhuyin candidate reranker model

- Upstream: https://huggingface.co/Funmula/0c-sequence-d512 (MIT, see LICENSE)
- Revision: df4b9f04f8aee1dd34dcb246ac2b83c4ab3d14a9 (version d512-pf512-20260926)
- Upstream model.onnx SHA-256: 71af1f0e77b2752cc71cbc04bf19c8e8f2e765662246f766e65a2822bab4e66e
- vocab.json and LICENSE are the upstream files unchanged.
- model.onnx here is the upstream model with weight-only quantization (output layer stored as
  its own MatMul weight, int8 input vocabulary table with per-row scales, 4-bit MatMulNBits for
  every MatMul; activations stay float32), 36,570,361 bytes,
  SHA-256 937729568d6b7fadeaea6bfb1f67530f8a5cce4f41642f01da6f4d3179f15a5d.

Reproduce with onnxruntime 1.31.0, onnx 1.23.2, onnx_ir 1.1.0, numpy 2.5.4:

```sh
python tools/zhuyin-rerank/quantize.py upstream/model.onnx java/assets/zhuyin-rerank/model.onnx
```

Dynamic int8 quantization (onnxruntime.quantization.quantize_dynamic) is not used: it also
quantizes activations, and ONNX Runtime on Android ARM then scores candidates wrongly.

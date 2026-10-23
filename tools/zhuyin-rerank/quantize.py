"""Builds java/assets/zhuyin-rerank/model.onnx from the upstream 0c-sequence-d512 model.

Weight-only quantization; activations stay float32. Dynamic int8 quantization (which also
quantizes activations) gives wrong scores with ONNX Runtime on Android ARM, so it is not used.

- The output layer's fixed rows of the vocabulary table become a separate MatMul weight (see
  untie_output), so ONNX Runtime does not fold them into a float32 constant at load time.
- The vocabulary table `m.network.decoder.weight` [27865, 512], now only read by the input
  Gather, is stored as int8 with one scale per row; each gathered row is converted back to
  float32 (Cast * scale).
- Every MatMul weight, including the output layer, becomes a 4-bit MatMulNBits weight
  (block size 32, symmetric). The small `lm_head.dense` Gemm stays float32.
- The node names used in untie_output are specific to upstream revision
  df4b9f04f8aee1dd34dcb246ac2b83c4ab3d14a9.

Usage: python quantize.py <upstream model.onnx> <output model.onnx>
Requires onnxruntime 1.31.0, onnx 1.23.2, onnx_ir 1.1.0, numpy 2.5.4.
"""
import sys

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper
from onnxruntime.quantization import matmul_nbits_quantizer

TABLE = "m.network.decoder.weight"


def untie_output(model):
    """
    The output layer reads fixed rows of the vocabulary table (GatherND with constant indices,
    then Gemm). ONNX Runtime folds that into a 57 MB float32 constant at load time, so store
    those rows as a plain MatMul weight instead; the MatMulNBits pass then quantizes it.
    """
    init = {i.name: i for i in model.graph.initializer}
    nodes = {n.name: n for n in model.graph.node}
    rows_node, bias_node, gemm = nodes["node_GatherND_742"], nodes["node_GatherND_749"], nodes["node_linear_49"]
    unsqueeze = nodes["node_Unsqueeze_740"]
    out_ids = numpy_helper.to_array(init[unsqueeze.input[0]])
    w = numpy_helper.to_array(init[TABLE]).astype(np.float32)
    bias = numpy_helper.to_array(init[bias_node.input[0]]).astype(np.float32)
    model.graph.initializer.extend([
        numpy_helper.from_array(np.ascontiguousarray(w[out_ids].T), "output_weight"),
        numpy_helper.from_array(bias[out_ids], "output_bias"),
    ])
    hidden, logits = gemm.input[0], gemm.output[0]
    for n in (rows_node, bias_node, gemm, unsqueeze):
        model.graph.node.remove(n)
    at = list(model.graph.node).index(next(n for n in model.graph.node if logits in n.input))
    model.graph.node.insert(at, helper.make_node("MatMul", [hidden, "output_weight"], ["output_matmul"], name="output_matmul"))
    model.graph.node.insert(at + 1, helper.make_node("Add", ["output_matmul", "output_bias"], [logits], name="output_add"))


def quantize_table(model):
    init = next(i for i in model.graph.initializer if i.name == TABLE)
    w = numpy_helper.to_array(init).astype(np.float32)
    scale = np.maximum(np.abs(w).max(axis=1), 1e-12) / 127.0
    wq = np.clip(np.round(w / scale[:, None]), -127, 127).astype(np.int8)
    model.graph.initializer.remove(init)
    model.graph.initializer.extend([
        numpy_helper.from_array(wq, TABLE + "_q"),
        numpy_helper.from_array(scale.astype(np.float32), TABLE + "_s"),
        numpy_helper.from_array(np.array([-1], dtype=np.int64), TABLE + "_ax"),
    ])

    nodes = []
    for n in model.graph.node:
        if TABLE not in n.input:
            nodes.append(n)
            continue
        if n.op_type not in ("Gather", "GatherND"):
            raise ValueError(f"{TABLE} is used by {n.op_type} {n.name}")
        out, idx = n.output[0], n.input[1]
        attrs = {a.name: helper.get_attribute_value(a) for a in n.attribute}
        nodes += [
            helper.make_node(n.op_type, [TABLE + "_q", idx], [out + "_q"], name=n.name + "_q", **attrs),
            helper.make_node(n.op_type, [TABLE + "_s", idx], [out + "_s"], name=n.name + "_s", **attrs),
            helper.make_node("Cast", [out + "_q"], [out + "_f"], to=TensorProto.FLOAT, name=n.name + "_c"),
            helper.make_node("Unsqueeze", [out + "_s", TABLE + "_ax"], [out + "_s1"], name=n.name + "_u"),
            helper.make_node("Mul", [out + "_f", out + "_s1"], [out], name=n.name + "_m"),
        ]
    del model.graph.node[:]
    model.graph.node.extend(nodes)


def main(src, dst):
    model = onnx.load(src)
    untie_output(model)
    quantize_table(model)
    config = matmul_nbits_quantizer.DefaultWeightOnlyQuantConfig(block_size=32, is_symmetric=True, bits=4)
    quantizer = matmul_nbits_quantizer.MatMulNBitsQuantizer(model, algo_config=config)
    quantizer.process()
    quantizer.model.save_model_to_file(dst, use_external_data_format=False)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])

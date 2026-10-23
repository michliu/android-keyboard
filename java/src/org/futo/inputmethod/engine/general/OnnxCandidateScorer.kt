package org.futo.inputmethod.engine.general

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.LongBuffer

/**
 * Scores Zhuyin candidates with the bundled 0c-sequence-d512 model (weight-only quantized) on
 * the CPU. [score] blocks; call it off the main thread, one call at a time. [score] and [close]
 * must run on the same single thread.
 */
class OnnxCandidateScorer private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val vocab: RerankVocab,
) : CandidateScorer, AutoCloseable {

    override fun covers(word: String): Boolean = vocab.covers(word)

    override fun score(context: String, reading: List<ZhuyinSyllable>, candidates: List<String>): FloatArray? {
        val t = RerankInput.build(vocab, context, reading, candidates) ?: return null
        val inputs = LinkedHashMap<String, OnnxTensor>()
        try {
            inputs["ids"] = row(t.ids)
            inputs["bases"] = row(t.bases)
            inputs["tones"] = row(t.tones)
            inputs["types"] = row(t.types)
            inputs["positions"] = row(t.positions)
            inputs["mask"] = OnnxTensor.createTensor(env, arrayOf(arrayOf(t.mask)))
            inputs["proj_pos"] = OnnxTensor.createTensor(env, LongBuffer.wrap(t.projPos), longArrayOf(t.projPos.size.toLong()))
            inputs["target_node"] = matrix(t.targetNode)
            inputs["target_ids"] = matrix(t.targetIds)
            session.run(inputs, setOf("scores")).use { result ->
                val scores = result.get(0).value as FloatArray
                return if (scores.size == candidates.size) scores else null
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    private fun row(values: LongArray) =
        OnnxTensor.createTensor(env, LongBuffer.wrap(values), longArrayOf(1, values.size.toLong()))

    private fun matrix(rows: Array<LongArray>): OnnxTensor {
        val width = rows.firstOrNull()?.size ?: 0
        val flat = LongArray(rows.size * width).also { out -> rows.forEachIndexed { r, v -> v.copyInto(out, r * width) } }
        return OnnxTensor.createTensor(env, LongBuffer.wrap(flat), longArrayOf(rows.size.toLong(), width.toLong()))
    }

    override fun close() {
        session.close()
    }

    companion object {
        private const val ASSET_DIR = "zhuyin-rerank"
        /** Sentences (up to 16 × 12 characters) need 4; 2 took ~350 ms on a phone. */
        private const val THREADS = 4

        /** Loads the bundled model; throws if it cannot (e.g. no native library on x86). */
        fun load(context: Context): OnnxCandidateScorer {
            val assets = context.assets
            val vocab = assets.open("$ASSET_DIR/vocab.json").use { RerankVocab.parse(it.readBytes().decodeToString()) }
            val model = assets.open("$ASSET_DIR/model.onnx").use { it.readBytes() }
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(THREADS)
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val session = options.use { env.createSession(model, it) }
            return OnnxCandidateScorer(env, session, vocab)
        }
    }
}

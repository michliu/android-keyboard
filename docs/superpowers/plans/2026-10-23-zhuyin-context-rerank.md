# 注音候選依前文排序 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 打 1–3 個注音音節時，用本機的 0c-sequence-d512 模型（int8）依游標前的文字評分 Rime 候選，必要時把最合理的候選移到第一位。

**Architecture:** 純 Kotlin 的 `ZhuyinReading`（拆組字區）、`RerankVocab`（字表）、`RerankInput`（組模型張量，候選合併成前綴樹一次評分）、`ZhuyinReranker`（決定順序）可在 JVM 單元測試；`OnnxCandidateScorer` 用 ONNX Runtime Android 執行 assets 中的模型；`ChineseIME` 在 Rime 送來候選後於單一背景執行緒評分，並以「顯示位置 → Rime 索引」對照表處理點選與空白鍵。

**Tech Stack:** Kotlin、ONNX Runtime Android 1.31.0（`ai.onnxruntime`）、kotlinx.coroutines、JUnit 4。

Spec：`docs/superpowers/specs/2026-10-23-zhuyin-context-rerank-design.md`

## Global Constraints

- 只用於注音布局（`isBopomofo`）；不在英文頁、編輯狀態（`editingState.active`）中評分。
- 只處理 1–3 個音節；4 個以上維持 Rime 順序。
- 每次最多評分 8 個候選；只評從清單開頭起、字數（code point）等於音節數的連續候選。
- `PROMOTE_MARGIN = 2.0f`：模型最高分的候選須比 Rime 第一名高出至少 2.0 才移到第一位，其餘候選維持 Rime 的相對順序。
- 前文：游標前最多 64 個 code point；字表外字元用 UNK（ID 4）。單音節時前文為空就不評分。
- 不評分：密碼欄位（`InputTypeUtils.isPasswordInputType` 或 `isVisiblePasswordInputType`）、`EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING`。
- 單次評分超過 300 ms 的結果丟棄（不調整），記 log。
- 只依照模型卡（MIT）公開的輸入格式自行實作；**不得複製或參考 0c-ime 的程式碼（AGPL-3.0）**。
- 模型 assets：`java/assets/zhuyin-rerank/`（`model.onnx`、`vocab.json`、`LICENSE`、`SOURCE.md`）。
- 設定鍵 `ChineseIME_rerank`（Boolean，預設 true）；介面字串用英文，與既有中文設定一致。
- Commit 結尾必須是這兩行（前面空一行）：
  ```
  Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01SxYaJPcW5bNCc9K2YVp6xA
  ```
- 單元測試指令：`./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.engine.general.*'`；建置：`./gradlew assembleUnstableDebug`。

## File Structure

| 檔案 | 責任 |
|---|---|
| `java/assets/zhuyin-rerank/model.onnx` | int8 模型（33,033,544 bytes，SHA-256 `6f26d3778dad91fa2376ca786c02073d062a825a78ae14e60a889babf6080b31`） |
| `java/assets/zhuyin-rerank/vocab.json` | 字表（上游原檔） |
| `java/assets/zhuyin-rerank/LICENSE` | 上游 MIT 授權原檔 |
| `java/assets/zhuyin-rerank/SOURCE.md` | 來源、revision、雜湊、量化指令 |
| `java/src/org/futo/inputmethod/engine/general/ZhuyinReading.kt` | `ZhuyinSyllable`、拆組字區文字 |
| `java/src/org/futo/inputmethod/engine/general/RerankVocab.kt` | 讀 `vocab.json`，字元／基底 → ID |
| `java/src/org/futo/inputmethod/engine/general/RerankInput.kt` | `RerankTensors`、組出一次評分的張量 |
| `java/src/org/futo/inputmethod/engine/general/ZhuyinReranker.kt` | `CandidateScorer` 介面、挑可評分候選、決定顯示順序 |
| `java/src/org/futo/inputmethod/engine/general/OnnxCandidateScorer.kt` | 用 ONNX Runtime 執行模型 |
| `java/src/org/futo/inputmethod/engine/general/ChineseIME.kt` | 設定、載入、評分時機、選字對照 |
| `java/res/values/strings-uix.xml` | 設定字串 |
| `build.gradle` | 相依、排除 x86 的 ONNX Runtime 原生庫 |
| `tests/unit/org/futo/inputmethod/engine/general/ZhuyinReadingTest.kt`、`RerankInputTest.kt`、`ZhuyinRerankerTest.kt` | 單元測試 |

---

### Task 1: 模型檔案、拆組字區、字表與模型輸入

**Files:**
- Create: `java/assets/zhuyin-rerank/model.onnx`、`vocab.json`、`LICENSE`、`SOURCE.md`
- Create: `java/src/org/futo/inputmethod/engine/general/ZhuyinReading.kt`
- Create: `java/src/org/futo/inputmethod/engine/general/RerankVocab.kt`
- Create: `java/src/org/futo/inputmethod/engine/general/RerankInput.kt`
- Test: `tests/unit/org/futo/inputmethod/engine/general/ZhuyinReadingTest.kt`
- Test: `tests/unit/org/futo/inputmethod/engine/general/RerankInputTest.kt`

**Interfaces:**
- Produces:
  - `data class ZhuyinSyllable(val base: String, val tone: Int)`
  - `object ZhuyinReading { fun parse(preedit: String): List<ZhuyinSyllable>? }`
  - `class RerankVocab { fun charId(ch: String): Int?; fun baseId(base: String): Int?; fun covers(word: String): Boolean; companion object { fun parse(json: String): RerankVocab } }`
  - `class RerankTensors(val ids: LongArray, val bases: LongArray, val tones: LongArray, val types: LongArray, val positions: LongArray, val mask: Array<BooleanArray>, val projPos: LongArray, val targetNode: Array<LongArray>, val targetIds: Array<LongArray>)`
  - `object RerankInput { const val MAX_CONTEXT = 64; fun build(vocab: RerankVocab, context: String, reading: List<ZhuyinSyllable>, candidates: List<String>): RerankTensors? }`

- [ ] **Step 1: 放入模型檔案**

來源檔已在 controller 的 scratchpad（`S=/private/tmp/claude-501/-Users-michliu-worker-android-keyboard/22de418b-623e-4091-aec6-e655a099ddee/scratchpad`）：

```bash
S=/private/tmp/claude-501/-Users-michliu-worker-android-keyboard/22de418b-623e-4091-aec6-e655a099ddee/scratchpad
mkdir -p java/assets/zhuyin-rerank
cp $S/m-select-int8.onnx java/assets/zhuyin-rerank/model.onnx
cp $S/m-select/vocab.json $S/m-select/LICENSE java/assets/zhuyin-rerank/
shasum -a 256 java/assets/zhuyin-rerank/*
```

Expected：`model.onnx` 為 `6f26d3778dad91fa2376ca786c02073d062a825a78ae14e60a889babf6080b31`，`vocab.json` 為 `556dffaffbf4c05211a846dcee55ef735cea68c5754f9bffcc6f9588124172b3`，`LICENSE` 為 `ab1525e5aba48f95223228d1fcd045820783e6b3846972fbec67970314177e4f`。任何一個不符就停下來回報。

建立 `java/assets/zhuyin-rerank/SOURCE.md`：

````markdown
# Zhuyin candidate reranker model

- Upstream: https://huggingface.co/Funmula/0c-sequence-d512 (MIT, see LICENSE)
- Revision: df4b9f04f8aee1dd34dcb246ac2b83c4ab3d14a9 (version d512-pf512-20260926)
- Upstream model.onnx SHA-256: 71af1f0e77b2752cc71cbc04bf19c8e8f2e765662246f766e65a2822bab4e66e
- vocab.json and LICENSE are the upstream files unchanged.
- model.onnx here is the upstream model with dynamic int8 weight quantization,
  SHA-256 6f26d3778dad91fa2376ca786c02073d062a825a78ae14e60a889babf6080b31.

Reproduce with onnxruntime 1.31.0, onnx 1.23.2, numpy 2.5.4:

```python
from onnxruntime.quantization import quantize_dynamic, QuantType
quantize_dynamic("model.onnx", "model-int8.onnx", weight_type=QuantType.QInt8)
```
````

- [ ] **Step 2: 寫 `ZhuyinReading` 的失敗測試**

`tests/unit/org/futo/inputmethod/engine/general/ZhuyinReadingTest.kt`：

```kotlin
package org.futo.inputmethod.engine.general

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZhuyinReadingTest {
    @Test
    fun splitsOnSpacesAndReadsTones() {
        assertEquals(
            listOf(ZhuyinSyllable("ㄍㄨㄥ", 1), ZhuyinSyllable("ㄩㄢ", 2)),
            ZhuyinReading.parse("ㄍㄨㄥ ㄩㄢˊ")
        )
    }

    @Test
    fun readsEveryToneMark() {
        assertEquals(
            listOf(
                ZhuyinSyllable("ㄇㄚ", 1), ZhuyinSyllable("ㄇㄚ", 2), ZhuyinSyllable("ㄇㄚ", 3),
                ZhuyinSyllable("ㄇㄚ", 4), ZhuyinSyllable("ㄇㄚ", 5),
            ),
            ZhuyinReading.parse("ㄇㄚˉ ㄇㄚˊ ㄇㄚˇ ㄇㄚˋ ㄇㄚ˙")
        )
    }

    @Test
    fun ignoresExtraWhitespace() {
        assertEquals(listOf(ZhuyinSyllable("ㄗㄞ", 4)), ZhuyinReading.parse("  ㄗㄞˋ "))
    }

    @Test
    fun rejectsTextThatIsNotZhuyin() {
        assertNull(ZhuyinReading.parse("公園ㄌㄧˇ"))
        assertNull(ZhuyinReading.parse("ab"))
    }

    @Test
    fun rejectsAToneWithoutASyllable() {
        assertNull(ZhuyinReading.parse("ˊ"))
    }

    @Test
    fun rejectsEmptyText() {
        assertNull(ZhuyinReading.parse(""))
        assertNull(ZhuyinReading.parse("   "))
    }
}
```

- [ ] **Step 3: 確認測試失敗**

Run: `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.engine.general.ZhuyinReadingTest'`
Expected: 編譯失敗，`Unresolved reference: ZhuyinReading`。

- [ ] **Step 4: 實作 `ZhuyinReading`**

`java/src/org/futo/inputmethod/engine/general/ZhuyinReading.kt`：

```kotlin
package org.futo.inputmethod.engine.general

/** One Zhuyin syllable: its symbols without the tone mark, and its tone (1–4, 5 = neutral). */
data class ZhuyinSyllable(val base: String, val tone: Int)

/** Reads Rime's Zhuyin preedit, where syllables are separated by spaces (e.g. "ㄍㄨㄥ ㄩㄢˊ"). */
object ZhuyinReading {
    private val TONES = mapOf('ˉ' to 1, 'ˊ' to 2, 'ˇ' to 3, 'ˋ' to 4, '˙' to 5)

    /** Zhuyin letters ㄅ (U+3105) to ㄩ (U+3129). */
    private fun isZhuyinLetter(c: Char) = c in 'ㄅ'..'ㄩ'

    /** The syllables of [preedit], or null if it is empty or holds anything but Zhuyin. */
    fun parse(preedit: String): List<ZhuyinSyllable>? {
        val tokens = preedit.split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        return tokens.map { token ->
            val tone = TONES[token.last()]
            val base = if (tone != null) token.dropLast(1) else token
            if (base.isEmpty() || !base.all(::isZhuyinLetter)) return null
            ZhuyinSyllable(base, tone ?: 1)
        }
    }
}
```

- [ ] **Step 5: 確認 `ZhuyinReadingTest` 通過**

Run: `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.engine.general.ZhuyinReadingTest'`
Expected: 6 tests PASS。

- [ ] **Step 6: 寫 `RerankInput` 的失敗測試**

預期值由電腦上依模型卡格式產生，並已驗證合併評分與逐一評分的分數一致（誤差 < 2e-5）。測試讀取 repo 內的 `java/assets/zhuyin-rerank/vocab.json`（Gradle 單元測試的工作目錄是專案根目錄）。

`tests/unit/org/futo/inputmethod/engine/general/RerankInputTest.kt`：

```kotlin
package org.futo.inputmethod.engine.general

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RerankInputTest {
    private val vocab = RerankVocab.parse(File("java/assets/zhuyin-rerank/vocab.json").readText())

    private fun syl(s: String) = ZhuyinReading.parse(s)!!
    private fun longs(vararg x: Long) = x
    private fun maskOf(t: RerankTensors) =
        t.mask.map { row -> row.joinToString("") { if (it) "1" else "0" } }
    private fun rows(t: Array<LongArray>) = t.map { it.toList() }

    @Test
    fun vocabularyIds() {
        assertEquals(7269, vocab.charId("候"))
        assertEquals(268, vocab.baseId("ㄓㄥ"))
        assertEquals(221, vocab.baseId("ㄐㄧㄢ"))
        assertNull(vocab.charId("𠀀"))
        assertNull(vocab.baseId("ㄅㄅ"))
        assertTrue(vocab.covers("政見"))
        assertFalse(vocab.covers("政𠀀"))
    }

    @Test
    fun twoSyllablesTwoCandidates() {
        val t = RerankInput.build(vocab, "候選人發表", syl("ㄓㄥˋ ㄐㄧㄢˋ"), listOf("證件", "政見"))!!
        assertArrayEquals(longs(1, 7269, 23690, 6921, 17055, 21630, 2, 0, 0, 3, 22364, 12652), t.ids)
        assertArrayEquals(longs(0, 0, 0, 0, 0, 0, 0, 268, 221, 0, 0, 0), t.bases)
        assertArrayEquals(longs(0, 0, 0, 0, 0, 0, 0, 4, 4, 0, 0, 0), t.tones)
        assertArrayEquals(longs(0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 2, 2), t.types)
        assertArrayEquals(longs(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 10), t.positions)
        assertArrayEquals(longs(9, 10, 11), t.projPos)
        assertEquals(listOf(listOf(0L, 1L), listOf(0L, 2L)), rows(t.targetNode))
        assertEquals(listOf(listOf(22358L, 6973L), listOf(12646L, 21912L)), rows(t.targetIds))
        assertEquals(
            listOf(
                "100000000000", "110000000000", "111000000000", "111100000000",
                "111110000000", "111111000000", "111111100000", "111111110000",
                "111111111000", "111111111100", "111111111110", "111111111101",
            ),
            maskOf(t)
        )
    }

    @Test
    fun oneSyllableUsesOnlyTheContext() {
        val t = RerankInput.build(vocab, "我現在", syl("ㄗㄞˋ"), listOf("再", "在"))!!
        assertArrayEquals(longs(1, 11839, 16419, 9055, 2, 3), t.ids)
        assertArrayEquals(longs(0, 0, 0, 0, 0, 0), t.bases)
        assertArrayEquals(longs(0, 0, 0, 0, 0, 0), t.tones)
        assertArrayEquals(longs(0, 0, 0, 0, 0, 0), t.types)
        assertArrayEquals(longs(0, 1, 2, 3, 4, 5), t.positions)
        assertArrayEquals(longs(5), t.projPos)
        assertEquals(listOf(listOf(0L), listOf(0L)), rows(t.targetNode))
        assertEquals(listOf(listOf(7628L), listOf(9049L)), rows(t.targetIds))
        assertEquals(listOf("100000", "110000", "111000", "111100", "111110", "111111"), maskOf(t))
    }

    @Test
    fun threeSyllablesShareCandidatePrefixes() {
        val t = RerankInput.build(vocab, "", syl("ㄍㄨㄥ ㄩㄢˊ ㄌㄧˇ"), listOf("公園裡", "公元裡", "公園禮"))!!
        assertArrayEquals(longs(1, 2, 0, 0, 0, 3, 7602, 9033, 7567), t.ids)
        assertArrayEquals(longs(0, 0, 177, 415, 142, 0, 0, 0, 0), t.bases)
        assertArrayEquals(longs(0, 0, 1, 2, 3, 0, 0, 0, 0), t.tones)
        assertArrayEquals(longs(0, 0, 1, 1, 1, 0, 2, 2, 2), t.types)
        assertArrayEquals(longs(0, 1, 2, 3, 4, 5, 6, 7, 7), t.positions)
        assertArrayEquals(longs(5, 6, 7, 8), t.projPos)
        assertEquals(listOf(listOf(0L, 1L, 2L), listOf(0L, 1L, 3L), listOf(0L, 1L, 2L)), rows(t.targetNode))
        assertEquals(
            listOf(listOf(7596L, 9027L, 21743L), listOf(7596L, 7561L, 21743L), listOf(7596L, 9027L, 17864L)),
            rows(t.targetIds)
        )
        assertEquals(
            listOf(
                "100000000", "110000000", "111000000", "111100000", "111110000",
                "111111000", "111111100", "111111110", "111111101",
            ),
            maskOf(t)
        )
    }

    @Test
    fun keepsTheLast64ContextCharactersAndUsesUnkForUnknownOnes() {
        val context = "𠀀" + "我".repeat(70)
        val t = RerankInput.build(vocab, context, syl("ㄗㄞˋ"), listOf("再", "在"))!!
        // BOS + 64 context + SEP + OUT
        assertEquals(67, t.ids.size)
        assertTrue(t.ids.slice(1..64).all { it == vocab.charId("我")!!.toLong() })

        val unk = RerankInput.build(vocab, "𠀀", syl("ㄗㄞˋ"), listOf("再", "在"))!!
        assertArrayEquals(longs(1, 4, 2, 3), unk.ids)
    }

    @Test
    fun rejectsUnsupportedInput() {
        // One syllable needs context.
        assertNull(RerankInput.build(vocab, "", syl("ㄗㄞˋ"), listOf("再", "在")))
        // Candidate length must match the syllable count.
        assertNull(RerankInput.build(vocab, "我", syl("ㄓㄥˋ ㄐㄧㄢˋ"), listOf("證件", "證")))
        // Unknown candidate character or Zhuyin base.
        assertNull(RerankInput.build(vocab, "我", syl("ㄓㄥˋ ㄐㄧㄢˋ"), listOf("證件", "政𠀀")))
        assertNull(RerankInput.build(vocab, "我", listOf(ZhuyinSyllable("ㄅㄅ", 1), ZhuyinSyllable("ㄐㄧㄢ", 4)), listOf("證件", "政見")))
        // No candidates, or no syllables.
        assertNull(RerankInput.build(vocab, "我", syl("ㄓㄥˋ ㄐㄧㄢˋ"), emptyList()))
        assertNull(RerankInput.build(vocab, "我", emptyList(), listOf("再")))
    }
}
```

- [ ] **Step 7: 確認測試失敗**

Run: `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.engine.general.RerankInputTest'`
Expected: 編譯失敗，`Unresolved reference: RerankVocab`。

- [ ] **Step 8: 實作 `RerankVocab`**

`java/src/org/futo/inputmethod/engine/general/RerankVocab.kt`：

```kotlin
package org.futo.inputmethod.engine.general

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The reranker's vocabulary (`vocab.json`). IDs 0–7 are special tokens, characters start at 8 and
 * Zhuyin bases (syllables without tone) at 2, in file order.
 */
class RerankVocab private constructor(
    private val chars: Map<String, Int>,
    private val bases: Map<String, Int>,
) {
    /** ID of the single character [ch] (one code point), or null if the model does not know it. */
    fun charId(ch: String): Int? = chars[ch]

    fun baseId(base: String): Int? = bases[base]

    /** True if every character of [word] is in the vocabulary. */
    fun covers(word: String): Boolean = codePoints(word).all { it in chars }

    @Serializable
    private class VocabFile(val characters: List<String>, val bases: List<String>)

    companion object {
        const val PAD = 0
        const val BOS = 1
        const val SEP = 2
        const val OUT = 3
        const val UNK = 4
        private const val FIRST_CHAR = 8
        private const val FIRST_BASE = 2

        private val JSON = Json { ignoreUnknownKeys = true }

        fun parse(json: String): RerankVocab {
            val file = JSON.decodeFromString(VocabFile.serializer(), json)
            return RerankVocab(
                file.characters.withIndex().associate { (i, c) -> c to i + FIRST_CHAR },
                file.bases.withIndex().associate { (i, b) -> b to i + FIRST_BASE },
            )
        }

        /** [text] split into one string per code point. */
        fun codePoints(text: String): List<String> {
            val out = ArrayList<String>(text.length)
            var i = 0
            while (i < text.length) {
                val next = text.offsetByCodePoints(i, 1)
                out.add(text.substring(i, next))
                i = next
            }
            return out
        }
    }
}
```

- [ ] **Step 9: 實作 `RerankInput`**

輸入格式（取自模型卡）：`[BOS] 前文 [SEP] 讀音×n [OUT] 候選前 n−1 字`。讀音 token 的 id 為 0、type 為 1、帶 base／tone；`OUT` 的 type 為 0；候選字 token 的 type 為 2。單音節時不放讀音 token（只看前文）。多個候選共用相同的開頭時合併成一棵樹：每個「候選前 i 個字」的節點只出現一次、位置是 `OUT 的位置 + i`、只能看到共用部分（前文、讀音、OUT）、自己的祖先節點與自己。`projPos` 是 OUT 與所有樹節點的位置（遞增）；`targetNode[c][i]` 是「預測候選 c 第 i 個字」的節點在 `projPos` 中的索引；`targetIds[c][i]` 是該字的 ID 減 6。

`java/src/org/futo/inputmethod/engine/general/RerankInput.kt`：

```kotlin
package org.futo.inputmethod.engine.general

/** Model inputs for scoring several candidates in one run; see [RerankInput.build]. */
class RerankTensors(
    val ids: LongArray,
    val bases: LongArray,
    val tones: LongArray,
    val types: LongArray,
    val positions: LongArray,
    /** Attention mask, [query][key]. */
    val mask: Array<BooleanArray>,
    val projPos: LongArray,
    val targetNode: Array<LongArray>,
    val targetIds: Array<LongArray>,
)

/**
 * Builds the reranker's input, following the model card's format:
 * `[BOS] context [SEP] reading×n [OUT] first n−1 characters of the candidate`.
 * Candidates sharing a prefix share its tokens (a tree), so one run scores them all.
 */
object RerankInput {
    const val MAX_CONTEXT = 64

    private const val TYPE_TEXT = 0L
    private const val TYPE_READING = 1L
    private const val TYPE_CANDIDATE = 2L
    private const val TARGET_OFFSET = 6

    /**
     * Inputs to score [candidates] after [context] for [reading], or null if the model cannot
     * score them: no syllables or candidates, a candidate whose length differs from the syllable
     * count, an unknown candidate character or Zhuyin base, or one syllable without context
     * (a single syllable is scored from the context alone).
     */
    fun build(vocab: RerankVocab, context: String, reading: List<ZhuyinSyllable>, candidates: List<String>): RerankTensors? {
        val n = reading.size
        if (n == 0 || candidates.isEmpty()) return null
        val textOnly = n == 1
        if (textOnly && context.isEmpty()) return null

        val encoded = candidates.map { word ->
            val chars = RerankVocab.codePoints(word)
            if (chars.size != n) return null
            chars.map { vocab.charId(it) ?: return null }
        }
        val sounds = if (textOnly) emptyList() else reading.map { (vocab.baseId(it.base) ?: return null) to it.tone }

        val ids = ArrayList<Long>(); val bases = ArrayList<Long>(); val tones = ArrayList<Long>()
        val types = ArrayList<Long>(); val positions = ArrayList<Long>()
        fun add(id: Int, base: Int, tone: Int, type: Long, position: Int) {
            ids.add(id.toLong()); bases.add(base.toLong()); tones.add(tone.toLong())
            types.add(type); positions.add(position.toLong())
        }

        add(RerankVocab.BOS, 0, 0, TYPE_TEXT, ids.size)
        RerankVocab.codePoints(context).takeLast(MAX_CONTEXT).forEach {
            add(vocab.charId(it) ?: RerankVocab.UNK, 0, 0, TYPE_TEXT, ids.size)
        }
        add(RerankVocab.SEP, 0, 0, TYPE_TEXT, ids.size)
        sounds.forEach { (base, tone) -> add(RerankVocab.PAD, base, tone, TYPE_READING, ids.size) }
        val out = ids.size
        add(RerankVocab.OUT, 0, 0, TYPE_TEXT, out)
        val shared = ids.size

        // Tree node for each distinct candidate prefix of length 1..n-1; the empty prefix is OUT.
        val nodeOf = HashMap<List<Int>, Int>().apply { put(emptyList(), out) }
        val parent = HashMap<Int, Int>()
        for (chars in encoded) {
            for (i in 1 until n) {
                val prefix = chars.subList(0, i)
                if (prefix in nodeOf) continue
                val node = ids.size
                parent[node] = nodeOf.getValue(chars.subList(0, i - 1))
                nodeOf[prefix] = node
                add(chars[i - 1], 0, 0, TYPE_CANDIDATE, out + i)
            }
        }

        val length = ids.size
        val mask = Array(length) { q ->
            BooleanArray(length).also { row ->
                if (q < shared) {
                    for (k in 0..q) row[k] = true
                } else {
                    for (k in 0 until shared) row[k] = true
                    var a: Int? = q
                    while (a != null) { row[a] = true; a = parent[a] }
                }
            }
        }

        val proj = nodeOf.values.sorted()
        val projIndex = proj.withIndex().associate { (i, p) -> p to i.toLong() }
        val targetNode = Array(encoded.size) { c ->
            LongArray(n) { i -> projIndex.getValue(nodeOf.getValue(encoded[c].subList(0, i))) }
        }
        val targetIds = Array(encoded.size) { c ->
            LongArray(n) { i -> (encoded[c][i] - TARGET_OFFSET).toLong() }
        }

        return RerankTensors(
            ids.toLongArray(), bases.toLongArray(), tones.toLongArray(), types.toLongArray(),
            positions.toLongArray(), mask, proj.map { it.toLong() }.toLongArray(), targetNode, targetIds,
        )
    }
}
```

- [ ] **Step 10: 確認兩個測試類別都通過**

Run: `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.engine.general.*'`
Expected: `ZhuyinReadingTest` 6 個、`RerankInputTest` 6 個全部 PASS，其餘既有測試也 PASS。

- [ ] **Step 11: Commit**

```bash
git add java/assets/zhuyin-rerank java/src/org/futo/inputmethod/engine/general/ZhuyinReading.kt \
  java/src/org/futo/inputmethod/engine/general/RerankVocab.kt java/src/org/futo/inputmethod/engine/general/RerankInput.kt \
  tests/unit/org/futo/inputmethod/engine/general/ZhuyinReadingTest.kt tests/unit/org/futo/inputmethod/engine/general/RerankInputTest.kt
git commit -m "Add the Zhuyin reranker model and its input builder

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01SxYaJPcW5bNCc9K2YVp6xA"
```

---

### Task 2: 決定顯示順序

**Files:**
- Create: `java/src/org/futo/inputmethod/engine/general/ZhuyinReranker.kt`
- Test: `tests/unit/org/futo/inputmethod/engine/general/ZhuyinRerankerTest.kt`

**Interfaces:**
- Consumes: `ZhuyinSyllable`、`RerankVocab.codePoints(text: String): List<String>`（Task 1）
- Produces:
  - `interface CandidateScorer { fun covers(word: String): Boolean; fun score(context: String, reading: List<ZhuyinSyllable>, candidates: List<String>): FloatArray? }`
  - `object ZhuyinReranker { const val PROMOTE_MARGIN = 2.0f; const val MAX_SCORED = 8; const val MAX_SYLLABLES = 3; fun scorable(candidates: List<String>, syllables: Int, covers: (String) -> Boolean): List<Int>; fun order(candidateCount: Int, scorable: List<Int>, scores: FloatArray): IntArray? }`

- [ ] **Step 1: 寫失敗測試**

`tests/unit/org/futo/inputmethod/engine/general/ZhuyinRerankerTest.kt`：

```kotlin
package org.futo.inputmethod.engine.general

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZhuyinRerankerTest {
    private val all: (String) -> Boolean = { true }

    @Test
    fun scorableTakesTheLeadingRunOfTheSyllableLength() {
        val words = listOf("證件", "整件", "正見", "政見", "整", "證件夾")
        assertEquals(listOf(0, 1, 2, 3), ZhuyinReranker.scorable(words, 2, all))
    }

    @Test
    fun scorableStopsAtEight() {
        assertEquals((0 until 8).toList(), ZhuyinReranker.scorable(List(12) { "甲乙" }, 2, all))
    }

    @Test
    fun scorableSkipsUncoveredCandidatesButNeedsTheFirst() {
        val covers: (String) -> Boolean = { !it.contains('𠀀') }
        assertEquals(listOf(0, 2), ZhuyinReranker.scorable(listOf("證件", "政𠀀", "政見"), 2, covers))
        assertEquals(emptyList<Int>(), ZhuyinReranker.scorable(listOf("證𠀀", "政見"), 2, covers))
    }

    @Test
    fun scorableCountsCodePoints() {
        assertEquals(listOf(0, 1), ZhuyinReranker.scorable(listOf("𠀀字", "政見"), 2, all))
    }

    @Test
    fun scorableIsEmptyWhenTheFirstCandidateIsShorter() {
        assertEquals(emptyList<Int>(), ZhuyinReranker.scorable(listOf("證", "政見"), 2, all))
    }

    @Test
    fun promotesTheBestWhenItWinsByTheMargin() {
        // Rime order: 證件 整件 正見 政見 整 ; scored: first four.
        val order = ZhuyinReranker.order(5, listOf(0, 1, 2, 3), floatArrayOf(-6.1f, -18.3f, -7.3f, -0.004f))
        assertArrayEquals(intArrayOf(3, 0, 1, 2, 4), order)
    }

    @Test
    fun keepsRimeOrderBelowTheMargin() {
        assertNull(ZhuyinReranker.order(2, listOf(0, 1), floatArrayOf(-3.0f, -1.01f)))
    }

    @Test
    fun promotesAtExactlyTheMargin() {
        assertArrayEquals(intArrayOf(1, 0), ZhuyinReranker.order(2, listOf(0, 1), floatArrayOf(-3.0f, -1.0f)))
    }

    @Test
    fun keepsRimeOrderWhenRimeFirstIsBest() {
        assertNull(ZhuyinReranker.order(3, listOf(0, 1, 2), floatArrayOf(-0.1f, -5f, -9f)))
    }

    @Test
    fun promotesAcrossAnUnscoredCandidate() {
        // Rime index 1 was not scorable; the winner (Rime 2) moves to the front, the rest keep order.
        assertArrayEquals(intArrayOf(2, 0, 1, 3), ZhuyinReranker.order(4, listOf(0, 2), floatArrayOf(-7f, -0.5f)))
    }

    @Test
    fun rejectsMismatchedScores() {
        assertNull(ZhuyinReranker.order(3, listOf(0, 1, 2), floatArrayOf(-1f, 0f)))
        assertNull(ZhuyinReranker.order(3, listOf(0, 1), floatArrayOf(Float.NaN, 0f)))
    }
}
```

- [ ] **Step 2: 確認測試失敗**

Run: `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.engine.general.ZhuyinRerankerTest'`
Expected: 編譯失敗，`Unresolved reference: ZhuyinReranker`。

- [ ] **Step 3: 實作**

`java/src/org/futo/inputmethod/engine/general/ZhuyinReranker.kt`：

```kotlin
package org.futo.inputmethod.engine.general

/** Scores Zhuyin candidates by how well they follow the text before the cursor. */
interface CandidateScorer {
    /** True if the scorer knows every character of [word]. */
    fun covers(word: String): Boolean

    /** Log-probability of each candidate, in order, or null if they cannot be scored. */
    fun score(context: String, reading: List<ZhuyinSyllable>, candidates: List<String>): FloatArray?
}

/**
 * Decides whether a candidate should move ahead of Rime's first one. Only one candidate moves,
 * and only when the model clearly prefers it; everything else keeps Rime's order.
 */
object ZhuyinReranker {
    const val PROMOTE_MARGIN = 2.0f
    const val MAX_SCORED = 8
    const val MAX_SYLLABLES = 3

    /**
     * Rime indexes worth scoring: from the start of [candidates], those as long as [syllables]
     * (in code points), up to [MAX_SCORED], skipping ones [covers] rejects. Empty when Rime's
     * first candidate cannot be scored, since there would be nothing to compare against.
     */
    fun scorable(candidates: List<String>, syllables: Int, covers: (String) -> Boolean): List<Int> {
        val run = candidates.take(MAX_SCORED)
            .takeWhile { it.codePointCount(0, it.length) == syllables }
        if (run.isEmpty() || !covers(run[0])) return emptyList()
        return run.indices.filter { covers(run[it]) }
    }

    /**
     * Display order (display position → Rime index) for [candidateCount] candidates, or null to
     * keep Rime's order. [scores] are for [scorable], whose first entry is Rime index 0.
     */
    fun order(candidateCount: Int, scorable: List<Int>, scores: FloatArray): IntArray? {
        if (scores.size != scorable.size || scorable.size < 2 || scorable[0] != 0) return null
        if (scores.any { it.isNaN() || it.isInfinite() }) return null
        var best = 0
        for (i in scores.indices) if (scores[i] > scores[best]) best = i
        if (best == 0 || scores[best] - scores[0] < PROMOTE_MARGIN) return null
        val winner = scorable[best]
        return (listOf(winner) + (0 until candidateCount).filter { it != winner }).toIntArray()
    }
}
```

- [ ] **Step 4: 確認測試通過**

Run: `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.engine.general.*'`
Expected: `ZhuyinRerankerTest` 11 個全部 PASS，其餘既有測試也 PASS。

- [ ] **Step 5: Commit**

```bash
git add java/src/org/futo/inputmethod/engine/general/ZhuyinReranker.kt tests/unit/org/futo/inputmethod/engine/general/ZhuyinRerankerTest.kt
git commit -m "Decide when a Zhuyin candidate moves ahead of Rime's first

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01SxYaJPcW5bNCc9K2YVp6xA"
```

---

### Task 3: ONNX Runtime 評分器與打包

**Files:**
- Create: `java/src/org/futo/inputmethod/engine/general/OnnxCandidateScorer.kt`
- Modify: `build.gradle`（`dependencies` 區塊與 `android { packagingOptions { jniLibs { ... } } }`）

**Interfaces:**
- Consumes: `CandidateScorer`（Task 2）、`RerankVocab`、`RerankInput`、`RerankTensors`、`ZhuyinSyllable`（Task 1）
- Produces: `class OnnxCandidateScorer : CandidateScorer, AutoCloseable { companion object { fun load(context: android.content.Context): OnnxCandidateScorer } }`；`load` 失敗時丟出例外（含 `LinkageError`，例如 x86 上沒有原生庫），由呼叫端處理。

- [ ] **Step 1: 加入相依與打包規則**

在 `build.gradle` 的 `dependencies { ... }` 中，`implementation 'org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1'` 那一行下方加入：

```groovy
    implementation 'com.microsoft.onnxruntime:onnxruntime-android:1.31.0'
```

在 `android { ... }` 中**第二個** `packagingOptions { jniLibs { useLegacyPackaging = true } }`（`buildFeatures` 之後那個）改成：

```groovy
    packagingOptions {
        jniLibs {
            useLegacyPackaging = true
            // The Zhuyin reranker (ONNX Runtime) only ships for ARM; it is ~40 MB per x86 ABI.
            excludes += ['lib/x86/libonnxruntime*.so', 'lib/x86_64/libonnxruntime*.so']
        }
    }
```

- [ ] **Step 2: 實作評分器**

`java/src/org/futo/inputmethod/engine/general/OnnxCandidateScorer.kt`：

```kotlin
package org.futo.inputmethod.engine.general

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.LongBuffer

/**
 * Scores Zhuyin candidates with the bundled 0c-sequence-d512 model (int8) on the CPU.
 * [score] blocks; call it off the main thread, one call at a time.
 */
class OnnxCandidateScorer private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val vocab: RerankVocab,
) : CandidateScorer, AutoCloseable {

    override fun covers(word: String): Boolean = vocab.covers(word)

    override fun score(context: String, reading: List<ZhuyinSyllable>, candidates: List<String>): FloatArray? {
        val t = RerankInput.build(vocab, context, reading, candidates) ?: return null
        val inputs = linkedMapOf(
            "ids" to row(t.ids),
            "bases" to row(t.bases),
            "tones" to row(t.tones),
            "types" to row(t.types),
            "positions" to row(t.positions),
            "mask" to OnnxTensor.createTensor(env, arrayOf(arrayOf(t.mask))),
            "proj_pos" to OnnxTensor.createTensor(env, LongBuffer.wrap(t.projPos), longArrayOf(t.projPos.size.toLong())),
            "target_node" to matrix(t.targetNode),
            "target_ids" to matrix(t.targetIds),
        )
        try {
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
        private const val THREADS = 2

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
```

- [ ] **Step 3: 建置並確認 APK 內容**

Run: `./gradlew assembleUnstableDebug`
Expected: BUILD SUCCESSFUL，`OnnxCandidateScorer.kt` 沒有新的 Kotlin 警告。

Run: `unzip -l build/outputs/apk/unstable/debug/android-keyboard-unstable-debug.apk | grep -E "onnxruntime|zhuyin-rerank"`
Expected：有 `lib/arm64-v8a/libonnxruntime.so`、`lib/armeabi-v7a/libonnxruntime.so`（以及各自的 `libonnxruntime4j_jni.so`）與 `assets/zhuyin-rerank/` 四個檔案；**沒有** `lib/x86/` 或 `lib/x86_64/` 下的 `libonnxruntime*.so`。

若 `OnnxTensor.createTensor(env, arrayOf(arrayOf(t.mask)))` 無法編譯（多維 boolean 陣列的型別推斷），改成 `OnnxTensor.createTensor(env, arrayOf(arrayOf(t.mask)) as Any)`，並在報告中註明。

- [ ] **Step 4: 執行既有單元測試**

Run: `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.engine.general.*'`
Expected: 全部 PASS。

- [ ] **Step 5: Commit**

```bash
git add build.gradle java/src/org/futo/inputmethod/engine/general/OnnxCandidateScorer.kt
git commit -m "Run the Zhuyin reranker with ONNX Runtime

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01SxYaJPcW5bNCc9K2YVp6xA"
```

---

### Task 4: 接進 ChineseIME 與設定

**Files:**
- Modify: `java/src/org/futo/inputmethod/engine/general/ChineseIME.kt`
- Modify: `java/res/values/strings-uix.xml`（在 `chinese_setting_association_subtitle` 之後）

**Interfaces:**
- Consumes: `OnnxCandidateScorer.load(context)`（Task 3）；`CandidateScorer`、`ZhuyinReranker`（Task 2）；`ZhuyinReading.parse`、`ZhuyinSyllable`（Task 1）
- Produces: 設定鍵 `ChineseIMESettings.RerankSetting`

- [ ] **Step 1: 字串**

在 `java/res/values/strings-uix.xml` 的 `chinese_setting_association_subtitle` 那一行之後加入：

```xml
    <string name="chinese_setting_rerank">Context-aware candidates</string>
    <string name="chinese_setting_rerank_subtitle">Zhuyin: use the text before the cursor to put the most likely word first (on-device)</string>
```

- [ ] **Step 2: 設定鍵與開關**

在 `ChineseIMESettings` 的 `AssociationSetting` 定義之後加入：

```kotlin
    val RerankSetting = SettingsKey(
        booleanPreferencesKey("ChineseIME_rerank"),
        true
    )
```

在 `settings = listOf(...)` 中「聯想詞」那個 `UserSetting(...) { ... },` 之後加入：

```kotlin
            UserSetting(R.string.chinese_setting_rerank, R.string.chinese_setting_rerank_subtitle) {
                SettingToggleDataStore(
                    stringResource(R.string.chinese_setting_rerank),
                    RerankSetting,
                    subtitle = stringResource(R.string.chinese_setting_rerank_subtitle)
                )
            },
```

- [ ] **Step 3: import 與狀態欄位**

在 import 區加入（依字母順序放在對應位置）：

```kotlin
import android.view.inputmethod.EditorInfo
import icu.astronot233.rime.RimeCandidate
import kotlinx.coroutines.asCoroutineDispatcher
import org.futo.inputmethod.latin.utils.InputTypeUtils
import java.util.concurrent.Executors
```

在 `private var associationCommitted: String = ""` 之後加入：

```kotlin

    /** Context reranker (依前文排序); null until loaded, or after it failed to load. */
    private var scorer: CandidateScorer? = null
    private var scorerJob: Job? = null
    private var scorerFailed = false
    private val rerankDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var rerankJob: Job? = null
    /** Display position → Rime index of the candidates shown; null while they are in Rime's order. */
    private var candidateOrder: IntArray? = null
```

在 `companion object` 中 `SchemaSelectRetryDelayMs` 之後加入：

```kotlin

        /** A rerank slower than this is dropped: the strip would change after the user moved on. */
        private const val RerankTimeoutMs = 300L
        private const val RerankContextChars = 64
```

- [ ] **Step 4: 載入評分器**

在 `endAssociation()` 函式之前加入：

```kotlin
    /** Loads the context reranker in the background once, if the Zhuyin layout and setting want it. */
    private fun ensureScorerLoaded() {
        if(!isBopomofo || scorer != null || scorerJob != null || scorerFailed) return
        if(!helper.context.getSetting(ChineseIMESettings.RerankSetting)) return
        scorerJob = coroScope.launch {
            val start = SystemClock.elapsedRealtime()
            val loaded = withContext(Dispatchers.IO) {
                try {
                    OnnxCandidateScorer.load(helper.context)
                } catch(e: CancellationException) {
                    throw e
                } catch(e: Exception) {
                    Log.e(TAG, "Failed to load the candidate reranker", e)
                    null
                } catch(e: LinkageError) {
                    Log.e(TAG, "No ONNX Runtime for this device", e)
                    null
                } catch(e: OutOfMemoryError) {
                    Log.e(TAG, "Out of memory loading the candidate reranker", e)
                    null
                }
            }
            scorer = loaded
            scorerFailed = loaded == null
            scorerJob = null
            Log.i(TAG, "Candidate reranker: ${if(loaded != null) "loaded" else "unavailable"} in ${SystemClock.elapsedRealtime() - start} ms")
        }
    }

    /**
     * Scores the shown candidates against the text before the cursor in the background and, if
     * the model clearly prefers another one, shows it first. Results for an outdated composition
     * are dropped.
     */
    private fun maybeRerank(candidates: List<RimeCandidate>) {
        rerankJob?.cancel()
        rerankJob = null
        val scorer = scorer ?: return
        if(!isBopomofo || isOnEnglishPage || editingState.active) return
        if(!helper.context.getSetting(ChineseIMESettings.RerankSetting)) return
        // Only once the last syllable has its tone (space counts as tone 1).
        if(!ZhuyinKeymap.isToneKey(rawInput.text.lastOrNull())) return
        val info = helper.getCurrentEditorInfo() ?: return
        if(InputTypeUtils.isPasswordInputType(info.inputType) ||
            InputTypeUtils.isVisiblePasswordInputType(info.inputType) ||
            (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) return

        val preedit = rime.preeditFlow.value
        val reading = ZhuyinReading.parse(preedit) ?: return
        if(reading.size > ZhuyinReranker.MAX_SYLLABLES) return
        val words = candidates.map { it.text }
        val scorable = ZhuyinReranker.scorable(words, reading.size, scorer::covers)
        if(scorable.size < 2) return
        val context = connect?.getTextBeforeCursor(RerankContextChars, 0)?.toString().orEmpty()
        if(reading.size == 1 && context.isEmpty()) return

        rerankJob = coroScope.launch {
            val start = SystemClock.elapsedRealtime()
            val scores = withContext(rerankDispatcher) {
                try {
                    scorer.score(context, reading, scorable.map { words[it] })
                } catch(e: CancellationException) {
                    throw e
                } catch(e: Exception) {
                    Log.e(TAG, "Candidate rerank failed", e)
                    null
                } catch(e: OutOfMemoryError) {
                    Log.e(TAG, "Out of memory during candidate rerank", e)
                    null
                }
            } ?: return@launch
            val elapsed = SystemClock.elapsedRealtime() - start
            if(elapsed > RerankTimeoutMs) {
                Log.w(TAG, "Candidate rerank took $elapsed ms; ignored")
                return@launch
            }
            // The user may have typed on while the model ran.
            if(rime.preeditFlow.value != preedit || rime.candidatesFlow.value.map { it.text } != words) return@launch
            val order = ZhuyinReranker.order(words.size, scorable, scores) ?: return@launch
            candidateOrder = order
            showSuggestionStrip(candidateWords(order.map { words[it] }, order.map { candidates[it].comment }))
        }
    }

    /** Rime index of the candidate shown at [displayIndex]. */
    private fun rimeIndexOf(displayIndex: Int): Int = candidateOrder?.getOrNull(displayIndex) ?: displayIndex
```

- [ ] **Step 5: 在候選更新時評分**

`subscribeToRimeCandidates()` 改成下面這樣（新增的是開頭的 `candidateOrder = null` 與最後的 `maybeRerank(cdd)`）：

```kotlin
    private fun subscribeToRimeCandidates() = rime.candidatesFlow.onEach { cdd ->
        // New candidates are always shown in Rime's order first.
        candidateOrder = null
        rerankJob?.cancel()

        waitingToSelect?.let { sel ->
            if(cdd.getOrNull(sel.second)?.text == sel.first) {
                if(rime.selectCandidate(sel.second)) {
                    waitingToSelect = null
                    return@onEach
                } else {
                    Log.e(TAG, "Error selecting candidate!")
                }
            }
        }

        // Rime clears its candidates after a commit; keep the associations shown for that commit.
        if(cdd.isEmpty() && associationContext != null) return@onEach

        if(cdd.isNotEmpty()) {
            associationContext = null
            associationWords = emptyList()
        }

        // Don't flicker an empty suggest bar when editing
        if(editingState != EditingState.NotEditing && cdd.isEmpty()) {
            return@onEach
        }

        showSuggestionStrip(candidateWords(cdd.map { it.text }, cdd.map { it.comment }))
        if(cdd.isNotEmpty()) maybeRerank(cdd)
    }.launchIn(coroScope)
```

- [ ] **Step 6: 點選與空白鍵依對照表選字**

在 `onEvent` 的 `EVENT_TYPE_SUGGESTION_PICKED` 分支，最後的 `else` 改成：

```kotlin
                } else {
                    val index = rimeIndexOf(suggestion.mCandidateIndex)
                    coroScope.launch {
                        if(!rime.selectCandidate(index)) {
                            Log.e(TAG, "Couldn't select candidate")
                        }
                    }
                }
```

在 `handleBopomofoKey` 的空白鍵處理中，「The syllable already has a tone」那個分支改成：

```kotlin
                    ZhuyinKeymap.isToneKey(rawInput.text.lastOrNull()) -> {
                        // The syllable already has a tone: space picks the first candidate shown
                        // (which the reranker may have moved ahead of Rime's first), like the
                        // pinyin layout does.
                        rawInput.clear()
                        if(!rime.selectCandidate(rimeIndexOf(0))) rime.commitComposition()
                    }
```

- [ ] **Step 7: 生命週期**

在 `onStartInput()` 最後一行 `ensureAssociationLoaded(reload = dictionaryChanged)` 之後加入：

```kotlin
        ensureScorerLoaded()
```

`onDestroy()` 改成：

```kotlin
    override fun onDestroy() {
        helper.lifecycleScope.cancel()
        coroScope.cancel()
        rime.shutdown()
        (scorer as? AutoCloseable)?.close()
        scorer = null
        rerankDispatcher.close()
    }
```

- [ ] **Step 8: 建置與單元測試**

Run: `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.engine.general.*' assembleUnstableDebug`
Expected: BUILD SUCCESSFUL；`ChineseIME.kt` 除了既有的 `locale` 棄用警告外沒有新警告。

- [ ] **Step 9: Commit**

```bash
git add java/src/org/futo/inputmethod/engine/general/ChineseIME.kt java/res/values/strings-uix.xml
git commit -m "Put the most likely Zhuyin candidate first by context

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01SxYaJPcW5bNCc9K2YVp6xA"
```

---

### Task 5: 真機驗證（controller 以 adb 執行）

需要使用者先連上無線偵錯並解鎖手機。測前 `adb shell settings put system screen_off_timeout 1800000`，測後還原為 `60000`。每次 `input tap` 前先截圖確認畫面。

- [ ] **Step 1: 安裝並確認載入**

```bash
adb install -r build/outputs/apk/unstable/debug/android-keyboard-unstable-debug.apk
adb shell ime set org.futo.inputmethod.latin.unstable/org.futo.inputmethod.latin.LatinIME
adb logcat -c
```

在 Chrome 網址列叫出注音鍵盤，然後 `adb logcat -d | grep "Candidate reranker"`。
Expected：`Candidate reranker: loaded in N ms`，N 約 200–1000。記下 APK 大小（`ls -la` 對照本計畫前的 102,532,915 bytes）。

- [ ] **Step 2: 情境檢查**

`adb shell input text` 會繞過輸入法並收起鍵盤，也無法輸入中文，所以前文要用螢幕鍵盤打注音並上屏。

| 前文（先打好上屏） | 輸入 | 預期第一位 |
|---|---|---|
| 候選人發表 | ㄓㄥˋ ㄐㄧㄢˋ | 政見 |
| 請把 | ㄓㄥˋ ㄐㄧㄢˋ | 證件（Rime 原本就排第一，不應變動） |
| 我現在 | ㄗㄞˋ | 在 |
| 慢慢 | ㄉㄜ˙ | 地 |
| （空白欄位） | ㄗㄞˋ | 再（單音節沒有前文，不評分） |

每一列：截圖建議列，然後按空白鍵，確認上屏的是建議列第一位；再重打一次改用點選第一位，確認一樣。

- [ ] **Step 3: 量測**
  - 延遲：`adb logcat -d | grep "Candidate rerank took"` 不應出現；另用 `adb shell dumpsys meminfo org.futo.inputmethod.latin.unstable | grep -E "TOTAL PSS|Native Heap"` 記錄載入前（設定關閉並重啟鍵盤程序）與載入後的數字。
  - 關閉「Context-aware candidates」後重打「候選人發表」＋ ㄓㄥˋ ㄐㄧㄢˋ，第一位應恢復為證件。量完把設定打開回來。

- [ ] **Step 4: 記錄結果**：把結果與數字寫進 `.superpowers/sdd/progress.md`（不 commit），並在最終回報中列出。

---

### Task 6: 單音節只評同讀音的字（2026-10-23 離線評估後追加）

離線評估（Common Voice zh-TW 19,144 句、86,626 個情境、小麥注音台灣讀音）顯示：單音節時 Rime 候選混有讀音不同的字（打 ㄉㄜ˙ 會出現「不」「到」），只看前文的模式會把它們排到第一（的→不、的→到）。只讓讀音相同（不含聲調）的字參加評分，單音節正確率 75.9% → 77.9%，改錯 1,155 → 530，整體 87.1% → 87.9%。

**Files:**
- Create: `java/src/org/futo/inputmethod/engine/general/ChineseCharReadings.kt`
- Test: `tests/unit/org/futo/inputmethod/engine/general/ChineseCharReadingsTest.kt`
- Modify: `java/src/org/futo/inputmethod/engine/general/ChineseIME.kt`
- Modify: `docs/superpowers/specs/2026-10-23-zhuyin-context-rerank-design.md`（「## 排序規則」加一條）

**Interfaces:**
- Consumes: `ZhuyinSyllables.toLunaPinyin(zhuyin: String): String?`（既有，把注音拼成 luna_pinyin 拼法，不含聲調，例如 "ㄉㄜ" → "de"）；`ZhuyinReading.parse`、`ZhuyinReranker.scorable`、`CandidateScorer.covers`。
- Produces: `class ChineseCharReadings { val size: Int; fun has(ch: String, spelling: String): Boolean; companion object { const val FILE = "luna_pinyin.dict.yaml"; fun load(sharedDir: File): ChineseCharReadings?; fun parse(reader: Reader): ChineseCharReadings } }`

`luna_pinyin.dict.yaml` 的格式：YAML 標頭以單獨一行 `...` 結束；之後每行 `字詞<TAB>拼音[<TAB>權重]`，拼音不含聲調，多字詞的拼音以空白分隔。只取單一 code point 的行；同一字可有多行（例如 `的	de	99.97%`、`的	di	0.03%`）。

- [ ] **Step 1: 寫失敗測試** `tests/unit/org/futo/inputmethod/engine/general/ChineseCharReadingsTest.kt`：

```kotlin
package org.futo.inputmethod.engine.general

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.StringReader
import java.nio.file.Files

class ChineseCharReadingsTest {
    private val sample = listOf(
        "# Rime dictionary",
        "---",
        "name: luna_pinyin",
        "...",
        "",
        "的\tde\t99.97%",
        "的\tdi\t0.03%",
        "不\tbu\t100%",
        "到\tdao",
        "# a comment",
        "我們\two men",
        "𠀀\tqiu",
        "壞行",
    ).joinToString("\n")

    private fun parse(text: String = sample) = ChineseCharReadings.parse(StringReader(text))

    @Test
    fun readsEverySpellingOfACharacter() {
        val r = parse()
        assertTrue(r.has("的", "de"))
        assertTrue(r.has("的", "di"))
        assertTrue(r.has("到", "dao"))
        assertFalse(r.has("不", "de"))
        assertFalse(r.has("到", "de"))
    }

    @Test
    fun skipsTheHeaderPhrasesAndMalformedLines() {
        val r = parse()
        assertFalse(r.has("我們", "wo men"))
        assertFalse(r.has("name: luna_pinyin", ""))
        assertEquals(4, r.size) // 的 不 到 𠀀
    }

    @Test
    fun handlesSupplementaryCharacters() {
        assertTrue(parse().has("𠀀", "qiu"))
    }

    @Test
    fun unknownCharacterHasNoReading() {
        assertFalse(parse().has("好", "hao"))
    }

    @Test
    fun loadReturnsNullWithoutTheFile() {
        val dir = Files.createTempDirectory("readings").toFile()
        try {
            assertNull(ChineseCharReadings.load(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun loadReadsTheFile() {
        val dir = Files.createTempDirectory("readings").toFile()
        try {
            File(dir, ChineseCharReadings.FILE).writeText(sample)
            assertTrue(ChineseCharReadings.load(dir)!!.has("的", "de"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
```

- [ ] **Step 2: 確認失敗**：`./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.engine.general.ChineseCharReadingsTest'`，預期編譯失敗（`Unresolved reference: ChineseCharReadings`）。

- [ ] **Step 3: 實作** `java/src/org/futo/inputmethod/engine/general/ChineseCharReadings.kt`：

```kotlin
package org.futo.inputmethod.engine.general

import java.io.File
import java.io.Reader

/**
 * Readings (luna_pinyin spellings, without tone) of single characters, from the dictionary's
 * `luna_pinyin.dict.yaml`. The reranker uses it so that, for one syllable, only characters
 * with the typed reading are scored; Rime also offers characters with other readings.
 */
class ChineseCharReadings private constructor(private val readings: Map<String, Set<String>>) {
    /** Number of characters with at least one reading. */
    val size: Int get() = readings.size

    /** True if [ch] (one character) can be read as [spelling]. */
    fun has(ch: String, spelling: String): Boolean = readings[ch]?.contains(spelling) == true

    companion object {
        const val FILE = "luna_pinyin.dict.yaml"

        /** The readings in [sharedDir], or null if the dictionary has no [FILE]. */
        fun load(sharedDir: File): ChineseCharReadings? {
            val file = File(sharedDir, FILE)
            if (!file.exists()) return null
            return file.bufferedReader().use { parse(it) }
        }

        fun parse(reader: Reader): ChineseCharReadings {
            val readings = HashMap<String, MutableSet<String>>()
            var inBody = false
            reader.forEachLine { line ->
                if (!inBody) {
                    if (line.trimEnd() == "...") inBody = true
                    return@forEachLine
                }
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                val fields = line.split('\t')
                if (fields.size < 2) return@forEachLine
                val ch = fields[0]
                if (ch.isEmpty() || ch.codePointCount(0, ch.length) != 1) return@forEachLine
                val spelling = fields[1].trim()
                if (spelling.isEmpty()) return@forEachLine
                readings.getOrPut(ch) { HashSet(2) }.add(spelling)
            }
            return ChineseCharReadings(readings)
        }
    }
}
```

- [ ] **Step 4: 確認測試通過**：同上指令，6 個 PASS。

- [ ] **Step 5: 接進 ChineseIME**
  - 新增欄位（放在 `scorer` 等欄位附近）：
    ```kotlin
    /** Single-character readings for the reranker; null until loaded, or without luna_pinyin.dict.yaml. */
    private var charReadings: ChineseCharReadings? = null
    private var charReadingsJob: Job? = null
    ```
  - 在 `ensureAssociationLoaded` 之後新增，寫法比照它（只在注音布局載入、詞庫換過時重載、所有例外記 log 後當作 null、`CancellationException` 往外拋）：
    ```kotlin
    /** Loads the single-character readings the reranker filters one-syllable candidates with. */
    private fun ensureCharReadingsLoaded(reload: Boolean = false) {
        if(reload) {
            charReadingsJob?.cancel()
            charReadingsJob = null
            charReadings = null
        }
        if(!isBopomofo || charReadingsJob != null) return
        charReadingsJob = coroScope.launch {
            charReadings = withContext(Dispatchers.IO) {
                try {
                    ChineseCharReadings.load(getShared(helper.context))
                } catch(e: CancellationException) {
                    throw e
                } catch(e: Exception) {
                    Log.e(TAG, "Failed to load character readings", e)
                    null
                } catch(e: OutOfMemoryError) {
                    Log.e(TAG, "Out of memory loading character readings", e)
                    null
                }
            }
            Log.i(TAG, "Character readings: ${charReadings?.size ?: 0} characters")
        }
    }
    ```
  - 在 `onStartInput()` 的 `ensureAssociationLoaded(reload = dictionaryChanged)` 之後加 `ensureCharReadingsLoaded(reload = dictionaryChanged)`；在布局切換處既有的 `ensureAssociationLoaded()` 之後加 `ensureCharReadingsLoaded()`。
  - `maybeRerank` 中把
    ```kotlin
    val scorable = ZhuyinReranker.scorable(words, reading.size, scorer::covers)
    ```
    改成
    ```kotlin
        // For one syllable the model reads only the context, and Rime also offers characters
        // with other readings (e.g. 不 and 到 for ㄉㄜ˙): score only characters with the typed reading.
        val spelling = if(reading.size == 1) ZhuyinSyllables.toLunaPinyin(reading[0].base) else null
        val readings = charReadings
        val covers: (String) -> Boolean =
            if(spelling != null && readings != null) { word -> scorer.covers(word) && readings.has(word, spelling) }
            else scorer::covers
        val scorable = ZhuyinReranker.scorable(words, reading.size, covers)
    ```

- [ ] **Step 6: Spec**：在「## 排序規則」的條列最後加一條：「單音節時，只有讀音（不含聲調，依詞庫 `luna_pinyin.dict.yaml`）和所打注音相同的字參加評分；Rime 第一名不符合時不調整。詞庫沒有這個檔案時不篩選。」

- [ ] **Step 7: 建置與測試**：`./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.engine.general.*' assembleUnstableDebug`，BUILD SUCCESSFUL、全部 PASS、改動的檔案沒有新警告（ChineseIME.kt 既有一個 `locale` 棄用警告）。

- [ ] **Step 8: Commit**（訊息 "Score only same-reading characters for one Zhuyin syllable"，結尾兩行 trailer 同 Global Constraints）。

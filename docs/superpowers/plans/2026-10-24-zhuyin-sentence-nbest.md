# 注音整句 N-best 重排 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 打 4–12 個音節時，用自建詞典產生多個整句，交給依前文排序的模型挑選，明顯更好時把模型的整句放第一位，上屏時逐段在 Rime 選字。

**Architecture:** 三個純 Kotlin 元件（`SentenceLexicon` 索引與快取檔、`SentenceNBest` 詞圖 beam search 與晉升判斷、`SentenceSelector` 逐段選字），加上 `ZhuyinReading.splitSelected` 解析「已選字＋注音」的組字區。`ChineseIME` 在背景建立／讀取詞典，在現有的 `maybeRerank` 中分流到整句路徑，並在空白鍵與點選時逐段選字。

**Tech Stack:** Kotlin、JUnit 4（JVM 單元測試）、ONNX Runtime（既有的 `OnnxCandidateScorer`）、librime（RimeJni）。

Spec：`docs/superpowers/specs/2026-10-24-zhuyin-sentence-nbest-design.md`

## Global Constraints

- 作用範圍：注音布局、不在英文頁、不在編輯狀態、`ChineseIME_rerank` 開啟、不是密碼欄位且沒有 `IME_FLAG_NO_PERSONALIZED_LEARNING`、簡體輸出關閉。
- 剩下未選的音節數 4–12（`SENTENCE_SYLLABLES = 4..12`），每個音節都有聲調；Rime 第一個候選字數（code point）等於音節數。
- N-best：K = 16，每個位置保留 2K 條路徑，詞長上限 5 個音節。Rime 的整句固定是第一個候選，總數最多 16。
- 晉升門檻沿用 `ZhuyinReranker.PROMOTE_MARGIN`（2.0）。
- 整句評分逾時 500 ms；1–3 音節維持 300 ms。ONNX session 改為 4 個執行緒（只有一個 session）。
- 詞典：單字讀音佔比 ≥ 5%（沒有佔比的一律保留；全部低於 5% 時保留全部），每詞最多 8 種讀音組合；2 字以上的詞 `essay 權重 + 0.5 ≥ 50`；每個讀音最多 16 個詞；分數 `ln((權重 + 0.5) / essay 總權重)`；字形經 `TaiwanVariants.toTaiwan`；個人詞權重為索引最大值 + 1。
- 快取檔：`filesDir/zhuyin-sentence/lexicon.bin`，tag 由詞庫名稱檔、版本檔和個人字典內容組成，不符就重建。
- 所有失敗都不得讓鍵盤崩潰；載入失敗後停用到程序重啟。
- Commit 訊息結尾：
  ```
  Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01SxYaJPcW5bNCc9K2YVp6xA
  ```
- 測試指令：`./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.engine.general.*'`

---

### Task 1: `SentenceLexicon`

**Files:**
- Create: `java/src/org/futo/inputmethod/engine/general/SentenceLexicon.kt`
- Test: `tests/unit/org/futo/inputmethod/engine/general/SentenceLexiconTest.kt`

**Interfaces:**
- Consumes: `TaiwanVariants.toTaiwan(text: String): String`、`ChineseCustomDict.Entry(text: String, code: String, weight: Int)`（code 已是 luna 拼音，以空白分隔）。
- Produces:
  - `class SentenceLexicon`：`data class Entry(val word: String, val score: Float)`、`fun lookup(reading: List<String>): List<Entry>`（最好的在前）、`val keyCount: Int`、`val entryCount: Int`、`fun write(out: OutputStream, tag: String)`。
  - `companion`：`const val MAX_WORD = 5`、`fun build(dict: Reader, essay: Reader, personal: List<ChineseCustomDict.Entry>): SentenceLexicon`、`fun read(input: InputStream, tag: String): SentenceLexicon?`、`fun tag(sharedDir: File, personal: List<ChineseCustomDict.Entry>): String`、`fun loadOrBuild(cache: File, sharedDir: File, personal: List<ChineseCustomDict.Entry>): SentenceLexicon?`。

- [ ] **Step 1: Write the failing tests**

```kotlin
package org.futo.inputmethod.engine.general

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.nio.file.Files
import kotlin.math.ln

class SentenceLexiconTest {
    private val dict = """
        # Rime dictionary
        ---
        name: luna_pinyin
        ...

        請	qing	95%
        請	jing	5%
        到	dao
        青	qing
        島	dao
        行	xing	96%
        行	hang	4%
        人	ren
        處	chu	3%
        處	cu	2%
        所	suo
        青島	qing dao
    """.trimIndent()

    private val essay = """
        請到	100
        青島	60
        行人	80
        處所	70
        請	500
        到	300
        到處	49
        人到	50
    """.trimIndent()

    private val total = 100 + 60 + 80 + 70 + 500 + 300 + 49 + 50

    private fun lexicon(d: String = dict, e: String = essay, personal: List<ChineseCustomDict.Entry> = emptyList()) =
        SentenceLexicon.build(StringReader(d), StringReader(e), personal)

    private fun words(lex: SentenceLexicon, reading: String) = lex.lookup(reading.split(' ')).map { it.word }

    @Test
    fun explicitAndComposedReadingsByWeight() {
        assertEquals(listOf("請到", "青島"), words(lexicon(), "qing dao"))
        // 請 has jing at exactly 5%, which is kept.
        assertEquals(listOf("請到"), words(lexicon(), "jing dao"))
    }

    @Test
    fun scoreIsLogShareOfEssayTotal() {
        val first = lexicon().lookup(listOf("qing", "dao")).first()
        assertEquals(ln(100.5 / total).toFloat(), first.score, 1e-6f)
    }

    @Test
    fun rareCharacterReadingsAreSkipped() {
        assertEquals(listOf("行人"), words(lexicon(), "xing ren"))
        assertEquals(emptyList<String>(), words(lexicon(), "hang ren"))
    }

    @Test
    fun allReadingsKeptWhenNoneReachTheMinimum() {
        assertEquals(listOf("處所"), words(lexicon(), "chu suo"))
        assertEquals(listOf("處所"), words(lexicon(), "cu suo"))
    }

    @Test
    fun multiCharacterWordsNeedTheMinimumWeight() {
        assertEquals(emptyList<String>(), words(lexicon(), "dao chu"))
        assertEquals(listOf("人到"), words(lexicon(), "ren dao"))
    }

    @Test
    fun singleCharactersAlwaysKept() {
        // 青 is not in essay.txt: weight 0.5.
        assertEquals(listOf("請", "青"), words(lexicon(), "qing"))
    }

    @Test
    fun taiwanFormsAreMerged() {
        val lex = lexicon("---\n...\n爲\twei\n為\twei\n了\tle", "爲了\t100\n為了\t60")
        val entries = lex.lookup(listOf("wei", "le"))
        assertEquals(listOf("為了"), entries.map { it.word })
        assertEquals(ln((100.5 + 60.5) / 160).toFloat(), entries[0].score, 1e-6f)
    }

    @Test
    fun atMostSixteenWordsPerReading() {
        val chars = (0 until 20).map { String(Character.toChars(0x4E00 + it)) }
        val lex = lexicon("---\n...\n" + chars.joinToString("\n") { "$it\ta" },
            chars.withIndex().joinToString("\n") { "${it.value}\t${it.index + 1}" })
        assertEquals(chars.reversed().take(16), words(lex, "a"))
    }

    @Test
    fun personalWordsComeFirst() {
        val lex = lexicon("---\n...\n以\tyi\n宜\tyi\n真\tzhen\n蓁\tzhen", "以真\t1000",
            listOf(ChineseCustomDict.Entry("宜蓁", "yi zhen", 1)))
        assertEquals(listOf("宜蓁", "以真"), words(lex, "yi zhen"))
    }

    @Test
    fun unknownReadingsGiveNothing() {
        assertEquals(emptyList<String>(), words(lexicon(), "zzz"))
        assertEquals(emptyList<SentenceLexicon.Entry>(), lexicon().lookup(emptyList()))
        assertEquals(emptyList<SentenceLexicon.Entry>(), lexicon().lookup(List(6) { "qing" }))
    }

    @Test
    fun writeAndReadRoundTrip() {
        val lex = lexicon()
        val out = ByteArrayOutputStream()
        lex.write(out, "tag-1")
        val back = SentenceLexicon.read(ByteArrayInputStream(out.toByteArray()), "tag-1")!!
        assertEquals(lex.keyCount, back.keyCount)
        assertEquals(lex.entryCount, back.entryCount)
        for (r in listOf("qing dao", "xing ren", "cu suo", "qing", "ren dao")) {
            assertEquals(lex.lookup(r.split(' ')), back.lookup(r.split(' ')))
        }
        assertNull(SentenceLexicon.read(ByteArrayInputStream(out.toByteArray()), "tag-2"))
    }

    @Test
    fun loadOrBuildCachesAndRebuildsOnTagChange() {
        val shared = Files.createTempDirectory("shared").toFile()
        val cache = Files.createTempDirectory("cache").resolve("x/lexicon.bin").toFile()
        try {
            shared.resolve("luna_pinyin.dict.yaml").writeText(dict)
            shared.resolve("essay.txt").writeText(essay)
            shared.resolve("FUTOKeyboard_ChineseDictionary_Name").writeText("zidian")
            shared.resolve("FUTOKeyboard_ChineseDictionary_Version").writeText("1")
            val built = SentenceLexicon.loadOrBuild(cache, shared, emptyList())!!
            assertTrue(cache.exists())
            assertEquals(listOf("請到", "青島"), built.lookup(listOf("qing", "dao")).map { it.word })
            // A changed dictionary version invalidates the cache.
            shared.resolve("FUTOKeyboard_ChineseDictionary_Version").writeText("2")
            shared.resolve("essay.txt").writeText("青島\t1000")
            val rebuilt = SentenceLexicon.loadOrBuild(cache, shared, emptyList())!!
            // 請到 is neither in the new essay.txt nor spelled out in the dictionary.
            assertEquals(listOf("青島"), rebuilt.lookup(listOf("qing", "dao")).map { it.word })
            // No dictionary: nothing to build.
            shared.resolve("essay.txt").delete()
            cache.delete()
            assertNull(SentenceLexicon.loadOrBuild(cache, shared, emptyList()))
        } finally {
            shared.deleteRecursively()
            cache.parentFile?.parentFile?.deleteRecursively()
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.engine.general.SentenceLexiconTest'`
Expected: compilation FAIL, `Unresolved reference: SentenceLexicon`.

- [ ] **Step 3: Implement `SentenceLexicon.kt`**

```kotlin
package org.futo.inputmethod.engine.general

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.Reader
import kotlin.math.ln

/**
 * Words by reading for whole-sentence candidates (整句), built from the dictionary's
 * `luna_pinyin.dict.yaml` and `essay.txt` plus the personal dictionary. Readings are
 * luna_pinyin syllables without tone.
 *
 * Stored compactly: a reading of up to [MAX_WORD] syllables is one Long key (syllable ids + 1
 * in base 512), keys are sorted, and the words of key i are entries
 * `entryStarts[i] until entryStarts[i + 1]`, best first; their text is one string cut at
 * `wordStarts`.
 */
class SentenceLexicon private constructor(
    private val syllables: Array<String>,
    private val keys: LongArray,
    private val entryStarts: IntArray,
    private val text: String,
    private val wordStarts: IntArray,
    private val scores: FloatArray,
) {
    /** A word and its log share of the essay weights. */
    data class Entry(val word: String, val score: Float)

    private val syllableIds: Map<String, Int> =
        HashMap<String, Int>(syllables.size * 2).also { m -> syllables.forEachIndexed { i, s -> m[s] = i } }

    val keyCount: Int get() = keys.size
    val entryCount: Int get() = scores.size

    /** The words read as [reading] (luna_pinyin syllables), best first. */
    fun lookup(reading: List<String>): List<Entry> {
        if (reading.isEmpty() || reading.size > MAX_WORD) return emptyList()
        var key = 0L
        for (s in reading) key = key * BASE + ((syllableIds[s] ?: return emptyList()) + 1)
        val i = java.util.Arrays.binarySearch(keys, key)
        if (i < 0) return emptyList()
        return (entryStarts[i] until entryStarts[i + 1]).map { e ->
            Entry(text.substring(wordStarts[e], wordStarts[e + 1]), scores[e])
        }
    }

    fun write(out: OutputStream, tag: String) {
        val data = DataOutputStream(BufferedOutputStream(out))
        data.writeInt(MAGIC)
        data.writeInt(FORMAT)
        data.writeUTF(tag)
        data.writeInt(syllables.size)
        syllables.forEach { data.writeUTF(it) }
        data.writeInt(keys.size)
        keys.forEach { data.writeLong(it) }
        entryStarts.forEach { data.writeInt(it) }
        data.writeInt(scores.size)
        wordStarts.forEach { data.writeInt(it) }
        scores.forEach { data.writeFloat(it) }
        val bytes = text.toByteArray(Charsets.UTF_8)
        data.writeInt(bytes.size)
        data.write(bytes)
        data.flush()
    }

    /** Growable (key, word, weight) records collected while building. */
    private class Records {
        var keys = LongArray(1 shl 16)
        var words = arrayOfNulls<String>(1 shl 16)
        var weights = DoubleArray(1 shl 16)
        var size = 0

        fun add(key: Long, word: String, weight: Double) {
            if (size == keys.size) {
                keys = keys.copyOf(size * 2)
                words = words.copyOf(size * 2)
                weights = weights.copyOf(size * 2)
            }
            keys[size] = key
            words[size] = word
            weights[size] = weight
            size++
        }
    }

    companion object {
        const val MAX_WORD = 5
        const val PER_KEY = 16
        const val MIN_WORD_WEIGHT = 50.0
        const val MIN_READING_PERCENT = 5.0
        const val MAX_COMBINATIONS = 8
        const val DICT_FILE = "luna_pinyin.dict.yaml"
        const val ESSAY_FILE = "essay.txt"
        private val TAG_FILES = listOf("FUTOKeyboard_ChineseDictionary_Name", "FUTOKeyboard_ChineseDictionary_Version")
        private const val BASE = 512L
        private const val MAGIC = 0x5A534C58 // "ZSLX"
        private const val FORMAT = 1

        /** Identifies the dictionary and personal words a lexicon was built from. */
        fun tag(sharedDir: File, personal: List<ChineseCustomDict.Entry>): String =
            TAG_FILES.joinToString("|") { name ->
                File(sharedDir, name).takeIf { it.exists() }?.readText()?.trim().orEmpty()
            } + "|" + personal.hashCode()

        /**
         * The lexicon cached in [cache] if it was built for the same dictionary and personal
         * words, otherwise one built from [sharedDir] (and written to [cache]); null if the
         * dictionary has no luna_pinyin.dict.yaml or essay.txt.
         */
        fun loadOrBuild(cache: File, sharedDir: File, personal: List<ChineseCustomDict.Entry>): SentenceLexicon? {
            val tag = tag(sharedDir, personal)
            if (cache.exists()) {
                try {
                    cache.inputStream().use { read(it, tag) }?.let { return it }
                } catch (e: IOException) {
                    // Truncated or corrupt: rebuild below.
                }
            }
            val dict = File(sharedDir, DICT_FILE)
            val essay = File(sharedDir, ESSAY_FILE)
            if (!dict.exists() || !essay.exists()) return null
            val lexicon = dict.bufferedReader().use { d -> essay.bufferedReader().use { e -> build(d, e, personal) } }
            cache.parentFile?.mkdirs()
            val tmp = File(cache.path + ".tmp")
            tmp.outputStream().use { lexicon.write(it, tag) }
            if (!tmp.renameTo(cache)) tmp.delete()
            return lexicon
        }

        /** The lexicon written by [write] with the same [tag], or null if the tag or format differs. */
        fun read(input: InputStream, tag: String): SentenceLexicon? {
            val data = DataInputStream(input.buffered())
            if (data.readInt() != MAGIC || data.readInt() != FORMAT || data.readUTF() != tag) return null
            val syllables = Array(data.readInt()) { data.readUTF() }
            val keyCount = data.readInt()
            val keys = LongArray(keyCount) { data.readLong() }
            val entryStarts = IntArray(keyCount + 1) { data.readInt() }
            val entryCount = data.readInt()
            val wordStarts = IntArray(entryCount + 1) { data.readInt() }
            val scores = FloatArray(entryCount) { data.readFloat() }
            val bytes = ByteArray(data.readInt())
            data.readFully(bytes)
            return SentenceLexicon(syllables, keys, entryStarts, String(bytes, Charsets.UTF_8), wordStarts, scores)
        }

        fun build(dict: Reader, essay: Reader, personal: List<ChineseCustomDict.Entry>): SentenceLexicon {
            // Character readings with their share (null when the line gives none), and the
            // readings the dictionary spells out for any entry, characters included.
            val charReadings = HashMap<String, MutableList<Pair<String, Double?>>>()
            val explicit = HashMap<String, MutableList<String>>()
            var inBody = false
            dict.forEachLine { line ->
                if (!inBody) {
                    if (line.trimEnd() == "...") inBody = true
                    return@forEachLine
                }
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                val fields = line.split('\t')
                if (fields.size < 2) return@forEachLine
                val word = fields[0].trim()
                val code = fields[1].trim()
                if (word.isEmpty() || code.isEmpty()) return@forEachLine
                if (word.codePointCount(0, word.length) == 1) {
                    val weight = fields.getOrNull(2)?.trim().orEmpty()
                    val percent = if (weight.endsWith("%")) weight.dropLast(1).toDoubleOrNull() else null
                    charReadings.getOrPut(word) { ArrayList(2) }.add(code to percent)
                }
                explicit.getOrPut(word) { ArrayList(1) }.add(code)
            }

            val essayWeights = HashMap<String, Long>()
            var total = 0L
            essay.forEachLine { line ->
                val fields = line.split('\t')
                if (fields.size != 2) return@forEachLine
                val weight = fields[1].trim().toLongOrNull()?.takeIf { it >= 0 } ?: return@forEachLine
                essayWeights[fields[0]] = (essayWeights[fields[0]] ?: 0L) + weight
                total += weight
            }

            val syllableIds = HashMap<String, Int>()
            fun keyOf(code: String, length: Int): Long? {
                val parts = code.split(' ')
                if (parts.size != length || length > MAX_WORD) return null
                var key = 0L
                for (p in parts) {
                    val id = syllableIds.getOrPut(p) { syllableIds.size }
                    if (id >= BASE - 1) return null
                    key = key * BASE + (id + 1)
                }
                return key
            }

            val records = Records()
            for (word in essayWeights.keys union explicit.keys) {
                val length = word.codePointCount(0, word.length)
                if (length !in 1..MAX_WORD) continue
                val weight = (essayWeights[word] ?: 0L) + 0.5
                if (length >= 2 && weight < MIN_WORD_WEIGHT) continue
                val taiwan = TaiwanVariants.toTaiwan(word)
                val codes = explicit[word] ?: combinations(word, charReadings)
                for (code in codes) keyOf(code, length)?.let { records.add(it, taiwan, weight) }
            }
            var maxWeight = 0.0
            for (i in 0 until records.size) maxWeight = maxOf(maxWeight, records.weights[i])
            for (entry in personal) {
                val word = TaiwanVariants.toTaiwan(entry.text)
                keyOf(entry.code, word.codePointCount(0, word.length))?.let { records.add(it, word, maxWeight + 1) }
            }

            return finish(records, syllableIds, total.coerceAtLeast(1))
        }

        /** Merges equal (key, word) records, keeps the [PER_KEY] best words of each key, and packs them. */
        private fun finish(records: Records, syllableIds: Map<String, Int>, total: Long): SentenceLexicon {
            val order = (0 until records.size).sortedWith(
                compareBy<Int>({ records.keys[it] }, { records.words[it] })
            )
            val keys = ArrayList<Long>()
            val entryStarts = ArrayList<Int>()
            val text = StringBuilder()
            val wordStarts = ArrayList<Int>()
            val scores = ArrayList<Float>()
            var i = 0
            while (i < order.size) {
                val key = records.keys[order[i]]
                val merged = ArrayList<Pair<String, Double>>()
                while (i < order.size && records.keys[order[i]] == key) {
                    val word = records.words[order[i]]!!
                    var weight = 0.0
                    while (i < order.size && records.keys[order[i]] == key && records.words[order[i]] == word) {
                        weight += records.weights[order[i]]
                        i++
                    }
                    merged.add(word to weight)
                }
                keys.add(key)
                entryStarts.add(scores.size)
                for ((word, weight) in merged.sortedWith(compareBy({ -it.second }, { it.first })).take(PER_KEY)) {
                    wordStarts.add(text.length)
                    text.append(word)
                    scores.add(ln(weight / total).toFloat())
                }
            }
            entryStarts.add(scores.size)
            wordStarts.add(text.length)
            val syllables = arrayOfNulls<String>(syllableIds.size)
            for ((s, id) in syllableIds) syllables[id] = s
            return SentenceLexicon(
                syllables.requireNoNulls(), keys.toLongArray(), entryStarts.toIntArray(),
                text.toString(), wordStarts.toIntArray(), scores.toFloatArray()
            )
        }

        /**
         * Readings of [word] made from its characters' readings, at most [MAX_COMBINATIONS], in
         * dictionary order with the last character varying fastest. A character's readings below
         * [MIN_READING_PERCENT] are left out unless all of them are.
         */
        private fun combinations(word: String, readings: Map<String, List<Pair<String, Double?>>>): List<String> {
            val options = ArrayList<List<String>>()
            var at = 0
            while (at < word.length) {
                val cp = word.codePointAt(at)
                at += Character.charCount(cp)
                val all = readings[String(Character.toChars(cp))] ?: return emptyList()
                options.add(all.filter { it.second == null || it.second!! >= MIN_READING_PERCENT }
                    .map { it.first }.ifEmpty { all.map { it.first } })
            }
            val out = ArrayList<String>()
            val index = IntArray(options.size)
            while (out.size < MAX_COMBINATIONS) {
                out.add(options.indices.joinToString(" ") { options[it][index[it]] })
                var p = options.size - 1
                while (p >= 0) {
                    index[p]++
                    if (index[p] < options[p].size) break
                    index[p] = 0
                    p--
                }
                if (p < 0) break
            }
            return out
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.engine.general.SentenceLexiconTest'`
Expected: 12 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add java/src/org/futo/inputmethod/engine/general/SentenceLexicon.kt tests/unit/org/futo/inputmethod/engine/general/SentenceLexiconTest.kt
git commit -m "Add the Zhuyin sentence lexicon"
```
(with the Global Constraints trailer)

---

### Task 2: `SentenceNBest`, `SentenceSelector`, `ZhuyinReading.splitSelected`

**Files:**
- Create: `java/src/org/futo/inputmethod/engine/general/SentenceNBest.kt`
- Create: `java/src/org/futo/inputmethod/engine/general/SentenceSelector.kt`
- Modify: `java/src/org/futo/inputmethod/engine/general/ZhuyinReading.kt` (add `splitSelected`)
- Test: `tests/unit/org/futo/inputmethod/engine/general/SentenceNBestTest.kt`
- Test: `tests/unit/org/futo/inputmethod/engine/general/SentenceSelectorTest.kt`
- Test: `tests/unit/org/futo/inputmethod/engine/general/ZhuyinReadingTest.kt` (add tests)

**Interfaces:**
- Consumes: `SentenceLexicon.lookup`, `SentenceLexicon.MAX_WORD`, `SentenceLexicon.build` (tests), `ZhuyinReranker.PROMOTE_MARGIN: Float`.
- Produces:
  - `object SentenceNBest`：`const val K = 16`、`val SYLLABLES = 4..12`、`data class Sentence(val text: String, val score: Double)`、`fun best(lexicon: SentenceLexicon, reading: List<String>, k: Int = K): List<Sentence>`、`fun candidates(lexicon: SentenceLexicon, reading: List<String>, rimeSentence: String, covers: (String) -> Boolean, k: Int = K): List<String>`、`fun promoted(scores: FloatArray): Int?`。
  - `object SentenceSelector`：`fun next(candidates: List<String>, remaining: String): Int`（-1 = 沒有可選的）。
  - `ZhuyinReading.splitSelected(preedit: String): Pair<String, List<ZhuyinSyllable>>?`。

- [ ] **Step 1: Write the failing tests**

`SentenceNBestTest.kt`:

```kotlin
package org.futo.inputmethod.engine.general

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class SentenceNBestTest {
    private val lexicon = SentenceLexicon.build(
        StringReader("---\n...\n請\tqing\n青\tqing\n到\tdao\n島\tdao\n登\tdeng\n記\tji"),
        StringReader("請到\t100\n青島\t60\n登記\t200\n請\t50\n到\t50\n青\t20\n島\t20\n登\t10\n記\t10"),
        emptyList(),
    )
    private val reading = listOf("qing", "dao", "deng", "ji")

    @Test
    fun bestPathFirstWithoutDuplicates() {
        val best = SentenceNBest.best(lexicon, reading)
        assertEquals("請到登記", best.first().text)
        assertTrue(best.any { it.text == "青島登記" })
        assertEquals(best.size, best.map { it.text }.toSet().size)
        assertEquals(best.sortedByDescending { it.score }.map { it.score }, best.map { it.score })
    }

    @Test
    fun keepsAtMostTwiceK() {
        assertTrue(SentenceNBest.best(lexicon, reading, k = 1).size <= 2)
    }

    @Test
    fun unknownSyllableGivesNothing() {
        assertEquals(emptyList<SentenceNBest.Sentence>(), SentenceNBest.best(lexicon, listOf("qing", "zzz")))
    }

    @Test
    fun candidatesStartWithRimesSentence() {
        val list = SentenceNBest.candidates(lexicon, reading, "青島登記", { true })
        assertEquals("青島登記", list[0])
        assertEquals("請到登記", list[1])
        assertEquals(list.size, list.toSet().size)
        assertTrue(list.size <= SentenceNBest.K)
    }

    @Test
    fun candidatesSkipWordsTheModelDoesNotKnow() {
        val list = SentenceNBest.candidates(lexicon, reading, "請到登記", { !it.contains('島') })
        assertTrue(list.none { it.contains('島') })
        assertEquals("請到登記", list[0])
    }

    @Test
    fun promotedNeedsTheMargin() {
        assertEquals(1, SentenceNBest.promoted(floatArrayOf(-5f, -2f, -6f)))
        assertEquals(1, SentenceNBest.promoted(floatArrayOf(-5f, -3f)))
        assertNull(SentenceNBest.promoted(floatArrayOf(-5f, -3.5f)))
        assertNull(SentenceNBest.promoted(floatArrayOf(-1f, -5f)))
        assertNull(SentenceNBest.promoted(floatArrayOf(-5f, Float.NaN)))
        assertNull(SentenceNBest.promoted(floatArrayOf(-5f)))
    }
}
```

`SentenceSelectorTest.kt`:

```kotlin
package org.futo.inputmethod.engine.general

import org.junit.Assert.assertEquals
import org.junit.Test

class SentenceSelectorTest {
    @Test
    fun remainingEqualsRimesFirst() {
        assertEquals(0, SentenceSelector.next(listOf("登記處進行登記", "登記處", "等級"), "登記處進行登記"))
    }

    @Test
    fun longestLeadingWord() {
        assertEquals(2, SentenceSelector.next(listOf("青島登記處", "青島", "請到", "請"), "請到登記處"))
        // Rime's first may itself be a leading part of the sentence.
        assertEquals(0, SentenceSelector.next(listOf("登記處進行", "登記處", "登記", "等級"), "登記處進行登記"))
    }

    @Test
    fun noWordLeadsThere() {
        assertEquals(-1, SentenceSelector.next(listOf("青島登記處", "青島", "清"), "請到登記處"))
        assertEquals(-1, SentenceSelector.next(emptyList(), "請到"))
        assertEquals(-1, SentenceSelector.next(listOf("請到"), ""))
    }
}
```

Add to `ZhuyinReadingTest.kt` (inside the class):

```kotlin
    @Test
    fun splitSelectedSeparatesChosenText() {
        assertEquals("請到" to listOf(ZhuyinSyllable("ㄉㄥ", 1), ZhuyinSyllable("ㄐㄧ", 4)),
            ZhuyinReading.splitSelected("請到ㄉㄥˉ ㄐㄧˋ"))
        assertEquals("" to listOf(ZhuyinSyllable("ㄉㄥ", 1)), ZhuyinReading.splitSelected("ㄉㄥˉ"))
    }

    @Test
    fun splitSelectedNeedsTonedSyllables() {
        assertEquals(null, ZhuyinReading.splitSelected("請到"))
        assertEquals(null, ZhuyinReading.splitSelected("請到ㄉㄥ"))
        assertEquals(null, ZhuyinReading.splitSelected(""))
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.engine.general.*'`
Expected: compilation FAIL (`SentenceNBest`, `SentenceSelector`, `splitSelected` unresolved).

- [ ] **Step 3: Implement**

`SentenceNBest.kt`:

```kotlin
package org.futo.inputmethod.engine.general

/**
 * Whole-sentence candidates (整句) from [SentenceLexicon]: a beam search over the word lattice of
 * a reading, scored by the words' log shares of the essay weights.
 */
object SentenceNBest {
    const val K = 16
    /** Syllable counts handled as sentences; fewer are left to [ZhuyinReranker]. */
    val SYLLABLES = 4..12

    data class Sentence(val text: String, val score: Double)

    /**
     * The best sentences for [reading] (luna_pinyin syllables), best first; each position keeps
     * the 2 × [k] best paths and one path per text. Empty if no path covers the reading.
     */
    fun best(lexicon: SentenceLexicon, reading: List<String>, k: Int = K): List<Sentence> {
        val n = reading.size
        if (n == 0) return emptyList()
        val beams = Array<List<Sentence>>(n + 1) { emptyList() }
        beams[0] = listOf(Sentence("", 0.0))
        for (i in 0 until n) {
            if (beams[i].isEmpty()) continue
            for (j in i + 1..minOf(n, i + SentenceLexicon.MAX_WORD)) {
                val words = lexicon.lookup(reading.subList(i, j))
                if (words.isEmpty()) continue
                val next = ArrayList<Sentence>(beams[j])
                for (path in beams[i]) for (w in words) next.add(Sentence(path.text + w.word, path.score + w.score))
                beams[j] = prune(next, 2 * k)
            }
        }
        return beams[n]
    }

    private fun prune(paths: List<Sentence>, limit: Int): List<Sentence> {
        val best = HashMap<String, Sentence>()
        for (p in paths) {
            val seen = best[p.text]
            if (seen == null || p.score > seen.score) best[p.text] = p
        }
        return best.values.sortedWith(compareByDescending<Sentence> { it.score }.thenByDescending { it.text }).take(limit)
    }

    /**
     * The sentences to score: Rime's own first, then the best others whose characters the model
     * knows ([covers]), [k] in all.
     */
    fun candidates(
        lexicon: SentenceLexicon, reading: List<String>, rimeSentence: String,
        covers: (String) -> Boolean, k: Int = K,
    ): List<String> = (listOf(rimeSentence) + best(lexicon, reading, k).map { it.text }
        .filter { it != rimeSentence && covers(it) }).take(k)

    /**
     * Index of the sentence to show ahead of Rime's (index 0), or null to keep Rime's: the model's
     * favourite, if it beats Rime's by at least [ZhuyinReranker.PROMOTE_MARGIN].
     */
    fun promoted(scores: FloatArray): Int? {
        if (scores.size < 2 || scores.any { it.isNaN() || it.isInfinite() }) return null
        val best = scores.indices.maxByOrNull { scores[it] } ?: return null
        if (best == 0 || scores[best] - scores[0] < ZhuyinReranker.PROMOTE_MARGIN) return null
        return best
    }
}
```

`SentenceSelector.kt`:

```kotlin
package org.futo.inputmethod.engine.general

/**
 * Picks Rime candidates one by one so that the composition becomes a given sentence and Rime
 * learns its words.
 */
object SentenceSelector {
    /**
     * Index of the candidate to select next to reach [remaining]: Rime's first if it is exactly
     * [remaining], else the longest candidate that [remaining] starts with (the first of equal
     * length), or -1 if none.
     */
    fun next(candidates: List<String>, remaining: String): Int {
        if (remaining.isEmpty()) return -1
        if (candidates.firstOrNull() == remaining) return 0
        var best = -1
        candidates.forEachIndexed { i, c ->
            if (c.isNotEmpty() && c.length < remaining.length && remaining.startsWith(c) &&
                (best < 0 || c.length > candidates[best].length)) best = i
        }
        return best
    }
}
```

In `ZhuyinReading.kt`, add inside `object ZhuyinReading` after `parse`:

```kotlin
    /**
     * Splits a preedit whose first words were already chosen (e.g. "請到ㄉㄥˉ ㄐㄧˋ") into the
     * chosen text and the syllables still to type; null if no toned syllables follow.
     */
    fun splitSelected(preedit: String): Pair<String, List<ZhuyinSyllable>>? {
        val start = preedit.indexOfFirst(::isZhuyinLetter)
        if (start < 0) return null
        val reading = parse(preedit.substring(start)) ?: return null
        return preedit.substring(0, start).trim() to reading
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.engine.general.*'`
Expected: all PASS (SentenceNBestTest 6, SentenceSelectorTest 3, ZhuyinReadingTest +2).

- [ ] **Step 5: Commit**

```bash
git add java/src/org/futo/inputmethod/engine/general/SentenceNBest.kt java/src/org/futo/inputmethod/engine/general/SentenceSelector.kt java/src/org/futo/inputmethod/engine/general/ZhuyinReading.kt tests/unit/org/futo/inputmethod/engine/general/SentenceNBestTest.kt tests/unit/org/futo/inputmethod/engine/general/SentenceSelectorTest.kt tests/unit/org/futo/inputmethod/engine/general/ZhuyinReadingTest.kt
git commit -m "Add the sentence N-best search and Rime word selector"
```
(with the Global Constraints trailer)

---

### Task 3: Wire sentence reranking into `ChineseIME`

**Files:**
- Modify: `java/src/org/futo/inputmethod/engine/general/ChineseIME.kt`
- Modify: `java/src/org/futo/inputmethod/engine/general/OnnxCandidateScorer.kt` (`THREADS = 4`)

**Interfaces:**
- Consumes: everything Task 1–2 produce; existing `CandidateScorer.covers/score`, `ZhuyinSyllables.toLunaPinyin(String): String?`, `rime.getCandidates()`, `rime.getPreedit()`, `rime.selectCandidate(Int)`, `rime.clearComposition()`.
- Produces: user-visible behaviour only.

- [ ] **Step 1: 4 ONNX threads**

In `OnnxCandidateScorer.kt` change `private const val THREADS = 2` to:

```kotlin
        /** Sentences (up to 16 × 12 characters) need 4; 2 took ~350 ms on a phone. */
        private const val THREADS = 4
```

- [ ] **Step 2: Constants and fields**

In the `companion object` next to `RerankTimeoutMs` add:

```kotlin
        private const val SentenceRerankTimeoutMs = 500L
        /** Rime selections tried for one sentence before committing the rest directly. */
        private const val MaxSentenceSteps = 12
```

After the `candidateOrder` field add:

```kotlin
    /** Words for whole-sentence candidates (整句); null until loaded or built, or after it failed. */
    private var sentenceLexicon: SentenceLexicon? = null
    private var sentenceLexiconJob: Job? = null
    private var sentenceLexiconFailed = false
    /** What [sentenceLexicon] was built from (see SentenceLexicon.tag). */
    private var sentenceLexiconTag: String? = null
    /** Personal-dictionary entries of the last configuration update, for [sentenceLexicon]. */
    private var personalEntries: List<ChineseCustomDict.Entry> = emptyList()
    /**
     * The model's sentence shown first in place of Rime's, with the text already chosen before
     * it in the composition; null when none is shown. Shown at display position 0, which
     * [candidateOrder] maps to -1.
     */
    private var sentenceOverride: Pair<String, String>? = null
    private var sentenceCommitting = false
```

- [ ] **Step 3: Reset the override wherever `candidateOrder` is reset**

In `onStartEdit()` and at the top of `subscribeToRimeCandidates` (both set `candidateOrder = null`), add right after that line:

```kotlin
        sentenceOverride = null
```

- [ ] **Step 4: Load the lexicon**

After `ensureScorerLoaded()` add:

```kotlin
    /**
     * Loads the sentence lexicon in the background, building it (seconds) on first use or when
     * the dictionary or personal words changed; does nothing if the loaded one still matches.
     * The previous one stays in use meanwhile.
     */
    private fun ensureSentenceLexicon() {
        if(!isBopomofo || sentenceLexiconJob != null || sentenceLexiconFailed) return
        if(!helper.context.getSetting(ChineseIMESettings.RerankSetting)) return
        val personal = personalEntries
        val shared = getShared(helper.context)
        val cache = File(helper.context.filesDir, "zhuyin-sentence/lexicon.bin")
        val loadedTag = sentenceLexiconTag
        sentenceLexiconJob = coroScope.launch {
            val start = SystemClock.elapsedRealtime()
            val result = withContext(Dispatchers.IO) {
                try {
                    val tag = SentenceLexicon.tag(shared, personal)
                    if(tag == loadedTag) null else tag to SentenceLexicon.loadOrBuild(cache, shared, personal)
                } catch(e: CancellationException) {
                    throw e
                } catch(e: Exception) {
                    Log.e(TAG, "Failed to load the sentence lexicon", e)
                    "" to null
                } catch(e: OutOfMemoryError) {
                    Log.e(TAG, "Out of memory loading the sentence lexicon", e)
                    "" to null
                }
            }
            sentenceLexiconJob = null
            val (tag, loaded) = result ?: return@launch
            if(loaded != null) {
                sentenceLexicon = loaded
                sentenceLexiconTag = tag
            } else {
                sentenceLexiconFailed = true
            }
            Log.i(TAG, "Sentence lexicon: ${loaded?.entryCount ?: 0} words in ${SystemClock.elapsedRealtime() - start} ms")
        }
    }
```

Call it only once the personal words are known (so the first build is not repeated), in `updateConfig` right after `personalWords = …`; `maybeRerankSentence` also calls it when no lexicon is loaded yet:

```kotlin
                personalEntries = customEntries
                ensureSentenceLexicon()
```

(`java.io.File` is already imported in `ChineseIME.kt`; add `import java.io.File` if the compiler says otherwise.)

- [ ] **Step 5: Route sentences in `maybeRerank`**

At the start of `maybeRerank`, after `val scorer = scorer ?: return`, add:

```kotlin
        if(sentenceCommitting) return
```

Replace these two lines:

```kotlin
        val preedit = rime.preeditFlow.value
        val reading = ZhuyinReading.parse(preedit) ?: return
        if(reading.size > ZhuyinReranker.MAX_SYLLABLES) return
```

with:

```kotlin
        val preedit = rime.preeditFlow.value
        val (chosen, reading) = ZhuyinReading.splitSelected(preedit) ?: return
        if(reading.size in SentenceNBest.SYLLABLES) {
            maybeRerankSentence(scorer, candidates, preedit, chosen, reading)
            return
        }
        // Words the user already chose are only handled for sentences.
        if(chosen.isNotEmpty() || reading.size > ZhuyinReranker.MAX_SYLLABLES) return
```

Add the new function after `maybeRerank`:

```kotlin
    /**
     * Scores Rime's sentence against the lexicon's best sentences for the same reading and, if
     * the model clearly prefers another, shows that one first (Rime's moves to second).
     */
    private fun maybeRerankSentence(
        scorer: CandidateScorer, candidates: List<RimeCandidate>, preedit: String,
        chosen: String, reading: List<ZhuyinSyllable>,
    ) {
        if(prevConfiguration?.simplification == true) return
        val lexicon = sentenceLexicon ?: run { ensureSentenceLexicon(); return }
        val words = candidates.map { it.text }
        val rimeSentence = words.first()
        if(rimeSentence.codePointCount(0, rimeSentence.length) != reading.size || !scorer.covers(rimeSentence)) return
        val spellings = reading.map { ZhuyinSyllables.toLunaPinyin(it.base) ?: return }
        val context = connect?.getTextBeforeCursor(RerankContextChars, 0)?.toString().orEmpty() + chosen

        rerankJob = coroScope.launch {
            val start = SystemClock.elapsedRealtime()
            val result = withContext(rerankDispatcher) {
                try {
                    val list = SentenceNBest.candidates(lexicon, spellings, rimeSentence, scorer::covers)
                    if(list.size < 2) null
                    else scorer.score(context, reading, list)?.let { list to it }
                } catch(e: CancellationException) {
                    throw e
                } catch(e: Exception) {
                    Log.e(TAG, "Sentence rerank failed", e)
                    null
                } catch(e: OutOfMemoryError) {
                    Log.e(TAG, "Out of memory during sentence rerank", e)
                    null
                }
            } ?: return@launch
            val (list, scores) = result
            val elapsed = SystemClock.elapsedRealtime() - start
            Log.d(TAG, "Sentence rerank: ${list.size} sentences in $elapsed ms")
            if(elapsed > SentenceRerankTimeoutMs) {
                Log.w(TAG, "Sentence rerank took $elapsed ms; ignored")
                return@launch
            }
            if(rime.preeditFlow.value != preedit || rime.candidatesFlow.value.map { it.text } != words) return@launch
            if(editingState.active || isOnEnglishPage || !isBopomofo || sentenceCommitting) return@launch
            val best = SentenceNBest.promoted(scores) ?: return@launch
            sentenceOverride = chosen to list[best]
            candidateOrder = IntArray(words.size + 1) { it - 1 }
            showSuggestionStrip(candidateWords(listOf(list[best]) + words, listOf(null) + candidates.map { it.comment }))
        }
    }

    /**
     * Commits the model's sentence: selects its words one by one among Rime's candidates so that
     * Rime learns them; if Rime offers no word that leads there, clears the composition and
     * commits the text directly.
     */
    private suspend fun commitSentence(sentence: Pair<String, String>) {
        val (chosen, target) = sentence
        sentenceCommitting = true
        try {
            var done = ""
            for(step in 0 until MaxSentenceSteps) {
                val candidates = rime.getCandidates().map { it.text }
                val remaining = target.substring(done.length)
                val index = SentenceSelector.next(candidates, remaining)
                if(index < 0) break
                val word = candidates[index]
                if(!rime.selectCandidate(index)) break
                // The whole rest was selected: Rime commits it (RimeMessage.Commit).
                if(word == remaining) return
                done += word
                if(!rime.getPreedit().startsWith(chosen + done)) break
            }
            rime.clearComposition()
            rawInput.clear()
            connect?.commitText(chosen + target, 1)
            showAssociations(chosen + target, chosen + target)
        } finally {
            sentenceCommitting = false
        }
    }
```



- [ ] **Step 6: Space key and taps**

In the space branch of `handleBopomofoKey`, change:

```kotlin
            val first = rimeIndexOf(0)
            val firstText = rime.candidatesFlow.value.getOrNull(first)?.text
```

to:

```kotlin
            val first = rimeIndexOf(0)
            val firstText = rime.candidatesFlow.value.getOrNull(first)?.text
            // -1: the model's sentence is shown first.
            val sentence = if(first < 0) sentenceOverride else null
```

and in the `ZhuyinKeymap.isToneKey(...)` branch replace:

```kotlin
                        rawInput.clear()
                        // The candidates may have changed since the key was pressed.
                        val index = if(rime.candidatesFlow.value.getOrNull(first)?.text == firstText) first else 0
                        if(!rime.selectCandidate(index)) rime.commitComposition()
```

with:

```kotlin
                        rawInput.clear()
                        if(sentence != null) {
                            commitSentence(sentence)
                        } else {
                            // The candidates may have changed since the key was pressed.
                            val index = if(rime.candidatesFlow.value.getOrNull(first)?.text == firstText) first else 0
                            if(!rime.selectCandidate(index)) rime.commitComposition()
                        }
```

In `EVENT_TYPE_SUGGESTION_PICKED`, in the final `else` branch, before `var index = rimeIndexOf(suggestion.mCandidateIndex)`, add:

```kotlin
                    val sentence = sentenceOverride
                    if(sentence != null && rimeIndexOf(suggestion.mCandidateIndex) < 0 && suggestion.word == sentence.second) {
                        rawInput.clear()
                        coroScope.launch { commitSentence(sentence) }
                        return
                    }
```

- [ ] **Step 7: Build and run all unit tests**

Run: `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.engine.general.*' assembleUnstableDebug`
Expected: BUILD SUCCESSFUL; no new Kotlin warnings in the changed files.

- [ ] **Step 8: Commit**

```bash
git add java/src/org/futo/inputmethod/engine/general/ChineseIME.kt java/src/org/futo/inputmethod/engine/general/OnnxCandidateScorer.kt
git commit -m "Rerank whole Zhuyin sentences with the context model"
```
(with the Global Constraints trailer)

---

### Task 4: Device verification (controller, via adb)

- [ ] Install: `./gradlew installUnstableDebug`; switch to the Zhuyin layout in Chrome's address bar.
- [ ] `adb logcat -s "ChineseIME (rime)"`: first use logs `Sentence lexicon: N words in X ms` (build); restart the keyboard (`am force-stop`) and confirm the second load is fast (cache). Record build time, load time, `ls -l files/zhuyin-sentence/lexicon.bin` size.
- [ ] Type ㄑㄧㄥˇ ㄉㄠˋ ㄉㄥˉ ㄐㄧˋ ㄔㄨˋ ㄐㄧㄣˋ ㄒㄧㄥˊ ㄉㄥˉ ㄐㄧˋ: first is 請到登記處進行登記, second 青島登記處進行登記; `Sentence rerank: … ms` under 500. Space commits 請到登記處進行登記.
- [ ] Type the same again: Rime itself now shows 請到… first (it learnt the words).
- [ ] Tap the model sentence on another example (e.g. ㄒㄧˋ ㄍㄨˇ ㄉㄨˊ ㄐㄧㄠˇ ㄕㄡˋ → 矽谷獨角獸).
- [ ] Choose the first word by hand (tap 請到), confirm the rest is still reranked with 請到 as context.
- [ ] Setting off → no sentence rerank; on again.
- [ ] Memory: `dumpsys meminfo` before and after the lexicon loads.
- [ ] Compare 5 sentences' N-best on the phone (temporary debug log of `list`) with `eval/lex-16-50.json` from the Python prototype; differences only in ties are acceptable. Remove the temporary log.
- [ ] Restore screen timeout; record results in `.superpowers/sdd/progress.md`.

package org.futo.inputmethod.engine.general

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.Reader
import kotlin.math.ln

/**
 * Words by reading for whole-sentence candidates (整句), built from the dictionary's
 * `luna_pinyin.dict.yaml` and `essay.txt`, with the personal dictionary's words layered on top
 * by [withPersonal]. Readings are luna_pinyin syllables without tone.
 *
 * Stored compactly: a reading of up to [MAX_WORD] syllables is one Long key (syllable ids + 1
 * in base 512), keys are sorted, and the words of key i are entries
 * `entryStarts[i] until entryStarts[i + 1]`, best first; their text is one string cut at
 * `wordStarts`. Only these dictionary words are cached (see [write]); the personal words live in
 * [overlay], keyed by the reading joined with single spaces.
 */
class SentenceLexicon private constructor(
    private val syllables: Array<String>,
    private val keys: LongArray,
    private val entryStarts: IntArray,
    private val text: String,
    private val wordStarts: IntArray,
    private val scores: FloatArray,
    /** The score of a word weighing one more than the heaviest dictionary word; personal words get it. */
    val topScore: Float,
    private val syllableIds: Map<String, Int> = idsOf(syllables),
    private val overlay: Map<String, List<Entry>> = emptyMap(),
) {
    /** A word and its log share of the essay weights. */
    data class Entry(val word: String, val score: Float)

    /** Dictionary readings and words; personal words are not counted. */
    val keyCount: Int get() = keys.size
    val entryCount: Int get() = scores.size

    /**
     * The words read as [reading] (luna_pinyin syllables), best first: personal words in the
     * order given to [withPersonal], then dictionary words not already listed, [PER_KEY] at most.
     */
    fun lookup(reading: List<String>): List<Entry> {
        if (reading.isEmpty() || reading.size > MAX_WORD) return emptyList()
        val personal = if (overlay.isEmpty()) emptyList() else overlay[reading.joinToString(" ")].orEmpty()
        val base = baseLookup(reading)
        if (personal.isEmpty()) return base
        val out = ArrayList<Entry>(minOf(PER_KEY, personal.size + base.size))
        personal.forEach { if (out.size < PER_KEY) out.add(it) }
        for (e in base) {
            if (out.size >= PER_KEY) break
            if (personal.none { it.word == e.word }) out.add(e)
        }
        return out
    }

    private fun baseLookup(reading: List<String>): List<Entry> {
        var key = 0L
        for (s in reading) key = key * BASE + ((syllableIds[s] ?: return emptyList()) + 1)
        val i = java.util.Arrays.binarySearch(keys, key)
        if (i < 0) return emptyList()
        return (entryStarts[i] until entryStarts[i + 1]).map { e ->
            Entry(text.substring(wordStarts[e], wordStarts[e + 1]), scores[e])
        }
    }

    /**
     * This lexicon's dictionary words with [personal] layered on top (replacing any earlier
     * personal words), sharing the dictionary data. Each personal word is taken in its Taiwan
     * form for its code's syllables (split on any whitespace) if it has one character per
     * syllable; words with a blank code or more than [MAX_WORD] syllables are skipped, and a
     * repeated word for the same reading counts once. All of them score [topScore].
     */
    fun withPersonal(personal: List<ChineseCustomDict.Entry>): SentenceLexicon {
        val map = HashMap<String, MutableList<Entry>>()
        val seen = HashSet<Pair<String, String>>()
        for (entry in personal) {
            if (entry.code.isBlank()) continue
            val parts = entry.code.trim().split(WHITESPACE)
            if (parts.size > MAX_WORD) continue
            val word = TaiwanVariants.toTaiwan(entry.text)
            if (word.codePointCount(0, word.length) != parts.size) continue
            val reading = parts.joinToString(" ")
            if (seen.add(reading to word)) map.getOrPut(reading) { ArrayList(1) }.add(Entry(word, topScore))
        }
        return SentenceLexicon(syllables, keys, entryStarts, text, wordStarts, scores, topScore, syllableIds, map)
    }

    /** Writes the dictionary words; personal words are not written. */
    fun write(out: OutputStream, tag: String) {
        val data = DataOutputStream(BufferedOutputStream(out))
        data.writeInt(MAGIC)
        data.writeInt(FORMAT)
        data.writeUTF(tag)
        data.writeFloat(topScore)
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
        var maxWeight = 0.0
        var total = 1L

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
            if (weight > maxWeight) maxWeight = weight
        }
    }

    private class LongBuf {
        var a = LongArray(1024)
        var size = 0
        fun add(v: Long) {
            if (size == a.size) a = a.copyOf(size * 2)
            a[size++] = v
        }
        fun toArray() = a.copyOf(size)
    }

    private class IntBuf {
        var a = IntArray(1024)
        var size = 0
        fun add(v: Int) {
            if (size == a.size) a = a.copyOf(size * 2)
            a[size++] = v
        }
        fun toArray() = a.copyOf(size)
    }

    private class FloatBuf {
        var a = FloatArray(1024)
        var size = 0
        fun add(v: Float) {
            if (size == a.size) a = a.copyOf(size * 2)
            a[size++] = v
        }
        fun toArray() = a.copyOf(size)
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
        // 2: personal words left out (see withPersonal), topScore added.
        private const val FORMAT = 2
        private val WHITESPACE = Regex("\\s+")

        private fun idsOf(syllables: Array<String>): Map<String, Int> =
            HashMap<String, Int>(syllables.size * 2).also { m -> syllables.forEachIndexed { i, s -> m[s] = i } }

        /** Identifies the dictionary a lexicon was built from. */
        fun tag(sharedDir: File): String =
            TAG_FILES.joinToString("|") { name ->
                File(sharedDir, name).takeIf { it.exists() }?.readText()?.trim().orEmpty()
            }

        /**
         * The lexicon cached in [cache] if it was built for the same dictionary, otherwise one
         * built from [sharedDir] (and written to [cache]); null if the dictionary has no
         * luna_pinyin.dict.yaml or essay.txt. Personal words are added by [withPersonal].
         */
        fun loadOrBuild(cache: File, sharedDir: File): SentenceLexicon? {
            val tag = try {
                tag(sharedDir)
            } catch (e: IOException) {
                return null
            }
            if (cache.exists()) {
                try {
                    cache.inputStream().use { read(it, tag, cache.length()) }?.let { return it }
                } catch (e: IOException) {
                    // Truncated or corrupt: rebuild below.
                } catch (e: RuntimeException) {
                    // Corrupt in a way that broke an assumption: rebuild below.
                } catch (e: OutOfMemoryError) {
                    // Absurd allocation from a corrupt file: rebuild below.
                }
            }
            val dict = File(sharedDir, DICT_FILE)
            val essay = File(sharedDir, ESSAY_FILE)
            if (!dict.exists() || !essay.exists()) return null
            // Temp files of a process killed while writing.
            cache.parentFile?.listFiles { f -> f.name.startsWith("lexicon") && f.name.endsWith(".tmp") }
                ?.forEach { it.delete() }
            val lexicon = try {
                dict.bufferedReader().use { d -> essay.bufferedReader().use { e -> build(d, e) } }
            } catch (e: IOException) {
                return null
            }
            var tmp: File? = null
            try {
                cache.parentFile?.mkdirs()
                tmp = File.createTempFile("lexicon", ".tmp", cache.parentFile)
                tmp.outputStream().use { lexicon.write(it, tag) }
                if (!tmp.renameTo(cache)) tmp.delete()
            } catch (e: IOException) {
                // The cache is only an optimisation.
                tmp?.delete()
            }
            return lexicon
        }

        private const val MAX_COUNT = 10_000_000
        private const val MAX_TEXT_BYTES = 100_000_000

        private fun DataInputStream.readCount(max: Int): Int {
            val n = readInt()
            if (n < 0 || n > max) throw IOException("Implausible count $n")
            return n
        }

        /**
         * The lexicon written by [write] with the same [tag], or null if the tag or format
         * differs. Throws [IOException] if the data is truncated or inconsistent.
         */
        fun read(input: InputStream, tag: String): SentenceLexicon? = read(input, tag, Long.MAX_VALUE)

        private class CountingInputStream(input: InputStream) : java.io.FilterInputStream(input) {
            var count = 0L
            override fun read(): Int = super.read().also { if (it >= 0) count++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                super.read(b, off, len).also { if (it > 0) count += it }
            override fun skip(n: Long): Long = super.skip(n).also { count += it }
        }

        /** As [read], with counts bounded by the [byteLimit] bytes the stream holds. */
        internal fun read(input: InputStream, tag: String, byteLimit: Long): SentenceLexicon? {
            val counter = CountingInputStream(input.buffered())
            val data = DataInputStream(counter)
            fun need(bytes: Long) {
                if (bytes < 0 || bytes > byteLimit - counter.count) throw IOException("Count exceeds file size")
            }
            if (data.readInt() != MAGIC || data.readInt() != FORMAT || data.readUTF() != tag) return null
            val topScore = data.readFloat()
            val syllableCount = data.readCount(BASE.toInt())
            need(syllableCount * 2L)
            val syllables = Array(syllableCount) { data.readUTF() }
            need(4)
            val keyCount = data.readCount(MAX_COUNT)
            need(keyCount * 8L + (keyCount + 1L) * 4)
            val keys = LongArray(keyCount) { data.readLong() }
            val entryStarts = IntArray(keyCount + 1) { data.readInt() }
            need(4)
            val entryCount = data.readCount(MAX_COUNT)
            need((entryCount + 1L) * 4 + entryCount * 4L)
            val wordStarts = IntArray(entryCount + 1) { data.readInt() }
            val scores = FloatArray(entryCount) { data.readFloat() }
            need(4)
            val byteCount = data.readCount(MAX_TEXT_BYTES)
            need(byteCount.toLong())
            val bytes = ByteArrayOutputStream(minOf(byteCount, 1 shl 20))
            val chunk = ByteArray(1 shl 16)
            var left = byteCount
            while (left > 0) {
                val n = data.read(chunk, 0, minOf(left, chunk.size))
                if (n < 0) throw EOFException()
                bytes.write(chunk, 0, n)
                left -= n
            }
            val text = String(bytes.toByteArray(), Charsets.UTF_8)

            fun bad(): Nothing = throw IOException("Corrupt sentence lexicon")
            if (topScore.isNaN()) bad()
            if (entryStarts[0] != 0 || entryStarts[keyCount] != entryCount) bad()
            for (i in 0 until keyCount) if (entryStarts[i + 1] < entryStarts[i]) bad()
            if (wordStarts[0] != 0 || wordStarts[entryCount] != text.length) bad()
            for (i in 0 until entryCount) if (wordStarts[i + 1] < wordStarts[i]) bad()
            for (i in 0 until keyCount) {
                if (i > 0 && keys[i] <= keys[i - 1]) bad()
                var k = keys[i]
                if (k <= 0) bad()
                var digits = 0
                while (k > 0) {
                    val d = (k % BASE).toInt()
                    if (d == 0 || d > syllables.size) bad()
                    k /= BASE
                    digits++
                }
                if (digits > MAX_WORD) bad()
            }
            return SentenceLexicon(syllables, keys, entryStarts, text, wordStarts, scores, topScore)
        }

        /** The dictionary words of [dict] and [essay]; see [withPersonal] for personal words. */
        fun build(dict: Reader, essay: Reader): SentenceLexicon {
            val syllableIds = HashMap<String, Int>()
            val records = collect(dict, essay, syllableIds)
            return finish(records, syllableIds)
        }

        /** Reads the sources into (key, word, weight) records; the intermediate maps die on return. */
        private fun collect(
            dict: Reader,
            essay: Reader,
            syllableIds: HashMap<String, Int>,
        ): Records {
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

            fun keyOf(code: String, length: Int): Long? {
                val parts = code.trim().split(WHITESPACE)
                if (parts.size != length || length > MAX_WORD) return null
                var key = 0L
                for (p in parts) {
                    val id = syllableIds[p] ?: run {
                        if (syllableIds.size >= BASE - 1) return null
                        syllableIds.size.also { syllableIds[p] = it }
                    }
                    key = key * BASE + (id + 1)
                }
                return key
            }

            val records = Records()
            records.total = total.coerceAtLeast(1)
            val seen = ArrayList<Long>(4)
            /** The weight [word] is kept with, or null if it is too long or (as a phrase) too rare. */
            fun weightOf(word: String, length: Int): Double? {
                if (length !in 1..MAX_WORD) return null
                val weight = (essayWeights[word] ?: 0L) + 0.5
                return if (length >= 2 && weight < MIN_WORD_WEIGHT) null else weight
            }
            fun addWord(word: String, length: Int, weight: Double, codes: List<String>) {
                val taiwan = TaiwanVariants.toTaiwan(word)
                seen.clear()
                for (code in codes) {
                    val key = keyOf(code, length) ?: continue
                    // The same reading listed twice for one word counts once.
                    if (key in seen) continue
                    seen.add(key)
                    records.add(key, taiwan, weight)
                }
            }
            // Filter first: readings are only combined for the words kept.
            for (word in essayWeights.keys) {
                val length = word.codePointCount(0, word.length)
                val weight = weightOf(word, length) ?: continue
                addWord(word, length, weight, explicit[word] ?: combinations(word, charReadings))
            }
            for ((word, codes) in explicit) {
                if (word in essayWeights) continue
                val length = word.codePointCount(0, word.length)
                val weight = weightOf(word, length) ?: continue
                addWord(word, length, weight, codes)
            }
            return records
        }

        /** Sorts [index] (positions in [records]) by key, then word. */
        private fun sortByKeyThenWord(index: IntArray, records: Records) {
            val tmp = IntArray(index.size)
            fun less(a: Int, b: Int): Boolean {
                val ka = records.keys[a]
                val kb = records.keys[b]
                return if (ka != kb) ka < kb else records.words[a]!! < records.words[b]!!
            }
            fun sort(lo: Int, hi: Int) {
                if (hi - lo < 2) return
                val mid = (lo + hi) ushr 1
                sort(lo, mid)
                sort(mid, hi)
                var i = lo
                var j = mid
                var k = lo
                while (i < mid && j < hi) tmp[k++] = if (less(index[j], index[i])) index[j++] else index[i++]
                while (i < mid) tmp[k++] = index[i++]
                while (j < hi) tmp[k++] = index[j++]
                System.arraycopy(tmp, lo, index, lo, hi - lo)
            }
            sort(0, index.size)
        }

        /** Merges equal (key, word) records, keeps the [PER_KEY] best words of each key, and packs them. */
        private fun finish(records: Records, syllableIds: Map<String, Int>): SentenceLexicon {
            val total = records.total
            val order = IntArray(records.size) { it }
            sortByKeyThenWord(order, records)
            val keys = LongBuf()
            val entryStarts = IntBuf()
            val text = StringBuilder()
            val wordStarts = IntBuf()
            val scores = FloatBuf()
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
                syllables.requireNoNulls(), keys.toArray(), entryStarts.toArray(),
                text.toString(), wordStarts.toArray(), scores.toArray(),
                ln((records.maxWeight + 1) / total).toFloat()
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

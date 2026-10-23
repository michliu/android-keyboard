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
                } catch (e: RuntimeException) {
                    // Corrupt in a way that broke an assumption: rebuild below.
                }
            }
            val dict = File(sharedDir, DICT_FILE)
            val essay = File(sharedDir, ESSAY_FILE)
            if (!dict.exists() || !essay.exists()) return null
            val lexicon = dict.bufferedReader().use { d -> essay.bufferedReader().use { e -> build(d, e, personal) } }
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
        fun read(input: InputStream, tag: String): SentenceLexicon? {
            val data = DataInputStream(input.buffered())
            if (data.readInt() != MAGIC || data.readInt() != FORMAT || data.readUTF() != tag) return null
            val syllables = Array(data.readCount(BASE.toInt())) { data.readUTF() }
            val keyCount = data.readCount(MAX_COUNT)
            val keys = LongArray(keyCount) { data.readLong() }
            val entryStarts = IntArray(keyCount + 1) { data.readInt() }
            val entryCount = data.readCount(MAX_COUNT)
            val wordStarts = IntArray(entryCount + 1) { data.readInt() }
            val scores = FloatArray(entryCount) { data.readFloat() }
            val byteCount = data.readCount(MAX_TEXT_BYTES)
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
            return SentenceLexicon(syllables, keys, entryStarts, text, wordStarts, scores)
        }

        fun build(dict: Reader, essay: Reader, personal: List<ChineseCustomDict.Entry>): SentenceLexicon {
            val syllableIds = HashMap<String, Int>()
            val records = collect(dict, essay, personal, syllableIds)
            return finish(records, syllableIds)
        }

        /** Reads the sources into (key, word, weight) records; the intermediate maps die on return. */
        private fun collect(
            dict: Reader,
            essay: Reader,
            personal: List<ChineseCustomDict.Entry>,
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

            val whitespace = Regex("\\s+")
            fun keyOf(code: String, length: Int): Long? {
                val parts = code.trim().split(whitespace)
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
            records.total = total.coerceAtLeast(1)
            val seen = ArrayList<Long>(4)
            fun addWord(word: String, codes: List<String>) {
                val length = word.codePointCount(0, word.length)
                if (length !in 1..MAX_WORD) return
                val weight = (essayWeights[word] ?: 0L) + 0.5
                if (length >= 2 && weight < MIN_WORD_WEIGHT) return
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
            for (word in essayWeights.keys) addWord(word, explicit[word] ?: combinations(word, charReadings))
            for ((word, codes) in explicit) if (word !in essayWeights) addWord(word, codes)

            val maxWeight = records.maxWeight
            val personalSeen = HashSet<Pair<Long, String>>()
            for (entry in personal) {
                val word = TaiwanVariants.toTaiwan(entry.text)
                val key = keyOf(entry.code, word.codePointCount(0, word.length)) ?: continue
                if (personalSeen.add(key to word)) records.add(key, word, maxWeight + 1)
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
                text.toString(), wordStarts.toArray(), scores.toArray()
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

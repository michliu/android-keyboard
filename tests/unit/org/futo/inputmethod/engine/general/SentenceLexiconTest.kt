package org.futo.inputmethod.engine.general

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
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
        SentenceLexicon.build(StringReader(d), StringReader(e)).withPersonal(personal)

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
            val built = SentenceLexicon.loadOrBuild(cache, shared)!!
            assertTrue(cache.exists())
            assertEquals(listOf("請到", "青島"), built.lookup(listOf("qing", "dao")).map { it.word })
            // A changed dictionary version invalidates the cache.
            shared.resolve("FUTOKeyboard_ChineseDictionary_Version").writeText("2")
            shared.resolve("essay.txt").writeText("青島\t1000")
            val rebuilt = SentenceLexicon.loadOrBuild(cache, shared)!!
            // 請到 is neither in the new essay.txt nor spelled out in the dictionary.
            assertEquals(listOf("青島"), rebuilt.lookup(listOf("qing", "dao")).map { it.word })
            // No dictionary: nothing to build.
            shared.resolve("essay.txt").delete()
            cache.delete()
            assertNull(SentenceLexicon.loadOrBuild(cache, shared))
        } finally {
            shared.deleteRecursively()
            cache.parentFile?.parentFile?.deleteRecursively()
        }
    }

    private fun withDictionary(block: (shared: File, cache: File) -> Unit) {
        val shared = Files.createTempDirectory("shared").toFile()
        val cacheRoot = Files.createTempDirectory("cache").toFile()
        try {
            shared.resolve("luna_pinyin.dict.yaml").writeText(dict)
            shared.resolve("essay.txt").writeText(essay)
            shared.resolve("FUTOKeyboard_ChineseDictionary_Name").writeText("zidian")
            shared.resolve("FUTOKeyboard_ChineseDictionary_Version").writeText("1")
            block(shared, cacheRoot.resolve("lexicon.bin"))
        } finally {
            shared.deleteRecursively()
            cacheRoot.deleteRecursively()
        }
    }

    private fun assertWorks(lex: SentenceLexicon?) {
        assertEquals(listOf("請到", "青島"), words(lex!!, "qing dao"))
    }

    @Test
    fun truncatedCacheIsRebuilt() = withDictionary { shared, cache ->
        assertWorks(SentenceLexicon.loadOrBuild(cache, shared))
        val bytes = cache.readBytes()
        cache.writeBytes(bytes.copyOf(bytes.size / 2))
        assertWorks(SentenceLexicon.loadOrBuild(cache, shared))
    }

    @Test
    fun corruptedCacheNumbersAreRebuilt() = withDictionary { shared, cache ->
        assertWorks(SentenceLexicon.loadOrBuild(cache, shared))
        val bytes = cache.readBytes()
        // Find the first key and make it -1.
        val stream = java.io.ByteArrayInputStream(bytes)
        val data = DataInputStream(stream)
        data.readInt(); data.readInt(); data.readUTF(); data.readFloat()
        repeat(data.readInt()) { data.readUTF() }
        data.readInt()
        val at = bytes.size - stream.available()
        for (i in 0 until 8) bytes[at + i] = 0xFF.toByte()
        cache.writeBytes(bytes)
        assertWorks(SentenceLexicon.loadOrBuild(cache, shared))
        // A huge key count (it sits just before the first key).
        val garbled = cache.readBytes()
        val countAt = at - 4
        val s2 = java.io.ByteArrayInputStream(garbled)
        val d2 = DataInputStream(s2)
        d2.readInt(); d2.readInt(); d2.readUTF(); d2.readFloat()
        repeat(d2.readInt()) { d2.readUTF() }
        assertEquals(countAt, garbled.size - s2.available())
        garbled[countAt] = 0x00; garbled[countAt + 1] = 0x7F
        garbled[countAt + 2] = 0xFF.toByte(); garbled[countAt + 3] = 0xFF.toByte()
        cache.writeBytes(garbled)
        assertWorks(SentenceLexicon.loadOrBuild(cache, shared))
    }

    @Test
    fun readRejectsHugeCountWithShortStream() {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use {
            it.writeInt(0x5A534C58)
            it.writeInt(2)
            it.writeUTF("t")
            it.writeFloat(0f)
            it.writeInt(0)
            it.writeInt(9_000_000)
        }
        val bytes = out.toByteArray()
        try {
            SentenceLexicon.read(ByteArrayInputStream(bytes), "t", bytes.size.toLong())
            fail("expected IOException")
        } catch (e: java.io.IOException) {
        }
    }

    @Test
    fun readRejectsNegativeCountsWithIOException() {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use {
            it.writeInt(0x5A534C58)
            it.writeInt(2)
            it.writeUTF("t")
            it.writeFloat(0f)
            it.writeInt(-1)
        }
        try {
            SentenceLexicon.read(ByteArrayInputStream(out.toByteArray()), "t")
            throw AssertionError("expected IOException")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test
    fun duplicateDictionaryLinesCountOnce() {
        val once = lexicon().lookup(listOf("qing", "dao"))
        val twice = lexicon(dict + "\n青島\tqing dao\n青島\tqing dao").lookup(listOf("qing", "dao"))
        assertEquals(once, twice)
    }

    @Test
    fun repeatedPersonalWordsCountOnce() {
        val entry = ChineseCustomDict.Entry("宜蓁", "yi zhen", 1)
        val d = "---\n...\n以\tyi\n宜\tyi\n真\tzhen\n蓁\tzhen"
        assertEquals(
            lexicon(d, "以真\t1000", listOf(entry)).lookup(listOf("yi", "zhen")),
            lexicon(d, "以真\t1000", listOf(entry, entry)).lookup(listOf("yi", "zhen"))
        )
        // Any whitespace separates the syllables.
        val tabbed = ChineseCustomDict.Entry("宜蓁", "yi \t zhen", 1)
        assertEquals(listOf("宜蓁", "以真"), words(lexicon(d, "以真\t1000", listOf(tabbed)), "yi zhen"))
    }

    @Test
    fun unwritableCacheStillReturnsTheLexicon() = withDictionary { shared, cache ->
        val blocker = cache.parentFile!!.resolve("blocker").also { it.writeText("x") }
        assertWorks(SentenceLexicon.loadOrBuild(blocker.resolve("sub/lexicon.bin"), shared))
    }

    private val yiZhen = "---\n...\n以\tyi\n宜\tyi\n真\tzhen\n蓁\tzhen"

    @Test
    fun personalWordsScoreAboveEveryDictionaryWord() {
        val base = lexicon(yiZhen, "以真\t1000")
        val lex = base.withPersonal(listOf(ChineseCustomDict.Entry("宜蓁", "yi zhen", 1)))
        val entries = lex.lookup(listOf("yi", "zhen"))
        assertEquals(ln(1001.5 / 1000).toFloat(), base.topScore, 1e-6f)
        assertEquals(base.topScore, entries[0].score, 0f)
        assertTrue(entries[0].score > entries[1].score)
    }

    @Test
    fun personalWordsKeepTheirListOrder() {
        val lex = lexicon(yiZhen, "以真\t1000", listOf(
            ChineseCustomDict.Entry("蓁蓁", "zhen zhen", 1),
            ChineseCustomDict.Entry("真真", "zhen zhen", 1),
        ))
        assertEquals(listOf("蓁蓁", "真真"), words(lex, "zhen zhen"))
    }

    @Test
    fun personalWordsForReadingsTheBaseNeverSaw() {
        val lex = lexicon(personal = listOf(
            ChineseCustomDict.Entry("曦", "xi", 1),
            ChineseCustomDict.Entry("請曦", "qing xi", 1),
        ))
        assertEquals(listOf("曦"), words(lex, "xi"))
        assertEquals(listOf("請曦"), words(lex, "qing xi"))
        assertEquals(emptyList<String>(), words(lexicon(), "xi"))
    }

    @Test
    fun personalWordAlreadyInTheBaseIsListedOnce() {
        val lex = lexicon(personal = listOf(ChineseCustomDict.Entry("青島", "qing dao", 1)))
        assertEquals(listOf("青島", "請到"), words(lex, "qing dao"))
    }

    @Test
    fun personalWordsInTaiwanFormsWithMatchingLength() {
        val lex = lexicon(personal = listOf(
            ChineseCustomDict.Entry("爲到", "wei dao", 1),
            // One character for two syllables, and a blank code: skipped.
            ChineseCustomDict.Entry("請", "qing dao", 1),
            ChineseCustomDict.Entry("青", " ", 1),
        ))
        assertEquals(listOf("為到"), words(lex, "wei dao"))
        assertEquals(listOf("請到", "青島"), words(lex, "qing dao"))
    }

    @Test
    fun atMostSixteenWordsWithPersonalWords() {
        val chars = (0 until 20).map { String(Character.toChars(0x4E00 + it)) }
        val base = lexicon("---\n...\n" + chars.joinToString("\n") { "$it\ta" },
            chars.withIndex().joinToString("\n") { "${it.value}\t${it.index + 1}" })
        val personal = (0 until 3).map { ChineseCustomDict.Entry(String(Character.toChars(0x4E80 + it)), "a", 1) }
        val list = words(base.withPersonal(personal), "a")
        assertEquals(16, list.size)
        assertEquals(personal.map { it.text } + chars.reversed().take(13), list)
        val many = (0 until 20).map { ChineseCustomDict.Entry(String(Character.toChars(0x4F00 + it)), "a", 1) }
        assertEquals(many.take(16).map { it.text }, words(base.withPersonal(many), "a"))
    }

    @Test
    fun noPersonalWordsBehavesLikeTheBase() {
        val base = SentenceLexicon.build(StringReader(dict), StringReader(essay))
        val lex = base.withPersonal(emptyList())
        for (r in listOf("qing dao", "xing ren", "cu suo", "qing", "ren dao", "zzz")) {
            assertEquals(base.lookup(r.split(' ')), lex.lookup(r.split(' ')))
        }
        assertEquals(base.keyCount, lex.keyCount)
        assertEquals(base.entryCount, lex.entryCount)
        assertEquals(base.topScore, lex.topScore, 0f)
    }

    @Test
    fun withPersonalReplacesAnEarlierOverlay() {
        val first = lexicon(yiZhen, "以真\t1000", listOf(ChineseCustomDict.Entry("宜蓁", "yi zhen", 1)))
        assertEquals(listOf("以真"), words(first.withPersonal(emptyList()), "yi zhen"))
    }

    @Test
    fun topScoreRoundTrips() {
        val lex = lexicon()
        val out = ByteArrayOutputStream()
        lex.write(out, "t")
        val back = SentenceLexicon.read(ByteArrayInputStream(out.toByteArray()), "t")!!
        assertEquals(ln((500.5 + 1) / total).toFloat(), lex.topScore, 1e-6f)
        assertEquals(lex.topScore, back.topScore, 0f)
    }

    @Test
    fun cacheIsNotRebuiltForPersonalWords() = withDictionary { shared, cache ->
        val first = SentenceLexicon.loadOrBuild(cache, shared)!!
        assertEquals(listOf("請到", "青島"), words(first.withPersonal(listOf(ChineseCustomDict.Entry("輕島", "qing dao", 1))), "qing dao").drop(1))
        cache.setLastModified(1_000_000L)
        val stamp = cache.lastModified()
        val again = SentenceLexicon.loadOrBuild(cache, shared)!!
        assertEquals(stamp, cache.lastModified())
        assertEquals(listOf("晴島", "請到", "青島"),
            words(again.withPersonal(listOf(ChineseCustomDict.Entry("晴島", "qing dao", 1))), "qing dao"))
    }
}

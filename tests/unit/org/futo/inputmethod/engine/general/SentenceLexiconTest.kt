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

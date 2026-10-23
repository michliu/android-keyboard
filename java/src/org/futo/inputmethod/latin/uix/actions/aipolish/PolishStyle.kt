package org.futo.inputmethod.latin.uix.actions.aipolish

/** What a piece of text is written for; [key] is the Clef choice key, [instruction] is appended to the prompt. */
enum class Scene(val key: String, val instruction: String) {
    Chat("chat", "風格：即時通訊聊天，口語自然、簡短。"),
    Email("email", "風格：工作 Email，禮貌清楚、適合商務往來。"),
    Formal("formal", "風格：正式書面用語。"),
    Social("social", "風格：社群貼文，生動自然，保留表情符號與 hashtag。");

    companion object {
        fun fromKey(key: String?): Scene? = values().firstOrNull { it.key == key }
    }
}

const val SOFTEN_INSTRUCTION = "請把語氣改得平和有禮，但保留原本的訴求。"
const val MIN_SCENE_CONFIDENCE = 0.5
const val MIN_HARSH_TONE = 1.5

/** Clef's verdict: the likeliest scene (null if unknown), how sure it was, and the tone score from 0 (calm) to 3 (harsh). */
data class ClefResult(val scene: Scene?, val sceneConfidence: Double, val tone: Double)

data class StyleChoice(val scene: Scene?, val soften: Boolean) {
    companion object {
        val None = StyleChoice(null, false)

        fun from(result: ClefResult) = StyleChoice(
            scene = result.scene.takeIf { result.sceneConfidence >= MIN_SCENE_CONFIDENCE },
            soften = result.tone >= MIN_HARSH_TONE,
        )
    }
}

/** The system prompt actually sent: the user's prompt followed by one line per chosen style. */
fun composePrompt(base: String, style: StyleChoice): String =
    listOfNotNull(base, style.scene?.instruction, SOFTEN_INSTRUCTION.takeIf { style.soften })
        .joinToString("\n")

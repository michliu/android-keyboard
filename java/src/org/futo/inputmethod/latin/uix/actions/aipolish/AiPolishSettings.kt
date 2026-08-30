package org.futo.inputmethod.latin.uix.actions.aipolish

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.datastore.preferences.core.stringPreferencesKey
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.settings.SettingTextField
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu

object AiPolishSettings {
    const val DEFAULT_PROMPT =
        "你是文字潤飾助手。請在不改變原意的前提下，修正錯字與語法、讓語句更通順自然，保留原本的語言與語氣，只輸出潤飾後的文字，不要加任何說明。"

    val BaseUrl = SettingsKey(stringPreferencesKey("ai_polish_base_url"), "https://api.openai.com/v1")
    val ApiKey = SettingsKey(stringPreferencesKey("ai_polish_api_key"), "")
    val Model = SettingsKey(stringPreferencesKey("ai_polish_model"), "gpt-4o-mini")
    val Prompt = SettingsKey(stringPreferencesKey("ai_polish_prompt"), DEFAULT_PROMPT)

    fun config(context: Context) = PolishConfig(
        baseUrl = context.getSetting(BaseUrl),
        apiKey = context.getSetting(ApiKey),
        model = context.getSetting(Model),
    )

    private fun textSetting(title: Int, key: SettingsKey<String>) = UserSetting(name = title) {
        SettingTextField(stringResource(title), key.default, key)
    }

    val menu = UserSettingsMenu(
        title = R.string.action_ai_polish_settings_title,
        navPath = "actions/ai_polish",
        registerNavPath = true,
        settings = listOf(
            textSetting(R.string.action_ai_polish_setting_base_url, BaseUrl),
            textSetting(R.string.action_ai_polish_setting_api_key, ApiKey),
            textSetting(R.string.action_ai_polish_setting_model, Model),
            textSetting(R.string.action_ai_polish_setting_prompt, Prompt),
        )
    )
}

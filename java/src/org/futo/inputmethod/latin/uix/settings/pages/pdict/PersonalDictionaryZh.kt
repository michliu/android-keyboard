package org.futo.inputmethod.latin.uix.settings.pages.pdict

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.futo.inputmethod.engine.GlobalIMEMessage
import org.futo.inputmethod.engine.IMEMessage
import org.futo.inputmethod.engine.general.ZhuyinSyllables
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.LocalNavController
import org.futo.inputmethod.latin.uix.PersonalWord
import org.futo.inputmethod.latin.uix.SettingsTextEdit
import org.futo.inputmethod.latin.uix.UserDictionaryIO
import java.util.Locale

private const val ZHUYIN_SYMBOLS = "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦ"
private const val ZHUYIN_TONES = "ˉˊˇˋ˙"

/** Frequency stored for words added here; UserDictionary accepts 1..255. */
private const val DEFAULT_FREQUENCY = 250

/**
 * Add/edit dialog for Chinese personal words. The word is typed normally; the Zhuyin reading
 * is entered with the built-in symbol keypad so it does not depend on which keyboard is
 * active (and is not turned into Hanzi by the IME). Stored as PersonalWord(word = Hanzi,
 * shortcut = Zhuyin); ChineseIME turns the reading into a Rime dictionary entry.
 */
@Composable
fun ZhuyinWordPopupDialog(selectedWord: PersonalWord? = null, locale: Locale? = null) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val word = remember { mutableStateOf(selectedWord?.word ?: "") }
    val zhuyin = remember { mutableStateOf(selectedWord?.shortcut ?: "") }

    val readingValid = ZhuyinSyllables.toLunaPinyin(zhuyin.value) != null
    val isError = zhuyin.value.isNotEmpty() && !readingValid

    fun finish() {
        GlobalIMEMessage.tryEmit(IMEMessage.ReloadPersonalDict)
        navController!!.navigateUp()
    }

    AlertDialog(
        icon = { },
        title = {
            Text(
                if (selectedWord == null) {
                    stringResource(R.string.user_dict_settings_add_dialog_title)
                } else {
                    stringResource(R.string.user_dict_settings_edit_dialog_title)
                }
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(stringResource(R.string.user_dict_settings_add_word_option_name))
                SettingsTextEdit(word, placeholder = stringResource(R.string.user_dict_settings_add_word_hint))

                Spacer(Modifier.height(12.dp))

                Text("注音：")
                Text(
                    text = "只在注音鍵盤生效，拼音與筆畫鍵盤不會使用這些字詞。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = zhuyin.value.ifEmpty { " " },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(8.dp)
                )
                if (isError) {
                    Text(
                        text = "注音不完整或有誤",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(Modifier.height(8.dp))

                ZhuyinKeypad(
                    onSymbol = { zhuyin.value += it },
                    onBackspace = { zhuyin.value = zhuyin.value.dropLast(1) }
                )
            }
        },
        onDismissRequest = {
            navController!!.navigateUp()
        },
        confirmButton = {
            Row {
                if (selectedWord != null) {
                    TextButton(
                        onClick = {
                            UserDictionaryIO(context).remove(listOf(selectedWord))
                            finish()
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(stringResource(R.string.user_dict_settings_delete))
                    }
                }
                Spacer(Modifier.weight(1.0f))

                TextButton(onClick = { navController!!.navigateUp() }) {
                    Text(stringResource(R.string.action_emoji_clear_recent_emojis_cancel))
                }

                TextButton(
                    onClick = {
                        val udictIo = UserDictionaryIO(context)
                        if (selectedWord != null) {
                            // Edit existing word by deleting it then re-inserting the new one
                            udictIo.remove(listOf(selectedWord))
                        }
                        udictIo.put(
                            listOf(
                                PersonalWord(
                                    word = word.value.trim(),
                                    frequency = DEFAULT_FREQUENCY,
                                    locale = locale?.toString(),
                                    appId = 0,
                                    shortcut = zhuyin.value
                                )
                            ),
                            clear = false
                        )
                        finish()
                    },
                    enabled = word.value.isNotBlank() && zhuyin.value.isNotEmpty() && readingValid
                ) {
                    Text(stringResource(R.string.user_dict_settings_add_dialog_confirm))
                }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZhuyinKeypad(onSymbol: (Char) -> Unit, onBackspace: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ZHUYIN_SYMBOLS.forEach { symbol ->
                ZhuyinKey(symbol.toString()) { onSymbol(symbol) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ZHUYIN_TONES.forEach { tone ->
                ZhuyinKey(tone.toString()) { onSymbol(tone) }
            }
            Spacer(Modifier.weight(1.0f))
            ZhuyinKey("⌫", onClick = onBackspace)
        }
    }
}

@Composable
private fun ZhuyinKey(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.titleMedium
        )
    }
}

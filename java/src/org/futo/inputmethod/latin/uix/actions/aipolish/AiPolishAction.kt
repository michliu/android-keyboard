package org.futo.inputmethod.latin.uix.actions.aipolish

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.ActionTextEditor
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.CloseResult
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.LocalKeyboardScheme
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.theme.Typography

private sealed class PolishState {
    object Idle : PolishState()
    object Loading : PolishState()
    data class Success(val text: String) : PolishState()
    data class Error(val message: String) : PolishState()
}

private enum class EditorSlot { Prompt, Text, None }

private class AiPolishWindow(
    private val manager: KeyboardManagerForAction,
    private val client: AiPolishClient,
) : ActionWindow() {
    private val context: Context = manager.getContext()
    private val source = pickSource(manager.getSelectedText(), manager.getAllText())

    val prompt: MutableState<String> = mutableStateOf(context.getSetting(AiPolishSettings.Prompt))
    val text: MutableState<String> = mutableStateOf(source.text)
    val state: MutableState<PolishState> = mutableStateOf(PolishState.Idle)

    // LatinIME only has a single input connection override slot, so at most one
    // ActionTextEditor may be mounted at a time. This slot decides which one currently
    // hosts it; None while switching so the outgoing editor's unsetInputConnection()
    // cannot clobber the incoming editor's override.
    val editorSlot: MutableState<EditorSlot> = mutableStateOf(EditorSlot.Text)
    private var switchJob: Job? = null

    private var job: Job? = null

    /** Unmount the current editor first; mount the target one on a later frame so the old
     *  editor's unsetInputConnection() cannot clobber the new editor's override. */
    private fun switchEditor(target: EditorSlot) {
        if (editorSlot.value == target) return
        switchJob?.cancel()
        editorSlot.value = EditorSlot.None
        switchJob = manager.getLifecycleScope().launch {
            // withFrameNanos requires a MonotonicFrameClock, which the lifecycle scope's
            // dispatcher does not provide, so use a short delay to let the outgoing
            // editor's disposal effects run on a later frame instead.
            delay(32)
            editorSlot.value = target
        }
    }

    @Composable
    override fun windowName(): String = stringResource(R.string.action_ai_polish_title)

    private fun run() {
        job?.cancel()
        state.value = PolishState.Loading
        job = manager.getLifecycleScope().launch {
            val result = client.polish(AiPolishSettings.config(context), prompt.value, text.value)
            state.value = result.fold(
                onSuccess = { PolishState.Success(it) },
                onFailure = { PolishState.Error(it.message ?: it.toString()) },
            )
        }
    }

    private fun resultOrNull(): String? = (state.value as? PolishState.Success)?.text

    private fun replace() {
        val result = resultOrNull() ?: return
        when (source.kind) {
            SourceKind.WholeField -> manager.replaceAllText(result)
            SourceKind.Selection, SourceKind.None -> manager.typeText(result)
        }
        manager.closeActionWindow()
    }

    private fun insert() {
        val result = resultOrNull() ?: return
        manager.typeText(result)
        manager.closeActionWindow()
    }

    private fun copy() {
        val result = resultOrNull() ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("AI Polish", result))
        Toast.makeText(context, R.string.action_ai_polish_copied, Toast.LENGTH_SHORT).show()
    }

    override fun close(): CloseResult {
        job?.cancel()
        switchJob?.cancel()
        return CloseResult.Default
    }

    @Composable
    private fun Label(@StringRes textRes: Int) {
        Text(stringResource(textRes), style = Typography.Small, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
    }

    @Composable
    private fun RowScope.PolishButton(@StringRes label: Int, enabled: Boolean, onClick: () -> Unit) {
        Button(onClick = onClick, enabled = enabled, modifier = Modifier.weight(1f)) {
            Text(stringResource(label))
        }
    }

    @Composable
    private fun Bordered(content: @Composable () -> Unit) {
        Surface(
            border = BorderStroke(1.dp, LocalKeyboardScheme.current.outline),
            shape = RoundedCornerShape(8.dp),
            color = Color.Transparent,
            modifier = Modifier.fillMaxWidth()
        ) { content() }
    }

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        val scheme = LocalKeyboardScheme.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label(R.string.action_ai_polish_prompt_label)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    switchEditor(if (editorSlot.value == EditorSlot.Prompt) EditorSlot.Text else EditorSlot.Prompt)
                }) {
                    Text(
                        stringResource(
                            if (editorSlot.value == EditorSlot.Prompt) R.string.action_ai_polish_done
                            else R.string.action_ai_polish_edit_prompt
                        )
                    )
                }
                TextButton(onClick = { prompt.value = context.getSetting(AiPolishSettings.Prompt) }) {
                    Text(stringResource(R.string.action_ai_polish_reset))
                }
            }
            Bordered {
                if (editorSlot.value == EditorSlot.Prompt) {
                    ActionTextEditor(
                        text = prompt,
                        multiline = true,
                        autofocus = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                    )
                } else {
                    Text(
                        prompt.value,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { switchEditor(EditorSlot.Prompt) }
                            .padding(4.dp)
                    )
                }
            }

            Label(R.string.action_ai_polish_source_label)
            Bordered {
                if (editorSlot.value == EditorSlot.Text) {
                    ActionTextEditor(
                        text = text,
                        multiline = true,
                        autofocus = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp),
                    )
                } else {
                    Text(
                        text.value,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { switchEditor(EditorSlot.Text) }
                            .padding(4.dp)
                    )
                }
            }

            Label(R.string.action_ai_polish_result_label)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .padding(4.dp),
                contentAlignment = Alignment.TopStart
            ) {
                when (val s = state.value) {
                    PolishState.Idle -> Text(
                        stringResource(R.string.action_ai_polish_result_placeholder),
                        color = scheme.onSurfaceVariant
                    )
                    PolishState.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    is PolishState.Success -> Text(
                        s.text,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    )
                    is PolishState.Error -> Text(
                        s.message,
                        color = scheme.error,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    )
                }
            }

            val hasResult = state.value is PolishState.Success
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                PolishButton(
                    label = R.string.action_ai_polish_run,
                    enabled = text.value.isNotBlank() && state.value !is PolishState.Loading,
                    onClick = { run() },
                )
                PolishButton(R.string.action_ai_polish_replace, hasResult) { replace() }
                PolishButton(R.string.action_ai_polish_insert, hasResult) { insert() }
                PolishButton(R.string.action_ai_polish_copy, hasResult) { copy() }
            }
        }
    }
}

val AiPolishAction = Action(
    icon = R.drawable.edit_text,
    name = R.string.action_ai_polish_title,
    canShowKeyboard = true,
    simplePressImpl = null,
    windowImpl = { manager, _ -> AiPolishWindow(manager, AiPolishClient(HttpUrlPolishTransport())) },
    settingsMenu = AiPolishSettings.menu,
)

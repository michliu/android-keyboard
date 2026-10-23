package org.futo.inputmethod.engine.general

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import icu.astronot233.rime.DeployStage
import icu.astronot233.rime.Rime
import icu.astronot233.rime.RimeCandidate
import icu.astronot233.rime.RimeMessage
import icu.astronot233.rime.RimeSchema
import icu.astronot233.rime.SyncStage
import icu.astronot233.rime.X11Keys.XK_BackSpace
import icu.astronot233.rime.X11Keys.XK_Linefeed
import icu.astronot233.rime.X11Keys.XK_Return
import icu.astronot233.rime.X11Keys.XK_Tab
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import org.futo.inputmethod.engine.ExpandableSuggestionBarConfiguration
import org.futo.inputmethod.engine.GlobalIMEMessage
import org.futo.inputmethod.engine.IMEHelper
import org.futo.inputmethod.engine.IMEInterface
import org.futo.inputmethod.engine.IMEMessage
import org.futo.inputmethod.event.Event
import org.futo.inputmethod.keyboard.internal.KeyboardLayoutKind
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.Subtypes.switchToNextLanguage
import org.futo.inputmethod.latin.SuggestedWords
import org.futo.inputmethod.latin.SuggestedWords.SuggestedWordInfo
import org.futo.inputmethod.latin.SuggestionBlacklist
import org.futo.inputmethod.latin.common.Constants
import org.futo.inputmethod.latin.common.InputPointers
import org.futo.inputmethod.latin.settings.Settings
import org.futo.inputmethod.latin.suggestions.SuggestionStripViewAccessor
import org.futo.inputmethod.latin.uix.FileKind
import org.futo.inputmethod.latin.uix.FloatingPreEdit
import org.futo.inputmethod.latin.uix.PreEditListener
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.UserDictionaryIO
import org.futo.inputmethod.latin.uix.actions.throwIfDebug
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.namePreferenceKeyFor
import org.futo.inputmethod.latin.uix.preferenceKeyFor
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.uix.settings.CollapsibleSection
import org.futo.inputmethod.latin.uix.settings.DropDownPickerSettingItem
import org.futo.inputmethod.latin.uix.settings.SettingToggleDataStore
import org.futo.inputmethod.latin.uix.settings.SettingToggleRaw
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.useDataStore
import org.futo.inputmethod.latin.utils.InputTypeUtils
import org.futo.inputmethod.latin.utils.ZipFileHelper
import org.futo.inputmethod.latin.utils.toEnumOrNull
import org.futo.inputmethod.v2keyboard.KeyboardLayoutSetV2
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

object ChineseIMESettings {
    enum class ChineseSimplificationMode(val stringResource: Int) {
        // These must not be renamed, or the existing setting value for users will break
        ByLanguage(R.string.chinese_setting_simplification_simplify_by_language_country),
        Simplified(R.string.chinese_setting_simplification_force_simplified),
        Traditional(R.string.chinese_setting_simplification_force_traditional);
    }

    enum class PinyinScheme(
        val displayNameZH: String,
        val displayNameEN: String,
        val rimeId: String
    ) {
        // These must not be renamed, or the existing setting value for users will break
        FullPinyin("全拼", "Full Pinyin", "luna_pinyin"),
        Natural("自然碼雙拼", "Double Pinyin", "double_pinyin"),
        ABC("智能ABC雙拼", "Double Pinyin - ABC", "double_pinyin_abc"),
        FlyPY("小鶴雙拼", "Double Pinyin - flyPY", "double_pinyin_flypy"),
        MS("微軟雙拼", "Double Pinyin - MS", "double_pinyin_mspy"),
        PYJJ("拼音加加雙拼", "Double Pinyin - PYJJ", "double_pinyin_pyjj"),
        ST("四通雙拼", "Double Pinyin - ST", "double_pinyin_st");
    }

    internal fun initialPatch(pattern: String, substitution: String, twoWay: Boolean)
            = buildList {
        add("derive/^${pattern}/${substitution}/")
        if(twoWay) add("derive/^${substitution}/${pattern}/")
    }

    internal fun finalPatch(pattern: String, substitution: String, twoWay: Boolean)
            = buildList {
        add("derive/${pattern}$/${substitution}/")
        if(twoWay) add("derive/${substitution}$/${pattern}/")
    }

    enum class FuzzyPinyinModes(val schemePatch: List<String>, val displayName: String) {
        // These must not be renamed, or the existing setting value for users will break
        Z_ZH(initialPatch("z", "zh", true), "z = zh"),
        C_CH(initialPatch("c", "ch", true), "c = ch"),
        S_SH(initialPatch("s", "sh", true), "s = sh"),
        L_N(initialPatch("l", "n", true), "l = n"),
        F_H(initialPatch("f", "h", true), "f = h"),
        R_L(initialPatch("r", "l", false), "r = l"),
        K_G(initialPatch("k", "g", false), "k = g"),

        AN_ANG(finalPatch("an", "ang", true), "an = ang"),
        EN_ENG(finalPatch("en", "eng", true), "en = eng"),
        IN_ING(finalPatch("in", "ing", true), "in = ing"),
        IAN_IANG(finalPatch("ian", "iang", true), "ian = iang"),
        UAN_UANG(finalPatch("uan", "uang", true), "uan = uang");

        companion object {
            @JvmStatic
            fun fromCommaString(str: String) =
                str.split(',').mapNotNull { it.toEnumOrNull<FuzzyPinyinModes>() }.toSet()

            @JvmStatic
            fun toCommaString(value: Set<FuzzyPinyinModes>) =
                value.joinToString(",") { it.name }
        }
    }

    val SimplificationSetting = SettingsKey(
        stringPreferencesKey("ChineseIME_simplification"),
        ChineseSimplificationMode.ByLanguage.name
    )

    val PinyinSchemeSetting = SettingsKey(
        stringPreferencesKey("ChineseIME_pinyin_scheme"),
        PinyinScheme.FullPinyin.name
    )

    val FuzzyPinyinSetting = SettingsKey(
        stringPreferencesKey("ChineseIME_fuzzy_pinyin"),
        ""
    )

    val AssociationSetting = SettingsKey(
        booleanPreferencesKey("ChineseIME_association"),
        true
    )

    val RerankSetting = SettingsKey(
        booleanPreferencesKey("ChineseIME_rerank"),
        true
    )

    /**
     * Rime's typo correction for Zhuyin. Off by default: it also turns correctly typed input into
     * other words (offline, whole 4–12 character phrases: 46% exact without it, 32% with it).
     */
    val ZhuyinCorrectionSetting = SettingsKey(
        booleanPreferencesKey("ChineseIME_zhuyin_correction"),
        false
    )

    var schemaList: List<RimeSchema> = emptyList()
    val menu = UserSettingsMenu(
        title = R.string.chinese_settings_title,
        searchTags = R.string.chinese_setting_search_tags,
        navPath = "ime/zh", registerNavPath = true,
        settings = listOf(
            UserSetting(R.string.chinese_setting_simplification) {
                val resources = LocalResources.current
                val (setting, setSetting) = useDataStore(SimplificationSetting)
                DropDownPickerSettingItem<ChineseSimplificationMode>(
                    stringResource(R.string.chinese_setting_simplification),
                    ChineseSimplificationMode.entries,
                    setting.toEnumOrNull<ChineseSimplificationMode>() ?: ChineseSimplificationMode.ByLanguage,
                    { setSetting(it.name) },
                    { resources.getString(it.stringResource) }
                )
            },

            UserSetting(R.string.chinese_setting_pinyin_scheme) {
                val context = LocalContext.current
                val resources = LocalResources.current
                val (setting, setSetting) = useDataStore(PinyinSchemeSetting)

                val availableEntries = remember {
                    val sharedDir = ChineseIME.getShared(context)
                    val files = (sharedDir.listFiles() ?: emptyArray()).map { it.name.split('.').first() }.toSet()
                    PinyinScheme.entries.filter {
                        files.contains(it.rimeId) || it == PinyinScheme.FullPinyin
                    }
                }
                DropDownPickerSettingItem<PinyinScheme>(
                    stringResource(R.string.chinese_setting_pinyin_scheme),
                    availableEntries,
                    setting.toEnumOrNull<PinyinScheme>() ?: PinyinScheme.FullPinyin,
                    { setSetting(it.name) },
                    { when(resources.configuration.locale.language) {
                        "zh" -> it.displayNameZH
                        else -> it.displayNameEN
                    } }
                )
            },

            UserSetting(R.string.chinese_setting_fuzzy_pinyin) {
                val (setting, setSetting) = useDataStore(FuzzyPinyinSetting)
                val set = remember(setting) { FuzzyPinyinModes.fromCommaString(setting) }

                CollapsibleSection(stringResource(R.string.chinese_setting_fuzzy_pinyin)) {
                    FuzzyPinyinModes.entries.forEach { mode ->
                        SettingToggleRaw(mode.displayName, set.contains(mode), { enabled ->
                            val newSet = if (enabled) {
                                set + setOf(mode)
                            } else {
                                set - setOf(mode)
                            }

                            setSetting(FuzzyPinyinModes.toCommaString(newSet))
                        })
                    }
                }

            },

            UserSetting(R.string.chinese_setting_association, R.string.chinese_setting_association_subtitle) {
                SettingToggleDataStore(
                    stringResource(R.string.chinese_setting_association),
                    AssociationSetting,
                    subtitle = stringResource(R.string.chinese_setting_association_subtitle)
                )
            },

            UserSetting(R.string.chinese_setting_rerank, R.string.chinese_setting_rerank_subtitle) {
                SettingToggleDataStore(
                    stringResource(R.string.chinese_setting_rerank),
                    RerankSetting,
                    subtitle = stringResource(R.string.chinese_setting_rerank_subtitle)
                )
            },

            UserSetting(R.string.chinese_setting_zhuyin_correction, R.string.chinese_setting_zhuyin_correction_subtitle) {
                SettingToggleDataStore(
                    stringResource(R.string.chinese_setting_zhuyin_correction),
                    ZhuyinCorrectionSetting,
                    subtitle = stringResource(R.string.chinese_setting_zhuyin_correction_subtitle)
                )
            },

        )
    )
}

class InputKeeper() {
    var text = ""
        private set

    fun clear() {
        text = ""
    }

    fun append(codepoint: Int) {
        text += Char(codepoint)
    }

    fun backspace() {
        if(text.isNotEmpty()) text = text.take(text.length - 1)
    }

    fun replace(with: String) {
        text = with
    }
}

class ChineseIME(val helper: IMEHelper) : IMEInterface, SuggestionStripViewAccessor, PreEditListener {
    private val TAG = "ChineseIME (rime)"

    private val rime: Rime
    private val connect get() = helper.getCurrentInputConnection()
    private val coroScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val expandableUiCfg = ExpandableSuggestionBarConfiguration(true, false)
    private val maxBufferLength = 0x100000
    private var layoutHint: String? = null
    private val rawInput = InputKeeper()
    private val rimeLoading = mutableStateOf(false)

    /** Associated phrases (聯想詞); null until loaded, or when the dictionary has no essay.txt. */
    private var association: ChineseAssociation? = null
    private var associationJob: Job? = null
    /** Text the shown associations continue; null when no associations are shown. */
    private var associationContext: String? = null
    private var associationWords: List<String> = emptyList()
    private var associationCommitted: String = ""

    /** Context reranker (依前文排序); null until loaded, or after it failed to load. */
    private var scorer: CandidateScorer? = null
    private var scorerJob: Job? = null
    private var scorerFailed = false

    /** Single-character readings for the reranker; null until loaded, or without luna_pinyin.dict.yaml. */
    private var charReadings: ChineseCharReadings? = null
    private var charReadingsJob: Job? = null
    private val rerankDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var rerankJob: Job? = null
    /** Display position → Rime index of the candidates shown; null while they are in Rime's order. */
    private var candidateOrder: IntArray? = null

    override fun getLoadingState(): MutableState<Boolean>? = rimeLoading

    private val isBopomofo get() = layoutHint == "bopomofo"

    /** True while the Zhuyin layout's embedded English page (sub-keyboard Alphabet1) is shown. */
    private val isOnEnglishPage get() =
        isBopomofo && helper.keyboardSwitcher.keyboard?.mId?.mElement?.kind == KeyboardLayoutKind.Alphabet1

    private val currentTransformation get() = when(layoutHint) {
        "stroke" -> StrokeTransformation
        "bopomofo" -> ZhuyinKeymap.toZhuyin
        else -> emptyMap()
    }

    private enum class EditingState(val active: Boolean) {
        NotEditing(false),
        Editing(true),
        Finishing(false)
    }
    private var editingState = EditingState.NotEditing

    companion object {
        @JvmStatic
        private val StrokeTransformation = mapOf(
            'h' to '⼀',
            's' to '⼁',
            'p' to '⼃',
            'n' to '⼂',
            'z' to '⼄',
        )

        /** How many times a failed schema selection is retried before giving up. */
        private const val MaxSchemaSelectRetries = 3
        private const val SchemaSelectRetryDelayMs = 2000L

        /** A rerank slower than this is dropped: the strip would change after the user moved on. */
        private const val RerankTimeoutMs = 300L
        private const val RerankContextChars = 64

        @JvmStatic
        fun getRimeDir(context: Context) = context.getExternalFilesDir("rime")
                ?: throw IllegalStateException("Failed to access ExternalFilesDir!")

        @JvmStatic
        fun getShared(context: Context) = File(getRimeDir(context), "shared")

        @JvmStatic
        fun getUser(context: Context) = File(getRimeDir(context), "user")

        val PreviouslyExtractedDictionaryName = SettingsKey(
            stringPreferencesKey("ChineseDictionaryExtractedValue"),
            ""
        )
        var localPrevExtractedDictionaryName: String? = null

        fun resetSharedFromResources(context: Context, scope: CoroutineScope): Boolean {
            val pref = FileKind.Dictionary.preferenceKeyFor("zh")
            val namePref = FileKind.Dictionary.namePreferenceKeyFor("zh")
            val filePath = context.getSetting(pref, "")
            val file = File(context.applicationContext.getExternalFilesDir(null), filePath)

            val name = context.getSetting(namePref, "?")



            if((localPrevExtractedDictionaryName ?: context.getSetting(PreviouslyExtractedDictionaryName)) == name) {
                return false
            }

            try {
                val shared = getShared(context)
                shared.walkBottomUp()
                    .fold(true) { res, it -> (it == shared || it.delete() || !it.exists()) && res }

                // We are intentionally checking this only after deleting the shared directory
                // This is because if the user deletes the dictionary file, we want to apply
                // the deletion (TODO: It doesn't apply)
                if(filePath.isEmpty()) return true
                if(!file.exists()) return true

                ZipFileHelper.extract(file, shared)

                return true
            } finally {
                localPrevExtractedDictionaryName = name
                scope.launch(Dispatchers.IO) {
                    context.setSetting(PreviouslyExtractedDictionaryName.key, name)
                }
            }
        }
    }

    init {
        val shared = getShared(helper.context)
        val user = getUser(helper.context)

        if(!shared.exists() || !user.exists()) {
            shared.mkdirs()
            user.mkdirs()
        }

        rime = Rime(shared.path, user.path, helper.context.packageName)
    }

    /**
     * Text to commit for a key Rime did not consume. Under the Zhuyin layout the key was one of
     * the standard-keyboard ASCII keys, so show the Zhuyin symbol the user actually pressed
     * (space stays a space: it is only tone 1 while composing).
     */
    private fun passByText(x11Code: Int): String {
        if (isBopomofo && x11Code != ' '.code && x11Code in 0..Char.MAX_VALUE.code) {
            ZhuyinKeymap.toZhuyin[x11Code.toChar()]?.let { return it.toString() }
        }
        return String(Character.toChars(x11Code))
    }

    private fun handlePassByMessage(x11Code: Int, mask: Int) {
        // A passed-back key is user input; associations shown for an earlier commit no longer apply.
        endAssociation()
        when (x11Code) {
            XK_BackSpace -> sendDownUpKeyEvent(KeyEvent.KEYCODE_DEL, 0)
            XK_Tab       -> sendDownUpKeyEvent(KeyEvent.KEYCODE_TAB, 0)
            XK_Linefeed  -> sendDownUpKeyEvent(KeyEvent.KEYCODE_ENTER, 0)
            XK_Return    -> sendDownUpKeyEvent(KeyEvent.KEYCODE_ENTER, 0)
            else -> {
                val text = passByText(x11Code)
                if (rime.preeditFlow.value.isEmpty() || editingState.active) {
                    connect?.commitText(text, 1)
                } else {
                    coroScope.launch {
                        rime.commitComposition()
                        rime.processX11Code(x11Code, mask)
                    }
                }
            }
        }
    }

    private fun subscribeToRimeMessage() = rime.messageFlow.onEach { msg -> when (msg) {
        is RimeMessage.Deploy -> when (msg.value) {
            DeployStage.Unknown -> Log.e(TAG, "Deploy: Failed")
            DeployStage.Startup -> Log.i(TAG, "Deploy: Startup")
            DeployStage.Success -> {
                Log.i(TAG, "Deploy: Success")
                coroScope.launch {
                    ChineseIMESettings.schemaList = rime.getSchemata().also {
                        Log.d(TAG, "Schemas: ${it.joinToString { it.toString() }} ")
                    }
                }
            }
            else -> {}
        }
        is RimeMessage.Sync -> when (msg.value) {
            SyncStage.Unknown -> Log.e(TAG, "Sync: Failed")
//            SyncStage.Startup ->
//            SyncStage.Success ->
            else -> {}
        }
        is RimeMessage.Commit -> {
            connect?.commitText(msg.value, 1)
            showAssociations(msg.value, msg.value)
        }
        is RimeMessage.Passby -> {
            val (x11Code, mask) = msg.value
            handlePassByMessage(x11Code, mask)
        }
        is RimeMessage.Unknown -> {
            Log.e(TAG, "Unrecognized error occurred: ${msg.value}")
        }
    }}.launchIn(coroScope)

    private fun subscribeToRimePreedit() = rime.preeditFlow.onEach { ped ->
        val notEditing = editingState == EditingState.NotEditing
        if(ped.isEmpty() && notEditing) { rawInput.clear() }

        helper.updateUiInputState(ped.isEmpty() && notEditing)
        helper.setPreedit(FloatingPreEdit.build(ped, this, currentTransformation, rawInput.text))

        // Typing tone 1 (space) marks the syllable but can leave Rime's candidates unchanged, and
        // the candidates flow does not emit an equal list again: score them for this preedit here.
        if(ped.isNotEmpty() && notEditing) {
            val candidates = rime.candidatesFlow.value
            if(candidates.isNotEmpty()) maybeRerank(candidates)
        }
    }.launchIn(coroScope)

    override fun onStartEdit() {
        editingState = EditingState.Editing
        rerankJob?.cancel()
        candidateOrder = null
    }

    // For pinyin/stroke layouts the only valid characters for input simulation are ASCII letters;
    // in particular space would end the composition. The Zhuyin schema instead accepts every key
    // of its alphabet, including space (tone 1), so the filter depends on the layout hint.
    private fun safeguardInputStringForSimulation(input: String): String = when {
        isBopomofo -> input.filter { it in ZhuyinKeymap.alphabet }
        else -> input.filter { it in 'a'..'z' || it in 'A'..'Z' }
    }

    private var updateEditJob: Job? = null
    override fun onUpdateEdit(string: String) {
        updateEditJob?.cancel()
        updateEditJob = coroScope.launch {
            ensureActive()
            rime.clearComposition()

            val converted = safeguardInputStringForSimulation(string)
            ensureActive()
            rime.simulateKeySequence(converted)
            rawInput.replace(converted)
        }
    }

    private var waitingToSelect: Pair<String, Int>? = null
    override fun onFinishEdit(string: String, candidateWord: String?, candidateIndex: Int?) {
        if(!editingState.active) return
        editingState = EditingState.Finishing

        coroScope.launch {
            rime.clearComposition()

            if(candidateWord != null && candidateIndex != null) {
                // TODO: This is kind of a buggy way of doing it
                waitingToSelect = candidateWord to candidateIndex
            }

            val converted = safeguardInputStringForSimulation(string)
            rime.simulateKeySequence(converted)
            rawInput.replace(converted)

            editingState = EditingState.NotEditing

            if(string.isEmpty()) {
                showSuggestionStrip(
                    SuggestedWords(
                        ArrayList(emptyList()),
                        ArrayList(emptyList()),
                        null,
                        false,
                        false,
                        false,
                        0,
                        0,
                        0
                    )
                )
            }
        }
    }

    /** Suggestion-strip words for candidates or associations; [comments] may be shorter than [words]. */
    private fun candidateWords(words: List<String>, comments: List<String?> = emptyList()): SuggestedWords {
        val infos = words.mapIndexed { index, word -> SuggestedWordInfo(
            word, "", Int.MAX_VALUE - index, 1, null,
            SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE,
            index, comments.getOrNull(index)
        ) }.let { ArrayList(it) }
        return SuggestedWords(infos, infos, null, false, false, false, 0, 0, 0)
    }

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

    /**
     * Loads the associated-phrase index in the background, once per dictionary. Only the Zhuyin
     * layout uses it, so pinyin and stroke users do not pay for the index.
     */
    private fun ensureAssociationLoaded(reload: Boolean = false) {
        if(reload) {
            associationJob?.cancel()
            associationJob = null
            association = null
        }
        if(!isBopomofo || associationJob != null) return
        associationJob = coroScope.launch {
            val start = SystemClock.elapsedRealtime()
            association = withContext(Dispatchers.IO) {
                try {
                    ChineseAssociation.load(getShared(helper.context))
                } catch(e: CancellationException) {
                    throw e
                } catch(e: Exception) {
                    Log.e(TAG, "Failed to load associated phrases", e)
                    null
                } catch(e: OutOfMemoryError) {
                    Log.e(TAG, "Out of memory loading associated phrases", e)
                    null
                }
            }
            Log.i(TAG, "Associated phrases: ${association?.size ?: 0} keys in ${SystemClock.elapsedRealtime() - start} ms")
        }
    }

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

    /**
     * Shows the phrases that follow [context] in the suggestion strip, or ends the association if
     * there are none. [committed] is the text that was just committed (the end of [context]).
     */
    private fun showAssociations(context: String, committed: String) {
        val words = association?.lookup(context).orEmpty()
        val show = words.isNotEmpty()
                && isBopomofo && !isOnEnglishPage
                && !editingState.active
                && prevConfiguration?.simplification != true
                && helper.context.getSetting(ChineseIMESettings.AssociationSetting)
                // A key typed right after the commit (e.g. a digit, which bypasses Rime) has
                // already ended the association; do not bring it back for text that is no longer
                // in front of the cursor.
                && connect?.getTextBeforeCursor(committed.length, 0)?.toString() == committed
        if(!show) {
            endAssociation()
            return
        }

        associationContext = context
        associationWords = words
        associationCommitted = committed
        showSuggestionStrip(candidateWords(words))
    }

    /** Loads the context reranker in the background once, if the Zhuyin layout and setting want it. */
    private fun ensureScorerLoaded() {
        if(!isBopomofo || scorer != null || scorerJob != null || scorerFailed) return
        if(!helper.context.getSetting(ChineseIMESettings.RerankSetting)) return
        scorerJob = coroScope.launch {
            val start = SystemClock.elapsedRealtime()
            var loaded: CandidateScorer? = null
            var stored = false
            try {
                withContext(Dispatchers.IO) {
                    loaded = try {
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
                stored = true
            } finally {
                // Cancelled (e.g. destroyed) after the load: nobody else will close it.
                if(!stored) (loaded as? AutoCloseable)?.close()
            }
            Log.i(TAG, "Candidate reranker: ${if(loaded != null) "loaded" else "unavailable"} in ${SystemClock.elapsedRealtime() - start} ms")
        }
    }

    /** Texts of the personal-dictionary words; Rime ranks them first, so the reranker leaves them. */
    private var personalWords: Set<String> = emptySet()

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
        // Personal-dictionary words are meant to stay first; never move one off the first place.
        if(candidates.firstOrNull()?.text in personalWords) return
        val info = helper.getCurrentEditorInfo() ?: return
        if(InputTypeUtils.isPasswordInputType(info.inputType) ||
            InputTypeUtils.isVisiblePasswordInputType(info.inputType) ||
            (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) return

        val preedit = rime.preeditFlow.value
        val reading = ZhuyinReading.parse(preedit) ?: return
        if(reading.size > ZhuyinReranker.MAX_SYLLABLES) return
        val words = candidates.map { it.text }
        // For one syllable the model reads only the context, and Rime also offers characters
        // with other readings (e.g. 不 and 到 for ㄉㄜ˙): score only characters with the typed reading.
        val spelling = if(reading.size == 1) ZhuyinSyllables.toLunaPinyin(reading[0].base) else null
        val readings = charReadings
        val covers: (String) -> Boolean =
            if(spelling != null && readings != null) { word -> scorer.covers(word) && readings.has(word, spelling) }
            else scorer::covers
        val scorable = ZhuyinReranker.scorable(words, reading.size, covers)
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
            if(editingState.active || isOnEnglishPage || !isBopomofo) return@launch
            candidateOrder = order
            showSuggestionStrip(candidateWords(order.map { words[it] }, order.map { candidates[it].comment }))
        }
    }

    /** Rime index of the candidate shown at [displayIndex]. */
    private fun rimeIndexOf(displayIndex: Int): Int = candidateOrder?.getOrNull(displayIndex) ?: displayIndex

    private fun endAssociation() {
        if(associationContext == null) return
        associationContext = null
        associationWords = emptyList()
        associationCommitted = ""
        setNeutralSuggestionStrip()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, composingSpanStart: Int, composingSpanEnd: Int) {
        if(associationContext == null) return
        // The user moved the cursor, selected text, or the app changed the text (e.g. cleared the
        // field after sending): the shown associations no longer follow the text at the cursor.
        // Our own commits pass this check, since they leave the committed text before the cursor.
        if(newSelStart != newSelEnd ||
            connect?.getTextBeforeCursor(associationCommitted.length, 0)?.toString() != associationCommitted) {
            endAssociation()
        }
    }

    override fun onCreate() {
        if (!rime.startup(false))
            Log.e(TAG, "Error occurred!")
        subscribeToRimeMessage()
        subscribeToRimePreedit()
        subscribeToRimeCandidates()

        helper.lifecycleScope.launch {
            GlobalIMEMessage.collect { message ->
                when(message) {
                    IMEMessage.ReloadResources -> coroScope.launch {
                        // Same serialisation as clearUserHistoryDictionaries(): no config update
                        // may run while Rime is restarted.
                        configSemaphore.acquire()
                        try {
                            rime.shutdown()
                            rime.startup(false)
                            invalidateConfiguration()
                        } finally {
                            configSemaphore.release()
                        }
                        updateConfig()
                    }
                    IMEMessage.ReloadPersonalDict -> updateConfig()
                    else -> {}
                }
            }
        }

        blacklist.init()
    }
    override fun onDestroy() {
        helper.lifecycleScope.cancel()
        coroScope.cancel()
        rime.shutdown()
        // A native run may still be scoring on the dispatcher: close the session after it.
        val s = scorer
        scorer = null
        rerankDispatcher.executor.execute { (s as? AutoCloseable)?.close() }
        rerankDispatcher.close()
    }

    override fun onStartInput() {
        endAssociation()
        // Order matters: extracting the dictionary wipes the shared directory, so the bundled
        // schemas are (re)copied afterwards.
        val dictionaryChanged = resetSharedFromResources(helper.context, helper.lifecycleScope)
        val bundledChanged = BundledRimeAssets.ensure(
            helper.context, getShared(helper.context), getUser(helper.context)
        )
        if(dictionaryChanged || bundledChanged) {
            invalidateConfiguration()
            coroScope.launch { rime.deploy() }
        }
        updateConfig()
        ensureAssociationLoaded(reload = dictionaryChanged)
        ensureCharReadingsLoaded(reload = dictionaryChanged)
        ensureScorerLoaded()
    }

    private fun isSimplifiedChinese(locale: Locale): Boolean {
        if (locale.language != "zh") return false
        val setting = helper.context.getSetting(ChineseIMESettings.SimplificationSetting).toEnumOrNull<ChineseIMESettings.ChineseSimplificationMode>()
        return when(setting) {
            ChineseIMESettings.ChineseSimplificationMode.Simplified -> true
            ChineseIMESettings.ChineseSimplificationMode.Traditional -> false
            else -> {
                locale.script.takeIf { it.isNotEmpty() }?.let {
                    return it.equals("Hans", ignoreCase = true)
                }

                return when (locale.country.uppercase()) {
                    "TW", "HK", "MO" -> false
                    else -> true
                }
            }
        }
    }



    private data class Configuration(
        val schema: String,
        val learning: Boolean,
        val simplification: Boolean,
        val autocorrect: Boolean,
        val fuzzyMode: Set<ChineseIMESettings.FuzzyPinyinModes>,
        /**
         * hashCode of the rendered futo_custom.dict.yaml; a change redeploys so Rime recompiles
         * it. 0 for schemas that do not read the dictionary (only the Zhuyin schemas do), so a
         * personal-dictionary change does not redeploy those.
         */
        val customDictHash: Int
    )
    private var prevConfiguration: Configuration? = null

    /** The configuration the retry budget below applies to. */
    private var retryConfiguration: Configuration? = null
    private var schemaSelectRetries = 0

    /** Forget the applied configuration and the retry budget; the next updateConfig() starts over. */
    private fun invalidateConfiguration() {
        prevConfiguration = null
        retryConfiguration = null
        schemaSelectRetries = 0
    }

    private fun writeCustomizationFile(cfg: Configuration) {
        val schema = cfg.schema
        val file = File(getUser(helper.context), "${schema}.custom.yaml")
        val content = buildString {
            appendLine("patch:")

            appendLine("    translator/enable_correction: ${cfg.autocorrect}")
            appendLine("    translator/enable_user_dict: ${cfg.learning}")

            appendLine("    switches/+:")
            appendLine("        - name: futo_zh_simp")
            appendLine("          reset: ${if(cfg.simplification) 1 else 0}")
            appendLine("          states: [ 漢字, 汉字 ]")
            // Taiwan character forms, Zhuyin only (see futo_bopomofo.schema.yaml).
            if(schema.startsWith("futo_bopomofo")) {
                appendLine("        - name: futo_zh_tw")
                appendLine("          reset: ${if(cfg.simplification) 0 else 1}")
                appendLine("          states: [ 傳統, 臺灣 ]")
            }
            appendLine("    simplifier/option_name: futo_zh_simp")

            // Fuzzy pinyin only added to pinyin layouts
            if(schema.startsWith("luna_pinyin")) {
                appendLine("    speller/algebra:")
                cfg.fuzzyMode.forEach {
                    it.schemePatch.forEach {
                        appendLine("        - $it")
                    }
                }
            }
        }

        if(file.parentFile?.isDirectory == true) {
            file.writeText(content)
        }
    }

    private var configSemaphore = Semaphore(1)
    private fun updateConfig() {
        coroScope.launch {
            if(editingState.active) return@launch
            if(!configSemaphore.tryAcquire()) return@launch

            try {
                val settings = Settings.getInstance().current
                val locale = settings.mLocale

                val simplified = isSimplifiedChinese(locale)

                val pinyinScheme = helper.context.getSetting(ChineseIMESettings.PinyinSchemeSetting)
                    .toEnumOrNull<ChineseIMESettings.PinyinScheme>()
                    ?: ChineseIMESettings.PinyinScheme.FullPinyin
                val schema = when (layoutHint) {
                    "qwerty" -> {
                        when {
                            pinyinScheme == ChineseIMESettings.PinyinScheme.FullPinyin -> when {
                                simplified -> "luna_pinyin_simp"
                                else -> "luna_pinyin"
                            }

                            else -> pinyinScheme.rimeId
                        }
                    }

                    "stroke" -> {
                        "stroke"
                    }

                    "bopomofo" -> {
                        val hasTerraPinyin = File(getShared(helper.context), "terra_pinyin.dict.yaml").exists()
                        if(hasTerraPinyin) "futo_bopomofo_terra" else "futo_bopomofo"
                    }

                    null -> {
                        return@launch
                    }

                    else -> {
                        throwIfDebug(IllegalStateException("Invalid layout hint '${layoutHint}'"))
                        "luna_pinyin"
                    }
                }

                val fuzzy = ChineseIMESettings.FuzzyPinyinModes.fromCommaString(
                    helper.context.getSetting(ChineseIMESettings.FuzzyPinyinSetting)
                )

                // Zhuyin has its own setting (see ZhuyinCorrectionSetting).
                val autocorrect = if(schema.startsWith("futo_bopomofo")) {
                    helper.context.getSetting(ChineseIMESettings.ZhuyinCorrectionSetting)
                } else {
                    settings.mAutoCorrectionEnabledPerUserSettings
                            || settings.isSuggestionsEnabledPerUserSettings
                }

                val learning = settings.isPersonalizationEnabled

                // The personal dictionary's Chinese words as a Rime dictionary (see
                // ChineseCustomDict). Rendered on every update so that the hash below notices
                // changes; the file itself is only rewritten when the content differs. The
                // ContentResolver query, entries() and render() all do I/O or cross-process work,
                // so keep them off the main thread.
                val customEntries = withContext(Dispatchers.IO) {
                    ChineseCustomDict.entries(UserDictionaryIO(helper.context).get())
                }
                val customDict = withContext(Dispatchers.IO) { ChineseCustomDict.render(customEntries) }
                // Rime shows them in Taiwan forms (the t2tw filter) unless output is simplified.
                personalWords = customEntries.flatMap { listOf(it.text, TaiwanVariants.toTaiwan(it.text)) }.toSet()

                // The IO block above is a suspension point: an editing session may have started
                // while the personal dictionary was being read. Do not deploy in that case.
                if(editingState.active) return@launch

                // Only the Zhuyin schemas read futo_custom; do not make a pinyin/stroke user's
                // personal-dictionary change trigger a redeploy of their (unrelated) schema.
                val customDictHash = if (schema.startsWith("futo_bopomofo")) customDict.hashCode() else 0

                val config = Configuration(
                    schema, learning, simplified, autocorrect, fuzzy, customDictHash
                )
                if (config != prevConfiguration) {
                    if (config != retryConfiguration) {
                        retryConfiguration = config
                        schemaSelectRetries = 0
                    } else if (schemaSelectRetries >= MaxSchemaSelectRetries) {
                        // Already gave up on this configuration (e.g. no dictionary imported);
                        // do not redeploy and block on every start of input. invalidateConfiguration()
                        // (dictionary change, resource reload, clearing the user dictionary) starts over.
                        return@launch
                    }

                    if(!withContext(Dispatchers.IO) { ChineseCustomDict.write(getUser(helper.context), customDict) }) {
                        // Leave prevConfiguration untouched so the next update retries; Rime keeps
                        // using the last successfully compiled personal dictionary.
                        Log.e(TAG, "Failed to write ${ChineseCustomDict.FILE_NAME}, not deploying")
                        return@launch
                    }

                    rimeLoading.value = true
                    writeCustomizationFile(config)
                    rime.deploy()

                    var result = false
                    for(i in 0..10) {
                        if(rime.selectSchema(config.schema)) {
                            result = true
                            break
                        }
                        Thread.sleep(300L)
                    }

                    rime.setOption("simplification", simplified)
                    rime.setOption("traditional", !simplified)

                    if(result) {
                        prevConfiguration = config
                        schemaSelectRetries = 0
                    } else {
                        Log.e(TAG, "Failed to select schema 3s after deployment!")
                        // Leave prevConfiguration untouched so the next onStartInput retries, and
                        // retry a bounded number of times ourselves: after the user directory was
                        // wiped every schema has to be rebuilt, which can take longer than 3s.
                        schemaSelectRetries++
                        if(schemaSelectRetries < MaxSchemaSelectRetries) {
                            coroScope.launch {
                                delay(SchemaSelectRetryDelayMs)
                                updateConfig()
                            }
                        } else {
                            Log.e(TAG, "Giving up on schema '${config.schema}' after $schemaSelectRetries attempts")
                        }
                    }
                }
            } finally {
                configSemaphore.release()
                rimeLoading.value = false
            }
        }
    }

    override fun onLayoutUpdated(layout: KeyboardLayoutSetV2) {
        endAssociation()
        layoutHint = layout.mainLayout.imeHint
        ensureAssociationLoaded()
        ensureCharReadingsLoaded()
        ensureScorerLoaded()
        if(helper.isImeActive(this)) updateConfig()
    }

    override fun onFinishInput() {
        endAssociation()
        if(editingState.active) return
        coroScope.launch {
            waitingToSelect = null
            rawInput.clear()
            rime.clearComposition()
        }
    }

    override fun onFinishSlidingInput() {
    }

    private fun interruptInput(text: CharSequence? = null) {
        coroScope.launch {
            rawInput.clear()
            rime.clearComposition()
        }
        connect?.finishComposingText()
        if (text != null) {
            connect?.commitText(text, 1)
        }
    }

    private fun sendDownUpKeyEvent(keyCode: Int, metaState: Int = 0) {
        // NOTE: Modified based on InputLogic
        val eventTime = SystemClock.uptimeMillis()
        connect?.sendKeyEvent(
            KeyEvent(
                eventTime, eventTime,
                KeyEvent.ACTION_DOWN, keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        )
        connect?.sendKeyEvent(
            KeyEvent(
                eventTime, eventTime,
                KeyEvent.ACTION_UP, keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        )
    }
    private fun triggerIfIsAction(keyCode: Int): Boolean {
        if (Constants.CODE_ACTION_0 <= keyCode && keyCode <= Constants.CODE_ACTION_MAX) {
            val actionId: Int = keyCode - Constants.CODE_ACTION_0
            helper.triggerAction(actionId, false)
            return true
        }
        if (Constants.CODE_ALT_ACTION_0 <= keyCode && keyCode <= Constants.CODE_ALT_ACTION_MAX) {
            val actionId: Int = keyCode - Constants.CODE_ALT_ACTION_0
            helper.triggerAction(actionId, true)
            return true
        }
        return false
    }

    /**
     * Key handling for the English page embedded in the Zhuyin layout: keys are plain text and
     * never edit a Zhuyin composition. A composition left pending when the user switched pages
     * is committed first.
     *
     * @return true if the key was fully handled; false lets backspace/enter take the generic path
     *         (after the pending composition, if any, was committed).
     */
    private fun handleEnglishPageKey(event: Event): Boolean {
        val composing = rawInput.text.isNotEmpty()
        rawInput.clear()
        val codePoint = if (event.mKeyCode == Event.NOT_A_KEY_CODE) event.mCodePoint else -1
        val text = if (codePoint in 0x20..0x7E) String(Character.toChars(codePoint)) else null
        coroScope.launch {
            // The preedit check is load-bearing: rawInput is cleared synchronously, so a second
            // key typed while the first flush is still suspended must also wait on Rime here,
            // otherwise its text would be committed ahead of the first key's.
            if (composing || rime.preeditFlow.value.isNotEmpty()) rime.commitComposition()
            if (text != null) connect?.commitText(text, 1)
        }
        return text != null
    }

    /**
     * Key handling specific to the Zhuyin layout. Zhuyin symbols are translated to the ASCII
     * keys of the standard keyboard; ASCII letters, digits and the speller punctuation (symbols
     * page, number row) bypass Rime because the schema would interpret them as Zhuyin keys;
     * other punctuation is left to Rime's punctuator; space is tone 1 while composing.
     *
     * Per-key coroutines only stay in order up to their first suspension point, so Rime's
     * preedit may lag behind fast typing. [rawInput] is updated before any suspension and is
     * therefore used as the source of truth for "is a syllable being composed".
     *
     * @return true if the key was fully handled.
     */
    private fun handleBopomofoKey(codePoint: Int): Boolean {
        ZhuyinKeymap.rimeKeyFor(codePoint)?.let { key ->
            coroScope.launch {
                // A tone with no syllable, or a second tone on an already toned syllable, is not
                // an initial in the schema; Rime would pass it back and it would be committed as
                // a literal digit. Ignore it instead.
                if(ZhuyinKeymap.isToneKey(key) &&
                    (rawInput.text.isEmpty() || ZhuyinKeymap.isToneKey(rawInput.text.lastOrNull()))) {
                    return@launch
                }
                rawInput.append(key.code)
                rime.processX11Code(key.code)
            }
            return true
        }

        if(ZhuyinKeymap.bypassesRime(codePoint)) {
            // Letters, digits and the speller punctuation never come from the Zhuyin keys (which
            // emit Zhuyin symbols; hardware keyboards are disabled by
            // ProductionFlags.IS_HARDWARE_KEYBOARD_SUPPORTED); they come from the symbols page or
            // number row and must not reach Rime, which would read them as Zhuyin keys. Other
            // punctuation still goes to Rime so its punctuator decides how to commit it (the
            // bundled dictionary passes it through unchanged, like the pinyin layout).
            coroScope.launch {
                val composing = rawInput.text.isNotEmpty() || rime.preeditFlow.value.isNotEmpty()
                rawInput.clear()
                if(composing) rime.commitComposition()
                connect?.commitText(String(Character.toChars(codePoint)), 1)
                // Committing the composition above may have shown associations for it; the
                // key's own text now follows, so they no longer apply.
                endAssociation()
            }
            return true
        }

        if(codePoint == ' '.code) {
            val first = rimeIndexOf(0)
            val firstText = rime.candidatesFlow.value.getOrNull(first)?.text
            coroScope.launch {
                when {
                    rawInput.text.isEmpty() && rime.preeditFlow.value.isEmpty() -> {
                        // Not composing: behave like the generic path (a plain space).
                        rawInput.clear()
                        rime.processX11Code(' '.code)
                    }
                    ZhuyinKeymap.isToneKey(rawInput.text.lastOrNull()) -> {
                        // The syllable already has a tone: space picks the first candidate shown
                        // (which the reranker may have moved ahead of Rime's first), like the
                        // pinyin layout does.
                        rawInput.clear()
                        // The candidates may have changed since the key was pressed.
                        val index = if(rime.candidatesFlow.value.getOrNull(first)?.text == firstText) first else 0
                        if(!rime.selectCandidate(index)) rime.commitComposition()
                    }
                    else -> {
                        rawInput.append(' '.code)
                        rime.processX11Code(' '.code)
                    }
                }
            }
            return true
        }

        return false
    }

    override fun onEvent(event: Event) {
        helper.requestCursorUpdate()
        when (event.eventType) {
            Event.EVENT_TYPE_INPUT_KEYPRESS,
            Event.EVENT_TYPE_INPUT_KEYPRESS_RESUMED -> {
                endAssociation()
                if (triggerIfIsAction(event.mKeyCode))
                    return
                if(isBopomofo && !editingState.active) {
                    if(isOnEnglishPage) {
                        if(handleEnglishPageKey(event)) return
                    } else if(event.mKeyCode == Event.NOT_A_KEY_CODE) {
                        if(handleBopomofoKey(event.mCodePoint)) return
                    }
                }
                val x11Code = when (event.mKeyCode) {
                    Constants.CODE_DELETE -> '\b'.code
                    Event.NOT_A_KEY_CODE -> event.mCodePoint
                    else -> return
                }.let { when (it) {
                    '\b'.code -> XK_BackSpace
                    '\t'.code -> XK_Tab
                    '\n'.code -> XK_Return // Use return instead of linefeed here.
                    '\r'.code -> XK_Return
                    else -> it
                } }

                if(!editingState.active) {
                    // Only update outside editing state. We cannot update inside editing state,
                    // because the cursor position is unknown and we assume it's always at the end
                    if(x11Code == XK_BackSpace) {
                        rawInput.backspace()
                    } else if(x11Code == XK_Return || x11Code == ' '.code) {
                        rawInput.clear()
                    } else if(event.mKeyCode == Event.NOT_A_KEY_CODE) {
                        rawInput.append(event.mCodePoint)
                    }

                    coroScope.launch { rime.processX11Code(x11Code) }
                } else {
                    handlePassByMessage(x11Code, 0)
                }
            }

            Event.EVENT_TYPE_SUGGESTION_PICKED -> {
                val suggestion = event.mSuggestedWordInfo ?: return
                val context = associationContext
                if (suggestion.isKindOf(SuggestedWordInfo.KIND_UNDO)) {
                    endAssociation()
                    connect?.commitText(suggestion.word, 1)
                } else if (context != null &&
                    associationWords.getOrNull(suggestion.mCandidateIndex) == suggestion.word) {
                    connect?.commitText(suggestion.word, 1)
                    val next = (context + suggestion.word).let { it.substring(it.offsetByCodePoints(it.length, -minOf(2, it.codePointCount(0, it.length)))) }
                    showAssociations(next, suggestion.word)
                } else {
                    var index = rimeIndexOf(suggestion.mCandidateIndex)
                    if(rime.candidatesFlow.value.getOrNull(index)?.text != suggestion.word) {
                        // The strip changed since it was drawn: find the tapped word itself.
                        index = rime.candidatesFlow.value.indexOfFirst { it.text == suggestion.word }
                            .takeIf { it >= 0 } ?: suggestion.mCandidateIndex
                    }
                    coroScope.launch {
                        if(!rime.selectCandidate(index)) {
                            Log.e(TAG, "Couldn't select candidate")
                        }
                    }
                }
            }

            Event.EVENT_TYPE_SOFTWARE_GENERATED_STRING -> {
                endAssociation()
                interruptInput(event.mText)
            }

            Event.EVENT_TYPE_DOWN_UP_KEYEVENT -> {
                endAssociation()
                interruptInput()
                if (event.mX == KeyEvent.META_CTRL_ON) when (event.mKeyCode) {
                    KeyEvent.KEYCODE_F1 -> {

                    }
                    KeyEvent.KEYCODE_F2 -> {
                        Log.d(TAG, "Redeploying...")
                        coroScope.launch { rime.deploy() }
                        return
                    }
                }
                sendDownUpKeyEvent(event.mKeyCode, event.mX)
            }

            else -> {}
        }
    }

    private var anchorOutOfDate = true
    private var anchorLeftCount = 0
    private var anchorRightCount = 0
    private val currentAnchorCursor: Pair<Int, Int> get() {
        if (anchorOutOfDate) {
            anchorLeftCount = (connect?.getTextBeforeCursor(maxBufferLength, 0) ?: "").length
            anchorRightCount = (connect?.getTextAfterCursor(maxBufferLength, 0) ?: "").length
            currentMovingCursor = anchorLeftCount
            anchorOutOfDate = false
        }
        return Pair(anchorLeftCount, anchorRightCount)
    }
    private var currentMovingCursor: Int = 0
    override fun onMovePointer(steps: Int, stepOverWords: Boolean, select: Boolean?) {
        endAssociation()
        var meta = 0
        if (stepOverWords) meta = meta or KeyEvent.META_CTRL_ON
        if (select == true) meta = meta or KeyEvent.META_SHIFT_ON
        if(steps < 0) {
            for(i in 0 until -steps) {
                sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT, meta)
            }
        } else {
            for(i in 0 until steps) {
                sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT, meta)
            }
        }
    }
    override fun onUpWithPointerActive() {
        // TODO("Unsupported yet")
    }
    override fun onMoveDeletePointer(steps: Int) {
        endAssociation()
        setNeutralSuggestionStrip()
        val (lBound, rBound) = currentAnchorCursor
        currentMovingCursor = when {
            steps < 0 -> max(0, currentMovingCursor - 1)
            steps > 0 -> min(lBound + rBound, currentMovingCursor + 1)
            else -> currentMovingCursor
        }
        connect?.setSelection(lBound, currentMovingCursor)
    }
    override fun onUpWithDeletePointerActive() {
        anchorOutOfDate = true
        val selection: CharSequence? = connect?.getSelectedText(0)
        if (selection == null) {
            onUpWithPointerActive()
            return
        }
        coroScope.launch {
            val preedit = rime.getPreedit()
            var reserved: String
            when {
                preedit.endsWith(selection) -> {
                    reserved = preedit.removeSuffix(selection).filterNot { it.isWhitespace() }
                    connect?.commitText(reserved, 1)
                }
                selection.endsWith(preedit) -> {
                    reserved = selection.removeSuffix(preedit) as String
                    sendDownUpKeyEvent(KeyEvent.KEYCODE_DEL, 0)
                }
                else -> {
                    sendDownUpKeyEvent(KeyEvent.KEYCODE_DEL, 0)
                    Log.w(TAG, "Wrong preedit text fetched?")
                    return@launch
                }
            }
            rawInput.clear()
            rime.clearComposition()
            val info = arrayListOf(SuggestedWordInfo(
                reserved,
                "",
                Int.MAX_VALUE,
                SuggestedWordInfo.KIND_UNDO,
                null,
                0,
                0
            ))
            showSuggestionStrip(SuggestedWords(
                info,
                null,
                null,
                false,
                false,
                false,
                0,
                0
            ))
        }
    }

    override fun onSwipeLanguage(direction: Int) {
        switchToNextLanguage(helper.context, direction)
    }

    private var prevSuggest: SuggestedWords? = null
    private val blacklist = SuggestionBlacklist(Settings.getInstance(), helper.context, helper.lifecycleScope)
    override fun setNeutralSuggestionStrip() {
        prevSuggest = null
        helper.setNeutralSuggestionStrip(expandableUiCfg)
    }
    override fun showSuggestionStrip(suggestedWords: SuggestedWords?) {
        val words = suggestedWords?.let { blacklist.filterBlacklistedSuggestions(it) }
        prevSuggest = words
        helper.showSuggestionStrip(words, expandableUiCfg)
    }

    override fun requestSuggestionRefresh() {
        endAssociation()
        showSuggestionStrip(null)
    }

    override fun isGestureHandlingAvailable() : Boolean = false
    override fun onCustomRequest(requestCode: Int): Boolean = false
    override fun onMovingCursorLockEvent(canMoveCursor: Boolean) {}

    override fun clearUserHistoryDictionaries() {
        Log.d(TAG, "Clearing Chinese dictionary...")

        coroScope.launch {
            // Hold the config semaphore so a concurrent updateConfig() cannot write its custom
            // file or select a schema while Rime is shut down and the user directory is wiped.
            configSemaphore.acquire()
            try {
                rime.shutdown()
                getUser(helper.context).let {
                    it.deleteRecursively()
                    it.mkdirs()
                }
                // The user directory held the bundled default.custom.yaml; restore it before Rime restarts.
                BundledRimeAssets.ensure(helper.context, getShared(helper.context), getUser(helper.context))
                rime.startup(false)
                invalidateConfiguration()
            } finally {
                configSemaphore.release()
            }
            // The compiled schemas were wiped with the user directory; re-select the schema for
            // the current layout now that Rime is back.
            updateConfig()
        }
    }

    override fun onStartBatchInput() {}
    override fun onUpdateBatchInput(batchPointers: InputPointers?) {}
    override fun onEndBatchInput(batchPointers: InputPointers?) {}
    override fun onCancelBatchInput() {}

// Non-behavior methods {{
// NOTE: These methods are scheduled to have no behavior
    override fun onCancelInput() {}
    override fun onDeviceUnlocked() {}
    override fun onOrientationChanged() {}
// Non-behavior methods }}

    val debugInfo: String
        get() = "configuration=${prevConfiguration}\nlayoutHint=${layoutHint}\nlocale=${Settings.getInstance().current.mLocale}\nisSimplified=${isSimplifiedChinese(Settings.getInstance().current.mLocale)}\nrawInput=${rawInput.text}\nassociation=${association?.size}"
}
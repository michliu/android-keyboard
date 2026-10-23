package org.futo.inputmethod.engine.general

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSetting
import java.io.File
import java.io.IOException

/**
 * Rime schema files shipped inside the APK under `assets/rime`. The Chinese dictionary the
 * user imports (zidian.dict) does not include a Zhuyin schema, so these are copied next to it
 * where Rime looks for schemas.
 */
object BundledRimeAssets {
    private const val TAG = "BundledRimeAssets"
    private const val ASSET_DIR = "rime"

    /** Bump whenever any file under assets/rime changes, so existing installs are refreshed. */
    const val VERSION = 5

    /** Copied into the Rime shared directory (recreated whenever the dictionary is re-extracted). */
    val SHARED_FILES = listOf(
        "futo_zhuyin.yaml",
        "futo_bopomofo.schema.yaml",
        "futo_bopomofo_terra.schema.yaml",
        "futo_custom_bopomofo.schema.yaml",
    )

    /** Copied into the Rime user directory (wiped when the user clears learned words). */
    val USER_FILES = listOf(
        "default.custom.yaml",
    )

    val VersionSetting = SettingsKey(intPreferencesKey("ChineseBundledRimeAssetsVersion"), 0)

    /**
     * Version written by this process. The persisted setting is written asynchronously (see
     * [ensure]); this keeps a second call in the same process from copying the files again
     * before that write has landed.
     */
    @Volatile
    private var ensuredVersion = 0

    private val settingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Makes sure every bundled file exists and is current. Called on the main thread
     * (onStartInput), so the version setting is persisted from a background coroutine:
     * blocking the main thread on the DataStore write here has deadlocked the IME process.
     * @return true if files were written and Rime needs to be redeployed.
     */
    fun ensure(context: Context, shared: File, user: File): Boolean {
        val upToDate = (ensuredVersion == VERSION || context.getSetting(VersionSetting) == VERSION)
                && SHARED_FILES.all { File(shared, it).exists() }
                && USER_FILES.all { File(user, it).exists() }
        if (upToDate) return false

        return try {
            shared.mkdirs()
            user.mkdirs()
            SHARED_FILES.forEach { copyAsset(context, it, File(shared, it)) }
            USER_FILES.forEach { copyAsset(context, it, File(user, it)) }
            ensuredVersion = VERSION
            settingScope.launch {
                // A failed write is harmless: the files are copied again on the next start.
                runCatching { context.setSetting(VersionSetting.key, VERSION) }
                    .onFailure { Log.w(TAG, "Failed to persist the bundled Rime asset version", it) }
            }
            true
        } catch (e: IOException) {
            // Leave the version setting untouched so the next call retries.
            Log.e(TAG, "Failed to copy bundled Rime assets", e)
            false
        }
    }

    private fun copyAsset(context: Context, name: String, destination: File) {
        context.assets.open("$ASSET_DIR/$name").use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
    }
}

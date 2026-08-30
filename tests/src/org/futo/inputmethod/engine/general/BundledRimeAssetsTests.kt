package org.futo.inputmethod.engine.general

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.futo.inputmethod.latin.uix.setSettingBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BundledRimeAssetsTests {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var shared: File
    private lateinit var user: File

    @Before
    fun setUp() {
        root = File(context.cacheDir, "bundled_rime_test").apply { deleteRecursively(); mkdirs() }
        shared = File(root, "shared")
        user = File(root, "user")
        context.setSettingBlocking(BundledRimeAssets.VersionSetting.key, 0)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        context.setSettingBlocking(BundledRimeAssets.VersionSetting.key, 0)
    }

    @Test
    fun copiesAllFilesOnFirstRun() {
        assertTrue(BundledRimeAssets.ensure(context, shared, user))
        BundledRimeAssets.SHARED_FILES.forEach { assertTrue(it, File(shared, it).length() > 0) }
        BundledRimeAssets.USER_FILES.forEach { assertTrue(it, File(user, it).length() > 0) }
        assertTrue(File(shared, "futo_bopomofo.schema.yaml").readText().contains("schema_id: futo_bopomofo"))
    }

    @Test
    fun doesNothingWhenUpToDate() {
        assertTrue(BundledRimeAssets.ensure(context, shared, user))
        assertFalse(BundledRimeAssets.ensure(context, shared, user))
    }

    @Test
    fun recopiesWhenAFileIsMissing() {
        assertTrue(BundledRimeAssets.ensure(context, shared, user))
        assertTrue(File(user, "default.custom.yaml").delete())
        assertTrue(BundledRimeAssets.ensure(context, shared, user))
        assertTrue(File(user, "default.custom.yaml").exists())
    }

    @Test
    fun recopiesWhenVersionChanges() {
        assertTrue(BundledRimeAssets.ensure(context, shared, user))
        val schema = File(shared, "futo_bopomofo.schema.yaml")
        schema.writeText("stale")
        context.setSettingBlocking(BundledRimeAssets.VersionSetting.key, BundledRimeAssets.VERSION - 1)
        assertTrue(BundledRimeAssets.ensure(context, shared, user))
        assertEquals(false, schema.readText() == "stale")
    }
}

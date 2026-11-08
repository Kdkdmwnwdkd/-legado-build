package io.legado.app.help.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用内「检查更新」依赖资产名解析版本，命名一旦回退到
 * app-app-release.apk 这类不带版本信息的形式，就会永远显示已是最新。
 * 这里锁住解析与比较逻辑。
 */
class AppReleaseInfoParseTest {

    private fun asset(name: String, tag: String = "nightly") = AppReleaseInfo(
        appVariant = AppVariant.OFFICIAL,
        createdAt = 0L,
        note = "",
        name = name,
        downloadUrl = "",
        assetUrl = "",
        tagName = tag
    )

    @Test
    fun `解析 nightly 资产名`() {
        val info = asset("shimmer_3.26.11082146_29902426.apk")
        assertEquals("3.26.11082146", info.versionName)
        assertEquals(29902426L, info.versionCode)
    }

    @Test
    fun `无版本信息的旧资产名不会伪造版本`() {
        val info = asset("app-app-release.apk")
        assertEquals(0L, info.versionCode)
        // 退回 tag，但 nightly 不是数字版本，不能被当成新版本
        assertEquals("nightly", info.versionName)
    }

    @Test
    fun `版本名按分段比较而不是字符串`() {
        assertTrue(AppUpdate.compareVersionName("3.26.11082146", "3.26.110817") > 0)
        assertTrue(AppUpdate.compareVersionName("3.26.110817", "3.26.102508") > 0)
        assertEquals(0, AppUpdate.compareVersionName("3.26.110817", "3.26.110817"))
    }

    @Test
    fun `非数字版本名不参与更新判定`() {
        val info = AppUpdate.UpdateInfo(
            tagName = "nightly",
            updateLog = "",
            downloadUrl = "",
            fileName = "app-app-release.apk"
        )
        assertEquals(0L, info.versionCode)
    }

    @Test
    fun `资产名里的版本号能被独立解析`() {
        assertEquals(29902426L, AppUpdate.versionCodeFromFileName("shimmer_3.26.11082146_29902426.apk"))
        assertEquals(0L, AppUpdate.versionCodeFromFileName("app-app-release.apk"))
    }
}

package com.shimmer.reader.ui.config

import com.shimmer.reader.help.http.dns.DnsScope
import android.net.Uri
import androidx.lifecycle.lifecycleScope
import com.shimmer.reader.R
import com.shimmer.reader.constant.PreferKey
import com.shimmer.reader.help.config.AppConfig
import com.shimmer.reader.help.http.addHeaders
import com.shimmer.reader.help.http.newCallResponse
import com.shimmer.reader.help.http.imageHttpClient as okHttpClient
import com.shimmer.reader.model.BookCover
import com.shimmer.reader.model.analyzeRule.AnalyzeUrl
import com.shimmer.reader.ui.config.compose.ComposeSettingFragment
import com.shimmer.reader.ui.config.compose.SettingActionSpec
import com.shimmer.reader.ui.config.compose.SettingPageSpec
import com.shimmer.reader.ui.config.compose.SettingSectionSpec
import com.shimmer.reader.ui.config.compose.SettingSliderSpec
import com.shimmer.reader.ui.config.compose.SettingSwitchSpec
import com.shimmer.reader.ui.file.HandleFileContract
import com.shimmer.reader.ui.widget.compose.showComposeActionListDialog
import com.shimmer.reader.utils.FileUtils
import com.shimmer.reader.utils.MD5Utils
import com.shimmer.reader.utils.externalFiles
import com.shimmer.reader.utils.inputStream
import com.shimmer.reader.utils.putPrefBoolean
import com.shimmer.reader.utils.putPrefString
import com.shimmer.reader.utils.readUri
import com.shimmer.reader.utils.removePref
import com.shimmer.reader.utils.toastOnUi
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.io.FileOutputStream

class WelcomeConfigFragment : ComposeSettingFragment() {

    override val titleRes: Int = R.string.welcome_style

    private val requestWelcomeImage = 221
    private val requestWelcomeImageDark = 222
    private val selectImage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            when (it.requestCode) {
                requestWelcomeImage -> setCoverFromUri(PreferKey.welcomeImage, uri)
                requestWelcomeImageDark -> setCoverFromUri(PreferKey.welcomeImageDark, uri)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val welcomeShowTime = intSetting(PreferKey.welcomeShowTime, 0)
        val safeWelcomeShowTime = welcomeShowTime.coerceIn(0, 800)
        if (welcomeShowTime != safeWelcomeShowTime) {
            updateIntSetting(PreferKey.welcomeShowTime, safeWelcomeShowTime)
        }
    }

    override fun buildPageSpec(): SettingPageSpec {
        return SettingPageSpec(
            titleRes = titleRes,
            sections = listOf(
                SettingSectionSpec(
                    items = listOf(
                        SettingSliderSpec(
                            key = PreferKey.welcomeShowTime,
                            title = str(R.string.welcome_show_time),
                            summary = str(R.string.welcome_show_time_summary),
                            value = intSetting(PreferKey.welcomeShowTime, 0).coerceIn(0, 800),
                            valueRange = 0..800,
                            onValueChange = {
                                updateIntSetting(PreferKey.welcomeShowTime, it)
                            }
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.customWelcome,
                            title = str(R.string.custom_welcome),
                            summary = str(R.string.custom_welcome_summary),
                            checked = booleanSetting(PreferKey.customWelcome, false),
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.customWelcome, it)
                            }
                        )
                    )
                ),
                SettingSectionSpec(
                    title = str(R.string.day),
                    items = listOf(
                        SettingActionSpec(
                            key = PreferKey.welcomeImage,
                            title = str(R.string.background_image),
                            summary = imageSummary(AppConfig.welcomeImage),
                            onClick = {
                                showImageActions(
                                    key = PreferKey.welcomeImage,
                                    requestCode = requestWelcomeImage
                                )
                            }
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.welcomeShowText,
                            title = str(R.string.show_welcome_text),
                            summary = str(R.string.welcome_text),
                            checked = booleanSetting(PreferKey.welcomeShowText, true),
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.welcomeShowText, it)
                            }
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.welcomeShowIcon,
                            title = str(R.string.show_icon),
                            summary = str(R.string.show_default_book_icon),
                            checked = booleanSetting(PreferKey.welcomeShowIcon, true),
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.welcomeShowIcon, it)
                            }
                        )
                    )
                ),
                SettingSectionSpec(
                    title = str(R.string.night),
                    items = listOf(
                        SettingActionSpec(
                            key = PreferKey.welcomeImageDark,
                            title = str(R.string.background_image),
                            summary = imageSummary(AppConfig.welcomeImageDark),
                            onClick = {
                                showImageActions(
                                    key = PreferKey.welcomeImageDark,
                                    requestCode = requestWelcomeImageDark
                                )
                            }
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.welcomeShowTextDark,
                            title = str(R.string.show_welcome_text),
                            summary = str(R.string.welcome_text),
                            checked = booleanSetting(PreferKey.welcomeShowTextDark, true),
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.welcomeShowTextDark, it)
                            }
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.welcomeShowIconDark,
                            title = str(R.string.show_icon),
                            summary = str(R.string.show_default_book_icon),
                            checked = booleanSetting(PreferKey.welcomeShowIconDark, true),
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.welcomeShowIconDark, it)
                            }
                        )
                    )
                )
            )
        )
    }

    private fun imageSummary(value: String?): String {
        return if (value.isNullOrBlank()) {
            str(R.string.select_image)
        } else {
            value
        }
    }

    private fun showImageActions(
        key: String,
        requestCode: Int
    ) {
        if (stringSetting(key, "").isBlank()) {
            launchImagePicker(requestCode)
            return
        }
        showComposeActionListDialog(
            title = str(R.string.select_image),
            labels = listOf(
                str(R.string.delete),
                str(R.string.select_image)
            ),
            dangerIndices = setOf(0),
            negativeText = str(R.string.cancel),
            onSelected = { index ->
                if (index == 0) {
                    removePref(key)
                    BookCover.upDefaultCover()
                } else {
                    launchImagePicker(requestCode)
                }
            }
        )
    }

    private fun launchImagePicker(requestCode: Int) {
        selectImage.launch {
            this.requestCode = requestCode
            mode = HandleFileContract.IMAGE
        }
    }

    private fun setCoverFromUri(preferenceKey: String, uri: Uri) {
        putPrefBoolean(PreferKey.customWelcome, true)
        if (uri.scheme?.lowercase() in listOf("http", "https")) {
            lifecycleScope.launch {
                kotlin.runCatching {
                    appCtx.toastOnUi(R.string.toast_downloading_image)
                    val analyzeUrl = AnalyzeUrl(uri.toString(), dnsScope = DnsScope.IMAGE)
                    val url = analyzeUrl.urlNoQuery
                    var file = requireContext().externalFiles
                    val res = okHttpClient.newCallResponse(0) {
                        addHeaders(analyzeUrl.headerMap)
                        url(url)
                    }
                    val contentType = res.header("Content-Type") ?: "image/jpeg"
                    val imageType = when {
                        contentType.contains("png", ignoreCase = true) -> "png"
                        contentType.contains("gif", ignoreCase = true) -> "gif"
                        contentType.contains("webp", ignoreCase = true) -> "webp"
                        else -> "jpg"
                    }
                    val suffix = if (url.contains(".9.png", true)) {
                        ".9.png"
                    } else {
                        ".$imageType"
                    }
                    val fileName = MD5Utils.md5Encode(url) + suffix
                    file = FileUtils.createFileIfNotExist(file, "covers", fileName)
                    res.body.byteStream().use { inputStream ->
                        FileOutputStream(file).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    putPrefString(preferenceKey, file.absolutePath)
                }.onSuccess {
                    appCtx.toastOnUi(R.string.toast_setting_saved)
                }.onFailure {
                    appCtx.toastOnUi(it.localizedMessage)
                }
            }
            return
        }
        readUri(uri) { fileDoc, inputStream ->
            kotlin.runCatching {
                var file = requireContext().externalFiles
                val suffix = if (fileDoc.name.contains(".9.png", true)) {
                    ".9.png"
                } else {
                    "." + fileDoc.name.substringAfterLast(".")
                }
                val fileName = uri.inputStream(requireContext()).getOrThrow().use {
                    MD5Utils.md5Encode(it) + suffix
                }
                file = FileUtils.createFileIfNotExist(file, "covers", fileName)
                FileOutputStream(file).use {
                    inputStream.copyTo(it)
                }
                putPrefString(preferenceKey, file.absolutePath)
            }.onFailure {
                appCtx.toastOnUi(it.localizedMessage)
            }
        }
    }
}

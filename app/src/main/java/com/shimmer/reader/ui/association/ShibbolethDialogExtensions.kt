package com.shimmer.reader.ui.association

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.shimmer.reader.R
import com.shimmer.reader.help.DirectLinkUpload
import com.shimmer.reader.ui.widget.compose.showComposeTextInputDialog
import com.shimmer.reader.utils.ShibbolethCodec
import com.shimmer.reader.utils.sendToClip
import com.shimmer.reader.utils.toastOnUi

fun AppCompatActivity.showShibbolethDialog(url: String, type: String) {
    val code = createShibboleth(url, type) ?: return
    showComposeTextInputDialog(
        title = getString(R.string.shibboleth),
        hint = getString(R.string.shibboleth),
        initialValue = code,
        readOnly = true,
        positiveText = getString(R.string.copy_text),
        onPositive = { sendToClip(code) }
    )
}

fun Fragment.showShibbolethDialog(url: String, type: String) {
    val code = createShibboleth(url, type) ?: return
    showComposeTextInputDialog(
        title = getString(R.string.shibboleth),
        hint = getString(R.string.shibboleth),
        initialValue = code,
        readOnly = true,
        positiveText = getString(R.string.copy_text),
        onPositive = { requireContext().sendToClip(code) }
    )
}

private fun AppCompatActivity.createShibboleth(url: String, type: String): String? {
    return ShibbolethCodec.encode(
        url = url,
        type = type,
        expiryDays = DirectLinkUpload.getExpiryDate()
    ).getOrElse {
        toastOnUi(R.string.shibboleth_https_only)
        null
    }
}

private fun Fragment.createShibboleth(url: String, type: String): String? {
    return ShibbolethCodec.encode(
        url = url,
        type = type,
        expiryDays = DirectLinkUpload.getExpiryDate()
    ).getOrElse {
        toastOnUi(R.string.shibboleth_https_only)
        null
    }
}

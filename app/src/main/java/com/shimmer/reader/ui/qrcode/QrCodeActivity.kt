package com.shimmer.reader.ui.qrcode

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import com.google.zxing.Result
import com.shimmer.reader.R
import com.shimmer.reader.base.BaseActivity
import com.shimmer.reader.databinding.ActivityQrcodeCaptureBinding
import com.shimmer.reader.ui.file.HandleFileContract
import com.shimmer.reader.utils.BitmapUtils
import com.shimmer.reader.utils.QRCodeUtils
import com.shimmer.reader.utils.viewbindingdelegate.viewBinding

class QrCodeActivity : BaseActivity<ActivityQrcodeCaptureBinding>(), ScanResultCallback {

    override val binding by viewBinding(ActivityQrcodeCaptureBinding::inflate)

    private val selectQrImage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            val bitmap = decodeQrBitmap(uri)
            onScanResultCallback(bitmap?.let { QRCodeUtils.parseCodeResult(it) })
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        val fTag = "qrCodeFragment"
        val qrCodeFragment = QrCodeFragment()
        supportFragmentManager.beginTransaction()
            .replace(R.id.fl_content, qrCodeFragment, fTag)
            .commit()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.qr_code_scan, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_choose_from_gallery -> selectQrImage.launch {
                mode = HandleFileContract.IMAGE
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun onScanResultCallback(result: Result?) {
        val intent = Intent()
        intent.putExtra("result", result?.text)
        setResult(RESULT_OK, intent)
        finish()
    }

    private fun decodeQrBitmap(uri: Uri): Bitmap? {
        return BitmapUtils.decodeBitmap(
            inputFactory = { contentResolver.openInputStream(uri) },
            width = MAX_QR_IMAGE_SIZE,
            height = MAX_QR_IMAGE_SIZE
        )
    }

    companion object {
        private const val MAX_QR_IMAGE_SIZE = 2048
    }

}

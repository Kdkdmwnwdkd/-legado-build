package io.legado.app.ui.qrcode

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import com.google.zxing.Result
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityQrcodeCaptureBinding
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.QRCodeUtils
import io.legado.app.utils.viewbindingdelegate.viewBinding

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

package com.shimmer.reader.ui.association

import android.os.Bundle
import com.shimmer.reader.base.BaseActivity
import com.shimmer.reader.constant.SourceType
import com.shimmer.reader.databinding.ActivityTranslucenceBinding
import com.shimmer.reader.utils.showDialogFragment
import com.shimmer.reader.utils.viewbindingdelegate.viewBinding

/**
 * 验证码
 */
class VerificationCodeActivity :
    BaseActivity<ActivityTranslucenceBinding>() {

    override val binding by viewBinding(ActivityTranslucenceBinding::inflate)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        intent.getStringExtra("imageUrl")?.let {
            val sourceOrigin = intent.getStringExtra("sourceOrigin")
            val sourceName = intent.getStringExtra("sourceName")
            val sourceType = intent.getIntExtra("sourceType", SourceType.book)
            showDialogFragment(
                VerificationCodeDialog(it, sourceOrigin, sourceName, sourceType)
            )
        } ?: finish()
    }

}
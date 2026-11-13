package com.shimmer.reader.utils.canvasrecorder.pools

import android.graphics.Picture
import com.shimmer.reader.utils.objectpool.BaseObjectPool

class PicturePool : BaseObjectPool<Picture>(64) {

    override fun create(): Picture = Picture()

}

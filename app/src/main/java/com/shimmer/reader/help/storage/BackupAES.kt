package com.shimmer.reader.help.storage

import cn.hutool.crypto.symmetric.AES
import com.shimmer.reader.help.config.LocalConfig
import com.shimmer.reader.utils.MD5Utils

class BackupAES : AES(
    MD5Utils.md5Encode(LocalConfig.password ?: "").encodeToByteArray(0, 16)
)
package com.shimmer.reader.exception

import com.shimmer.reader.R
import splitties.init.appCtx

class NoBooksDirException: NoStackTraceException(appCtx.getString(R.string.no_books_dir))
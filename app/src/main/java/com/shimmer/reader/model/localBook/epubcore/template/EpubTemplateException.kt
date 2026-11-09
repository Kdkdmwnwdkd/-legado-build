package com.shimmer.reader.model.localBook.epubcore.template

/** Carries the failed source revision so recovery cannot disable an unrelated template. */
class EpubTemplateException(val templateHash: String, message: String) : IllegalStateException(message)

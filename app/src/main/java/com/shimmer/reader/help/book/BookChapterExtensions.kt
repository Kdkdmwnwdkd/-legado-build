@file:Suppress("unused")

package com.shimmer.reader.help.book

import com.shimmer.reader.data.entities.BookChapter
import com.shimmer.reader.help.RuleBigDataHelp.getDanmakuFile

fun BookChapter.getDanmaku(): Any? { //读取弹幕数据
    return variableMap["danmaku"] ?: getDanmakuFile(bookUrl, url)
}
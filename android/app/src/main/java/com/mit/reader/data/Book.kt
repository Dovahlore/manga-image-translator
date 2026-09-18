package com.mit.reader.data

import java.io.File

enum class ReadingMode { MANGA, NORMAL }

/** 书源解析结果：EPUB/MOBI 解析器统一返回这个类型。 */
data class ParsedBook(val title: String, val pages: List<File>)

data class Book(
    val id: String,
    val title: String,
    val mode: ReadingMode,
    val pageCount: Int,
    val coverFile: File,
    val pageFiles: List<File>,   // 按 OPF spine 顺序，pageFiles[0] == 封面
)

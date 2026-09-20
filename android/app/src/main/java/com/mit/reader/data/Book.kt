package com.mit.reader.data

import java.io.File

enum class ReadingMode { MANGA, NORMAL }

/** 书源解析结果：EPUB/MOBI 解析器统一返回这个类型。 */
data class ParsedBook(val title: String, val pages: List<File>)

/** 收藏夹：把一套漫画的多卷收在一起，书库页不铺满。 */
data class Folder(
    val id: String,
    val name: String,
)

/** 阅读进度：上次读到的页（0-based）+ 时间戳。 */
data class ReadingProgress(val page: Int, val lastReadAt: Long)

data class Book(
    val id: String,
    val title: String,
    val mode: ReadingMode,
    val pageCount: Int,
    val coverFile: File,
    val pageFiles: List<File>,   // 按 OPF spine 顺序，pageFiles[0] == 封面
    val folderId: String? = null, // 所属收藏夹；null = 未分类
)

package com.mit.reader.data

import be.stef.rar.Unrar5j
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.util.zip.ZipFile

/**
 * 漫画压缩包解析（CBZ = ZIP / CBR = RAR4/RAR5，以及普通 zip/rar）：
 * 把包里所有图片按**文件名自然顺序**抽成页（1.jpg < 2.jpg < 10.jpg，不会排成 1,10,2）。
 * 整条路径参与自然排序，多章节目录也能排好；输出统一压平成 pages/page-0001.ext。
 *
 * RAR 用纯 Java 的 unrar5j（junrar 不支持 RAR5，会抛 UnsupportedRarV5Exception）。
 * 它只提供「整包解到目录」的接口，所以先解到临时目录再挑图；其 unpackedFiles 实测为空，不能依赖。
 */
object ArchiveParser {

    private val IMAGE_EXT = setOf("jpg", "jpeg", "jfif", "png", "webp", "gif", "bmp", "avif")

    /** 按文件头嗅探是否是 ZIP（PK\x03\x04）。 */
    fun isZip(f: File): Boolean = head(f, 4)?.let {
        it[0] == 0x50.toByte() && it[1] == 0x4B.toByte() && it[2] == 0x03.toByte() && it[3] == 0x04.toByte()
    } ?: false

    /** 按文件头嗅探是否是 RAR（Rar!\x1a\x07，RAR4/RAR5 通用）。 */
    fun isRar(f: File): Boolean = head(f, 7)?.let {
        it[0] == 'R'.code.toByte() && it[1] == 'a'.code.toByte() && it[2] == 'r'.code.toByte() &&
            it[3] == '!'.code.toByte() && it[4] == 0x1A.toByte() && it[5] == 0x07.toByte()
    } ?: false

    /** 解压图片页到 bookDir/pages，返回页文件（已按名字自然顺序编号）。 */
    fun extract(pack: File, bookDir: File): ParsedBook {
        val pagesDir = File(bookDir, "pages").apply { mkdirs() }
        val out = mutableListOf<File>()
        var imageCount = 0

        if (isRar(pack)) {
            val tmp = File(bookDir, "rar_tmp").apply { mkdirs() }
            try {
                extractRar(pack, tmp)
                val files = tmp.walkTopDown()
                    .filter { it.isFile && isImage(it.name) }
                    .sortedWith { a, b -> naturalCompare(rel(tmp, a), rel(tmp, b)) }
                    .toList()
                imageCount = files.size
                files.forEachIndexed { i, f -> moveInto(f, pagesDir, i + 1)?.let { out += it } }
            } finally {
                tmp.deleteRecursively()
            }
        } else {
            ZipFile(pack).use { zip ->
                val entries = zip.entries().asSequence()
                    .filter { !it.isDirectory && isImage(it.name) }
                    .sortedWith { a, b -> naturalCompare(a.name, b.name) }
                    .toList()
                imageCount = entries.size
                entries.forEachIndexed { i, e ->
                    writeStream(zip.getInputStream(e), pagesDir, i + 1, e.name)?.let { out += it }
                }
            }
        }

        if (out.isEmpty()) {
            throw IllegalStateException(
                if (imageCount == 0) "压缩包里没找到图片（支持 jpg/png/webp/gif/bmp/avif）"
                else "压缩包里的图片都解不出来（可能已损坏或加密）",
            )
        }
        return ParsedBook(pack.nameWithoutExtension, out)
    }

    // ---------------------------------------------------------------- RAR

    private val rarLock = Any()

    /** unrar5j 会往 stdout 打进度条，解压期间临时静音（用锁串行，避免并发互相抢 System.out）。 */
    private fun extractRar(pack: File, tmpDir: File) {
        synchronized(rarLock) {
            val realOut = System.out
            val realErr = System.err
            System.setOut(PrintStream(NullSink))
            System.setErr(PrintStream(NullSink))
            try {
                Unrar5j.extract(pack.absolutePath, tmpDir.absolutePath, null)
            } finally {
                System.setOut(realOut)
                System.setErr(realErr)
            }
        }
    }

    private object NullSink : OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }

    // ---------------------------------------------------------------- 内部

    private fun rel(base: File, f: File): String = f.relativeTo(base).path.replace('\\', '/')

    private fun pageFile(pagesDir: File, index: Int, srcName: String): File {
        val ext = srcName.substringAfterLast('.', "jpg").lowercase()
            .let { if (it in IMAGE_EXT) it else "jpg" }
        return File(pagesDir, "page-${index.toString().padStart(4, '0')}.$ext")
    }

    /** 流式写入（ZIP 用）。 */
    private fun writeStream(ins: InputStream, pagesDir: File, index: Int, srcName: String): File? {
        val dest = pageFile(pagesDir, index, srcName)
        return try {
            ins.use { input -> dest.outputStream().use { input.copyTo(it) } }
            dest
        } catch (e: Exception) {
            dest.delete()   // 半截图片删掉，不污染书页
            null
        }
    }

    /** 搬移已解出的文件（RAR 用）：同一分区 rename 免拷贝，失败退回复制。 */
    private fun moveInto(src: File, pagesDir: File, index: Int): File? {
        val dest = pageFile(pagesDir, index, src.name)
        return try {
            if (src.renameTo(dest)) dest
            else {
                src.copyTo(dest, overwrite = true)
                src.delete()
                dest
            }
        } catch (e: Exception) {
            dest.delete()
            null
        }
    }

    private fun isImage(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in IMAGE_EXT

    private fun head(f: File, n: Int): ByteArray? = runCatching {
        f.inputStream().use { ins ->
            val buf = ByteArray(n)
            if (ins.read(buf) >= n) buf else null
        }
    }.getOrNull()

    /** 自然序比较：连续数字按数值比，其余按字符（忽略大小写）比。 */
    internal fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var x = i
                while (x < a.length && a[x].isDigit()) x++
                var y = j
                while (y < b.length && b[y].isDigit()) y++
                val na = a.substring(i, x).trimStart('0')
                val nb = b.substring(j, y).trimStart('0')
                val c = if (na.length != nb.length) na.length - nb.length else na.compareTo(nb)
                if (c != 0) return c
                i = x
                j = y
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}

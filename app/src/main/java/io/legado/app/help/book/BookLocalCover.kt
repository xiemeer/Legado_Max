package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import splitties.init.appCtx
import java.io.File
import java.security.MessageDigest

/**
 * 本地书籍封面。
 *
 * 把网络封面下载到 app 私有目录，并在 Book.customCoverUrl 里存一个相对标记
 * （localCover:文件名），而不是绝对路径。显示时由 [resolve] 拼上当前设备的目录，
 * 因此换机、换存储卷后恢复备份也不需要重写数据库里的封面地址。
 *
 * 目录与封面图集（externalFiles/covers 下的散图）分开，不会进入图集分组表，
 * 也就不会被当作"替身封面"随机分配给其它书籍。
 */
object BookLocalCover {

    const val PREFIX = "localCover:"
    const val BACKUP_DIR_NAME = "bookCovers"
    private const val DIR_NAME = "bookCovers"

    private val dirPath: String by lazy {
        appCtx.externalFiles.getFile("covers", DIR_NAME).createFolderIfNotExist().absolutePath
    }

    val dir: File
        get() = File(dirPath).createFolderIfNotExist()

    fun isLocal(path: String?): Boolean = path?.startsWith(PREFIX) == true

    fun fileName(path: String): String = path.removePrefix(PREFIX)

    fun resolve(path: String?): String? {
        if (!isLocal(path)) return path
        return File(dirPath, fileName(path!!)).absolutePath
    }

    /** 一本书固定一个文件名，重复执行批量保存即为覆盖更新。 */
    fun nameFor(book: Book): String = md5(book.bookUrl) + ".jpg"

    fun isImage(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val b0 = bytes[0].toInt() and 0xff
        val b1 = bytes[1].toInt() and 0xff
        if (b0 == 0xff && b1 == 0xd8) return true
        if (b0 == 0x89 && b1 == 0x50) return true
        val head = String(bytes, 0, 4, Charsets.US_ASCII)
        if (head == "RIFF" && bytes.size >= 12 &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
        ) return true
        if (head.startsWith("GIF8")) return true
        return false
    }

    private fun md5(text: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            bytes.forEach { append("%02x".format(it.toInt() and 0xff)) }
        }
    }
}

package io.legado.app.help.book

import android.content.ContentValues
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.script.rhino.RhinoInterruptError
import com.script.rhino.runScriptWithContext
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.model.CacheBook
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.ImageUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.StringUtils
import io.legado.app.utils.SvgUtils
import io.legado.app.utils.UrlUtil
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.exists
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.onEachParallel
import io.legado.app.utils.onEachParallelSafe
import io.legado.app.utils.postEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.apache.commons.text.similarity.JaccardSimilarity
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Suppress("unused", "ConstPropertyName")
object BookHelp {
    private val downloadDir: File = appCtx.externalFiles
    private const val cacheFolderName = "book_cache"
    private const val cacheImageFolderName = "images"
    private const val cacheEpubFolderName = "epub"
    /** 章节图片清单文件名：非数字前缀，不会被按编号解析成图片。 */
    private const val MANIFEST_NAME = "manifest.json"
    private val downloadImages = ConcurrentHashMap<String, Mutex>()

    val cachePath = FileUtils.getPath(downloadDir, cacheFolderName)

    /**
     * 单本书的缓存目录
     */
    fun getCacheDir(book: Book): File {
        return downloadDir.getFile(cacheFolderName, book.getFolderName())
    }

    fun clearCache() {
        //先停缓存任务，再释放 media3 缓存实例（实例持有目录锁），最后删目录
        CacheBook.close()
        ExoPlayerHelper.releaseAllBookCaches()
        FileUtils.delete(
            FileUtils.getPath(downloadDir, cacheFolderName)
        )
    }

    fun clearCache(book: Book) {
        CacheBook.cacheBookMap[book.bookUrl]?.stop()
        ExoPlayerHelper.releaseBookCaches(book)
        val filePath = FileUtils.getPath(downloadDir, cacheFolderName, book.getFolderName())
        FileUtils.delete(filePath)
    }

    fun updateCacheFolder(oldBook: Book, newBook: Book) {
        val oldFolderName = oldBook.getFolderNameNoCache()
        val newFolderName = newBook.getFolderNameNoCache()
        if (oldFolderName == newFolderName) return
        val oldFolderPath = FileUtils.getPath(
            downloadDir,
            cacheFolderName,
            oldFolderName
        )
        val newFolderPath = FileUtils.getPath(
            downloadDir,
            cacheFolderName,
            newFolderName
        )
        //整个目录要挪走：先释放旧路径的缓存实例，否则新路径会再建一个实例指向同一物理目录
        ExoPlayerHelper.releaseBookMediaCacheOf(File(oldFolderPath))
        FileUtils.move(oldFolderPath, newFolderPath)
    }

    /**
     * 创建目录刷新时的章节缓存迁移器
     */
    fun createChapterCacheMigrator(
        book: Book,
        oldChapters: List<BookChapter>
    ): ChapterCacheMigrator {
        return ChapterCacheMigrator(
            FileUtils.getPath(downloadDir, cacheFolderName, book.getFolderName()),
            oldChapters
        )
    }

    /**
     * 清除已删除书的缓存 解压缓存
     */
    suspend fun clearInvalidCache() {
        withContext(IO) {
            val bookFolderNames = hashSetOf<String>()
            val originNames = hashSetOf<String>()
            appDb.bookDao.all.forEach {
                clearComicCache(it)
                bookFolderNames.add(it.getFolderName())
                if (it.isEpub) originNames.add(it.originName)
            }
            downloadDir.getFile(cacheFolderName)
                .listFiles()?.forEach { bookFile ->
                    //有缓存清单说明是"书已从书架删除、只剩缓存"的目录：
                    //缓存管理页还要靠它列出这书并支持"加入书架/使用缓存"，不能当无效缓存清掉
                    if (!bookFolderNames.contains(bookFile.name) &&
                        !CacheManifestHelper.hasManifest(bookFile)
                    ) {
                        //删目录前先放掉 media3 缓存实例，否则实例会一直指向已删除的目录
                        ExoPlayerHelper.releaseBookMediaCacheOf(bookFile)
                        FileUtils.delete(bookFile.absolutePath)
                    }
                }
            downloadDir.getFile(cacheEpubFolderName)
                .listFiles()?.forEach { epubFile ->
                    if (!originNames.contains(epubFile.name)) {
                        FileUtils.delete(epubFile.absolutePath)
                    }
                }
            FileUtils.delete(ArchiveUtils.TEMP_PATH)
            val filesDir = appCtx.filesDir
            FileUtils.delete("$filesDir/shareBookSource.json")
            FileUtils.delete("$filesDir/shareRssSource.json")
            FileUtils.delete("$filesDir/books.json")
        }
    }

    //清除已经看过的漫画数据
    private fun clearComicCache(book: Book) {
        //只处理漫画
        //为0的时候，不清除已缓存数据
        if (!book.isImage || AppConfig.imageRetainNum == 0) {
            return
        }
        //向前保留设定数量，向后保留预下载数量
        val startIndex = book.durChapterIndex - AppConfig.imageRetainNum
        val endIndex = book.durChapterIndex + AppConfig.preDownloadNum
        val chapterList = appDb.bookChapterDao.getChapterList(book.bookUrl, startIndex, endIndex)
        val imgNames = hashSetOf<String>()
        //获取需要保留章节的图片信息
        chapterList.forEach {
            val content = getContent(book, it)
            if (content != null) {
                val matcher = AppPattern.imgPattern.matcher(content)
                while (matcher.find()) {
                    val src = matcher.group(1) ?: continue
                    val mSrc = NetworkUtils.getAbsoluteURL(it.url, src)
                    imgNames.add("${MD5Utils.md5Encode16(mSrc)}.${getImageSuffix(mSrc)}")
                }
            }
        }
        downloadDir.getFile(
            cacheFolderName,
            book.getFolderName(),
            cacheImageFolderName
        ).listFiles()?.forEach { imgFile ->
            if (!imgNames.contains(imgFile.name)) {
                imgFile.delete()
            }
        }
    }

    suspend fun saveContent(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        content: String
    ) {
        try {
            saveText(book, bookChapter, content)
            //saveImages(bookSource, book, bookChapter, content)
            postEvent(EventBus.SAVE_CONTENT, Pair(book, bookChapter))
        } catch (e: Throwable) {
            e.printStackTrace()
            AppLog.put("保存正文失败 ${book.name} ${bookChapter.title}", e)
        }
    }

    fun saveText(
        book: Book,
        bookChapter: BookChapter,
        content: String
    ) {
        if (content.isEmpty()) return
        //保存文本
        FileUtils.createFileIfNotExist(
            downloadDir,
            cacheFolderName,
            book.getFolderName(),
            bookChapter.getFileName(),
        ).writeText(content)
        if (book.isOnLineTxt && AppConfig.tocCountWords) {
            val wordCount = StringUtils.wordCountFormat(content.length)
            bookChapter.wordCount = wordCount
            appDb.bookChapterDao.upWordCount(bookChapter.bookUrl, bookChapter.url, wordCount)
        }
    }

    fun flowImages(bookChapter: BookChapter, content: String): Flow<String> {
        return flow {
            val matcher = AppPattern.imgPattern.matcher(content)
            while (matcher.find()) {
                val src = matcher.group(1) ?: continue
                val mSrc = NetworkUtils.getAbsoluteURL(bookChapter.url, src)
                emit(mSrc)
            }
        }
    }

    suspend fun saveImages(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        content: String,
        concurrency: Int = AppConfig.threadCount
    ) = coroutineScope {
        // 并发数夹取：设置项允许填任意值（曾出现填 500），但图片下载单张动辄数 MB，
        // 过高的协程数只会堆积内存并争抢 writeImage 的对象锁，反而更慢。
        // 上限与 OkHttp Dispatcher 的单域名并发保持一致，避免"协程排队但请求发不出去"。
        val parallelism = concurrency.coerceIn(1, 32)
        // 按章节正文里图片出现的顺序编号，导出为 001/002/003…… 的连续序列。
        // 编号是唯一跳过依据：目标文件夹已有同编号文件则跳过（保留已有文件、不覆盖、不追加后缀），
        // 否则按原编号写出。绝不比较图片内容——内容相同但编号不同的图片必须各自正常导出，
        // 内容不同但编号相同的图片也不能因内容差异另存为带后缀的新文件。
        // 用 onEachParallelSafe：单张图片下载失败不会取消整个协程作用域，避免"一张失败、后面全丢"
        val srcList = flowImages(bookChapter, content).toList()
        srcList.asFlow().withIndex().onEachParallelSafe(parallelism) { indexed ->
            saveImage(bookSource, book, indexed.value, bookChapter, indexed.index)
        }.collect()
        // 导出完成后写章节清单：断网/清缓存时可用它把"导出编号"还原成原始图片 URL，
        // 从而复用私有缓存链路离线显示已下载图片（清单写失败不影响导出结果）。
        runCatching {
            writeChapterManifest(book, bookChapter, bookSource.bookSourceName, srcList)
        }.onFailure {
            AppLog.put("写入章节图片清单失败 ${book.name} ${bookChapter.title}: ${it.localizedMessage}")
        }
    }

    suspend fun saveImage(
        bookSource: BookSource?,
        book: Book,
        src: String,
        chapter: BookChapter? = null
    ) = saveImage(bookSource, book, src, chapter, -1)

    /**
     * 下载并保存单张图片。
     * @param index 章节内图片序号（从 0 开始）；>=0 时同步导出一份到公共 Download 目录
     */
    suspend fun saveImage(
        bookSource: BookSource?,
        book: Book,
        src: String,
        chapter: BookChapter?,
        index: Int
    ) {
        // 阅读器显示图片时（chapter==null）只负责把图存进私有缓存；缓存命中即直接返回，避免重复下载
        if (chapter == null && isImageExist(book, src)) {
            return
        }
        val mutex = synchronized(this) {
            downloadImages.getOrPut(src) { Mutex() }
        }
        mutex.lock()
        try {
            // 优先复用阅读器已下载到私有缓存的图片（按 MD5(src) 命名，与章节正文 <img> 出现顺序一一对应），
            // 直接按序改名复制出去：零网络、且不受翻页取消影响，从根本上解决"首尾图片遗漏"。
            // 仅当私有缓存未命中（该图尚未被阅读器加载过）时才真正下载，并写回私有缓存。
            val privateFile = getImage(book, src)
            val bytes = if (privateFile.exists() && privateFile.length() > 0) {
                privateFile.readBytes()
            } else {
                // 单张图片最多重试 3 次（间隔 300ms），吸收偶发网络抖动/超时
                val raw = retry(3) {
                    val analyzeUrl = AnalyzeUrl(
                        src, source = bookSource, coroutineContext = currentCoroutineContext()
                    )
                    analyzeUrl.getByteArrayAwait()
                }
                //某些图片被加密，需要进一步解密
                runScriptWithContext {
                    ImageUtils.decode(src, raw, isCover = false, bookSource, book)
                }?.also {
                    if (!checkImage(it)) {
                        // 如果部分图片失效，每次进入正文都会花很长时间再次获取图片数据
                        // 所以无论如何都要将数据写入到文件里
                        AppLog.put("${book.name} ${chapter?.title} 图片 $src 下载错误 数据异常")
                    }
                    // 写回私有缓存，供阅读器直接复用，也供后续"按序复制"再次命中
                    writeImage(book, src, it)
                }
            }
            bytes ?: return
            // 导出到公共 Download/阅读器/<书源名>/<书名>/<章节>/<序号>.后缀（按章节内出现顺序编号）。
            // 是否跳过由 writePublicImage 按"该编号文件是否已存在"决定，与图片内容无关。
            if (chapter != null && index >= 0) {
                writePublicImage(
                    book, chapter, index, src,
                    bookSource?.bookSourceName ?: "未知书源", bytes
                )
            }
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            if (e is RhinoInterruptError && e.cause is CancellationException) {
                return
            }
            currentCoroutineContext().ensureActive()
            val msg = "${book.name} ${chapter?.title} 图片 $src 处理失败\n${e.localizedMessage}"
            AppLog.put(msg, e)
        } finally {
            downloadImages.remove(src)
            mutex.unlock()
        }
    }

    /**
     * 简单重试：成功即返回；被取消(CancellationException)立即上抛；其余异常最多重试 times-1 次，
     * 全部失败则抛出最后一次异常。用于单张图片下载，避免偶发网络抖动导致图片被永久漏存。
     */
    private suspend fun <T> retry(times: Int, block: suspend () -> T): T {
        var lastError: Throwable? = null
        repeat(times) { attempt ->
            try {
                return block()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                lastError = e
                if (attempt < times - 1) delay(300)
            }
        }
        throw lastError ?: IllegalStateException("retry failed")
    }

    fun getImage(book: Book, src: String): File {
        return downloadDir.getFile(
            cacheFolderName,
            book.getFolderName(),
            cacheImageFolderName,
            "${MD5Utils.md5Encode16(src)}.${getImageSuffix(src)}"
        )
    }

    @Synchronized
    fun writeImage(book: Book, src: String, bytes: ByteArray) {
        getImage(book, src).createFileIfNotExist().writeBytes(bytes)
    }

    @Synchronized
    fun isImageExist(book: Book, src: String): Boolean {
        return getImage(book, src).exists()
    }

    fun getImageSuffix(src: String): String {
        return UrlUtil.getSuffix(src, "jpg")
    }

    /**
     * 章节名清洗：去掉文件系统非法字符（/ \ : * ? " < > |）并收敛空白，空名兜底为"未命名章节"
     */
    fun formatChapterName(title: String): String {
        val cleaned = title.replace(Regex("[\\\\/:*?\"<>|]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (cleaned.isEmpty()) "未命名章节" else cleaned
    }

    private fun mimeForSuffix(suffix: String): String = when (suffix.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        else -> "image/jpeg"
    }

    /**
     * 把章节内第 index 张图片（从 0 开始）按 001/002/003…… 命名导出到公共 Download 目录：
     *   Download/阅读器/<书源名>/<格式化书名>/<格式化章节名>/001.jpg
     * 图片字节优先来自阅读器私有缓存（复用已下载的文件，按 MD5(src) 定位），仅在缓存缺失时才下载。
     * Android Q(29) 及以上走 MediaStore（绕开分区存储限制），以下走传统 File。
     * 任何异常/不支持都静默降级——绝不影响 App 自身的私有缓存与阅读体验。
     */
    @Synchronized
    private fun writePublicImage(
        book: Book,
        chapter: BookChapter,
        index: Int,
        src: String,
        bookSourceName: String,
        bytes: ByteArray
    ) {
        try {
            val suffix = getImageSuffix(src)
            val number = (index + 1).toString().padStart(3, '0')
            val name = "$number.$suffix"
            // Download/阅读器/<书源名>/<书名>/<章节名>/
            val relative =
                "阅读器/${formatBookName(bookSourceName)}/${formatBookName(book.name)}/${formatChapterName(chapter.title)}"
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeToMediaStoreDownloads(number, name, relative, suffix, bytes)
            } else {
                writeToFileDownloads(number, name, relative, bytes)
            }
            if (!ok) {
                AppLog.put("图片导出到 Download 失败（已降级为私有缓存） ${book.name} ${chapter.title}")
            }
        } catch (e: Throwable) {
            AppLog.put(
                "图片导出到 Download 失败（已降级为私有缓存） ${book.name} ${chapter.title}: ${e.localizedMessage}"
            )
        }
    }

    @Suppress("NewApi")
    private fun writeToFileDownloads(
        number: String,
        name: String,
        relative: String,
        bytes: ByteArray
    ): Boolean {
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ?: return false
        val dir = File(root, relative)
        if (!dir.exists() && !dir.mkdirs()) return false
        // 仅按编号判存：目录里已有 "<编号>.<任意图片后缀>" 的文件即跳过，
        // 保留已有文件、不覆盖、不追加后缀；图片后缀不同也视为同一编号，避免一个编号出现两个文件。
        if (dir.listFiles()?.any { it.isFile && it.name.startsWith("$number.") } == true) {
            return true
        }
        val file = File(dir, name)
        if (file.exists()) return true
        return runCatching { file.writeBytes(bytes) }.isSuccess
    }

    @Suppress("NewApi")
    private fun writeToMediaStoreDownloads(
        number: String,
        name: String,
        relative: String,
        suffix: String,
        bytes: ByteArray
    ): Boolean {
        val resolver = appCtx.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relPath = Environment.DIRECTORY_DOWNLOADS + "/" + relative
        // 仅按编号判存：目标文件夹已有 "<编号>.<任意图片后缀>" 即跳过，
        // 保留已有文件、不覆盖、不追加后缀；不比较图片内容。
        if (mediaStoreIndexExists(resolver, collection, number, relPath)) {
            return true
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mimeForSuffix(suffix))
            put(MediaStore.Downloads.RELATIVE_PATH, relPath)
        }
        val uri = resolver.insert(collection, values) ?: return false
        // 双保险：MediaStore 在 DISPLAY_NAME 撞名时会自动改成 001(1).jpg 这类带后缀名字。
        // 一旦发生，删掉刚插入的空记录并视为"已存在"跳过，绝不留下带后缀的重复文件。
        val actualName = mediaStoreDisplayName(resolver, uri)
        if (actualName != null && actualName != name) {
            runCatching { resolver.delete(uri, null, null) }
            return true
        }
        return runCatching {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } != null
        }.getOrElse {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }

    /**
     * 仅按编号判断目标文件夹里是否已有该图片：存在 "<编号>.<任意后缀>" 即视为已导出。
     * RELATIVE_PATH 在 MediaStore 中可能带/不带尾部斜杠，两种都精确匹配；
     * 刻意不用前缀 LIKE，避免"第1章"误命中"第10章"等同前缀目录而导致少导出。
     * 绝不比较图片内容：内容相同但编号不同的图必须各自导出，内容不同但编号相同的图也不另存。
     */
    @Suppress("NewApi")
    private fun mediaStoreIndexExists(
        resolver: ContentResolver,
        collection: Uri,
        number: String,
        relPath: String
    ): Boolean {
        val selection =
            "(${MediaStore.Downloads.RELATIVE_PATH} = ? OR ${MediaStore.Downloads.RELATIVE_PATH} = ?) " +
                    "AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
        return runCatching {
            resolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID),
                selection,
                arrayOf(relPath, "$relPath/", "$number.%"),
                null
            )?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
    }

    /** 读取刚插入记录的 DISPLAY_NAME，用于识别系统是否自动加了 (1) 之类的后缀。 */
    @Suppress("NewApi")
    private fun mediaStoreDisplayName(resolver: ContentResolver, uri: Uri): String? =
        runCatching {
            resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val col = cursor.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
                    if (col >= 0 && cursor.moveToFirst()) cursor.getString(col) else null
                }
        }.getOrDefault(null)

    /** 章节图片清单：离线时把"导出编号"还原成原始图片 URL，复用私有缓存链路。 */
    data class ChapterManifest(
        val images: List<String>,   // 按正文 <img> 原始顺序（编号 001 对应 images[0]）
        val count: Int,
        val sourceName: String,
        val chapterTitle: String
    )

    private fun chapterRelative(
        bookSourceName: String,
        book: Book,
        chapter: BookChapter
    ): String =
        "阅读器/${formatBookName(bookSourceName)}/${formatBookName(book.name)}/${formatChapterName(chapter.title)}"

    /**
     * 导出收尾时写章节清单。清单文件名不含数字编号，不会干扰"按编号判存"的导出规则。
     * 写失败只记日志，绝不影响已完成的图片导出。
     */
    suspend fun writeChapterManifest(
        book: Book,
        chapter: BookChapter,
        bookSourceName: String,
        srcList: List<String>
    ) = withContext(IO) {
        if (srcList.isEmpty()) return@withContext
        val json = JSONObject().apply {
            put("v", 1)
            put("book", book.name)
            put("source", bookSourceName)
            put("chapter", chapter.title)
            put("count", srcList.size)
            put("images", JSONArray().apply { srcList.forEach { put(it) } })
        }.toString()
        val relative = chapterRelative(bookSourceName, book, chapter)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeManifestToMediaStore(relative, json)
            } else {
                val root =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (root != null) {
                    val dir = File(root, relative)
                    if ((dir.exists() || dir.mkdirs()) && dir.isDirectory) {
                        File(dir, MANIFEST_NAME).writeText(json, Charsets.UTF_8)
                    }
                }
            }
        }.onFailure {
            if (it is CancellationException) throw it
            AppLog.put("写入章节图片清单失败 ${book.name} ${chapter.title}: ${it.localizedMessage}")
        }
    }

    @Suppress("NewApi")
    private fun writeManifestToMediaStore(relative: String, json: String) {
        val resolver = appCtx.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relPath = Environment.DIRECTORY_DOWNLOADS + "/" + relative
        // 先删同名旧记录，避免系统自动加 (1) 后缀导致下次读不到最新清单
        runCatching {
            resolver.delete(
                collection,
                "${MediaStore.Downloads.DISPLAY_NAME} = ? AND " +
                        "(${MediaStore.Downloads.RELATIVE_PATH} = ? OR ${MediaStore.Downloads.RELATIVE_PATH} = ?)",
                arrayOf(MANIFEST_NAME, relPath, "$relPath/")
            )
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, MANIFEST_NAME)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, relPath)
        }
        val uri = resolver.insert(collection, values) ?: return
        runCatching {
            resolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                ?: throw IOException("openOutputStream 返回空")
        }.getOrElse {
            runCatching { resolver.delete(uri, null, null) }
            AppLog.put("写入章节图片清单失败（已忽略）: ${it.localizedMessage}")
        }
    }

    /** 读取章节清单；不存在或解析失败返回 null，由调用方走"直接读本地文件"兜底。 */
    suspend fun readChapterManifest(
        book: Book,
        chapter: BookChapter,
        bookSourceName: String
    ): ChapterManifest? = withContext(IO) {
        val relative = chapterRelative(bookSourceName, book, chapter)
        val text = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                readManifestFromMediaStore(relative)
            } else {
                val root =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = root?.let { File(it, "$relative/$MANIFEST_NAME") }
                if (file != null && file.isFile) file.readText(Charsets.UTF_8) else null
            }
        }.getOrElse {
            if (it is CancellationException) throw it
            AppLog.put("读取章节图片清单失败 ${book.name} ${chapter.title}: ${it.localizedMessage}")
            null
        } ?: return@withContext null
        runCatching {
            val obj = JSONObject(text)
            val arr = obj.optJSONArray("images") ?: return@runCatching null
            val images = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "")
                if (s.isNotBlank()) images.add(s)
            }
            if (images.isEmpty()) null else ChapterManifest(
                images,
                images.size,
                obj.optString("source", ""),
                obj.optString("chapter", "")
            )
        }.getOrElse {
            if (it is CancellationException) throw it
            AppLog.put("解析章节图片清单失败 ${book.name} ${chapter.title}: ${it.localizedMessage}")
            null
        }
    }

    @Suppress("NewApi")
    private fun readManifestFromMediaStore(relative: String): String? {
        val resolver = appCtx.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relPath = Environment.DIRECTORY_DOWNLOADS + "/" + relative
        return resolver.query(
            collection,
            arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
            "${MediaStore.Downloads.DISPLAY_NAME} LIKE ? AND " +
                    "(${MediaStore.Downloads.RELATIVE_PATH} = ? OR ${MediaStore.Downloads.RELATIVE_PATH} = ?)",
            arrayOf("manifest%.json", relPath, "$relPath/"),
            "${MediaStore.Downloads._ID} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(MediaStore.Downloads._ID)
            if (idCol < 0 || !cursor.moveToFirst()) return@use null
            val uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol))
            resolver.openInputStream(uri)?.use { it.readBytes() }?.toString(Charsets.UTF_8)
        }
    }

    /**
     * 整书一次扫描：返回 "格式化章节名 -> 已导出图片数"，供章节目录显示独立的「已下载」标记。
     * 性能红线：绝不逐章查询；Q+ 每个候选书源名只做一次 RELATIVE_PATH 前缀查询，Q- 只 listFiles 一次书目录。
     */
    suspend fun listDownloadedChapters(book: Book, bookSourceName: String): Map<String, Int> =
        withContext(IO) {
            val result = HashMap<String, Int>()
            runCatching {
                val candidates = linkedSetOf(bookSourceName, "未知书源", book.origin)
                for (candidate in candidates) {
                    val prefix = "阅读器/${formatBookName(candidate)}/${formatBookName(book.name)}"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val resolver = appCtx.contentResolver
                        val collection =
                            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        val relPrefix = "${Environment.DIRECTORY_DOWNLOADS}/$prefix/"
                        resolver.query(
                            collection,
                            arrayOf(
                                MediaStore.Downloads.RELATIVE_PATH,
                                MediaStore.Downloads.DISPLAY_NAME
                            ),
                            "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                            arrayOf("$relPrefix%"),
                            null
                        )?.use { cursor ->
                            val relCol = cursor.getColumnIndex(MediaStore.Downloads.RELATIVE_PATH)
                            val nameCol = cursor.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
                            while (cursor.moveToNext()) {
                                val rel =
                                    (if (relCol >= 0) cursor.getString(relCol) else null) ?: continue
                                val chapterDir = rel.removePrefix(relPrefix).trimEnd('/')
                                if (chapterDir.isEmpty() || chapterDir.contains('/')) continue
                                val name =
                                    (if (nameCol >= 0) cursor.getString(nameCol) else null)
                                        ?: continue
                                val num = name.substringBefore('.').toIntOrNull()
                                if (num != null && num >= 1) {
                                    result[chapterDir] = (result[chapterDir] ?: 0) + 1
                                }
                            }
                        }
                    } else {
                        val root = Environment
                            .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        if (root != null) {
                            File(root, prefix).listFiles()?.forEach { dir ->
                                if (!dir.isDirectory) return@forEach
                                val n = dir.listFiles()?.count { f ->
                                    f.isFile && (f.name.substringBefore('.').toIntOrNull() ?: 0) >= 1
                                } ?: 0
                                if (n > 0) result[dir.name] = maxOf(result[dir.name] ?: 0, n)
                            }
                        }
                    }
                }
            }.onFailure {
                if (it is CancellationException) throw it
                AppLog.put("扫描已下载章节失败 ${book.name}: ${it.localizedMessage}")
            }
            result
        }

    /** 多个候选书源名下做精确查询（不使用 LIKE 全表扫描），命中即返回。 */
    suspend fun findLocalImagesWithCandidates(
        book: Book,
        chapter: BookChapter,
        bookSourceName: String
    ): Map<Int, String> = withContext(IO) {
        val candidates = linkedSetOf(bookSourceName, "未知书源", book.origin)
        for (candidate in candidates) {
            val hit = runCatching {
                findDownloadedChapterImage(book, chapter, candidate)
            }.getOrElse {
                if (it is CancellationException) throw it
                AppLog.put("本章本地图查询异常 ${book.name} ${chapter.title}: ${it.localizedMessage}")
                emptyMap()
            }
            if (hit.isNotEmpty()) {
                AppLog.put("本章本地图命中目录（书源名=$candidate）：${hit.size} 个编号")
                return@withContext hit
            }
        }
        emptyMap()
    }

    /**
     * 把 MediaStore 的 content:// URI 落地成 App 私有缓存里的真实文件路径，
     * 供无清单的历史章节直接交给 Glide 渲染（避免 Glide 对 content URI 支持的不确定性）。
     */
    suspend fun materializeLocalImages(
        book: Book,
        chapter: BookChapter,
        localMap: Map<Int, String>
    ): Map<Int, String> = withContext(IO) {
        val result = LinkedHashMap<Int, String>()
        val dir = File(appCtx.cacheDir, "offline_manga")
        if (!dir.exists()) dir.mkdirs()
        localMap.toSortedMap().forEach { (index, path) ->
            runCatching {
                if (path.startsWith("content://")) {
                    val target = File(dir, "${MD5Utils.md5Encode16(path)}.img")
                    if (!target.exists() || target.length() <= 0) {
                        appCtx.contentResolver.openInputStream(Uri.parse(path))?.use { input ->
                            FileOutputStream(target).use { output -> input.copyTo(output) }
                        } ?: return@runCatching
                    }
                    if (target.exists() && target.length() > 0) {
                        result[index] = target.absolutePath
                    }
                } else {
                    val f = File(path)
                    if (f.exists() && f.length() > 0) result[index] = f.absolutePath
                }
            }.onFailure {
                if (it is CancellationException) throw it
                AppLog.put(
                    "离线图片落地失败 ${book.name} ${chapter.title} #${index + 1}: ${it.localizedMessage}"
                )
            }
        }
        result
    }

    /**
     * 查找本章在公共 Download/阅读器 目录下已导出的图片，返回 "章节内图片序号(index, 0 基) -> 本地路径/Content URI"。
     * 仅在打开漫画章节时调用一次：Q+ 做一次 MediaStore 查询、Q- 做一次 listFiles，均为 O(1)，不阻塞主线程。
     * 编号规则与导出一致：第 index 张图对应文件名 "<index+1>.<任意后缀>"（如 001.jpg）。
     * 历史残留的 "001(1).jpg" 等带后缀文件因编号解析失败被忽略，安全降级为原网络加载。
     */
    @Suppress("NewApi")
    suspend fun findDownloadedChapterImage(
        book: Book,
        chapter: BookChapter,
        bookSourceName: String
    ): Map<Int, String> = withContext(IO) {
        val result = hashMapOf<Int, String>()
        val relative =
            "阅读器/${formatBookName(bookSourceName)}/${formatBookName(book.name)}/${formatChapterName(chapter.title)}"
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = appCtx.contentResolver
                val collection =
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val relPath = Environment.DIRECTORY_DOWNLOADS + "/" + relative
                val selection =
                    "(${MediaStore.Downloads.RELATIVE_PATH} = ? OR ${MediaStore.Downloads.RELATIVE_PATH} = ?)"
                resolver.query(
                    collection,
                    arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
                    selection,
                    arrayOf(relPath, "$relPath/"),
                    null
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndex(MediaStore.Downloads._ID)
                    val nameCol = cursor.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
                    while (cursor.moveToNext()) {
                        val name = if (nameCol >= 0) cursor.getString(nameCol) else null
                        if (name.isNullOrEmpty()) continue
                        val num = name.substringBefore('.').toIntOrNull()
                        if (num != null && num >= 1 && idCol >= 0) {
                            val uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol))
                            result[num - 1] = uri.toString()
                        }
                    }
                }
            } else {
                val root =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        ?: return@withContext result
                val dir = File(root, relative)
                dir.listFiles()?.forEach { f ->
                    if (!f.isFile) return@forEach
                    val num = f.name.substringBefore('.').toIntOrNull()
                    if (num != null && num >= 1) {
                        result[num - 1] = f.absolutePath
                    }
                }
            }
        }.onFailure {
            AppLog.put("查找本地已下载图片失败 ${book.name} ${chapter.title}: ${it.localizedMessage}")
        }
        result
    }

    /**
     * 把公共 Download 目录里已导出的本地图片导入到阅读器私有缓存（book_cache/.../images/<MD5(src)>）。
     * 导入成功后，渲染层按原逻辑从私有缓存命中加载，零网络、零重复下载。
     * 仅当私有缓存尚不存在该图时才读本地文件；本地文件读取失败则静默降级，由上层继续走网络下载。
     */
    @Suppress("NewApi")
    suspend fun importLocalImageToCache(book: Book, src: String, localPath: String): Boolean =
        // 读文件/ContentResolver 全部切到 IO，绝不在渲染层的主线程协程里做磁盘 IO。
        withContext(IO) {
            val target = getImage(book, src)
            if (target.exists() && target.length() > 0) return@withContext false
            runCatching {
                val bytes = if (localPath.startsWith("content://")) {
                    appCtx.contentResolver.openInputStream(Uri.parse(localPath))
                        ?.use { it.readBytes() }
                } else {
                    val f = File(localPath)
                    if (f.exists() && f.length() > 0) f.readBytes() else null
                }
                if (bytes == null || bytes.isEmpty()) return@withContext false
                writeImage(book, src, bytes)
                true
            }.getOrElse {
                AppLog.put("导入本地图片到缓存失败 ${book.name} $src: ${it.localizedMessage}")
                false
            }
        }

    /** 章节级本地图准备结果，供上层做"全章就绪"提示与预加载复用。 */
    data class ChapterImagePrepare(
        val total: Int,                       // 本章图片总数
        val localHit: Int,                    // Download 目录中命中并尝试导入的张数
        val imported: Int,                    // 本次成功导入私有缓存的张数
        val ready: Int,                       // 私有缓存中已就绪（存在且非空）的张数
        val readyPaths: Map<String, String>   // src -> 私有缓存文件绝对路径
    )

    /**
     * 导出/查找共用的书源名：与两个 ViewModel 的导出逻辑保持同一套回退，
     * 避免导出用真实书源名、查找却用 "未知书源" 导致目录对不上。
     */
    fun chapterExportSourceName(book: Book, preferred: BookSource? = null): String =
        (preferred ?: book.getBookSource())?.bookSourceName?.takeIf { it.isNotBlank() }
            ?: "未知书源"

    /**
     * 章节内容就绪时一次性准备本章图片（**这是"Download 已下载优先"的真正接入点**）：
     * 1) 一次查询 Download/阅读器 下本章已导出的本地图（编号 -> 路径/URI）；
     * 2) 按正文 <img> 序列编号对齐，把命中的本地图批量导入私有缓存；
     * 3) 统计每张图的私有缓存文件是否就绪，返回给上层用于全章就绪提示与预加载。
     *
     * 之所以必须放在章节级而非渲染时逐张判断：导出链路保证私有缓存里已有同图，
     * 渲染层第一步就会缓存命中直接返回，逐张导入分支永远不会执行（形同死代码）。
     * 本函数在图片被渲染/预加载之前就把本地图就位，从根本上绕开该短路。
     *
     * 全程 IO、顺序处理（不占满 IO 池）、支持取消、逐张异常兜底、绝不主动联网。
     */
    suspend fun prepareChapterImages(
        book: Book,
        chapter: BookChapter,
        bookSourceName: String,
        srcList: List<String>,
        knownLocalMap: Map<Int, String>? = null
    ): ChapterImagePrepare = withContext(IO) {
        val total = srcList.size
        val readyPaths = HashMap<String, String>(total)
        if (total == 0) {
            return@withContext ChapterImagePrepare(0, 0, 0, 0, readyPaths)
        }

        // 精确路径优先；为空时再按历史可能用到的书源名各做一次精确查询（都是索引匹配，
        // 不用 RELATIVE_PATH LIKE 全表扫描，避免大量遗留文件时拖慢查询）。
        val localMap = knownLocalMap
            ?: findLocalImagesWithCandidates(book, chapter, bookSourceName)
        val expectDir =
            "Download/阅读器/${formatBookName(bookSourceName)}/${formatBookName(book.name)}/${formatChapterName(chapter.title)}"

        var localHit = 0
        var imported = 0
        var ready = 0
        srcList.forEachIndexed { index, src ->
            currentCoroutineContext().ensureActive()   // 章节切换/退出时及时停下
            runCatching {
                var file = getImage(book, src)
                if (!file.exists() || file.length() <= 0) {
                    val local = localMap[index]
                    if (!local.isNullOrEmpty()) {
                        localHit++
                        if (importLocalImageToCache(book, src, local)) {
                            imported++
                        }
                        file = getImage(book, src)
                    }
                }
                if (file.exists() && file.length() > 0) {
                    readyPaths[src] = file.absolutePath
                    ready++
                }
            }.onFailure {
                AppLog.put("本章图片准备失败 ${book.name} ${chapter.title} #${index + 1}: ${it.localizedMessage}")
            }
        }
        AppLog.put(
            "本章图片准备 ${book.name} ${chapter.title}：本地命中 $localHit/$total，" +
                    "导入成功 $imported，缓存就绪 $ready/$total，期望目录 $expectDir"
        )
        ChapterImagePrepare(total, localHit, imported, ready, readyPaths)
    }

    @Throws(IOException::class, FileNotFoundException::class)
    fun getEpubFile(book: Book): ZipFile {
        val uri = book.getLocalUri()
        if (uri.isContentScheme()) {
            FileUtils.createFolderIfNotExist(downloadDir, cacheEpubFolderName)
            val path = FileUtils.getPath(downloadDir, cacheEpubFolderName, book.originName)
            val file = File(path)
            val doc = DocumentFile.fromSingleUri(appCtx, uri)
                ?: throw IOException("文件不存在")
            if (!file.exists() || doc.lastModified() > book.latestChapterTime) {
                LocalBook.getBookInputStream(book).use { inputStream ->
                    FileOutputStream(file).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            }
            return ZipFile(file)
        }
        return ZipFile(uri.path)
    }

    /**
     * 获取本地书籍文件的ParcelFileDescriptor
     *
     * @param book
     * @return
     */
    @Throws(IOException::class, FileNotFoundException::class)
    fun getBookPFD(book: Book): ParcelFileDescriptor? {
        val uri = book.getLocalUri()
        return if (uri.isContentScheme()) {
            appCtx.contentResolver.openFileDescriptor(uri, "r")
        } else {
            ParcelFileDescriptor.open(File(uri.path!!), ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }

    fun getChapterFiles(book: Book): HashSet<String> {
        val fileNames = hashSetOf<String>()
        if (book.isLocalTxt) {
            return fileNames
        }
        FileUtils.createFolderIfNotExist(
            downloadDir,
            subDirs = arrayOf(cacheFolderName, book.getFolderName())
        ).list()?.let {
            fileNames.addAll(it)
        }
        return fileNames
    }

    /**
     * 批量获取指定书籍的缓存文件
     * 只扫描指定的文件夹列表，避免扫描整个book_cache目录
     * 用于优化离线缓存界面的加载速度
     * @param folderNames 需要扫描的文件夹名列表
     * @return Map<文件夹名, 文件列表>
     */
    fun getCacheFiles(folderNames: Set<String>): Map<String, HashSet<String>> {
        val result = mutableMapOf<String, HashSet<String>>()
        if (folderNames.isEmpty()) return result
        
        val cacheDir = File(downloadDir, cacheFolderName)
        if (!cacheDir.exists() || !cacheDir.isDirectory) {
            return result
        }
        
        folderNames.forEach { folderName ->
            val bookFolder = File(cacheDir, folderName)
            if (bookFolder.exists() && bookFolder.isDirectory) {
                val files = hashSetOf<String>()
                bookFolder.list()?.let { files.addAll(it) }
                result[folderName] = files
            }
        }
        return result
    }

    /**
     * 检测该章节是否下载
     */
    fun hasContent(book: Book, bookChapter: BookChapter): Boolean {
        if (book.isVideo || book.isAudio) {
            // 音视频章节的离线内容是媒体文件：判定要走"缓存时用的那个地址"，
            // 章节表里的地址可能已过期或被新解析结果覆盖，只看它会误判成没缓存
            return CacheManifestHelper.cachedMediaUrl(book, bookChapter) != null
        }
        return if (book.isLocalTxt ||
            (bookChapter.isVolume && bookChapter.url.startsWith(bookChapter.title))
        ) {
            true
        } else {
            //标题/序号被目录刷新改过时，缓存文件名对不上当前章节，交给清单按缓存当时的名字找回
            CacheManifestHelper.cachedTextFileName(book, bookChapter) != null
        }
    }

    /**
     * 检测图片是否下载
     */
    fun hasImageContent(book: Book, bookChapter: BookChapter): Boolean {
        if (!hasContent(book, bookChapter)) {
            return false
        }
        var ret = true
        val op = BitmapFactory.Options()
        op.inJustDecodeBounds = true
        getContent(book, bookChapter)?.let {
            val matcher = AppPattern.imgPattern.matcher(it)
            while (matcher.find()) {
                val src = matcher.group(1)!!
                val image = getImage(book, src)
                if (!image.exists()) {
                    ret = false
                    continue
                }
                BitmapFactory.decodeFile(image.absolutePath, op)
                if (op.outWidth < 1 && op.outHeight < 1) {
                    if (SvgUtils.getSize(image.absolutePath) != null) {
                        continue
                    }
                    ret = false
                    image.delete()
                }
            }
        }
        return ret
    }

    private fun checkImage(bytes: ByteArray): Boolean {
        val op = BitmapFactory.Options()
        op.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, op)
        if (op.outWidth < 1 && op.outHeight < 1) {
            return SvgUtils.getSize(ByteArrayInputStream(bytes)) != null
        }
        return true
    }

    /**
     * 读取章节内容
     */
    fun getContent(book: Book, bookChapter: BookChapter): String? {
        //标题/序号变过时按当前名字找不到文件，清单里记着缓存当时的名字
        val file = CacheManifestHelper.cachedTextFile(book, bookChapter) ?: downloadDir.getFile(
            cacheFolderName,
            book.getFolderName(),
            bookChapter.getFileName()
        )
        if (file.exists()) {
            val string = file.readText()
            if (string.isEmpty()) {
                return null
            }
            return string
        }
        if (book.isLocal) {
            val string = LocalBook.getContent(book, bookChapter)
            if (string != null && book.isEpub) {
                saveText(book, bookChapter, string)
            }
            return string
        }
        return null
    }

    /**
     * 删除章节内容
     */
    fun delContent(book: Book, bookChapter: BookChapter) {
        FileUtils.createFileIfNotExist(
            downloadDir,
            cacheFolderName,
            book.getFolderName(),
            bookChapter.getFileName()
        ).delete()
        //删掉缓存后清单里的"已缓存"要与实际一致
        CacheManifestHelper.refreshAsync(book)
    }

    /**
     * 设置是否禁用正文的去除重复标题,针对单个章节
     */
    fun setRemoveSameTitle(book: Book, bookChapter: BookChapter, removeSameTitle: Boolean) {
        val fileName = bookChapter.getFileName("nr")
        val contentProcessor = ContentProcessor.get(book)
        if (removeSameTitle) {
            val path = FileUtils.getPath(
                downloadDir,
                cacheFolderName,
                book.getFolderName(),
                fileName
            )
            contentProcessor.removeSameTitleCache.remove(fileName)
            File(path).delete()
        } else {
            FileUtils.createFileIfNotExist(
                downloadDir,
                cacheFolderName,
                book.getFolderName(),
                fileName
            )
            contentProcessor.removeSameTitleCache.add(fileName)
        }
    }

    /**
     * 获取是否去除重复标题
     */
    fun removeSameTitle(book: Book, bookChapter: BookChapter): Boolean {
        val path = FileUtils.getPath(
            downloadDir,
            cacheFolderName,
            book.getFolderName(),
            bookChapter.getFileName("nr")
        )
        return !File(path).exists()
    }

    /**
     * 格式化书名
     */
    fun formatBookName(name: String): String {
        return name
            .replace(AppPattern.nameRegex, "")
            .trim { it <= ' ' }
    }

    /**
     * 格式化作者
     */
    fun formatBookAuthor(author: String): String {
        return author
            .replace(AppPattern.authorRegex, "")
            .trim { it <= ' ' }
    }

    private val jaccardSimilarity by lazy {
        JaccardSimilarity()
    }

    /**
     * 根据目录名获取当前章节
     */
    fun getDurChapter(
        oldDurChapterIndex: Int,
        oldDurChapterName: String?,
        newChapterList: List<BookChapter>,
        oldChapterListSize: Int = 0
    ): Int {
        if (oldDurChapterIndex <= 0) return 0
        if (newChapterList.isEmpty()) return oldDurChapterIndex
        val oldChapterNum = getChapterNum(oldDurChapterName)
        val oldName = getPureChapterName(oldDurChapterName)
        val newChapterSize = newChapterList.size
        val durIndex =
            if (oldChapterListSize == 0) oldDurChapterIndex
            else oldDurChapterIndex * oldChapterListSize / newChapterSize
        val min = max(0, min(oldDurChapterIndex, durIndex) - 10)
        val max = min(newChapterSize - 1, max(oldDurChapterIndex, durIndex) + 10)
        var nameSim = 0.0
        var newIndex = 0
        var newNum = 0
        if (oldName.isNotEmpty()) {
            for (i in min..max) {
                val newName = getPureChapterName(newChapterList[i].title)
                val temp = jaccardSimilarity.apply(oldName, newName)
                if (temp > nameSim) {
                    nameSim = temp
                    newIndex = i
                }
            }
        }
        if (nameSim < 0.96 && oldChapterNum > 0) {
            for (i in min..max) {
                val temp = getChapterNum(newChapterList[i].title)
                if (temp == oldChapterNum) {
                    newNum = temp
                    newIndex = i
                    break
                } else if (abs(temp - oldChapterNum) < abs(newNum - oldChapterNum)) {
                    newNum = temp
                    newIndex = i
                }
            }
        }
        return if (nameSim > 0.96 || abs(newNum - oldChapterNum) < 1) {
            newIndex
        } else {
            min(max(0, newChapterList.size - 1), oldDurChapterIndex)
        }
    }

    fun getDurChapter(
        oldBook: Book,
        newChapterList: List<BookChapter>
    ): Int {
        return oldBook.run {
            getDurChapter(durChapterIndex, durChapterTitle, newChapterList, totalChapterNum)
        }
    }

    private val chapterNamePattern1 by lazy {
        Pattern.compile(
            ".*?第([\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)[章节篇回集话]"
        )
    }

    @Suppress("RegExpSimplifiable")
    private val chapterNamePattern2 by lazy {
        Pattern.compile(
            "^(?:[\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+[,:、])*([\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)(?:[,:、]|\\.[^\\d])"
        )
    }

    private val regexA by lazy {
        return@lazy "\\s".toRegex()
    }

    private fun getChapterNum(chapterName: String?): Int {
        chapterName ?: return -1
        val chapterName1 = StringUtils.fullToHalf(chapterName).replace(regexA, "")
        return StringUtils.stringToInt(
            (
                    chapterNamePattern1.matcher(chapterName1).takeIf { it.find() }
                        ?: chapterNamePattern2.matcher(chapterName1).takeIf { it.find() }
                    )?.group(1)
                ?: "-1"
        )
    }

    private val regexOther by lazy {
        // 所有非字母数字中日韩文字 CJK区+扩展A-F区
        @Suppress("RegExpDuplicateCharacterInClass")
        return@lazy "[^\\w\\u4E00-\\u9FEF〇\\u3400-\\u4DBF\\u20000-\\u2A6DF\\u2A700-\\u2EBEF]".toRegex()
    }

    @Suppress("RegExpUnnecessaryNonCapturingGroup", "RegExpSimplifiable")
    private val regexB by lazy {
        //章节序号，排除处于结尾的状况，避免将章节名替换为空字串
        return@lazy "^.*?第(?:[\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)[章节篇回集话](?!$)|^(?:[\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+[,:、])*(?:[\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)(?:[,:、](?!$)|\\.(?=[^\\d]))".toRegex()
    }

    private val regexC by lazy {
        //前后附加内容，整个章节名都在括号中时只剔除首尾括号，避免将章节名替换为空字串
        return@lazy "(?!^)(?:[〖【《〔\\[{(][^〖【《〔\\[{()〕》】〗\\]}]+)?[)〕》】〗\\]}]$|^[〖【《〔\\[{(](?:[^〖【《〔\\[{()〕》】〗\\]}]+[〕》】〗\\]})])?(?!$)".toRegex()
    }

    private fun getPureChapterName(chapterName: String?): String {
        return if (chapterName == null) "" else StringUtils.fullToHalf(chapterName)
            .replace(regexA, "")
            .replace(regexB, "")
            .replace(regexC, "")
            .replace(regexOther, "")
    }

}

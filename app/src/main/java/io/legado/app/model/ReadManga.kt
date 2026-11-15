package io.legado.app.model

import io.legado.app.constant.AppLog
import io.legado.app.constant.ReadConstants
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.constant.AppConst
import io.legado.app.data.entities.BookSource
import io.legado.app.help.AppWebDav
import io.legado.app.help.ConcurrentRateLimiter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isSameNameAuthor
import io.legado.app.help.book.readSimulating
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.globalExecutor
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.manga.entities.BaseMangaPage
import io.legado.app.ui.book.manga.entities.MangaChapter
import io.legado.app.ui.book.manga.entities.MangaContent
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.book.manga.entities.ReaderLoading
import io.legado.app.utils.mapIndexed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlin.math.min
import java.util.concurrent.ConcurrentHashMap
//漫画阅读模型
@Suppress("MemberVisibilityCanBePrivate")
object ReadManga : CoroutineScope by MainScope() {
    var inBookshelf = false
    var book: Book? = null
    val executor = globalExecutor
    var durChapterIndex = 0 //章节位置
    var chapterSize = 0//总章节
    var durChapterPos = 0
    var chapterChanged = false
    var prevMangaChapter: MangaChapter? = null
    var curMangaChapter: MangaChapter? = null
    var nextMangaChapter: MangaChapter? = null
    var bookSource: BookSource? = null
    var readStartTime: Long = System.currentTimeMillis()
    private val loadingChapters = arrayListOf<Int>()
    var simulatedChapterSize = 0
    var mCallback: Callback? = null
    var preDownloadTask: Job? = null
    val downloadedChapters = hashSetOf<Int>()
    val downloadFailChapters = hashMapOf<Int, Int>()
    val downloadScope = CoroutineScope(SupervisorJob() + IO)
    val preDownloadSemaphore = Semaphore(ReadConstants.PRE_DOWNLOAD_CONCURRENCY)
    // 章节级图片准备任务：章节切换时取消，避免旧章节结果串到新章节
    private var chapterPrepareJob: Job? = null
    private var chapterReadyNotified = false
    // src -> 私有缓存文件绝对路径：由章节级批量准备填充，供预加载直接复用本地文件（零磁盘 IO）
    val chapterReadyFiles = ConcurrentHashMap<String, String>()
    var rateLimiter = ConcurrentRateLimiter(null)
    val mangaContents get() = buildMangaContent()
    val hasNextChapter get() = durChapterIndex < simulatedChapterSize - 1
    //重置阅读数据
    fun resetData(book: Book) {
        // 换书前落库上一本书未入库的阅读会话
        ReadSessionRecorder.flush()
        ReadManga.book = book
        readStartTime = System.currentTimeMillis()
        chapterSize = appDb.bookChapterDao.getChapterCount(book.bookUrl)
        simulatedChapterSize = if (book.readSimulating()) {
            book.simulatedTotalChapterNum()
        } else {
            chapterSize
        }
        durChapterIndex = book.durChapterIndex
        durChapterPos = book.durChapterPos
        clearMangaChapter()
        upWebBook(book)
        synchronized(this) {
            loadingChapters.clear()
            downloadedChapters.clear()
            downloadFailChapters.clear()
        }
    }
    //更新阅读数据
    fun upData(book: Book) {
        ReadManga.book = book
        chapterSize = appDb.bookChapterDao.getChapterCount(book.bookUrl)
        simulatedChapterSize = if (book.readSimulating()) {
            book.simulatedTotalChapterNum()
        } else {
            chapterSize
        }

        if (durChapterIndex != book.durChapterIndex) {
            durChapterIndex = book.durChapterIndex
            durChapterPos = book.durChapterPos
            clearMangaChapter()
        }
        upWebBook(book)
        synchronized(this) {
            loadingChapters.clear()
            downloadedChapters.clear()
            downloadFailChapters.clear()
        }
    }
    //更新网络书籍
    fun upWebBook(book: Book) {
        appDb.bookSourceDao.getBookSource(book.origin)?.let {
            bookSource = it
            rateLimiter = ConcurrentRateLimiter(it)
        } ?: let {
            bookSource = null
        }
    }
    //清除当前章节内容
    fun clearMangaChapter() {
        prevMangaChapter = null
        curMangaChapter = null
        nextMangaChapter = null
    }

    /**
     * 翻页心跳：只延长内存中的打开会话，不逐页写库。
     * 会话在暂停/退出/换书/跨度过长时由 [ReadSessionRecorder] 统一落库。
     */
    fun upReadTime() {
        val book = book ?: return
        if (!AppConfig.enableReadRecord) {
            return
        }
        val now = System.currentTimeMillis()
        readStartTime = now
        ReadSessionRecorder.onReadTick(
            deviceId = AppConst.androidId,
            bookName = book.name,
            bookAuthor = book.author,
            chapterTitle = book.durChapterTitle.orEmpty(),
            now = now
        )
    }

    /** 阅读暂停/退出：落库当前会话 */
    fun flushReadTime() {
        if (!AppConfig.enableReadRecord) {
            return
        }
        ReadSessionRecorder.flush()
    }

    fun markReadStart() {
        val book = book ?: return
        if (!AppConfig.enableReadRecord) {
            return
        }
        val now = System.currentTimeMillis()
        readStartTime = now
        ReadSessionRecorder.onReadStart(
            deviceId = AppConst.androidId,
            bookName = book.name,
            bookAuthor = book.author,
            chapterTitle = book.durChapterTitle.orEmpty(),
            now = now
        )
    }

    @Synchronized
    private fun addLoading(index: Int): Boolean {
        if (loadingChapters.contains(index)) return false
        loadingChapters.add(index)
        return true
    }

    @Synchronized
    fun removeLoading(index: Int) {
        loadingChapters.remove(index)
    }
    //加载当前章节前后2章
    fun loadContent() {
        clearMangaChapter()
        loadContent(durChapterIndex)
        loadNearChapterContent()
    }
    //加载或更新章节内容
    fun loadOrUpContent() {
        if (curMangaChapter == null) {
            loadContent(durChapterIndex)
        } else {
            mCallback?.upContent()
        }
        loadNearChapterContent()
    }

    private fun loadNearChapterContent() {
        if (nextMangaChapter == null) {
            loadContent(durChapterIndex + 1)
        }
        if (prevMangaChapter == null) {
            loadContent(durChapterIndex - 1)
        }
    }
    //加载章节内容
    private fun loadContent(index: Int) {
        Coroutine.async {
            val book = book!!
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, index) ?: return@async
            if (addLoading(index)) {
                BookHelp.getContent(book, chapter)?.let {
                    contentLoadFinish(chapter, it)
                } ?: run {
                    download(downloadScope, chapter)
                }
            }
        }.onError {
            AppLog.put("加载正文出错\n${it.localizedMessage}")
        }
    }

    /**
     * 内容加载完成
     */
    suspend fun contentLoadFinish(
        chapter: BookChapter,
        content: String?,
        errorMsg: String = "加载内容失败",
        canceled: Boolean = false
    ) {
        removeLoading(chapter.index)
        if (canceled || chapter.index !in durChapterIndex - 1..durChapterIndex + 1) {
            return
        }
        when (val offset = chapter.index - durChapterIndex) {
            0 -> {
                if (content == null) {
                    // 断网 / 清缓存后正文不可用：先尝试用 Download 里已导出的图片离线打开本章
                    if (tryLoadOfflineChapter(chapter)) return
                    mCallback?.loadFail(errorMsg)
                    return
                }
                if (content.isEmpty() && !chapter.isVolume) {
                    mCallback?.loadFail("正文内容为空")
                    return
                }
                val mangaChapter = getManageChapter(chapter, content)
                if (mangaChapter.imageCount == 0 && !chapter.isVolume) {
                    mCallback?.loadFail("正文没有图片")
                    return
                }
                curMangaChapter = mangaChapter
                mCallback?.upContent()
                // 先渲染再异步准备：本地图导入与就绪判定都不阻塞章节显示
                prepareChapterImagesAsync(chapter, content)
            }

            -1, 1 -> {
                if (content == null || (!chapter.isVolume && content.isEmpty())) {
                    return
                }
                val mangaChapter = getManageChapter(chapter, content)
                if (mangaChapter.imageCount == 0 && !chapter.isVolume) {
                    return
                }

                when (offset) {
                    -1 -> prevMangaChapter = mangaChapter
                    1 -> nextMangaChapter = mangaChapter
                }

                mCallback?.upContent()
            }
        }
    }

    /**
     * 章节切换/重新打开时重置图片准备状态，并取消上一章尚未完成的准备任务。
     */
    fun resetChapterImageState() {
        chapterPrepareJob?.cancel()
        chapterPrepareJob = null
        chapterReadyFiles.clear()
        chapterReadyNotified = false
    }

    /**
     * 章节内容就绪后异步准备本章图片：
     * - 一次查询 Download 已导出图并批量导入私有缓存（不主动联网）；
     * - 按"私有缓存文件是否全部就绪"判定全章就绪，**不依赖 Glide 渲染与用户滚动**；
     * - 就绪路径写入 [chapterReadyFiles]，供预加载直接复用本地文件。
     *
     * 这里刻意用正文原始 <img> 序列（不去重）：其下标恰好等于导出编号 - 1，
     * 避免与漫画页 distinctUntilChanged 后的序号错位而贴错图。
     * 全程 IO、支持取消、异常兜底；只有结果仍属当前章节时才写状态/发提示。
     */
    private fun prepareChapterImagesAsync(chapter: BookChapter, content: String) {
        val book = book ?: return
        val targetChapterIndex = chapter.index
        resetChapterImageState()
        chapterPrepareJob = launch(IO) {
            runCatching {
                val srcList = BookHelp.flowImages(chapter, content).toList()
                if (srcList.isEmpty()) return@launch
                val sourceName = BookHelp.chapterExportSourceName(book, bookSource)
                val result = BookHelp.prepareChapterImages(book, chapter, sourceName, srcList)
                // 准备期间用户可能已切章：丢弃过期结果，避免把上一章的提示弹到新章节
                if (targetChapterIndex != durChapterIndex) return@launch
                ensureActive()
                result.readyPaths.forEach { (src, path) -> chapterReadyFiles[src] = path }
                if (!chapterReadyNotified && result.total > 0 && result.ready >= result.total) {
                    chapterReadyNotified = true
                    withContext(Main) {
                        if (targetChapterIndex == durChapterIndex) {
                            mCallback?.onChapterImagesReady()
                        }
                    }
                } else if (result.ready < result.total) {
                    AppLog.put(
                        "本章图片未全部就绪 ${result.ready}/${result.total}（${book.name} ${chapter.title}），" +
                                "缺失部分按原方案按需加载，暂不提示"
                    )
                }
            }.onFailure {
                if (it is CancellationException) return@onFailure
                AppLog.put("本章图片准备异常 ${book.name} ${chapter.title}: ${it.localizedMessage}")
            }
        }
    }

    //构建漫画内容
    private fun buildMangaContent(): MangaContent {
        val items = arrayListOf<BaseMangaPage>()
        var pos = 0
        var curFinish = false
        var nextFinish = false
        prevMangaChapter?.let {
            pos += it.pages.size
            items.addAll(it.pages)
        }
        curMangaChapter?.let {
            curFinish = true
            items.addAll(it.pages)
            durChapterPos = if (it.imageCount > 0) {
                durChapterPos.coerceIn(0, it.imageCount - 1)
            } else {
                0
            }
            pos += durChapterPos
            if (!AppConfig.hideMangaTitle && it.imageCount > 0) {
                pos++
            }
        }
        nextMangaChapter?.let {
            nextFinish = true
            items.addAll(it.pages)
        }
        return MangaContent(pos, items, curFinish, nextFinish)
    }

    /**
     * 加载下一章
     */
    fun moveToNextChapter(toFirst: Boolean = false): Boolean {
        if (durChapterIndex < simulatedChapterSize - 1) {
            if (toFirst) {
                mCallback?.showLoading()
                durChapterPos = 0
            }
            durChapterIndex++
            prevMangaChapter = curMangaChapter
            curMangaChapter = nextMangaChapter
            nextMangaChapter = null
            resetChapterImageState()
            if (curMangaChapter == null) {
                mCallback?.startLoad()
                loadContent(durChapterIndex)
            } else {
                mCallback?.upContent()
            }
            loadContent(durChapterIndex + 1)
            saveRead()
            AppLog.putReaderDebug("moveToNextChapter-curPageChanged()")
            curPageChanged()
            return true
        } else {
            AppLog.putReaderDebug("跳转下一章失败,没有下一章")
            return false
        }
    }
    
    fun moveToPrevChapter(toFirst: Boolean = false): Boolean {
        if (durChapterIndex > 0) {
            if (toFirst) {
                mCallback?.showLoading()
                durChapterPos = 0
            }
            durChapterIndex--
            nextMangaChapter = curMangaChapter
            curMangaChapter = prevMangaChapter
            prevMangaChapter = null
            resetChapterImageState()
            if (curMangaChapter == null) {
                loadContent(durChapterIndex)
            } else {
                mCallback?.upContent()
            }
            loadContent(durChapterIndex - 1)
            saveRead()
            return true
        }
        return false
    }
    
    fun curPageChanged() {
        upReadTime()
        preDownload()
    }
    //保存阅读进度
    fun saveRead(pageChanged: Boolean = false) {
        executor.execute {
            kotlin.runCatching {
                val book = book ?: return@execute
                book.lastCheckCount = 0
                book.durChapterTime = System.currentTimeMillis()
                val chapterChanged = book.durChapterIndex != durChapterIndex
                book.durChapterIndex = durChapterIndex
                book.durChapterPos = durChapterPos
                if (!pageChanged || chapterChanged) {
                    appDb.bookChapterDao.getChapter(book.bookUrl, durChapterIndex)?.let {
                        book.durChapterTitle = it.getDisplayTitle(
                            ContentProcessor.get(book.name, book.origin).getTitleReplaceRules(),
                            book.getUseReplaceRule(),
                            replaceBook = book.toReplaceBook()
                        )
                    }
                }
                appDb.bookDao.update(book)
            }.onFailure {
                AppLog.put("保存漫画阅读进度信息出错\n$it", it)
            }
        }
    }
    //下载网络内容
    private fun downloadNetworkContent(
        bookSource: BookSource,
        scope: CoroutineScope,
        chapter: BookChapter,
        book: Book,
        semaphore: Semaphore?,
        success: suspend (String) -> Unit = {},
        error: suspend () -> Unit = {},
        cancel: suspend () -> Unit = {},
    ) {
        WebBook.getContent(
            scope,
            bookSource,
            book,
            chapter,
            start = CoroutineStart.LAZY,
            executeContext = IO,
            semaphore = semaphore
        ).onSuccess { content ->
            success.invoke(content)
        }.onError {
            error.invoke()
        }.onCancel {
            cancel.invoke()
        }.start()
    }
    //预下载当前章节前后2章，避免用户切换章节时，需要等待下载完成
    private fun preDownload() {
        if (book?.isLocal == true) return
        executor.execute {
            if (AppConfig.preDownloadNum < 2) {
                upToc()
                return@execute
            }
            preDownloadTask?.cancel()
            preDownloadTask = launch(IO) {
                //预下载当前章节前后2章，避免用户切换章节时，需要等待下载完成
                launch {
                    val maxChapterIndex =
                        min(durChapterIndex + AppConfig.preDownloadNum, chapterSize)
                    for (i in durChapterIndex.plus(2)..maxChapterIndex) {
                        if (downloadedChapters.contains(i)) continue
                        if ((downloadFailChapters[i] ?: 0) >= ReadConstants.MAX_DOWNLOAD_FAIL_COUNT) continue
                        downloadIndex(i)
                    }
                }
                launch {
                    val minChapterIndex = durChapterIndex - min(AppConfig.backwardPreDownloadNum, AppConfig.preDownloadNum)
                    for (i in durChapterIndex.minus(2) downTo minChapterIndex) {
                        if (downloadedChapters.contains(i)) continue
                        if ((downloadFailChapters[i] ?: 0) >= ReadConstants.MAX_DOWNLOAD_FAIL_COUNT) continue
                        downloadIndex(i)
                    }
                }
            }
        }
    }

    fun cancelPreDownloadTask() {
        if (curMangaChapter != null && nextMangaChapter != null) {
            preDownloadTask?.cancel()
            downloadScope.coroutineContext.cancelChildren()
        }
    }

    private suspend fun downloadIndex(index: Int) {
        if (index < 0) return
        if (index > chapterSize - 1) {
            upToc()
            return
        }
        val book = book ?: return
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, index) ?: return
        if (BookHelp.hasContent(book, chapter)) {
            downloadedChapters.add(chapter.index)
        } else {
            delay(ReadConstants.PRE_DOWNLOAD_DELAY_MS)
            if (addLoading(index)) {
                download(downloadScope, chapter, preDownloadSemaphore)
            }
        }
    }

    /**
     * 获取正文
     */
    private suspend fun download(
        scope: CoroutineScope,
        chapter: BookChapter,
        semaphore: Semaphore? = null,
    ) {
        val book = book ?: return removeLoading(chapter.index)
        val bookSource = bookSource
        if (bookSource != null) {
            downloadNetworkContent(bookSource, scope, chapter, book, semaphore, success = {
                downloadedChapters.add(chapter.index)
                downloadFailChapters.remove(chapter.index)
                contentLoadFinish(chapter, it)
            }, error = {
                downloadFailChapters[chapter.index] =
                    (downloadFailChapters[chapter.index] ?: 0) + 1
                contentLoadFinish(chapter, null)
            }, cancel = {
                contentLoadFinish(chapter, null, canceled = true)
            })
        } else {
            contentLoadFinish(chapter, null, "加载内容失败 没有书源")
        }
    }

    @Synchronized
    fun upToc() {
        val bookSource = bookSource ?: return
        val book = book ?: return
        if (!book.canUpdate) return
        if (chapterSize - durChapterIndex - 1 >= ReadConstants.TOC_UPDATE_REMAINING_THRESHOLD) return
        if (System.currentTimeMillis() - book.lastCheckTime < ReadConstants.TOC_UPDATE_MIN_INTERVAL_MS) return
        book.lastCheckTime = System.currentTimeMillis()
        val oldBook = book.copy()
        WebBook.getChapterList(this, bookSource, book).onSuccess(IO) { cList ->
            ensureActive()
            if (cList.size > chapterSize) {
                if (oldBook.bookUrl == book.bookUrl) {
                    appDb.bookDao.update(book)
                } else {
                    appDb.bookDao.replace(oldBook, book)
                    BookHelp.updateCacheFolder(oldBook, book)
                }
                appDb.bookChapterDao.delByBook(oldBook.bookUrl)
                appDb.bookChapterDao.insert(*cList.toTypedArray())
                onChapterListUpdated(book, false)
                nextMangaChapter ?: loadContent(durChapterIndex + 1)
            }
        }
    }

    fun uploadProgress(successAction: (() -> Unit)? = null) {
        book?.let {
            launch(IO) {
                AppWebDav.uploadBookProgress(it) {
                    successAction?.invoke()
                }
                ensureActive()
                it.update()
            }
        }
    }

    /**
     * 同步阅读进度
     * 如果当前进度快于服务器进度或者没有进度进行上传，如果慢与服务器进度则执行传入动作
     */
    fun syncProgress(
        newProgressAction: ((progress: BookProgress) -> Unit)? = null,
        uploadSuccessAction: (() -> Unit)? = null,
        syncSuccessAction: (() -> Unit)? = null,
    ) {
        if (!AppConfig.syncBookProgress) return
        val book = book ?: return
        Coroutine.async {
            AppWebDav.getBookProgress(book)
        }.onError {
            AppLog.put("拉取阅读进度失败", it)
        }.onSuccess { progress ->
            if (progress == null || progress.durChapterIndex < book.durChapterIndex ||
                (progress.durChapterIndex == book.durChapterIndex
                        && progress.durChapterPos < book.durChapterPos)
            ) {
                // 服务器没有进度或者进度比服务器快，上传现有进度
                Coroutine.async {
                    AppWebDav.uploadBookProgress(BookProgress(book), uploadSuccessAction)
                    book.update()
                }
            } else if (progress.durChapterIndex > book.durChapterIndex ||
                progress.durChapterPos > book.durChapterPos
            ) {
                // 进度比服务器慢，执行传入动作
                newProgressAction?.invoke(progress)
            } else {
                syncSuccessAction?.invoke()
            }
        }
    }

    fun setProgress(progress: BookProgress) {
        if (progress.durChapterIndex < chapterSize &&
            (durChapterIndex != progress.durChapterIndex
                    || durChapterPos != progress.durChapterPos)
        ) {
            mCallback?.showLoading()
            if (progress.durChapterIndex == durChapterIndex) {
                durChapterPos = progress.durChapterPos
                mCallback?.upContent()
            } else {
                durChapterIndex = progress.durChapterIndex
                durChapterPos = progress.durChapterPos
                loadContent()
            }
            saveRead()
        }
    }

    fun showLoading() {
        mCallback?.showLoading()
    }

    fun loadFail(msg: String, retry: Boolean = true) {
        mCallback?.loadFail(msg, retry)
    }

    fun onChapterListUpdated(newBook: Book, loadContent: Boolean = true) {
        if (newBook.isSameNameAuthor(book)) {
            book = newBook
            chapterSize = newBook.totalChapterNum
            simulatedChapterSize = newBook.simulatedTotalChapterNum()
            if (simulatedChapterSize > 0 && durChapterIndex > simulatedChapterSize - 1) {
                durChapterIndex = simulatedChapterSize - 1
            }
            if (mCallback == null) {
                clearMangaChapter()
            } else if (loadContent) {
                loadContent()
            }
        }
    }

    /**
     * 注册回调
     */
    fun register(cb: Callback) {
        mCallback = cb
    }

    /**
     * 取消注册回调
     */
    fun unregister(cb: Callback) {
        if (mCallback === cb) {
            mCallback = null
        }
        // 页面销毁时一并取消章节图片准备，避免协程继续持有 Activity 回调
        resetChapterImageState()
        preDownloadTask?.cancel()
        preDownloadTask = null
        downloadScope.coroutineContext.cancelChildren()
        coroutineContext.cancelChildren()
    }

    private suspend fun getManageChapter(chapter: BookChapter, content: String): MangaChapter =
        buildMangaChapter(chapter, BookHelp.flowImages(chapter, content).toList(), null)

    /**
     * 由"正文图片 URL 序列"构造漫画章节。srcList 的下标即导出编号 - 1（含相邻重复）。
     * 漫画页要去掉相邻重复图，因此同步记录 "漫画页序号 -> 导出序列下标" 的映射，避免本地图贴错页。
     * knownLocalMap 由离线兜底传入（已查询过），为空时按统一书源名做一次精确查询。
     */
    private suspend fun buildMangaChapter(
        chapter: BookChapter,
        srcList: List<String>,
        knownLocalMap: Map<Int, String>?
    ): MangaChapter {
        val displaySrc = ArrayList<String>(srcList.size)
        val displayToExport = ArrayList<Int>(srcList.size)
        var lastSrc: String? = null
        srcList.forEachIndexed { exportIndex, src ->
            if (src != lastSrc) {
                lastSrc = src
                displaySrc.add(src)
                displayToExport.add(exportIndex)
            }
        }
        val list = displaySrc.mapIndexed { index, src ->
            MangaPage(
                chapterIndex = chapter.index,
                chapterSize = chapterSize,
                mImageUrl = src,
                index = index,
                mChapterName = chapter.title
            )
        }
        val imageCount = list.size

        val bk = book
        val localMap = when {
            knownLocalMap != null -> knownLocalMap
            bk != null -> BookHelp.findLocalImagesWithCandidates(
                bk, chapter, BookHelp.chapterExportSourceName(bk, bookSource)
            )
            else -> emptyMap()
        }
        list.forEach {
            it.imageCount = imageCount
            it.localPath = localMap[displayToExport.getOrNull(it.index) ?: it.index]
        }

        if (AppConfig.hideMangaTitle && imageCount > 0) {
            return MangaChapter(chapter, list, imageCount)
        }
        return MangaChapter(chapter, buildMangaPages(chapter, list, imageCount), imageCount)
    }

    private fun buildMangaPages(
        chapter: BookChapter,
        list: List<MangaPage>,
        imageCount: Int
    ): List<BaseMangaPage> {
        val pages = mutableListOf<BaseMangaPage>()
        if (imageCount == 0 && chapter.isVolume) {
            pages.add(ReaderLoading(chapter.index, -1, chapter.title, true))
        } else {
            pages.add(ReaderLoading(chapter.index, -1, "阅读 ${chapter.title}"))
            pages.addAll(list)
            pages.add(ReaderLoading(chapter.index, imageCount, "已读完 ${chapter.title}"))
        }
        return pages
    }

    /** 无清单的历史章节：按导出编号顺序直接把本地文件当作图片源渲染。 */
    private fun buildOfflineMangaChapter(
        chapter: BookChapter,
        localPaths: Map<Int, String>
    ): MangaChapter {
        val imageCount = localPaths.size
        val list = localPaths.toSortedMap().values.mapIndexed { index, path ->
            MangaPage(
                chapterIndex = chapter.index,
                chapterSize = chapterSize,
                mImageUrl = "offline://${chapter.index}/$index",
                index = index,
                mChapterName = chapter.title
            ).apply {
                this.imageCount = imageCount
                localPath = path
                directLocal = true
            }
        }
        return MangaChapter(chapter, buildMangaPages(chapter, list, imageCount), imageCount)
    }

    /**
     * 断网 / 清缓存后的离线兜底：正文缓存为空且网络失败时，用 Download 里已导出的图片直接打开章节。
     * 有清单 → 还原 URL 序列、先把本地图导入私有缓存再上屏（复用现有渲染链路）；
     * 无清单 → 按编号直接渲染本地文件（历史章节无需重新导出）。
     * 全程 IO、支持取消、异常全兜底；只有结果仍属当前章节时才上屏。
     */
    private suspend fun tryLoadOfflineChapter(chapter: BookChapter): Boolean = withContext(IO) {
        val bk = book ?: return@withContext false
        val targetIndex = chapter.index
        if (targetIndex != durChapterIndex) return@withContext false
        resetChapterImageState()
        val sourceName = BookHelp.chapterExportSourceName(bk, bookSource)
        runCatching {
            val manifest = BookHelp.readChapterManifest(bk, chapter, sourceName)
            val localMap = BookHelp.findLocalImagesWithCandidates(bk, chapter, sourceName)
            if (localMap.isEmpty()) {
                AppLog.put("离线兜底未找到本地图片 ${bk.name} ${chapter.title}")
                return@runCatching false
            }
            ensureActive()
            val offlineCount: Int
            val mangaChapter: MangaChapter
            if (manifest != null && manifest.images.isNotEmpty()) {
                val result = BookHelp.prepareChapterImages(
                    bk, chapter, sourceName, manifest.images, localMap
                )
                result.readyPaths.forEach { (src, path) -> chapterReadyFiles[src] = path }
                offlineCount = result.ready
                mangaChapter = buildMangaChapter(chapter, manifest.images, localMap)
                AppLog.put(
                    "离线兜底(清单) ${bk.name} ${chapter.title}：清单 ${manifest.count} 张，" +
                            "本地 ${localMap.size} 张，缓存就绪 ${result.ready} 张"
                )
            } else {
                val materialized = BookHelp.materializeLocalImages(bk, chapter, localMap)
                if (materialized.isEmpty()) return@runCatching false
                offlineCount = materialized.size
                mangaChapter = buildOfflineMangaChapter(chapter, materialized)
                AppLog.put("离线兜底(直读) ${bk.name} ${chapter.title}：本地 $offlineCount 张")
            }
            if (targetIndex != durChapterIndex) return@runCatching false
            ensureActive()
            withContext(Main) {
                if (targetIndex == durChapterIndex) {
                    curMangaChapter = mangaChapter
                    mCallback?.upContent()
                    mCallback?.onChapterOfflineReady(offlineCount)
                }
            }
            true
        }.getOrElse {
            if (it is CancellationException) throw it
            AppLog.put("离线章节兜底失败 ${bk.name} ${chapter.title}: ${it.localizedMessage}")
            false
        }
    }

    interface Callback {
        fun upContent()
        fun loadFail(msg: String, retry: Boolean = true)
        fun sureNewProgress(progress: BookProgress)
        fun showLoading()
        fun startLoad()
        /** 当前章节所有图片加载完成时回调；默认空实现，避免影响其它实现者 */
        fun onChapterImagesReady() {}
        /** 离线兜底成功时回调，count 为实际可用的本地图片张数 */
        fun onChapterOfflineReady(count: Int) {}
    }
}

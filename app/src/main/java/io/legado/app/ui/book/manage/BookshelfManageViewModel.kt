package io.legado.app.ui.book.manage

import android.app.Application
import androidx.lifecycle.MutableLiveData
import androidx.room.withTransaction
import com.script.rhino.runScriptWithContext
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.BookLocalCover
import io.legado.app.help.book.BookTagManagement
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.SourceCallBack
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.ImageUtils
import io.legado.app.utils.postEvent
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.writeToOutputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class BookshelfManageViewModel(application: Application) : BaseViewModel(application) {
    var groupId: Long = -1L
    var groupName: String? = null
    val batchChangeSourceState = MutableLiveData<Boolean>()
    val batchChangeSourceProcessLiveData = MutableLiveData<String>()
    var batchChangeSourceCoroutine: Coroutine<*>? = null
    val batchSaveCoverState = MutableLiveData<Boolean>()
    val batchSaveCoverProcessLiveData = MutableLiveData<String>()
    var batchSaveCoverCoroutine: Coroutine<*>? = null

    fun upCanUpdate(books: List<Book>, canUpdate: Boolean) {
        execute {
            val array = Array(books.size) {
                books[it].copy(canUpdate = canUpdate).apply {
                    if (!canUpdate) {
                        removeType(BookType.updateError)
                    }
                }
            }
            appDb.bookDao.update(*array)
        }.onFinally {
            // 更新状态影响"不可更新"等智能标签的命中，写库完成后刷新书架标签栏
            postEvent(EventBus.BOOKSHELF_REFRESH, "")
        }
    }

    fun updateBook(vararg book: Book) {
        execute {
            appDb.bookDao.update(*book)
        }.onFinally {
            // 改分组会同时改变原分组与新分组的标签命中数量，必须通知书架重算标签栏
            postEvent(EventBus.BOOKSHELF_REFRESH, "")
        }
    }

    fun deleteBook(books: List<Book>, deleteOriginal: Boolean = false) {
        execute {
            appDb.bookDao.delete(*books.toTypedArray())
            books.forEach {
                if (it.isLocal) {
                    LocalBook.deleteBook(it, deleteOriginal)
                } else {
                    val source = appDb.bookSourceDao.getBookSource(it.origin)
                    SourceCallBack.callBackBook(SourceCallBack.DEL_BOOK_SHELF, source, it)
                }
            }
        }.onFinally {
            postEvent(EventBus.BOOKSHELF_REFRESH, "")
        }
    }

    fun saveAllUseBookSourceToFile(success: (file: File) -> Unit) {
        execute {
            val path = "${context.filesDir}/shareBookSource.json"
            FileUtils.delete(path)
            val file = FileUtils.createFileWithReplace(path)
            val sources = appDb.bookDao.getAllUseBookSource()
            file.outputStream().buffered().use {
                GSON.writeToOutputStream(it, sources)
            }
            file
        }.onSuccess {
            success.invoke(it)
        }.onError {
            context.toastOnUi(it.stackTraceStr)
        }
    }

    /**
     * 批量换源。
     *
     * 单本换源实际上是四件事，缺任何一件用户看到的都是「没换成」：
     * 1. 精确搜索（书名与作者全等）拿到目标源上的新书；
     * 2. 拉到新目录；
     * 3. 把旧书的进度、分组、排序、标签、阅读配置搬到新书上，再 `replace` 删旧插新。
     *    只 `insert` 不删旧，书架里会同时留下原书与副本，看起来就是「点了很多本但书架没变」；
     * 4. 迁移已读缓存目录，否则换源后已下载的正文/图片全部失效。
     *
     * 每本书的结果单独记账，结束时汇总提示：以前失败只写进日志，用户完全不知道 20 本里成了几本。
     */
    /**
     * 批量把网络封面下载到本机，之后离线也能显示，并随备份一起迁移。
     * 已设置自定义封面（非本地标记）的书与本地书跳过；单本失败不影响其它书。
     */
    fun saveCovers(books: List<Book>) {
        batchSaveCoverCoroutine?.cancel()
        batchSaveCoverCoroutine = execute {
            val semaphore = Semaphore(6)
            val success = AtomicInteger()
            val skipped = AtomicInteger()
            val failed = AtomicInteger()
            val processed = AtomicInteger()
            coroutineScope {
                books.map { book ->
                    async {
                        semaphore.withPermit {
                            currentCoroutineContext().ensureActive()
                            when (saveSingleCover(book)) {
                                CoverSaveOutcome.SUCCESS -> success.incrementAndGet()
                                CoverSaveOutcome.SKIPPED -> skipped.incrementAndGet()
                                CoverSaveOutcome.FAILED -> failed.incrementAndGet()
                            }
                            val done = processed.incrementAndGet()
                            batchSaveCoverProcessLiveData.postValue(
                                context.getString(
                                    R.string.batch_save_cover_process,
                                    done, books.size, book.name
                                )
                            )
                        }
                    }
                }.awaitAll()
            }
            Triple(success.get(), skipped.get(), failed.get())
        }.onSuccess {
            context.toastOnUi(
                context.getString(
                    R.string.batch_save_cover_result,
                    it.first, it.second, it.third
                )
            )
        }.onStart {
            batchSaveCoverState.postValue(true)
        }.onFinally {
            batchSaveCoverState.postValue(false)
            postEvent(EventBus.BOOKSHELF_REFRESH, "")
        }
    }

    private enum class CoverSaveOutcome { SUCCESS, SKIPPED, FAILED }

    private suspend fun saveSingleCover(book: Book): CoverSaveOutcome {
        if (book.isLocal) return CoverSaveOutcome.SKIPPED
        val custom = book.customCoverUrl
        if (!custom.isNullOrBlank() && !BookLocalCover.isLocal(custom)) {
            return CoverSaveOutcome.SKIPPED
        }
        val src = book.coverUrl?.trim()?.takeUnless { it.isBlank() }
            ?: return CoverSaveOutcome.SKIPPED
        return runCatching {
            val source = appDb.bookSourceDao.getBookSource(book.origin)
            val raw = AnalyzeUrl(
                src,
                baseUrl = book.tocUrl,
                source = source,
                coroutineContext = currentCoroutineContext()
            ).getByteArrayAwait()
            val bytes = runScriptWithContext {
                ImageUtils.decode(src, raw, true, source, book)
            } ?: raw
            if (!BookLocalCover.isImage(bytes)) {
                error("封面数据不是有效图片")
            }
            val fileName = BookLocalCover.nameFor(book)
            val target = File(BookLocalCover.dir, fileName)
            val tmp = File(target.parentFile, "$fileName.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            appDb.bookDao.update(book.copy(customCoverUrl = BookLocalCover.PREFIX + fileName))
            CoverSaveOutcome.SUCCESS
        }.getOrElse {
            AppLog.put("保存封面失败《${book.name}》：${it.localizedMessage}")
            CoverSaveOutcome.FAILED
        }
    }

    fun changeSource(books: List<Book>, source: BookSource) {
        batchChangeSourceCoroutine?.cancel()
        batchChangeSourceCoroutine = execute {
            val changeSourceDelay = AppConfig.batchChangeSourceDelay * 1000L
            var success = 0
            var notFound = 0
            var failed = 0
            var skipped = 0
            for ((index, book) in books.withIndex()) {
                currentCoroutineContext().ensureActive()
                batchChangeSourceProcessLiveData.postValue(
                    context.getString(
                        R.string.change_source_process, index + 1, books.size, book.name
                    )
                )
                val outcome = if (book.isLocal || book.origin == source.bookSourceUrl) {
                    ChangeSourceOutcome.SKIPPED
                } else {
                    switchSingleSource(book, source)
                }
                when (outcome) {
                    ChangeSourceOutcome.SUCCESS -> success++
                    ChangeSourceOutcome.NOT_FOUND -> notFound++
                    ChangeSourceOutcome.FAILED -> failed++
                    ChangeSourceOutcome.SKIPPED -> skipped++
                }
                // 只要真的发过请求就按用户设置节流，本地书/同源书没请求不必等
                if (outcome != ChangeSourceOutcome.SKIPPED && changeSourceDelay > 0) {
                    delay(changeSourceDelay)
                }
            }
            ChangeSourceSummary(success, notFound, failed, skipped)
        }.onSuccess {
            context.toastOnUi(
                context.getString(
                    R.string.change_source_result, it.success, it.notFound, it.failed, it.skipped
                )
            )
        }.onStart {
            batchChangeSourceState.postValue(true)
        }.onFinally {
            batchChangeSourceState.postValue(false)
            // 换源改了 bookUrl 与来源名，书架标签栏与分组统计都要重算
            postEvent(EventBus.BOOKSHELF_REFRESH, "")
        }
    }

    /** 换一本：搜索 → 详情 → 目录 → 搬属性 → 写库 → 迁缓存。任何一步失败都只影响这一本。 */
    private suspend fun switchSingleSource(book: Book, source: BookSource): ChangeSourceOutcome {
        val newBook = WebBook.preciseSearchAwait(source, book.name, book.author)
            .onFailure {
                AppLog.put("换源搜索失败《${book.name}》\n${it.localizedMessage}", it)
            }.getOrNull() ?: return ChangeSourceOutcome.NOT_FOUND
        runCatching {
            if (newBook.tocUrl.isEmpty()) {
                WebBook.getBookInfoAwait(source, newBook)
            }
        }.onFailure {
            AppLog.put("换源获取书籍详情失败《${book.name}》\n${it.localizedMessage}", it)
            return ChangeSourceOutcome.FAILED
        }
        val toc = WebBook.getChapterListAwait(source, newBook)
            .onFailure {
                AppLog.put("换源获取目录失败《${book.name}》\n${it.localizedMessage}", it)
            }.getOrNull()
        if (toc.isNullOrEmpty()) return ChangeSourceOutcome.FAILED
        book.migrateTo(newBook, toc)
        newBook.removeType(BookType.updateError)
        appDb.withTransaction {
            // 删旧插新：只 insert 会让书架多出一本，原书还停在旧源上
            appDb.bookDao.replace(book, newBook)
            // 旧 bookUrl 的目录必须清掉，否则换源后旧章节残留、章节表越来越臃肿
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookChapterDao.insert(*toc.toTypedArray())
        }
        // 缓存目录名跟着 bookUrl 走，不迁移等于把已读缓存全丢
        BookHelp.updateCacheFolder(book, newBook)
        return ChangeSourceOutcome.SUCCESS
    }

    /**
     * 批量改地址：不搜索、不发网络请求，直接把选中书籍挂到目标书源上。
     *
     * A 模式（replaceBookUrl = false）：只改 origin / originName。bookUrl 不变，
     * 章节、阅读进度、书签、缓存目录（书名前 9 字 + MD5(bookUrl)）全部原样保留。
     * 代价是新书源必须能解析现有 tocUrl —— 目录刷新全走它（WebBook.getChapterList）。
     *
     * B 模式：按「旧前缀 → 新前缀」替换 bookUrl / tocUrl 及章节各 URL 字段。
     * chapters.bookUrl 是指向 books 的外键，只有 onDelete=CASCADE、没有 onUpdate，
     * 而 books 又有 UNIQUE(name, author)，所以既不能 UPDATE 主键、也不能先插后删：
     * 只能先读出章节 → delete 旧书（级联清章节）→ insert 新书 → 章节改 bookUrl 后插回。
     * 缓存目录名随 bookUrl 变化，必须在事务外补一次 updateCacheFolder。
     */
    fun changeUrlDirect(
        books: List<Book>,
        source: BookSource,
        replaceBookUrl: Boolean,
        oldPrefix: String,
        newPrefix: String,
    ) {
        execute {
            var success = 0
            var skipped = 0
            var failed = 0
            if (!replaceBookUrl) {
                val urls = books.filterNot { it.isLocal }.map { it.bookUrl }
                skipped = books.size - urls.size
                if (urls.isNotEmpty()) {
                    // SQLite 单条语句绑定变量上限 999，书架书多时分块提交，避免整批失败
                    val changed = appDb.withTransaction {
                        var count = 0
                        urls.chunked(400).forEach { chunk ->
                            count += appDb.bookDao.updateOriginOfBooks(
                                chunk, source.bookSourceUrl, source.bookSourceName
                            )
                        }
                        count
                    }
                    success = changed
                    skipped += urls.size - changed
                }
            } else {
                val oldP = oldPrefix.trim().trimEnd('/')
                val newP = newPrefix.trim().trimEnd('/')
                val cacheMoves = ArrayList<Pair<Book, Book>>()
                for (book in books) {
                    currentCoroutineContext().ensureActive()
                    if (book.isLocal) {
                        skipped++
                        continue
                    }
                    val newUrl = replaceUrl(book.bookUrl, oldP, newP)
                    // 前缀没命中，或目标地址已被别的书占用（insert 是 REPLACE，会覆盖它）
                    if (newUrl.isNullOrEmpty() || newUrl == book.bookUrl || appDb.bookDao.has(newUrl)) {
                        skipped++
                        continue
                    }
                    runCatching {
                        val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
                        val newTocUrl = (replaceUrl(book.tocUrl, oldP, newP) ?: "")
                            .ifEmpty { newUrl }
                        val newBook = book.copy(
                            bookUrl = newUrl,
                            tocUrl = newTocUrl,
                            origin = source.bookSourceUrl,
                            originName = source.bookSourceName,
                        ).apply { removeType(BookType.updateError) }
                        appDb.withTransaction {
                            appDb.bookDao.delete(book)
                            appDb.bookDao.insert(newBook)
                            val newChapters = chapters.map { chapter ->
                                chapter.copy(
                                    url = replaceUrl(chapter.url, oldP, newP) ?: chapter.url,
                                    baseUrl = replaceUrl(chapter.baseUrl, oldP, newP) ?: chapter.baseUrl,
                                    bookUrl = newUrl,
                                    resourceUrl = replaceUrl(chapter.resourceUrl, oldP, newP),
                                    imgUrl = replaceUrl(chapter.imgUrl, oldP, newP),
                                )
                            }
                            appDb.bookChapterDao.insert(*newChapters.toTypedArray())
                        }
                        cacheMoves.add(book to newBook)
                        success++
                    }.onFailure {
                        AppLog.put("批量改地址失败《${book.name}》\n${it.localizedMessage}", it)
                        failed++
                    }
                }
                // 文件迁移不放在 DB 事务里：失败只丢缓存，不影响已写完的书籍记录
                cacheMoves.forEach { (old, new) ->
                    runCatching { BookHelp.updateCacheFolder(old, new) }.onFailure {
                        AppLog.put("批量改地址迁移缓存失败《${old.name}》\n${it.localizedMessage}", it)
                    }
                }
            }
            ChangeUrlSummary(success, skipped, failed)
        }.onSuccess {
            context.toastOnUi(
                context.getString(R.string.batch_change_url_result, it.success, it.skipped, it.failed)
            )
        }.onFinally {
            postEvent(EventBus.BOOKSHELF_REFRESH, "")
        }
    }

    /** 只替换以旧前缀开头的绝对地址；相对章节 URL 原样保留。 */
    private fun replaceUrl(url: String?, oldPrefix: String, newPrefix: String): String? {
        if (url.isNullOrEmpty() || oldPrefix.isEmpty() || !url.startsWith(oldPrefix)) return url
        return newPrefix + url.substring(oldPrefix.length)
    }

    fun clearCache(books: List<Book>) {
        execute {
            books.forEach {
                BookHelp.clearCache(it)
            }
        }.onSuccess {
            context.toastOnUi(R.string.clear_cache_success)
        }
    }

    /**
     * 批量添加/移除标签。
     *
     * 语义是「动作」而不是「覆盖」：只动用户点到的这几个标签，其余标签原样保留，
     * 因此批量操作不会误抹掉书籍已有的分类。写入全部包在一个事务里，几百本书也只是一次提交；
     * [BookTagManagement.updateTag] 对已经是目标状态的书籍返回 null，天然跳过无效写入。
     *
     * @param books 选中的书籍
     * @param tags 要增删的标签（调用方已去重）
     * @param add true=添加，false=移除
     */
    fun updateTags(books: List<Book>, tags: List<String>, add: Boolean) {
        val normalized = BookTagManagement.mergeTags(emptyList(), tags)
        if (books.isEmpty() || normalized.isEmpty()) return
        execute {
            var changed = 0
            appDb.withTransaction {
                books.forEach { book ->
                    var customTag = book.customTag
                    var bookChanged = false
                    normalized.forEach tagLoop@ { tag ->
                        val write = BookTagManagement.updateTag(customTag, tag, add)
                            ?: return@tagLoop
                        customTag = write.customTag
                        bookChanged = true
                    }
                    if (bookChanged) {
                        appDb.bookDao.updateCustomTag(book.bookUrl, customTag)
                        changed++
                    }
                }
            }
            if (add) {
                registerTagsToGroups(books, normalized)
            }
            changed
        }.onSuccess { count ->
            postEvent(EventBus.BOOKSHELF_REFRESH, "")
            context.toastOnUi(context.getString(R.string.batch_tag_result, count))
        }
    }

    /**
     * 把批量添加的标签登记进书籍所属用户分组的标签配置，并取消其隐藏状态。
     *
     * 两件事都不能省：
     * - 不登记分组配置的话，标签只是"这本书上有"，一旦这本书移除该标签，标签就从标签栏消失了；
     * - 标签若正被 [AppConfig.bookshelfHiddenTags] 隐藏，加了也看不见，看起来就像"没生效"。
     *
     * 分组归属按书籍的 `group` 位掩码逐本求并集，一本在多组的书会被登记到所有相关分组。
     */
    private suspend fun registerTagsToGroups(books: List<Book>, tags: List<String>) {
        val validGroupIds = appDb.bookGroupDao.all.mapTo(HashSet()) { it.groupId }
        val groupIds = books.asSequence()
            .flatMap { book ->
                validGroupIds.asSequence().filter { it > 0 && book.group and it > 0L }
            }
            .toSet()
        if (groupIds.isEmpty()) return

        val configMap = AppConfig.bookshelfGroupTags.toMutableMap()
        var configChanged = false
        groupIds.forEach { groupId ->
            val merged = BookTagManagement.mergeTags(configMap[groupId].orEmpty(), tags)
            if (merged != configMap[groupId]) {
                configMap[groupId] = merged
                configChanged = true
            }
        }
        if (configChanged) {
            AppConfig.bookshelfGroupTags = configMap
        }

        val hiddenMap = AppConfig.bookshelfHiddenTags.toMutableMap()
        var hiddenChanged = false
        groupIds.forEach groupLoop@ { groupId ->
            val hidden = hiddenMap[groupId] ?: return@groupLoop
            val visible = hidden.filterNot { item -> tags.any { it.equals(item, true) } }
            if (visible.size == hidden.size) return@groupLoop
            hiddenChanged = true
            if (visible.isEmpty()) hiddenMap.remove(groupId) else hiddenMap[groupId] = visible.toSet()
        }
        if (hiddenChanged) {
            AppConfig.bookshelfHiddenTags = hiddenMap
        }
    }
}

/** 单本换源的结果分类，用于结尾汇总。 */
private enum class ChangeSourceOutcome {
    SUCCESS, NOT_FOUND, FAILED, SKIPPED
}

private data class ChangeSourceSummary(
    val success: Int,
    val notFound: Int,
    val failed: Int,
    val skipped: Int,
)

/** 批量改地址的结果分类，「未找到」不适用（不搜索），单列「跳过」。 */
private data class ChangeUrlSummary(
    val success: Int,
    val skipped: Int,
    val failed: Int,
)

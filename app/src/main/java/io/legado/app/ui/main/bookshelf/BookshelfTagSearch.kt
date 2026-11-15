package io.legado.app.ui.main.bookshelf

import io.legado.app.data.dao.BookShelfDisplay
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookTagHelper

/**
 * 书架「按标签搜索」的关键词解析与匹配。
 *
 * 背景：书架的二级标签栏只能筛「自定义标签（customTag）」与「智能标签（未读/在读…）」，
 * 书源在详情页抓到的来源标签（[BookShelfDisplay.kind]，形如「玄幻,热血」）从来没有参与过筛选，
 * 用户在书架里无法用「玄幻」这类标签找书。这里把 kind 一并纳入匹配范围。
 *
 * 书架主页用的是 Room 投影 [BookShelfDisplay]，书架管理界面用的是 [Book] 实体，
 * 两者都委托给同一个 [matches] 通用重载，保证同一关键词在两个界面得到一致结果。
 *
 * 匹配口径（多个关键词之间是「与」关系，全部命中才保留）：
 * - 书名 / 作者 / 附加字段：包含关键词即命中，不区分大小写；
 * - 自定义标签 / 来源标签：按标签分隔符切开后，任一标签**包含**关键词即命中。
 *   用包含而不是整词相等，是因为用户输入「玄」期望能搜到「玄幻」，这更符合搜索直觉。
 */
internal object BookshelfTagSearch {

    /** 关键词分隔符：空白、中英文逗号、顿号、分号、竖线、斜杠（与 [BookTagHelper] 同口径） */
    private val keywordSplitter = Regex("[\\s,，;；、|/]+")

    /** 把输入框内容拆成去重后的关键词列表；空白输入返回空列表，代表不筛选 */
    fun parse(key: String?): List<String> = key.orEmpty()
        .split(keywordSplitter)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    /**
     * 通用匹配。关键词为空时直接返回 true，调用方无需额外判空。
     *
     * @param extraFields 调用方额外要参与匹配的文本。书架管理界面用它保留原有的
     *   书源名 / 书源地址 / 简介，避免换成标签匹配后这几项反而搜不到了。
     */
    fun matches(
        name: String,
        author: String,
        customTag: String?,
        kind: String?,
        keywords: List<String>,
        extraFields: List<String?> = emptyList(),
    ): Boolean {
        if (keywords.isEmpty()) return true
        // 标签解析是这本书里最贵的一步，用 lazy 保证「书名已命中」时不做无谓解析
        val tagWords by lazy(LazyThreadSafetyMode.NONE) {
            (BookTagHelper.parse(customTag) + BookTagHelper.parse(kind))
                .map { it.lowercase() }
        }
        return keywords.all { keyword ->
            val kw = keyword.lowercase()
            name.contains(kw, ignoreCase = true) ||
                author.contains(kw, ignoreCase = true) ||
                tagWords.any { it.contains(kw) } ||
                extraFields.any { it?.contains(kw, ignoreCase = true) == true }
        }
    }

    /** 书架主页：Room 投影 [BookShelfDisplay]。 */
    fun matches(display: BookShelfDisplay, keywords: List<String>): Boolean =
        matches(display.name, display.author, display.customTag, display.kind, keywords)

    /**
     * 书架管理界面：[Book] 实体。
     *
     * 除标签外还带上书源名 / 书源地址 / 简介——这三样原先由 Book.contains 覆盖，
     * 换成标签匹配后不能丢，否则「按书源名找书」这类用法会失效。
     */
    fun matches(book: Book, keywords: List<String>): Boolean =
        matches(
            book.name,
            book.author,
            book.customTag,
            book.kind,
            keywords,
            listOf(book.originName, book.origin, book.intro),
        )
}

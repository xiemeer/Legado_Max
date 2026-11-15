package io.legado.app.ui.book.manga.entities

data class MangaPage(
    override val chapterIndex: Int = 0,//总章节位置
    val chapterSize: Int,//总章节数量
    val mImageUrl: String = "",//当前URL
    override val index: Int = 0,//当前章节位置
    var imageCount: Int = 0,//当前章节内容总数
    val mChapterName: String = "",//章节名称
) : BaseMangaPage {
    /**
     * Download/阅读器 目录下该图已导出的本地路径或 Content URI；为空表示无本地副本，按原方案处理。
     * 故意放在类体（非主构造），不参与 data class 的 equals/hashCode，避免影响列表差分与复用判定。
     */
    var localPath: String? = null

    /** 离线直读页：mImageUrl 为占位，直接用 localPath 渲染，跳过缓存/网络逻辑。 */
    var directLocal: Boolean = false
}

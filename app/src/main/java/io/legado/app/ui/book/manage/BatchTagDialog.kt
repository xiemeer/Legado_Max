package io.legado.app.ui.book.manage

import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.databinding.DialogBookshelfBatchTagBinding
import io.legado.app.help.book.BookTagHelper
import io.legado.app.help.book.BookTagManagement
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import java.util.Locale

/**
 * 书架管理：批量设置标签对话框。
 *
 * 采用「动作语义」而不是「状态语义」：多选书籍的标签本来各不相同，用勾选状态无法同时表达
 * "全部都有 / 全部没有 / 只有一部分有"，所以这里让用户先选动作（添加 / 移除），再选标签，
 * 动作统一作用于全部选中书籍。这样不会出现"以为只是加，结果把原有标签重置了"的误操作。
 *
 * 标签候选 = 选中书籍出现过的标签 ∪ 分组已配置的标签，并标出命中数量（如 `玄幻（2/3）`），
 * 让用户在选择前就能看到这批书的标签分布。智能标签是实时计算的虚拟标签（不写库），
 * 因此不在此列表中出现，也不可批量设置。
 */
class BatchTagDialog() : BaseDialogFragment(R.layout.dialog_bookshelf_batch_tag, true) {

    private val binding by viewBinding(DialogBookshelfBatchTagBinding::bind)
    private val viewModel by viewModels<ViewModel>()

    /** 已勾选标签：小写归一 → 原始写法。切换添加/移除时清空，避免两种动作的勾选状态互相污染 */
    private val checkedTags = linkedMapOf<String, String>()

    constructor(bookUrls: Collection<String>) : this() {
        arguments = Bundle().apply {
            putStringArrayList("bookUrls", ArrayList(bookUrls))
        }
    }

    private val isAddMode: Boolean
        get() = binding.rbAdd.isChecked

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        val args = arguments
        if (args == null) {
            dismissAllowingStateLoss()
            return
        }
        viewModel.init(args) {
            // 异步加载完成时视图可能已销毁（对话框被关闭），此处需判空防崩溃
            if (isAdded && this@BatchTagDialog.view != null) {
                renderTip()
                renderTags()
            }
        }
        // 滚动区按屏幕高度比例约束，避免横屏/小屏时卡片（含底部按钮行）溢出屏幕
        binding.scrollView.updateLayoutParams {
            height = (resources.displayMetrics.heightPixels * 0.4f).toInt()
        }
        binding.etFilter.doAfterTextChanged {
            // 过滤直接重建列表：已勾选状态存在 checkedTags 里，重建不会丢
            renderTags()
        }
        binding.rgMode.setOnCheckedChangeListener { _, _ ->
            checkedTags.clear()
            binding.llNewTag.visibility = if (isAddMode) View.VISIBLE else View.GONE
            renderTags()
        }
        binding.llNewTag.visibility = if (isAddMode) View.VISIBLE else View.GONE
        binding.tvCancel.setOnClickListener {
            dismissAllowingStateLoss()
        }
        binding.tvOk.setTextColor(requireContext().accentColor)
        binding.tvOk.setOnClickListener {
            applyTags()
        }
    }

    private fun renderTip() {
        binding.tvTip.text = getString(R.string.batch_tag_tip, viewModel.bookCount)
    }

    /**
     * 渲染候选标签。
     *
     * 每个标签都带命中数量，添加模式下也列出当前没有任何选中书拥有的历史标签（它们是可复用的
     * 既有标签），移除模式则只列至少一本书拥有的标签，避免出现"移除一个根本不存在的标签"。
     */
    private fun renderTags() {
        val add = isAddMode
        val total = viewModel.bookCount
        binding.llTags.removeAllViews()
        viewModel.options(add, binding.etFilter.text?.toString().orEmpty()).forEach { option ->
            val key = option.name.lowercase(Locale.ROOT)
            val checkBox = CheckBox(requireContext()).apply {
                text = getString(R.string.batch_tag_option, option.name, option.count, total)
                isChecked = key in checkedTags
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) checkedTags[key] = option.name else checkedTags.remove(key)
                }
            }
            binding.llTags.addView(checkBox)
        }
    }

    private fun applyTags() {
        val selected = checkedTags.values.toMutableList()
        if (isAddMode) {
            // 新增标签只在添加模式有意义：移除一个用户刚敲进来的标签没有任何效果
            selected += BookTagHelper.parse(binding.etNewTags.text?.toString())
        }
        val tags = BookTagManagement.mergeTags(emptyList(), selected)
        if (tags.isEmpty()) {
            requireContext().toastOnUi(R.string.batch_tag_empty)
            return
        }
        callback?.applyBatchTags(viewModel.bookUrls, tags, isAddMode)
        dismissAllowingStateLoss()
    }

    val callback: Callback?
        get() = (parentFragment as? Callback) ?: (activity as? Callback)

    class ViewModel(application: Application) : BaseViewModel(application) {

        /** 选中书籍的 URL，用于计算标签命中数量，并原样回传给调用方 */
        var bookUrls: List<String> = emptyList()
            private set

        val bookCount: Int
            get() = bookUrls.size

        /** 候选标签的展示名（保持首次出现的写法） */
        private var candidates: List<String> = emptyList()

        /** 小写归一 → 命中数量 */
        private var counts: Map<String, Int> = emptyMap()

        private var inited = false

        fun init(arguments: Bundle, onFinally: () -> Unit) {
            if (inited) {
                onFinally.invoke()
                return
            }
            inited = true
            val bookUrls = arguments.getStringArrayList("bookUrls").orEmpty()
            this.bookUrls = bookUrls
            execute {
                val urlSet = bookUrls.toHashSet()
                val hitCounts = HashMap<String, Int>()
                val usedTags = mutableListOf<String>()
                if (urlSet.isNotEmpty()) {
                    // 整表一次查询后按选中集合过滤，避免逐本查库
                    appDb.bookDao.allTagInfos.forEach { info ->
                        if (info.bookUrl !in urlSet) return@forEach
                        BookTagHelper.parse(info.customTag).forEach { tag ->
                            usedTags += tag
                            val key = tag.lowercase(Locale.ROOT)
                            hitCounts[key] = (hitCounts[key] ?: 0) + 1
                        }
                    }
                }
                counts = hitCounts
                // 可复用标签 = 现存分组的标签配置 + 选中书籍实际使用的标签
                val validGroupIds = appDb.bookGroupDao.all.mapTo(HashSet()) { it.groupId }
                val configuredTags = BookTagManagement
                    .pruneUnknownGroups(AppConfig.bookshelfGroupTags, validGroupIds)
                    .values
                    .flatten()
                candidates = BookTagManagement.mergeTags(configuredTags, usedTags)
            }.onFinally {
                onFinally.invoke()
            }
        }

        /**
         * 生成候选标签项。
         *
         * @param add true=添加模式，列出全部候选；false=移除模式，只列至少一本书拥有的标签
         * @param filter 关键字，空串表示不过滤
         */
        fun options(add: Boolean, filter: String): List<TagOption> {
            val query = filter.trim()
            return candidates.mapNotNull { name ->
                val count = counts[name.lowercase(Locale.ROOT)] ?: 0
                if (!add && count == 0) return@mapNotNull null
                if (query.isNotEmpty() && !name.contains(query, true)) return@mapNotNull null
                TagOption(name, count)
            }.sortedWith(compareByDescending<TagOption> { it.count }.thenBy { it.name })
        }
    }

    /** 候选标签项：展示名 + 命中数量 */
    data class TagOption(val name: String, val count: Int)

    interface Callback {

        /**
         * 应用批量标签。
         *
         * @param bookUrls 选中书籍的 URL
         * @param tags 去重后的标签列表
         * @param add true=添加，false=移除
         */
        fun applyBatchTags(bookUrls: List<String>, tags: List<String>, add: Boolean)
    }
}

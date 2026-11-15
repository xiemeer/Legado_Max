package io.legado.app.ui.book.manage

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogBookshelfBatchChangeUrlBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import splitties.views.onClick

/**
 * 书架管理：批量改地址对话框。
 *
 * 与「批量换源」的区别是全程不搜索、不发网络请求：
 * - 「只换书源归属」只改 origin / originName，书籍网址、章节、进度、缓存一律不动；
 * - 「连网址一起换」按旧前缀 → 新前缀替换书籍与章节里的绝对地址，并重建章节、迁移缓存。
 *
 * 使用者需要自己保证新书源能解析现有目录地址，所以这里把风险提示和替换预览放在确认之前，
 * 让人在按下确定前就能看出前缀填得对不对。
 */
class BatchChangeUrlDialog() :
    BaseDialogFragment(R.layout.dialog_bookshelf_batch_change_url, true) {

    private val binding by viewBinding(DialogBookshelfBatchChangeUrlBinding::bind)

    /**
     * @param sampleUrl 选中书籍里第一本的 bookUrl，用于实时预览替换结果。
     */
    constructor(
        bookUrls: Collection<String>,
        sourceUrl: String,
        sourceName: String,
        sampleUrl: String,
    ) : this() {
        arguments = Bundle().apply {
            putStringArrayList("bookUrls", ArrayList(bookUrls))
            putString("sourceUrl", sourceUrl)
            putString("sourceName", sourceName)
            putString("sampleUrl", sampleUrl)
        }
    }

    private val bookUrls: List<String>
        get() = arguments?.getStringArrayList("bookUrls").orEmpty()

    private val sourceUrl: String
        get() = arguments?.getString("sourceUrl").orEmpty()

    private val sourceName: String
        get() = arguments?.getString("sourceName").orEmpty()

    private val sampleUrl: String
        get() = arguments?.getString("sampleUrl").orEmpty()

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        if (sourceUrl.isEmpty() || bookUrls.isEmpty()) {
            dismissAllowingStateLoss()
            return
        }
        binding.tvTip.text = getString(R.string.batch_change_url_tip, bookUrls.size, sourceName)

        binding.etNewPrefix.setText(sourceUrl.trimEnd('/'))
        binding.etOldPrefix.setText(guessOldPrefix(sampleUrl))

        binding.rgMode.setOnCheckedChangeListener { _, checkedId ->
            val replaceUrls = checkedId == R.id.rb_replace_url
            binding.llPrefix.visibility = if (replaceUrls) View.VISIBLE else View.GONE
            if (replaceUrls) renderPreview()
        }
        binding.etOldPrefix.doAfterTextChanged { renderPreview() }
        binding.etNewPrefix.doAfterTextChanged { renderPreview() }

        binding.tvCancel.onClick { dismissAllowingStateLoss() }
        binding.tvOk.onClick { apply() }
    }

    private fun apply() {
        val replaceBookUrl = binding.rbReplaceUrl.isChecked
        var oldPrefix = ""
        var newPrefix = ""
        if (replaceBookUrl) {
            oldPrefix = binding.etOldPrefix.text.toString().trim().trimEnd('/')
            newPrefix = binding.etNewPrefix.text.toString().trim().trimEnd('/')
            if (oldPrefix.isEmpty() || newPrefix.isEmpty()) {
                requireContext().toastOnUi(R.string.batch_change_url_need_prefix)
                return
            }
        }
        callback?.applyUrlChange(bookUrls, sourceUrl, replaceBookUrl, oldPrefix, newPrefix)
        dismissAllowingStateLoss()
    }

    /** 用第一本书的网址实时演示替换结果，避免前缀填错导致整批被跳过。 */
    private fun renderPreview() {
        if (sampleUrl.isEmpty()) {
            binding.tvPreview.text = ""
            return
        }
        val oldP = binding.etOldPrefix.text.toString().trim().trimEnd('/')
        val newP = binding.etNewPrefix.text.toString().trim().trimEnd('/')
        binding.tvPreview.text = when {
            oldP.isEmpty() || newP.isEmpty() || !sampleUrl.startsWith(oldP) ->
                getString(R.string.batch_change_url_preview_none)
            else -> getString(
                R.string.batch_change_url_preview,
                sampleUrl,
                newP + sampleUrl.substring(oldP.length)
            )
        }
    }

    /** 从示例网址里猜出 scheme://host 作为旧前缀默认值，用户可继续改成更长的路径前缀。 */
    private fun guessOldPrefix(url: String): String {
        return runCatching {
            val uri = Uri.parse(url)
            val scheme = uri.scheme
            val host = uri.host
            if (scheme.isNullOrEmpty() || host.isNullOrEmpty()) return ""
            val port = if (uri.port != -1) ":${uri.port}" else ""
            "$scheme://$host$port"
        }.getOrDefault("")
    }

    private val callback: Callback?
        get() = (parentFragment as? Callback) ?: activity as? Callback

    interface Callback {
        fun applyUrlChange(
            bookUrls: List<String>,
            sourceUrl: String,
            replaceBookUrl: Boolean,
            oldPrefix: String,
            newPrefix: String,
        )
    }

}

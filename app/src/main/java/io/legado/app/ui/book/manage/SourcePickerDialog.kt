package io.legado.app.ui.book.manage

import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.os.bundleOf
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookSourceType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.databinding.DialogSourcePickerBinding
import io.legado.app.databinding.Item1lineTextBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.applyTint
import io.legado.app.utils.dpToPx
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import splitties.views.onClick

/**
 * 书源选择
 */
class SourcePickerDialog : BaseDialogFragment(R.layout.dialog_source_picker),
    Toolbar.OnMenuItemClickListener {

    private val binding by viewBinding(DialogSourcePickerBinding::bind)
    private val searchView: SearchView by lazy {
        binding.toolBar.findViewById(R.id.search_view)
    }
    private val toolBar: Toolbar by lazy {
        binding.toolBar.toolbar
    }
    private val adapter by lazy {
        SourceAdapter(requireContext())
    }
    private var sourceFlowJob: Job? = null

    /** 需要筛选的书源类型，[SOURCE_TYPE_ANY] 表示不限。 */
    private val sourceType: Int by lazy {
        arguments?.getInt(ARG_SOURCE_TYPE, SOURCE_TYPE_ANY) ?: SOURCE_TYPE_ANY
    }

    /** true 表示这次选源是给「批量改地址」用的，回调走专用通道而不是搜索式换源。 */
    private val forUrlChange: Boolean by lazy {
        arguments?.getBoolean(ARG_FOR_URL_CHANGE, false) ?: false
    }

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initView()
        initData()
        initMenu()
    }

    private fun initView() {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.toolBar.title = when (sourceType) {
            BookSourceType.default -> getString(R.string.change_source_picker_title_text)
            BookSourceType.image -> getString(R.string.change_source_picker_title_image)
            BookSourceType.audio -> getString(R.string.change_source_picker_title_audio)
            else -> getString(R.string.change_source_picker_title_all)
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        searchView.applyTint(primaryTextColor)
        searchView.isSubmitButtonEnabled = true
        searchView.queryHint = getString(R.string.search_book_source)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                initData(newText)
                return false
            }
        })
    }

    private fun initData(searchKey: String? = null) {
        sourceFlowJob?.cancel()
        sourceFlowJob = lifecycleScope.launch {
            when {
                searchKey.isNullOrEmpty() -> appDb.bookSourceDao.flowEnabled()
                else -> appDb.bookSourceDao.flowSearchEnabled(searchKey)
            }.map { list ->
                // 图片书只能换图片源、文本同理：类型对不上的源换过去目录规则必然失败
                if (sourceType == SOURCE_TYPE_ANY) list
                else list.filter { it.bookSourceType == sourceType }
            }.catch {
                AppLog.put("书源选择界面获取书源数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).collect {
                adapter.setItems(it)
            }
        }
    }

    private fun initMenu() {
        toolBar.setOnMenuItemClickListener(this)
        toolBar.inflateMenu(R.menu.source_picker)
        toolBar.menu.applyTint(requireContext())
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_change_source_delay -> NumberPickerDialog(requireContext())
                .setTitle(getString(R.string.change_source_delay))
                .setMaxValue(9999)
                .setMinValue(0)
                .setValue(AppConfig.batchChangeSourceDelay)
                .show {
                    AppConfig.batchChangeSourceDelay = it
                }
        }
        return true
    }

    inner class SourceAdapter(context: Context) :
        RecyclerAdapter<BookSourcePart, Item1lineTextBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): Item1lineTextBinding {
            return Item1lineTextBinding.inflate(inflater, parent, false).apply {
                root.setPadding(16.dpToPx())
            }
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: Item1lineTextBinding,
            item: BookSourcePart,
            payloads: MutableList<Any>
        ) {
            binding.textView.text = item.getDisPlayNameGroup()
        }

        override fun registerListener(holder: ItemViewHolder, binding: Item1lineTextBinding) {
            binding.root.onClick {
                getItemByLayoutPosition(holder.layoutPosition)?.let {
                    it.getBookSource()?.let { source ->
                        if (forUrlChange) {
                            callback?.onSourcePickedForUrlChange(source)
                        } else {
                            callback?.sourceOnClick(source)
                        }
                    }
                    dismissAllowingStateLoss()
                }
            }
        }

    }

    private val callback: Callback?
        get() {
            return (parentFragment as? Callback) ?: activity as? Callback
        }

    interface Callback {
        fun sourceOnClick(source: BookSource)

        /** 默认实现让未接入「批量改地址」的调用方保持原行为。 */
        fun onSourcePickedForUrlChange(source: BookSource) {
            sourceOnClick(source)
        }
    }

    companion object {
        const val SOURCE_TYPE_ANY = -1
        private const val ARG_SOURCE_TYPE = "sourceType"
        private const val ARG_FOR_URL_CHANGE = "forUrlChange"

        /** 按书源类型创建一个只显示对应类型书源的选择器。 */
        fun newInstance(
            sourceType: Int = SOURCE_TYPE_ANY,
            forUrlChange: Boolean = false,
        ) = SourcePickerDialog().apply {
            arguments = bundleOf(
                ARG_SOURCE_TYPE to sourceType,
                ARG_FOR_URL_CHANGE to forUrlChange
            )
        }
    }

}
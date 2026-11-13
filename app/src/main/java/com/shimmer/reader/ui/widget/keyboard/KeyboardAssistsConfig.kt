package com.shimmer.reader.ui.widget.keyboard

import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shimmer.reader.R
import com.shimmer.reader.base.BaseDialogFragment
import com.shimmer.reader.base.adapter.ItemViewHolder
import com.shimmer.reader.base.adapter.RecyclerAdapter
import com.shimmer.reader.constant.AppLog
import com.shimmer.reader.constant.PreferKey
import com.shimmer.reader.data.appDb
import com.shimmer.reader.data.entities.KeyboardAssist
import com.shimmer.reader.databinding.DialogMultipleEditTextBinding
import com.shimmer.reader.databinding.DialogRecyclerViewBinding
import com.shimmer.reader.databinding.Item1lineTextAndDelBinding
import com.shimmer.reader.help.config.AppConfig
import com.shimmer.reader.lib.dialogs.alert
import com.shimmer.reader.lib.theme.applyUiBodyTypefaceDeep
import com.shimmer.reader.lib.theme.backgroundColor
import com.shimmer.reader.lib.theme.primaryColor
import com.shimmer.reader.lib.theme.uiTypeface
import com.shimmer.reader.ui.widget.number.NumberPickerDialog
import com.shimmer.reader.ui.widget.recycler.ItemTouchCallback
import com.shimmer.reader.ui.widget.recycler.VerticalDivider
import com.shimmer.reader.utils.applyTint
import com.shimmer.reader.utils.dpToPx
import com.shimmer.reader.utils.putPrefInt
import com.shimmer.reader.utils.setLayout
import com.shimmer.reader.utils.viewbindingdelegate.viewBinding
import com.shimmer.reader.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * 辅助按键配置
 */
class KeyboardAssistsConfig(private val callBack: CallBack) : BaseDialogFragment(R.layout.dialog_recycler_view),
    Toolbar.OnMenuItemClickListener {

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val adapter by lazy { KeyAdapter(requireContext()) }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.9f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initView()
        initMenu()
        initData()
    }

    private fun initView() {
        binding.toolBar.run {
            setBackgroundColor(primaryColor)
            setTitle(R.string.assists_key_config)
            subtitle = AppConfig.showBoardLine.toLineStr(context)
            setOnClickListener {
                NumberPickerDialog(requireContext())
                    .setTitle(getString(R.string.setting_show_line_number))
                    .setMaxValue(5)
                    .setMinValue(1)
                    .setValue(AppConfig.showBoardLine)
                    .show {
                        putPrefInt(PreferKey.showBoardLine, it)
                        subtitle = it.toLineStr(context)
                        callBack.requestLayout()
                    }
            }
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.addItemDecoration(VerticalDivider(requireContext()))
        binding.recyclerView.adapter = adapter
        val itemTouchCallback = ItemTouchCallback(adapter)
        itemTouchCallback.isCanDrag = true
        ItemTouchHelper(itemTouchCallback).attachToRecyclerView(binding.recyclerView)

    }

    private fun initMenu() {
        binding.toolBar.setOnMenuItemClickListener(this)
        binding.toolBar.inflateMenu(R.menu.keyboard_assists_config)
        binding.toolBar.menu.applyTint(requireContext())
    }

    private fun initData() {
        lifecycleScope.launch {
            appDb.keyboardAssistsDao.flowAll.catch {
                AppLog.put("辅助按键配置获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).collect {
                adapter.setItems(it)
            }
        }
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_add -> editKey(null)
        }
        return false
    }

    private fun editKey(keyboardAssist: KeyboardAssist?) {
        alert {
            setTitle(getString(R.string.keyboard_assists_title))
            val alertBinding = DialogMultipleEditTextBinding.inflate(layoutInflater).apply {
                layout1.hint = "key"
                edit1.setText(keyboardAssist?.key)
                layout2.hint = "value"
                layout2.visible()
                edit2.setText(keyboardAssist?.value)
                root.applyUiBodyTypefaceDeep(requireContext().uiTypeface())
            }
            setCustomView(alertBinding.root)
            cancelButton()
            okButton {
                lifecycleScope.launch(IO) {
                    val newKeyboardAssist = KeyboardAssist(
                        key = alertBinding.edit1.text.toString(),
                        value = alertBinding.edit2.text.toString()
                    )
                    if (keyboardAssist == null) {
                        newKeyboardAssist.serialNo = appDb.keyboardAssistsDao.maxSerialNo + 1
                        appDb.keyboardAssistsDao.insert(newKeyboardAssist)
                    } else {
                        newKeyboardAssist.serialNo = keyboardAssist.serialNo
                        appDb.keyboardAssistsDao.delete(keyboardAssist)
                        appDb.keyboardAssistsDao.insert(newKeyboardAssist)
                    }
                }
            }
        }
    }

    private inner class KeyAdapter(context: Context) :
        RecyclerAdapter<KeyboardAssist, Item1lineTextAndDelBinding>(context),
        ItemTouchCallback.Callback {

        private var isMoved = false

        override fun getViewBinding(parent: ViewGroup): Item1lineTextAndDelBinding {
            return Item1lineTextAndDelBinding.inflate(inflater, parent, false).apply {
                root.setPadding(16.dpToPx())
                ivDelete.visible()
            }
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: Item1lineTextAndDelBinding,
            item: KeyboardAssist,
            payloads: MutableList<Any>
        ) {
            binding.root.setBackgroundColor(context.backgroundColor)
            binding.textView.text = item.key
            binding.textView.typeface = context.uiTypeface()
        }

        override fun registerListener(holder: ItemViewHolder, binding: Item1lineTextAndDelBinding) {
            binding.root.setOnClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.let { keyboardAssist ->
                    editKey(keyboardAssist)
                }
            }
            binding.ivDelete.setOnClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.let { keyboardAssist ->
                    lifecycleScope.launch(IO) {
                        appDb.keyboardAssistsDao.delete(keyboardAssist)
                    }
                }
            }
        }

        override fun swap(srcPosition: Int, targetPosition: Int): Boolean {
            swapItem(srcPosition, targetPosition)
            isMoved = true
            return true
        }

        override fun onClearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            if (isMoved) {
                for ((index, item) in getItems().withIndex()) {
                    item.serialNo = index + 1
                }
                lifecycleScope.launch(IO) {
                    appDb.keyboardAssistsDao.update(*getItems().toTypedArray())
                }
            }
            isMoved = false
        }
    }
    interface CallBack {
         /**通知布局管理器重新布局*/
        fun requestLayout()
    }

    private fun Int.toLineStr(context: Context): String {
      return context.getString(R.string.show_line_number, this)
    }
}

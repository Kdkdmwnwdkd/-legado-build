package io.legado.app.ui.book.source.trust

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Switch
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.databinding.ActivityRecyclerViewBinding
import io.legado.app.help.SourceTrustManager
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SourceTrustManageActivity : BaseActivity<ActivityRecyclerViewBinding>() {

    override val binding by viewBinding(ActivityRecyclerViewBinding::inflate)
    private val adapter by lazy { SourceTrustAdapter(this) }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        binding.titleBar.title = getString(R.string.source_trust_manage)
        initRecyclerView()
        loadSources()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        menuInflater.inflate(R.menu.source_trust_manage, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_clear_all -> {
                SourceTrustManager.clear()
                adapter.notifyDataSetChanged()
                toastOnUi("已清空白名单")
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initRecyclerView() {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun loadSources() {
        lifecycleScope.launch {
            val sources = withContext(Dispatchers.IO) {
                appDb.bookSourceDao.all
            }
            adapter.setItems(sources)
        }
    }

    fun toggleTrust(source: BookSource, trusted: Boolean) {
        if (trusted) {
            SourceTrustManager.add(source.bookSourceUrl)
        } else {
            SourceTrustManager.remove(source.bookSourceUrl)
        }
    }

    class SourceTrustAdapter(private val activity: SourceTrustManageActivity) :
        androidx.recyclerview.widget.RecyclerView.Adapter<SourceTrustAdapter.ViewHolder>() {

        private var items: List<BookSource> = emptyList()

        fun setItems(sources: List<BookSource>) {
            items = sources
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_source_trust, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val source = items[position]
            holder.nameView.text = source.bookSourceName
            holder.urlView.text = source.bookSourceUrl
            holder.switchView.setOnCheckedChangeListener(null)
            val trusted = SourceTrustManager.isTrusted(source.bookSourceUrl)
            holder.switchView.isChecked = trusted
            holder.switchView.setOnCheckedChangeListener { _, isChecked ->
                activity.toggleTrust(source, isChecked)
            }
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(view: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val nameView: TextView = view.findViewById(R.id.tv_source_name)
            val urlView: TextView = view.findViewById(R.id.tv_source_url)
            val switchView: Switch = view.findViewById(R.id.sw_trust)
        }
    }
}

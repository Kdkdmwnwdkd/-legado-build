package io.legado.app.ui.book.source.trust

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.SourceTrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SourceTrustManageActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private val adapter by lazy { SourceTrustAdapter(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_source_trust_manage)

        setSupportActionBar(findViewById(R.id.title_bar))
        supportActionBar?.setTitle(R.string.source_trust_manage)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        recyclerView = findViewById(R.id.recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        loadSources()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.source_trust_manage, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_clear_all -> {
                SourceTrustManager.clear()
                adapter.notifyDataSetChanged()
            }
            android.R.id.home -> {
                finish()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
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
        RecyclerView.Adapter<SourceTrustAdapter.ViewHolder>() {

        private var items: List<BookSource> = emptyList()

        fun setItems(sources: List<BookSource>) {
            items = sources
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
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

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val nameView: TextView = view.findViewById(R.id.tv_source_name)
            val urlView: TextView = view.findViewById(R.id.tv_source_url)
            val switchView: Switch = view.findViewById(R.id.sw_trust)
        }
    }
}

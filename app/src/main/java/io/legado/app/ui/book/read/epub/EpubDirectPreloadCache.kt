package io.legado.app.ui.book.read.epub

internal class EpubDirectPreloadCache<T : Any>(
    capacity: Int,
    private val onEvicted: (T) -> Unit
) {

    private var capacity = capacity

    init {
        require(capacity > 0) { "EPUB preload cache capacity must be positive" }
    }

    private val entries = LinkedHashMap<String, T>(capacity, 0.75f, true)

    val size: Int get() = entries.size

    fun resize(capacity: Int) {
        require(capacity > 0) { "EPUB preload cache capacity must be positive" }
        this.capacity = capacity
        trimToCapacity()
    }

    fun contains(key: String): Boolean = entries[key] != null

    fun take(key: String): T? = entries.remove(key)

    fun put(key: String, value: T) {
        entries.entries.firstOrNull { it.key != key && it.value === value }?.let {
            entries.remove(it.key)
        }
        val replaced = entries.remove(key)
        if (replaced != null && replaced !== value) onEvicted(replaced)
        entries[key] = value
        trimToCapacity()
    }

    private fun trimToCapacity() {
        while (entries.size > capacity) {
            val eldest = entries.entries.firstOrNull() ?: break
            entries.remove(eldest.key)
            onEvicted(eldest.value)
        }
    }

    fun removeValue(value: T): Boolean {
        val entry = entries.entries.firstOrNull { it.value === value } ?: return false
        entries.remove(entry.key)
        return true
    }

    fun forEachValue(action: (T) -> Unit) {
        entries.values.toList().forEach(action)
    }

    fun evictWhere(predicate: (T) -> Boolean) {
        val evicted = entries.entries.filter { predicate(it.value) }.map { it.key to it.value }
        evicted.forEach { (key, _) -> entries.remove(key) }
        evicted.forEach { (_, value) -> onEvicted(value) }
    }

    fun clear() {
        val values = entries.values.toList()
        entries.clear()
        values.forEach(onEvicted)
    }
}

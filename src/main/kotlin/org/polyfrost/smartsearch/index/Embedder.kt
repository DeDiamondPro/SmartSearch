package org.polyfrost.smartsearch.index

import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.polyfrost.oneconfig.internal.ui.search.SearchDocument
import org.polyfrost.smartsearch.SmartSearchClient
import org.polyfrost.smartsearch.cache.CacheStore
import org.polyfrost.smartsearch.config.SmartSearchConfig
import org.polyfrost.smartsearch.model.ModelController
import java.util.concurrent.atomic.AtomicBoolean

object Embedder {
    const val BATCH_SIZE = 50

    /** Skip embedding sound tweak options, adds barely any quality and adds like 3k embeddings */
    private val SKIPPED_IDS: Set<String> = setOf("soundtweaks.json::")

    private val toEmbed: MutableMap<String, SearchDocument<*>> = LinkedHashMap()

    /** Every document currently being embedded in this batch */
    private val inFlight: MutableMap<String, String> = HashMap()

    /** Guards [toEmbed] and [inFlight] */
    private val queueLock = Any()

    private val isRunning = AtomicBoolean(false)

    fun queueEmbedding(toEmbed: List<SearchDocument<*>>) {
        synchronized(queueLock) {
            for (doc in toEmbed) {
                if (SKIPPED_IDS.any { doc.id.startsWith(it) }) continue

                // Skip if embedding would have the same result
                if (inFlight[doc.id] == doc.hash()) continue
                this.toEmbed[doc.id] = doc
            }
        }
        startEmbeddings()
    }

    fun dropQueued(ids: Set<String>) {
        if (ids.isEmpty()) return
        synchronized(queueLock) {
            ids.forEach(toEmbed::remove)
        }
    }

    /** Check if any pending embeddings exists in a cache update, and use cache instead of embedding */
    fun recheckCache() {
        val cached = synchronized(queueLock) {
            val cached = toEmbed.mapNotNull { (_, doc) ->
                val embedding = CacheStore.get(doc.hash()) ?: return@mapNotNull null
                doc to embedding
            }.toMap()

            for (doc in cached.keys) {
                toEmbed.remove(doc.id)
            }
            cached
        }

        DataStore.addEmbeddings(cached)
        DataStore.flush()
    }

    private fun takeBatch(): List<SearchDocument<*>> = synchronized(queueLock) {
        val batch = toEmbed.values.take(BATCH_SIZE)
        inFlight.clear()
        for (doc in batch) {
            toEmbed.remove(doc.id)
            inFlight[doc.id] = doc.hash()
        }
        batch
    }

    fun startEmbeddings() {
        if (pending == 0 || !ModelController.isReady() || !SmartSearchConfig.enableSemantic
            || !SmartSearchClient.scope.isActive || isRunning.getAndSet(true)
        ) {
            return
        }
        SmartSearchClient.LOGGER.info("Starting embedding of $pending documents...")
        val start = System.currentTimeMillis()

        SmartSearchClient.scope.launch {
            val model = ModelController.getModel()
            try {
                // isActive is checked every batch so this can be canceled if needed
                while (isActive) {
                    val batch = takeBatch()
                    if (batch.isEmpty()) break
                    val embedded = batch.mapNotNull {
                        val str = embeddingText(it)
                        if (str.isBlank()) return@mapNotNull null
                        it to model.embed(str).content()
                    }.toMap()
                    // Filter out re-queued (updated) documents
                    DataStore.addEmbeddings(synchronized(queueLock) {
                        embedded.filterKeys { it.id !in toEmbed }
                    })
                }
                SmartSearchClient.LOGGER.info("Embedding finished in ${System.currentTimeMillis() - start}ms")
            } finally {
                synchronized(queueLock) { inFlight.clear() }
                // Commit all batches
                DataStore.flush()
                isRunning.set(false)
            }

            startEmbeddings() // Re-trigger in case of a race condition
        }
    }

    fun isRunning() = isRunning.get()
    val pending: Int get() = synchronized(queueLock) { toEmbed.size }
}

/**
 * Skip including descriptions starting with this, skips the sound tweaks descriptions for example.
 * This just dilutes the embedding
 */
private val skippedDescriptionPrefixes = listOf("Internal ID:")

internal fun embeddingText(doc: SearchDocument<*>): String = buildString {
    addNotNull(doc.metadata.title)
    val categories = listOfNotNull(
        // Filter out default "general", it doesn't provide any useful info
        doc.metadata.category?.takeIf { it.lowercase() != "general" },
        doc.metadata.distinctSubcategory?.takeIf { it.lowercase() != "general" }
    )
    addNotNull(categories.takeIf { it.isNotEmpty() }) { it.joinToString() }
    addNotNull(doc.metadata.tags.takeIf { it.isNotEmpty() }) { it.joinToString() }
    addNotNull(doc.metadata.description?.takeUnless { skippedDescriptionPrefixes.any { pre -> it.startsWith(pre) } })
    addNotNull(doc.metadata.modTitle)
    addNotNull(doc.metadata.modDescription)
}.removeSuffix("\n")

private fun <T> StringBuilder.addNotNull(obj: T?, terminator: String? = "\n", strFunc: ((T) -> String)? = null) {
    if (obj == null) return
    var res = strFunc?.invoke(obj) ?: obj
    if (res is String) res = res.normalize()
    append(res)
    terminator?.let { append(it) }
}

private val WHITESPACE_REGEX = "[^\\S\\r\\n]+".toRegex()

/**
 * Remove normalize whitespace, remove duplicate newlines and whitespaces
 */
private fun String.normalize(): String =
    this.replace(WHITESPACE_REGEX, " ")
        .split("\n")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
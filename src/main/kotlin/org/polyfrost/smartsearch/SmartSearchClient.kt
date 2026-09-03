package org.polyfrost.smartsearch

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.polyfrost.oneconfig.api.event.v1.EventManager
import org.polyfrost.oneconfig.api.event.v1.events.ShutdownEvent
import org.polyfrost.oneconfig.internal.ui.search.SearchProviderRegistry
import org.polyfrost.smartsearch.cache.RemoteCacheProvider
import org.polyfrost.smartsearch.config.SmartSearchConfig
import org.polyfrost.smartsearch.index.DataStore
import org.polyfrost.smartsearch.index.Embedder
import org.polyfrost.smartsearch.model.ArcticEmbedXsFactory
import org.polyfrost.smartsearch.model.ModelController
import org.polyfrost.smartsearch.search.SmartSearchProvider
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object SmartSearchClient {
    val LOGGER: Logger = LoggerFactory.getLogger(SmartSearchClient::class.java)

    private val job = SupervisorJob()

    /**
     * Scope for all background work touching [DataStore]. Canceled on shutdown.
     */
    internal val scope = CoroutineScope(Dispatchers.Default + job)

    internal fun init() {
        SmartSearchConfig.preload()
        RemoteCacheProvider.register()
        SearchProviderRegistry.registerSearchProvider(SmartSearchProvider)
        if (SmartSearchConfig.enableSemantic) {
            startModel()
        }
        EventManager.register(ShutdownEvent::class.java) { _ ->
            shutdown()
        }
        DataStore.removeTrackedStaleEntries()
    }

    internal fun startModel() {
        RemoteCacheProvider.sync()
        scope.launch {
            ModelController.init(ArcticEmbedXsFactory)
            Embedder.startEmbeddings()
        }
    }

    private fun shutdown() {
        runBlocking { job.cancelAndJoin() }
        DataStore.close()
    }
}
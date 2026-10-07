package org.polyfrost.smartsearch.index

import org.polyfrost.oneconfig.internal.ui.search.SearchCorpus
import org.polyfrost.smartsearch.config.SmartSearchConfig

/** The game's search index. */
object DataStore : SearchIndex(SmartSearchConfig.dbPath()) {
    /** Amount of launches an entry needs to be unseen before it's removed */
    private const val LAUNCHES_BEFORE_REMOVAL = 2

    /** Drops every indexed document whose id is not in [keep]. */
    fun clean(keep: Set<String> = SearchCorpus.corpus.keys) {
        val stale = collectUnknownEntries(keep)
        removeEntries(stale)
        // Update config store
        SmartSearchConfig.staleEntries.clear()
        SmartSearchConfig.save()
    }

    /** Update the tracked stale entries in the DB */
    fun updateTrackedStaleEntries(keep: Set<String> = SearchCorpus.corpus.keys) {
        val stale = collectUnknownEntries(keep)
        if (SmartSearchConfig.staleEntries.track(stale, LAUNCHES_BEFORE_REMOVAL)) {
            SmartSearchConfig.save()
        }
    }

    /** Count down the launches and remove entries that haven't been seen in [LAUNCHES_BEFORE_REMOVAL] launches */
    fun removeTrackedStaleEntries() {
        val expired = SmartSearchConfig.staleEntries.countDown()
        removeEntries(expired)
        SmartSearchConfig.save()
    }
}

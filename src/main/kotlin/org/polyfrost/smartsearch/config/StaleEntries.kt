package org.polyfrost.smartsearch.config

import com.google.gson.Gson
import org.polyfrost.smartsearch.SmartSearchClient
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.bufferedReader
import kotlin.io.path.bufferedWriter
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.moveTo

/**
 * Class keeping track of stale config entries, as 2 arrays since OneConfig doesn't serialize maps properly
 */
class StaleEntries {
    var ids: Array<String> = emptyArray()
    var launchesLeft: IntArray = IntArray(0)

    val size: Int
        @Synchronized get() {
            trim()
            return ids.size
        }

    @Synchronized
    fun isEmpty(): Boolean {
        trim()
        return ids.isEmpty()
    }

    fun isNotEmpty(): Boolean = !isEmpty()

    @Synchronized
    fun clear() {
        ids = emptyArray()
        launchesLeft = IntArray(0)
    }

    /**
     * Tracks exactly the stale set, forgetting everything else (assuming it is now seen)
     *
     * @return Whether something was updated
     */
    @Synchronized
    fun track(stale: Set<String>, launches: Int): Boolean {
        trim()
        val newIds = stale.toTypedArray()
        val newLaunchesLeft = IntArray(newIds.size) { i ->
            val old = ids.indexOf(newIds[i])
            if (old == -1) launches else launchesLeft[old]
        }
        if (newIds.contentEquals(ids) && newLaunchesLeft.contentEquals(launchesLeft)) return false
        ids = newIds
        launchesLeft = newLaunchesLeft
        return true
    }

    /** Count down one launch for every tracked entry, returning and forgetting the ones that ran out. */
    @Synchronized
    fun countDown(): Set<String> {
        trim()
        val expired = LinkedHashSet<String>()
        val keptIds = ArrayList<String>(ids.size)
        val keptLaunchesLeft = ArrayList<Int>(ids.size)
        for (i in ids.indices) {
            val left = launchesLeft[i] - 1
            if (left <= 0) {
                expired.add(ids[i])
            } else {
                keptIds.add(ids[i])
                keptLaunchesLeft.add(left)
            }
        }
        ids = keptIds.toTypedArray()
        launchesLeft = keptLaunchesLeft.toIntArray()
        return expired
    }

    @Synchronized
    fun save() {
        runCatching {
            file.parent?.createDirectories()
            val partial = file.resolveSibling("${file.fileName}.part")
            partial.bufferedWriter().use { GSON.toJson(this, it) }
            partial.moveTo(file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure {
            SmartSearchClient.LOGGER.error("Failed to save stale entries", it)
        }
    }

    private fun trim() {
        if (ids.size == launchesLeft.size) return
        val size = minOf(ids.size, launchesLeft.size)
        ids = ids.copyOfRange(0, size)
        launchesLeft = launchesLeft.copyOfRange(0, size)
    }

    companion object {
        private val GSON = Gson()
        private val file: Path
            get() = SmartSearchConfig.dbPath().resolve("stale-entries.json")

        fun load(): StaleEntries {
            if (!file.exists()) return StaleEntries()
            return runCatching {
                file.bufferedReader().use { GSON.fromJson(it, StaleEntries::class.java) }
            }.getOrNull()
                // Gson leaves missing, non-null, fields null
                ?.takeIf { (it.ids as Array<String>?) != null && (it.launchesLeft as IntArray?) != null }
                ?: StaleEntries().also { SmartSearchClient.LOGGER.warn("Failed to read stale entries, starting fresh") }
        }
    }
}

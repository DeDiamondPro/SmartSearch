package org.polyfrost.smartsearch.eval

import org.polyfrost.oneconfig.internal.ui.search.SearchDocument
import org.polyfrost.oneconfig.internal.ui.search.SearchScope
import java.security.MessageDigest
import kotlin.math.max
import kotlin.random.Random

/** One judged query: [text] should surface [relevant] when searched within [scopes]. */
data class EvalQuery(
    val family: String,
    val text: String,
    val relevant: Set<String>,
    val scopes: Set<SearchScope>,
)

/**
 * How many generated queries each mod contributes per family.
 * Prevents one mod dominating in the benchmark
 */
private const val PER_MOD = 25

private const val SEED = 20260804L

/**
 * The judged query set. Families scored separately since they score different things.
 */
object QuerySet {

    val queries: List<EvalQuery> by lazy {
        buildList {
            addAll(titleExact())
            addAll(titlePrefix())
            addAll(titleTypo())
            addAll(titleSubset())
            addAll(modQualified())
            addAll(descriptions())
            addAll(modNames())
            addAll(modNameParts())
            addAll(modNameJoins())
            addAll(keybinds())
            addAll(CuratedQueries.queries)
            addAll(ShortQueries.queries)
        }
    }

    private val unambiguousOptions: List<SearchDocument<Unit>> by lazy {
        EvalCorpus.documents.filter { doc ->
            SearchScope.Options in doc.scopes &&
                    doc.metadata.title?.lowercase()?.let { it !in EvalCorpus.ambiguousTitles } == true
        }
    }

    private fun titleExact() = sample(unambiguousOptions, "title-exact").map { doc ->
        EvalQuery("title-exact", doc.metadata.title!!, setOf(doc.id), setOf(SearchScope.Options))
    }

    /** Search-as-you-type */
    private fun titlePrefix() = sample(unambiguousOptions.filter { it.metadata.title!!.length >= 8 }, "title-prefix")
        .mapNotNull { doc ->
            val title = doc.metadata.title!!
            val cut = title.take(max(4, (title.length * 0.6).toInt())).trimEnd()
            if (cut.isBlank()) null
            else EvalQuery("title-prefix", cut, optionsTitledWithPrefix(cut), setOf(SearchScope.Options))
        }

    /** Every option whose title begins with [prefix]. */
    private fun optionsTitledWithPrefix(prefix: String): Set<String> {
        val lower = prefix.lowercase()
        return EvalCorpus.documents
            .filter { SearchScope.Options in it.scopes && it.metadata.title?.lowercase()?.startsWith(lower) == true }
            .map { it.id }
            .toSet()
    }

    /** One typo somewhere in the title. */
    private fun titleTypo(): List<EvalQuery> {
        val random = Random(SEED + "title-typo".hashCode())
        return sample(unambiguousOptions, "title-typo").mapNotNull { doc ->
            val title = doc.metadata.title!!
            val words = title.split(" ").toMutableList()
            val target = words.indices.filter { words[it].length >= 5 }.randomOrNull(random) ?: return@mapNotNull null
            val word = words[target]
            // Swap two adjacent letters: the most common real typo, and one edit away from the original.
            val at = random.nextInt(1, word.length - 1)
            words[target] = word.substring(0, at) + word[at + 1] + word[at] + word.substring(at + 2)
            EvalQuery("title-typo", words.joinToString(" "), setOf(doc.id), setOf(SearchScope.Options))
        }
    }

    /** The user remembers most of the name but not all of it. */
    private fun titleSubset(): List<EvalQuery> {
        val random = Random(SEED + "title-subset".hashCode())
        return sample(unambiguousOptions.filter { it.metadata.title!!.split(" ").size >= 3 }, "title-subset")
            .map { doc ->
                val words = doc.metadata.title!!.split(" ").toMutableList()
                words.removeAt(random.nextInt(words.size))
                EvalQuery("title-subset", words.joinToString(" "), setOf(doc.id), setOf(SearchScope.Options))
            }
    }

    /** "<mod> <option>"*/
    private fun modQualified() = sample(unambiguousOptions.filter { it.metadata.modTitle != null }, "mod-qualified")
        .map { doc ->
            EvalQuery(
                "mod-qualified",
                "${doc.metadata.modTitle} ${doc.metadata.title}",
                setOf(doc.id),
                setOf(SearchScope.Options),
            )
        }

    /**
     * Seach for part of description
     */
    private fun descriptions(): List<EvalQuery> {
        val random = Random(SEED + "description".hashCode())
        val usable = unambiguousOptions.filter { doc ->
            val description = doc.metadata.description ?: return@filter false
            !description.startsWith("Internal ID:") &&
                    description.length in 20..160 &&
                    !description.contains('\n') &&
                    !description.contains(doc.metadata.title!!, ignoreCase = true)
        }
        return sample(usable, "description").mapNotNull { doc ->
            val words = doc.metadata.description!!.split(" ").filter { it.isNotBlank() }
            if (words.size < 4) return@mapNotNull null
            val window = max(3, words.size / 2)
            val from = random.nextInt(0, words.size - window + 1)
            val text = words.subList(from, from + window).joinToString(" ").trim(' ', '.', ',')
            EvalQuery("description", text, setOf(doc.id), setOf(SearchScope.Options))
        }
    }

    /** Looking for a mod by name on the mods screen. */
    private fun modNames() = EvalCorpus.documents
        .filter { SearchScope.Mods in it.scopes && it.metadata.title != null }
        .map { EvalQuery("mod-name", it.metadata.title!!, setOf(it.id), setOf(SearchScope.Mods)) }

    /**
     * One word out of a mod's name, when the name is made of several.
     * Like "particles" out of "OverflowParticles"
     */
    private fun modNameParts(): List<EvalQuery> {
        val relevantByPart = HashMap<String, MutableSet<String>>()
        EvalCorpus.documents
            .filter { SearchScope.Mods in it.scopes && it.metadata.title != null }
            .forEach { mod ->
                nameParts(mod.metadata.title!!).forEach { part ->
                    relevantByPart.getOrPut(part) { mutableSetOf() }.add(mod.id)
                }
            }
        return relevantByPart.entries.sortedBy { it.key }
            .map { (part, ids) -> EvalQuery("mod-part", part, ids, setOf(SearchScope.Mods)) }
    }

    /**
     * Get some joined words out of one word mod names.
     * Like "hurtcam" out of "BetterHurtCam"
     */
    private fun modNameJoins(): List<EvalQuery> {
        val relevantByJoin = HashMap<String, MutableSet<String>>()
        EvalCorpus.documents
            .filter { SearchScope.Mods in it.scopes && it.metadata.title != null }
            .forEach { mod ->
                for (word in mod.metadata.title!!.split(Regex("[^A-Za-z0-9]+"))) {
                    val parts = word.split(Regex("(?<=[a-z0-9])(?=[A-Z])")).map { it.lowercase() }
                    for (start in parts.indices) {
                        for (end in start + 2..parts.size) {
                            if (start == 0 && end == parts.size) continue
                            relevantByJoin.getOrPut(parts.subList(start, end).joinToString("")) { mutableSetOf() }
                                .add(mod.id)
                        }
                    }
                }
            }
        return relevantByJoin.entries.sortedBy { it.key }
            .map { (join, ids) -> EvalQuery("mod-join", join, ids, setOf(SearchScope.Mods)) }
    }

    /**
     * Split parts out of a name
     */
    private fun nameParts(title: String): List<String> {
        val parts = title.split(Regex("[^A-Za-z0-9]+"))
            .flatMap { it.split(Regex("(?<=[a-z0-9])(?=[A-Z])")) }
            .map { it.lowercase() }
            .filter { it.length >= 4 }
            .distinct()
        return if (parts.size < 2) emptyList() else parts
    }

    /** Looking for a keybind by name on the keybind screen. */
    private fun keybinds() = EvalCorpus.documents
        .filter { SearchScope.Keybinds in it.scopes && it.metadata.title != null }
        .filter { it.metadata.title!!.lowercase() !in EvalCorpus.ambiguousTitles }
        .map { EvalQuery("keybind", it.metadata.title!!, setOf(it.id), setOf(SearchScope.Keybinds)) }

    /**
     * Take a subsample of queries per mod
     */
    private fun sample(documents: List<SearchDocument<Unit>>, salt: String): List<SearchDocument<Unit>> =
        documents.groupBy { it.metadata.modTitle }
            .values
            .flatMap { forMod -> forMod.sortedBy { sampleKey(salt, it.id) }.take(PER_MOD) }

    /** Stable pseudo-random ordering, independent of the rest of the corpus. */
    private fun sampleKey(salt: String, id: String): Long {
        val digest = MessageDigest.getInstance("MD5").digest("$SEED:$salt:$id".toByteArray())
        return (0 until 8).fold(0L) { acc, i -> (acc shl 8) or (digest[i].toLong() and 0xFF) }
    }
}

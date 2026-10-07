package org.polyfrost.smartsearch.config

import org.polyfrost.oneconfig.api.config.v1.Config
import org.polyfrost.oneconfig.api.config.v1.Properties
import org.polyfrost.oneconfig.api.config.v1.Tree
import org.polyfrost.oneconfig.api.config.v1.annotations.Button
import org.polyfrost.oneconfig.api.config.v1.annotations.Number
import org.polyfrost.oneconfig.api.config.v1.annotations.Switch
import org.polyfrost.smartsearch.SmartSearchClient
import org.polyfrost.smartsearch.index.DataStore
import org.polyfrost.smartsearch.search.SearchParams
import org.polyfrost.smartsearch.ui.IndexerStatusVisualizer
import org.polyfrost.smartsearch.util.DocumentExporter
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.Path

object SmartSearchConfig : Config(
    "smartsearch.json",
    "SmartSearch",
    Category.OTHER
) {
    @Switch(
        title = "Enabled",
        description = "Whether you want to enable SmartSearch. " +
                "If this is disabled you will use the default OneConfig search."
    )
    var enabled: Boolean = true

    @Switch(
        title = "Enable Semantic Search",
        description = "Whether you want to enable semantic search, " +
                "this activates an ML embedding model to help with searches."
    )
    var enableSemantic: Boolean = true

    fun dbPath(): Path {
        // Make the folder in a hashed subdirectory, so for OneClient, if the mods folder is overwritten
        // it will get a unique folder per instance
        val folder = Path("smartsearch-db")
        val modsFolder = (System.getProperty("fabric.modsFolder") ?: "mods")
        val folderHash = MessageDigest.getInstance("MD5").digest(modsFolder.toByteArray())
        return folder.resolve(folderHash.toHexString())
    }

    @Button(
        title = "Clean database",
    )
    fun cleanDb() {
        DataStore.clean()
    }

    val params: SearchParams
        get() = if (!overwriteDefault) SearchParams.DEFAULT else SearchParams(
            maxResults = maxResults,
            maxLexicalResults = maxLexicalResults,
            lexicalTitleBoost = lexicalTitleBoost,
            lexicalModBoost = lexicalModBoost,
            lexicalContextBoost = lexicalContextBoost,
            lexicalTagBoost = lexicalTagBoost,
            lexicalFieldTieBreak = lexicalFieldTieBreak,
            lexicalTitleStartBoost = lexicalTitleStartBoost,
            lexicalExactBoost = lexicalExactBoost,
            lexicalPhraseBoost = lexicalPhraseBoost,
            lexicalPhraseSlop = lexicalPhraseSlop,
            lexicalMinFuzzyLength = lexicalMinFuzzyLength,
            lexicalFuzzyPrefixLength = lexicalFuzzyPrefixLength,
            lexicalPrefixExpansions = lexicalPrefixExpansions,
            lexicalScoreFloor = lexicalScoreFloor,
            resultScoreFloor = resultScoreFloor,
            maxKnnResults = maxKnnResults,
            minKnnScore = minKnnScore,
            knnWeightScalingStartWords = knnWeightScalingStartWords,
            maxKnnWeightWords = maxKnnWeightWords,
            minKnnWeight = minKnnWeight,
            maxKnnWeight = maxKnnWeight,
        )

    @Switch(
        title = "Overwrite Default Search Settings",
        description = "Overwrite the search tuning settings, only enable this if you know what you are doing.",
        category = "Advanced",
        subcategory = "General",
    )
    var overwriteDefault: Boolean = false

    @Button(
        title = "Export Search Documents",
        description = "Export all currently loaded search documents, useful for benchmarking.",
        category = "Advanced",
    )
    fun exportSearchDocuments() {
        DocumentExporter.export()
    }

    @Button(
        title = "Create Embedding Cache",
        description = "Export all currently loaded embeddings to a cache file that can be used by others.",
        category = "Advanced",
    )
    fun createEmbeddingCache() {
        DataStore.createEmbeddingCache().write(File("embedding-cache"))
    }

    @Number(
        title = "Maximum Results",
        description = "The maximum amount of search results",
        category = "Advanced",
        subcategory = "General",
        min = 1f,
        max = 200f
    )
    var maxResults: Int = SearchParams.DEFAULT.maxResults

    @Number(
        title = "Result Floor",
        description = "Drop results that have a score below this value, after fusing lexical and semantic values.",
        category = "Advanced",
        subcategory = "General",
        min = 0f,
        max = 1f,
    )
    var resultScoreFloor: Float = SearchParams.DEFAULT.resultScoreFloor

    @Number(
        title = "Maximum Lexical Results",
        description = "Amount of results the lexical search pulls before fusion",
        category = "Advanced",
        subcategory = "Lexical",
        min = 1f,
        max = 200f
    )
    var maxLexicalResults: Int = SearchParams.DEFAULT.maxLexicalResults

    @Number(
        title = "Lexical Title Boost",
        description = "Boost in lexical search if the match is found in the title",
        category = "Advanced",
        subcategory = "Lexical",
        min = 1f,
        max = 3f
    )
    var lexicalTitleBoost: Float = SearchParams.DEFAULT.lexicalTitleBoost

    @Number(
        title = "Lexical Mod Name Boost",
        description = "Boost in lexical search if the match is found in the name of the mod the option belongs to",
        category = "Advanced",
        subcategory = "Lexical",
        min = 0f,
        max = 3f
    )
    var lexicalModBoost: Float = SearchParams.DEFAULT.lexicalModBoost

    @Number(
        title = "Lexical Category Boost",
        description = "Boost in lexical search if the match is found in the option's category, subcategory or page",
        category = "Advanced",
        subcategory = "Lexical",
        min = 0f,
        max = 3f
    )
    var lexicalContextBoost: Float = SearchParams.DEFAULT.lexicalContextBoost

    @Number(
        title = "Lexical Tag Boost",
        description = "Boost in lexical search if the match is found in the option's tags",
        category = "Advanced",
        subcategory = "Lexical",
        min = 0f,
        max = 3f
    )
    var lexicalTagBoost: Float = SearchParams.DEFAULT.lexicalTagBoost

    @Number(
        title = "Lexical Field Agreement",
        description = "How much a word matching in several fields at once counts for. At 0 a word is scored against " +
                "whichever field suits it best, so matching in the title, the mod name and the category is worth " +
                "no more than matching in the title alone.",
        category = "Advanced",
        subcategory = "Lexical",
        min = 0f,
        max = 1f
    )
    var lexicalFieldTieBreak: Float = SearchParams.DEFAULT.lexicalFieldTieBreak

    @Number(
        title = "Lexical Title Start Boost",
        description = "How much to boost an option if the word you are currently typing in the query matches the title.",
        category = "Advanced",
        subcategory = "Lexical",
        min = 0f,
        max = 10f
    )
    var lexicalTitleStartBoost: Float = SearchParams.DEFAULT.lexicalTitleStartBoost

    @Number(
        title = "Lexical Exact Match Boost",
        description = "Boost in lexical search if there is an exact match. Below 1 because the fuzzy and prefix " +
                "matches already score the exact word too, so it would otherwise be counted several times over.",
        category = "Advanced",
        subcategory = "Lexical",
        min = 0f,
        max = 3f
    )
    var lexicalExactBoost: Float = SearchParams.DEFAULT.lexicalExactBoost

    @Number(
        title = "Lexical Phrase Boost",
        description = "Boost in lexical search when the searched words appear together, in order, rather than " +
                "scattered across the option",
        category = "Advanced",
        subcategory = "Lexical",
        min = 0f,
        max = 10f
    )
    var lexicalPhraseBoost: Float = SearchParams.DEFAULT.lexicalPhraseBoost

    @Number(
        title = "Lexical Phrase Slop",
        description = "How many words may sit between the searched words and still count as a phrase",
        category = "Advanced",
        subcategory = "Lexical",
        min = 0f,
        max = 8f
    )
    var lexicalPhraseSlop: Int = SearchParams.DEFAULT.lexicalPhraseSlop

    @Number(
        title = "Minimum Length for Fuzzy Search",
        description = "Minimum length of the search query before fuzzy search is used, " +
                "shorter queries will surface a lot of junk with fuzzy search.",
        category = "Advanced",
        subcategory = "Lexical",
        min = 1f,
        max = 20f
    )
    var lexicalMinFuzzyLength: Int = SearchParams.DEFAULT.lexicalMinFuzzyLength

    @Number(
        title = "Fuzzy Search Fixed Prefix",
        description = "How many leading characters a fuzzy match must get right.",
        category = "Advanced",
        subcategory = "Lexical",
        min = 0f,
        max = 4f
    )
    var lexicalFuzzyPrefixLength: Int = SearchParams.DEFAULT.lexicalFuzzyPrefixLength

    @Number(
        title = "Search Prefix Expansions",
        description = "In search-as-you-type search how many prefix completions are tried to evaluate and score.",
        category = "Advanced",
        subcategory = "Lexical",
        min = 0f,
        max = 256f
    )
    var lexicalPrefixExpansions: Int = SearchParams.DEFAULT.lexicalPrefixExpansions

    @Number(
        title = "Lexical Result Floor",
        description = "Drop matches that are below this score, before fusing with semantic results.",
        category = "Advanced",
        subcategory = "Lexical",
        min = 0f,
        max = 1f,
    )
    var lexicalScoreFloor: Float = SearchParams.DEFAULT.lexicalScoreFloor

    @Number(
        title = "Maximum Semantic Results",
        description = "Amount of results the semantic (KNN) vector search pulls before fusion.",
        category = "Advanced",
        subcategory = "Semantic",
        min = 1f,
        max = 200f
    )
    var maxKnnResults: Int = SearchParams.DEFAULT.maxKnnResults

    @Number(
        title = "Minimum Semantic Score",
        description = "The minimum score for semantic (KNN) vector search results.",
        category = "Advanced",
        subcategory = "Semantic",
        min = 0f,
        max = 1f,
    )
    var minKnnScore: Float = SearchParams.DEFAULT.minKnnScore

    @Number(
        title = "Words before weight scaling starts",
        description = "The amount of words needed before the weight scaling of semantic results start.",
        category = "Advanced",
        subcategory = "Semantic",
        min = 1f,
        max = 10f,
    )
    var knnWeightScalingStartWords: Int = SearchParams.DEFAULT.knnWeightScalingStartWords

    @Number(
        title = "Words for maximum semantic weight",
        description = "The weight of the semantic search depends on the length of the search query, " +
                "this option decides the amount of words before the maximum weight is reached.",
        category = "Advanced",
        subcategory = "Semantic",
        min = 1f,
        max = 10f,
    )
    var maxKnnWeightWords: Int = SearchParams.DEFAULT.maxKnnWeightWords

    @Number(
        title = "Minimum Semantic Weight",
        description = "The weight attached to semantic search results for a short query.",
        category = "Advanced",
        subcategory = "Semantic",
        min = 0f,
        max = 3f,
    )
    var minKnnWeight: Float = SearchParams.DEFAULT.minKnnWeight

    @Number(
        title = "Maximum Semantic Weight",
        description = "The weight attached to semantic search results for a long query.",
        category = "Advanced",
        subcategory = "Semantic",
        min = 0f,
        max = 3f,
    )
    var maxKnnWeight: Float = SearchParams.DEFAULT.maxKnnWeight

    override fun makeTree(): Tree {
        // Put at the top
        val collected = super.makeTree()
        val reordered = Tree(collected.id, collected.title, collected.description, null)

        // Add status visualizer
        reordered.put(
            Properties.dummy(
                "indexerStatus",
                "Search index",
                "The state of the embedding model and the search index."
            ).apply {
                addMetadata("visualizer", IndexerStatusVisualizer)
                addMetadata("category", "General")
                addMetadata("subcategory", "General")
            })
        collected.map.values.forEach(reordered::put)
        collected.metadata?.let { reordered.addMetadata(HashMap(it)) }

        return reordered
    }

    init {
        addDependency("resultScoreFloor", "overwriteDefault")
        addDependency("maxResults", "overwriteDefault")
        addDependency("maxLexicalResults", "overwriteDefault")
        addDependency("lexicalTitleBoost", "overwriteDefault")
        addDependency("lexicalModBoost", "overwriteDefault")
        addDependency("lexicalContextBoost", "overwriteDefault")
        addDependency("lexicalTagBoost", "overwriteDefault")
        addDependency("lexicalFieldTieBreak", "overwriteDefault")
        addDependency("lexicalTitleStartBoost", "overwriteDefault")
        addDependency("lexicalExactBoost", "overwriteDefault")
        addDependency("lexicalPhraseBoost", "overwriteDefault")
        addDependency("lexicalPhraseSlop", "overwriteDefault")
        addDependency("lexicalMinFuzzyLength", "overwriteDefault")
        addDependency("lexicalFuzzyPrefixLength", "overwriteDefault")
        addDependency("lexicalPrefixExpansions", "overwriteDefault")
        addDependency("lexicalScoreFloor", "overwriteDefault")
        addDependency("maxKnnResults", "overwriteDefault")
        addDependency("minKnnScore", "overwriteDefault")
        addDependency("knnWeightScalingStartWords", "overwriteDefault")
        addDependency("maxKnnWeightWords", "overwriteDefault")
        addDependency("minKnnWeight", "overwriteDefault")
        addDependency("maxKnnWeight", "overwriteDefault")

        addCallback<Boolean>("enableSemantic") { value ->
            if (value) {
                SmartSearchClient.startModel()
            }
            false
        }
    }
}

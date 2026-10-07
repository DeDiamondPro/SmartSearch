package org.polyfrost.smartsearch.search

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute
import org.apache.lucene.index.Term
import org.apache.lucene.search.*
import org.polyfrost.oneconfig.internal.ui.search.SearchScope
import org.polyfrost.smartsearch.index.JOINS_SUFFIX
import org.polyfrost.smartsearch.index.SearchIndex
import org.polyfrost.smartsearch.index.titleKey
import org.polyfrost.smartsearch.index.toKey

private val WORD_TERMINATOR = Regex("[\\s_\\-.]+")

/** Every indexed text field, in the order their boosts are listed in [SearchParams]. */
private val SEARCH_FIELDS = listOf("title", "description", "mod", "context", "tags")

/** IDs of mods options we derank to prevent them polluting search */
private val DERANKED_IDS = listOf("soundtweaks.json")

object SearchEngine {
    /**
     * One side of the search before fusing, [zero] notes the zero point of this arm for scoring
     */
    private class Arm(val hits: List<ScoreDoc>, val weight: Double, val zero: Float)

    /**
     * Query cut in to terms, and check if the last part is a partial
     */
    private class Analyzed(val terms: List<String>, val lastIsPartial: Boolean)

    /**
     * Ranked document ids for [query], best first, capped at [SearchParams.maxResults].
     *
     * [embedQuery] turns the query into a vector, or returns null when no model is loaded, then we fall back
     * to lexical search
     */
    fun rank(
        index: SearchIndex,
        query: String,
        scopes: Set<SearchScope>,
        params: SearchParams = SearchParams.DEFAULT,
        embedQuery: (String) -> FloatArray?,
    ): List<String> {
        if (query.isBlank()) return emptyList()
        val scopeFilter = if (scopes.isEmpty()) null else scopeQuery(scopes)

        return index.search { reader, searcher, analyzer ->
            val lexical = textQuery(analyzer, query, params)?.let {
                searcher.search(
                    filtered(it, scopeFilter),
                    params.maxLexicalResults
                ).scoreDocs.asList()
            }.orEmpty().aboveFloor(params.lexicalScoreFloor)

            val semantic = embedQuery(query)?.let {
                searcher.search(
                    KnnFloatVectorQuery("embedding", it, params.maxKnnResults, scopeFilter),
                    params.maxKnnResults
                    // Hits come back descending, so the first one under the floor ends the useful part of the list.
                ).scoreDocs.takeWhile { hit -> hit.score >= params.minKnnScore }
            }.orEmpty()

            val storedFields = reader.storedFields()
            fuse(
                params,
                Arm(lexical, weight = 1.0, zero = 0f),
                Arm(semantic, weight = semanticWeight(query, params), zero = params.minKnnScore),
            ).mapNotNull { doc ->
                storedFields.document(doc, setOf("id")).get("id")
            }.sortedBy { it.isDeranked() } // Move deranked to bottom
        }.orEmpty()
    }

    private fun String.isDeranked(): Boolean = DERANKED_IDS.any { startsWith("$it::") }

    /**
     * Drop hits scoring less then [floor]
     */
    private fun List<ScoreDoc>.aboveFloor(floor: Float): List<ScoreDoc> {
        if (floor <= 0f || isEmpty()) return this
        val cutoff = first().score * floor
        return takeWhile { it.score >= cutoff }
    }

    /** Merge the arms into one ranking, with a weight assigned to each list. */
    private fun fuse(params: SearchParams, vararg arms: Arm): List<Int> {
        val scores = HashMap<Int, Double>()
        for (arm in arms) {
            val contribution = contributions(params, arm)
            arm.hits.forEachIndexed { rank, hit ->
                scores.merge(hit.doc, arm.weight * contribution[rank], Double::plus)
            }
        }
        // Ties are broken by document id for determinism
        val ranked = scores.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Double>> { it.value }.thenBy { it.key })
            .take(params.maxResults)
        val cutoff = (ranked.firstOrNull()?.value ?: 0.0) * params.resultScoreFloor
        return ranked.takeWhile { it.value >= cutoff }.map { it.key }
    }

    /**
     * Calculate the weight of each entry
     *
     * [FusionMode.SCORE] normalises the score across the hits in the arm
     */
    private fun contributions(params: SearchParams, arm: Arm): DoubleArray {
        val hits = arm.hits
        if (params.fusion == FusionMode.RANK) {
            return DoubleArray(hits.size) { 1.0 / (params.rankFusionDampening + it + 1) }
        }

        val span = (hits.firstOrNull()?.score ?: 0f) - arm.zero
        if (span <= 1e-6f) return DoubleArray(hits.size) { 1.0 }
        return DoubleArray(hits.size) { ((hits[it].score - arm.zero) / span).toDouble().coerceAtLeast(0.0) }
    }

    /**
     * Weight of lexical vs semantic
     */
    private fun semanticWeight(query: String, params: SearchParams): Double {
        val words = query.trim().split(WORD_TERMINATOR).size
        val ramp = ((words - params.knnWeightScalingStartWords).toDouble()
                / (params.maxKnnWeightWords - params.knnWeightScalingStartWords).coerceAtLeast(1))
            .coerceIn(0.0, 1.0)
        return params.minKnnWeight + ramp * (params.maxKnnWeight - params.minKnnWeight)
    }

    private fun filtered(query: Query, filter: Query?): Query = if (filter == null) query else {
        BooleanQuery.Builder()
            .add(query, BooleanClause.Occur.MUST)
            .add(filter, BooleanClause.Occur.FILTER)
            .build()
    }

    /** Matches documents carrying at least one of [scopes]. */
    private fun scopeQuery(scopes: Set<SearchScope>): Query {
        val builder = BooleanQuery.Builder()
        for (scope in scopes) {
            builder.add(TermQuery(Term("scope", scope.toKey())), BooleanClause.Occur.SHOULD)
        }
        builder.setMinimumNumberShouldMatch(1)
        return builder.build()
    }

    /** Matches [queryText] against the indexed text, or null when it analyzes to nothing searchable. */
    private fun textQuery(analyzer: Analyzer, queryText: String, params: SearchParams): Query? {
        val analyzed = analyzeToTerms(analyzer, SEARCH_FIELDS.first(), queryText)
        val terms = analyzed.terms
        if (terms.isEmpty()) return null

        val boosts = listOf(
            params.lexicalTitleBoost,
            1f,
            params.lexicalModBoost,
            params.lexicalContextBoost,
            params.lexicalTagBoost,
        )
        val fields = SEARCH_FIELDS.zip(boosts)
        val wordFields = fields + listOf(
            "title$JOINS_SUFFIX" to params.lexicalTitleBoost,
            "mod$JOINS_SUFFIX" to params.lexicalModBoost,
        )
        // The user is still typing unless they ended on a separator, so the last token is treated as a prefix.
        val lastIsPartial = analyzed.lastIsPartial

        val words = BooleanQuery.Builder()
        terms.forEachIndexed { index, term ->
            val partial = lastIsPartial && index == terms.lastIndex
            words.add(
                bestField(params, wordFields) { field -> termClause(field, term, partial, params) },
                BooleanClause.Occur.SHOULD,
            )
        }

        val builder = BooleanQuery.Builder().add(words.build(), BooleanClause.Occur.MUST)
        bestFieldOrNull(params, fields) { field -> phraseClause(field, terms, lastIsPartial, params) }
            ?.let { builder.add(it, BooleanClause.Occur.SHOULD) }
        titleStartClause(queryText, params)?.let { builder.add(it, BooleanClause.Occur.SHOULD) }
        return builder.build()
    }

    /**
     * Reward options where the title begins with was typed.
     * Helps with accuracy when starting to type a title.
     */
    private fun titleStartClause(queryText: String, params: SearchParams): Query? {
        if (params.lexicalTitleStartBoost <= 0f) return null
        val key = titleKey(queryText)
        if (key.isEmpty()) return null
        return BoostQuery(PrefixQuery(Term("title_key", key)), params.lexicalTitleStartBoost)
    }

    private fun bestField(
        params: SearchParams,
        fields: List<Pair<String, Float>>,
        clause: (String) -> Query,
    ): Query = DisjunctionMaxQuery(
        fields.map { (field, boost) -> BoostQuery(clause(field), boost) },
        params.lexicalFieldTieBreak,
    )

    private fun bestFieldOrNull(
        params: SearchParams,
        fields: List<Pair<String, Float>>,
        clause: (String) -> Query?,
    ): Query? {
        val clauses = fields.mapNotNull { (field, boost) -> clause(field)?.let { BoostQuery(it, boost) } }
        if (clauses.isEmpty()) return null
        return DisjunctionMaxQuery(clauses, params.lexicalFieldTieBreak)
    }

    /**
     * Rewards documents where the query's words appear together and in order.
     */
    private fun phraseClause(field: String, terms: List<String>, lastIsPartial: Boolean, params: SearchParams): Query? {
        if (params.lexicalPhraseBoost <= 0f) return null
        // Last word is not complete so doesn't take part in the sentence
        val phraseTerms = if (lastIsPartial) terms.dropLast(1) else terms
        if (phraseTerms.size < 2) return null

        val phrase = PhraseQuery.Builder().apply {
            setSlop(params.lexicalPhraseSlop)
            phraseTerms.forEach { add(Term(field, it)) }
        }.build()
        return BoostQuery(phrase, params.lexicalPhraseBoost)
    }

    /**
     * Create a clause for a term against a field, supports partial and fuzzy matching
     *
     * The three clauses overlap, so the boost if all 3 hit shouldn't be astronomical
     */
    private fun termClause(field: String, term: String, partial: Boolean, params: SearchParams): Query {
        val exact = TermQuery(Term(field, term))
        val builder = BooleanQuery.Builder()
            .add(BoostQuery(exact, params.lexicalExactBoost), BooleanClause.Occur.SHOULD)

        if (partial) {
            // Score the expansion of what the user could be typing instead, since PrefixQuery is constant score
            builder.add(
                PrefixQuery(
                    Term(field, term),
                    MultiTermQuery.TopTermsScoringBooleanQueryRewrite(params.lexicalPrefixExpansions)
                ),
                BooleanClause.Occur.SHOULD,
            )
        }
        // Below this length an edit covers too much of the word to stay meaningful.
        if (term.length >= params.lexicalMinFuzzyLength) {
            builder.add(
                FuzzyQuery(Term(field, term), 1, params.lexicalFuzzyPrefixLength),
                BooleanClause.Occur.SHOULD,
            )
        } else if (!partial) {
            // No lexical exact boost since with just the exact there is no overlap with fuzzy/partial
            return exact
        }

        builder.setMinimumNumberShouldMatch(1)
        return builder.build()
    }

    private fun analyzeToTerms(analyzer: Analyzer, field: String, text: String): Analyzed {
        val terms = mutableListOf<String>()
        var lastEnd = -1
        analyzer.tokenStream(field, text).use { tokenStream ->
            val attr = tokenStream.addAttribute(CharTermAttribute::class.java)
            val offset = tokenStream.addAttribute(OffsetAttribute::class.java)

            tokenStream.reset()
            while (tokenStream.incrementToken()) {
                terms.add(attr.toString())
                lastEnd = offset.endOffset()
            }
            tokenStream.end()
        }

        return Analyzed(terms, lastEnd == text.length)
    }
}

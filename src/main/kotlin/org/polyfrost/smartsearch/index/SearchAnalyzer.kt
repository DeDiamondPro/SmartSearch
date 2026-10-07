package org.polyfrost.smartsearch.index

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.LowerCaseFilter
import org.apache.lucene.analysis.TokenFilter
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.core.FlattenGraphFilter
import org.apache.lucene.analysis.en.EnglishMinimalStemFilter
import org.apache.lucene.analysis.en.EnglishPossessiveFilter
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter
import org.apache.lucene.analysis.miscellaneous.WordDelimiterGraphFilter
import org.apache.lucene.analysis.standard.StandardTokenizer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute

/**
 * Splits a joined name (like OverflowParticles) into split, searchable words
 */
private const val DELIMITER_FLAGS = WordDelimiterGraphFilter.GENERATE_WORD_PARTS or
        WordDelimiterGraphFilter.GENERATE_NUMBER_PARTS or
        WordDelimiterGraphFilter.SPLIT_ON_CASE_CHANGE or
        WordDelimiterGraphFilter.SPLIT_ON_NUMERICS or
        WordDelimiterGraphFilter.PRESERVE_ORIGINAL

/**
 * Fields ending in this hold the joined parts of split names, see [PartJoinFilter].
 */
const val JOINS_SUFFIX = "_joins"

/**
 * How option/query text is cut into searchable words.
 */
class SearchAnalyzer(private val splitCompounds: Boolean) : Analyzer(PER_FIELD_REUSE_STRATEGY) {

    override fun createComponents(fieldName: String): TokenStreamComponents {
        val tokenizer = StandardTokenizer()
        var stream: TokenStream = tokenizer
        if (splitCompounds) {
            // Before lowercasing, or there is no case left to split on.
            stream = WordDelimiterGraphFilter(stream, DELIMITER_FLAGS, null)
            stream = FlattenGraphFilter(stream)
            if (fieldName.endsWith(JOINS_SUFFIX)) stream = PartJoinFilter(stream)
        }
        stream = LowerCaseFilter(stream)
        stream = ASCIIFoldingFilter(stream)
        stream = EnglishPossessiveFilter(stream)
        stream = EnglishMinimalStemFilter(stream)
        return TokenStreamComponents(tokenizer, stream)
    }
}

/**
 * After "BetterHurtCam" gets split to "better", "hurt", "cam" this will re-assemble it to "betterhurt" and "hurtcam"
 * so search on "hurtcam" can match.
 */
internal class PartJoinFilter(input: TokenStream) : TokenFilter(input) {
    private val termAttr = addAttribute(CharTermAttribute::class.java)
    private val posIncAttr = addAttribute(PositionIncrementAttribute::class.java)
    private val posLenAttr = addAttribute(PositionLengthAttribute::class.java)

    private var position = -1
    private var compoundEnd = -1
    private val parts = mutableListOf<String>()
    private val pending = ArrayDeque<String>()
    private var partState: State? = null

    override fun incrementToken(): Boolean {
        while (pending.isEmpty()) {
            if (!input.incrementToken()) return false
            position += posIncAttr.positionIncrement
            if (posLenAttr.positionLength > 1) {
                compoundEnd = position + posLenAttr.positionLength
                parts.clear()
            } else if (position < compoundEnd) {
                parts.add(termAttr.toString())
                val isLast = position == compoundEnd - 1
                // Don't join all parts, would result in full name, which we already have
                for (start in (if (isLast) 1 else 0) until parts.size - 1) {
                    pending.add(parts.subList(start, parts.size).joinToString(""))
                }
                partState = captureState()
            }
        }
        restoreState(partState)
        termAttr.setEmpty().append(pending.removeFirst())
        posIncAttr.positionIncrement = 1
        posLenAttr.positionLength = 1
        return true
    }

    override fun reset() {
        super.reset()
        position = -1
        compoundEnd = -1
        parts.clear()
        pending.clear()
        partState = null
    }
}

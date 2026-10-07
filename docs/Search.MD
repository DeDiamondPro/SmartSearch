# Search Docs

Technical documentation about how the search works, split in the lexical and semantics part.

## Lexical Search

### Analyzer

Lucense is used for analysis and indexing. The analyzer used is in SearchAnalyzer.kt, this tokenizes the string. The
following filters are used:

- When indexing (not in queries) we use `WordDelimiterGraphFilter` to split names like "OverflowParticles" into
  "overflow" and "particles", this improves search a lot if the user just searches "particles". This graph is then
  flattened.
- The title and mod title are also indexed a second time into joined parts so for "BetterHurtCam" we store "betterhurt"
  and "hurtcam", this makes it so we can search on "hurtcam"
- The tokenstream is converted to lowercase
- ASCIIFoldingFilter, folds letters to ASCII ("é" -> "e")
- EnglishPossessiveFilter, takes thing like "Jeff's" -> "Jeff"
- EnglishMinimalStemFilter does same basic stemming, like "cat" -> "cats"

These steps basically ensure we have a normalized string to search and index.

### Used fields

Lexical search uses almost all fields available, since we don't have to worry about vector dilution, which is a concern
in semantic search.

We use the following fields:

- title
- description
- mod title
- category, subcategory, section and path concatenated into 1 field,
- search tags

Additionally, we also store the title again, but without being analyzed. This improves search results targeting exactly
the title while the user is still typing.

### Search

We then search against these fields, having quite a bit of boosts depending on what was match, the exact values of these
boosts can be found in SearchParams.kt.

For each word in the text we add the following queries that can match:

- Exact match (weighted higher)
- Fuzzy match
- If it's the last word (user might be typing it), we try to rank the top 64 most likely expansions. The reason we do
  this instead of just searching for something that starts with the string is that would cause every match to be treated
  as equal, which is not the case, so this improves ranking.

Then we have an additional query that gives a huge boost that checks if the title of an option starts with exactly what
the user is typing, using the un-analyzed field.

Lastly we have a PhraseQuery where we reward matches where the words in the query appear together and in the correct
order.

All of these things together help with finding a match and ranking possible matches reliably.

## Semantic search

Semantic search uses an embedding model to create a 384 dimensional vector, which we use for a cosine distance search.
We use the arctic embed xs model since it is very small (23mil params) and very fast. A larger model could help a bit in
some cases, but usually isn't worth it due to us searching a relatively small amount of entries.

For the embedding we use the following:

- The title of the entry
- The category and subcategory, but only if they are not "general" (this would dilute the vector), subcategory is also
  omitted if it matches the category
- The search tags
- The description of the option
- The title of the mod
- The description of the mod, this can help in cases where an option itself has a weak description

Queries then use the model specific prefix when embedding for optimal performance.

All results with a confidence under 0.8 (lucene's normalized cosine score) are dropped since they usually just add junk
results.

## Fusing

Both searches run separately and produce their own ranked list, called an arm. These two arms then have to be merged
into one ranking, which is what fusing does.

Each arm then converts its hits into a contribution per rank, this is done by normalizing the scores in [0,1], The zero
point has to be different per arm, for lexical search the zero point is simply 0, for semantic it is the confidence
floor (0.8), since a 0.8 result is worthless to us and treating it as such would compress the whole useful range into
the top 20% of the scale.

The contributions of both arms are then multiplied by that arm's weight and summed per document, so documents found by
both arms naturally rank higher.

The lexical arm always has weight 1, the semantic weight depends on the amount of words in the query. Shorter queries
usually are a better match for lexical, while longer queries is where semantic search shines. The weight of semantic
search scales from 0.25 to 2 when going from 2 words to 6 words.

Lastly the merged list is capped at the maximum amount of results, and everything under a score threshold (0.4) is
dropped to weed out junk results.

If no embedding model is loaded only lexical search will be used.
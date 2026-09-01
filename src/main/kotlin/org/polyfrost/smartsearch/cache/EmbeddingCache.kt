package org.polyfrost.smartsearch.cache

import dev.langchain4j.data.embedding.Embedding
import java.io.File
import java.nio.ByteBuffer

private const val EMBEDDING_SIZE = 384
private val MAGIC_BYTES = "OCSS".toByteArray() // 4 bytes: "OneConfig SmartSearch"
private const val FILE_FORMAT_VERSION: Byte = 1

/**
 * Cache of precomputed embeddings for config options, loaded from a file.
 *
 * The file format is just some magic bytes, file version number, and then for each entry
 * the MD5 hash and embedding vector after each other, these are a set size so no extra info is needed.
 */
class EmbeddingCache(
    /**
     * Map of MD5 hashes to embeddings
     */
    val cache: Map<String, Embedding>
) {
    fun write(file: File) {
        file.outputStream().buffered().use { out ->
            out.write(MAGIC_BYTES + FILE_FORMAT_VERSION)

            // Room for MD5 + float vector
            val buffer = ByteBuffer.allocate(16 + EMBEDDING_SIZE * 4)
            cache.forEach { (hash, embedding) ->
                val hashBytes = hash.hexToByteArray()
                val vector = embedding.vector()
                check(hashBytes.size == 16) { "bad hash length for $hash" }
                check(vector.size == EMBEDDING_SIZE) { "bad vector size for $hash" }

                // MD5 + float vector
                buffer.rewind()
                buffer.put(hashBytes)
                vector.forEach { buffer.putFloat(it) }
                out.write(buffer.array())
            }
        }
    }

    companion object {
        fun read(file: File): EmbeddingCache {
            file.inputStream().buffered().use { input ->
                // Check magic bytes
                val magicBytes = input.readNBytes(MAGIC_BYTES.size)
                check(magicBytes.size == MAGIC_BYTES.size) { "Could not read magic bytes, got ${magicBytes.toHexString()}" }
                check(magicBytes.contentEquals(MAGIC_BYTES)) { "Invalid magic bytes, got ${magicBytes.toHexString()}" }
                // Check version
                val version = input.read()
                check(version.toByte() == FILE_FORMAT_VERSION) { "Unsupported file format version, got $version" }

                // Read entries
                val buff = ByteBuffer.allocate(16 + EMBEDDING_SIZE * 4)
                val res = mutableMapOf<String, Embedding>()
                while (true) {
                    val read = input.readNBytes(buff.array(), 0, buff.capacity())
                    if (read == 0) break
                    check(read == buff.capacity()) { "Corrupt cache: partial entry ($read bytes)" }

                    buff.rewind()
                    val hashBytes = ByteArray(16)
                    buff.get(hashBytes)
                    val vector = FloatArray(EMBEDDING_SIZE)
                    for (i in 0 until EMBEDDING_SIZE) {
                        vector[i] = buff.getFloat()
                    }
                    res[hashBytes.toHexString()] = Embedding(vector)
                }
                return EmbeddingCache(res)
            }
        }
    }
}
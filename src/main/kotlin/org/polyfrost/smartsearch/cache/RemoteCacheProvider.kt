package org.polyfrost.smartsearch.cache

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.polyfrost.smartsearch.SmartSearchClient
import org.polyfrost.smartsearch.config.SmartSearchConfig
import org.polyfrost.smartsearch.index.Embedder
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import kotlin.io.path.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

private const val CACHE_URL = "https://data-v2.polyfrost.org/smartsearch/embedding-cache"
private const val CHECKSUM_URL = "$CACHE_URL.sha256"

private val CACHE_FILE: Path = Path("smartsearch-db/embedding-cache")
private val PARTIAL_FILE: Path = Path("smartsearch-db/embedding-cache.part")

/**
 * Downloads the embedding cache and loads it
 */
object RemoteCacheProvider {
    private val hasRegistered = AtomicBoolean(false)
    private val syncStarted = AtomicBoolean(false)

    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(10.seconds.toJavaDuration())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    fun register() {
        if (hasRegistered.getAndSet(true)) return
        CacheStore.registerCacheProvider(::load)
    }

    /**
     * Downloads a new cache if the remote one is newer
     */
    fun sync() {
        if (!SmartSearchConfig.enableSemantic || syncStarted.getAndSet(true)) return
        SmartSearchClient.scope.launch { runSync() }
    }

    private fun load(): EmbeddingCache? {
        if (!CACHE_FILE.exists()) return null
        return EmbeddingCache.read(CACHE_FILE.toFile())
    }

    private suspend fun runSync() {
        val remoteHash = try {
            runInterruptible(Dispatchers.IO) { fetchChecksum() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SmartSearchClient.LOGGER.warn("Failed to fetch embedding cache checksum", e)
            null
        } ?: return

        if (remoteHash == runInterruptible(Dispatchers.IO) { localHash() }) {
            SmartSearchClient.LOGGER.info("Embedding cache is up to date")
            return
        }

        try {
            runInterruptible(Dispatchers.IO) { download(remoteHash) }
        } catch (e: TimeoutCancellationException) {
            SmartSearchClient.LOGGER.error("Timed out downloading embedding cache", e)
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SmartSearchClient.LOGGER.error("Failed to download embedding cache", e)
            return
        } finally {
            runCatching { PARTIAL_FILE.deleteIfExists() }
        }

        CacheStore.invalidate()
        Embedder.recheckCache()
    }

    private fun localHash(): String? {
        if (!CACHE_FILE.exists()) return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            CACHE_FILE.inputStream().use { input ->
                DigestInputStream(input, digest).use { it.transferTo(OutputStream.nullOutputStream()) }
            }
            digest.digest().toHexString()
        } catch (e: Exception) {
            SmartSearchClient.LOGGER.warn("Failed to hash the local embedding cache", e)
            null
        }
    }

    private fun fetchChecksum(): String? {
        val response =
            client.send(request(CHECKSUM_URL, 10.seconds.toJavaDuration()), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            SmartSearchClient.LOGGER.warn("Checksum request for cache returned ${response.statusCode()}")
            return null
        }

        return response.body().trim()
    }

    private fun download(expectedHash: String) {
        PARTIAL_FILE.parent?.createDirectories()
        val response =
            client.send(request(CACHE_URL, 2.minutes.toJavaDuration()), HttpResponse.BodyHandlers.ofInputStream())
        check(response.statusCode() == 200) { "Cache download returned ${response.statusCode()}" }

        val digest = MessageDigest.getInstance("SHA-256")
        PARTIAL_FILE.outputStream().buffered().use { out ->
            DigestInputStream(decode(response), digest).use { it.transferTo(out) }
        }
        val hash = digest.digest().toHexString()
        check(hash == expectedHash) { "Downloaded cache is corrupt, expected $expectedHash, got $hash" }

        Files.move(PARTIAL_FILE, CACHE_FILE, StandardCopyOption.REPLACE_EXISTING)
        SmartSearchClient.LOGGER.info("Updated embedding cache")
    }

    private fun request(url: String, timeout: Duration): HttpRequest = HttpRequest.newBuilder(URI.create(url))
        .header("User-Agent", "SmartSearch")
        .header("Accept-Encoding", "gzip")
        .timeout(timeout)
        .GET()
        .build()

    private fun decode(response: HttpResponse<InputStream>): InputStream {
        val encoding = response.headers().firstValue("Content-Encoding").orElse("")
        return if (encoding.equals("gzip", ignoreCase = true)) {
            GZIPInputStream(response.body())
        } else {
            response.body()
        }
    }
}

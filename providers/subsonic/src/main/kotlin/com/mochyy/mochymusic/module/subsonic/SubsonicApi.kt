package com.mochyy.mochymusic.module.subsonic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder
import java.security.MessageDigest

/** Blocking HTTP, provided by whoever uses the API (module host or app). */
fun interface HttpExecutor {
    /** returns HTTP status code + body text */
    fun get(url: String, headers: Map<String, String>): Pair<Int, String>
}

data class SubSong(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String?,
    val album: String?,
    val albumId: String?,
    val durationSec: Int,
    val year: Int?,
    val genre: String?,
    val coverArt: String?,
    val track: Int?,
)

data class SubAlbum(
    val id: String,
    val name: String,
    val artist: String?,
    val artistId: String?,
    val year: Int?,
    val genre: String?,
    val coverArt: String?,
    val songCount: Int,
)

data class SubArtist(
    val id: String,
    val name: String,
    val albumCount: Int,
    val coverArt: String?,
)

/**
 * Minimal Subsonic client (JSON API): token auth, search, library walk,
 * stream and cover-art URLs. Works against plain servers and Octo-Fiesta
 * proxies (whose ext-* Deezer/Qobuz entries stream through getStream).
 */
class SubsonicApi(
    val base: String,
    private val user: String,
    private val pass: String,
    private val http: HttpExecutor,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun ping(): Boolean {
        val (code, body) = runCatching { http.get(url("ping.view"), emptyMap()) }.getOrNull() ?: return false
        return code in 200..299 && ok(body) != null
    }

    fun searchSongs(query: String, count: Int = 25): List<SubSong> {
        val (_, body) = runCatching {
            http.get(url("search3.view", "query" to query, "songCount" to count.toString()), emptyMap())
        }.getOrNull() ?: return emptyList()
        val result = ok(body)?.get("searchResult3") as? JsonObject ?: return emptyList()
        return (result["song"] as? JsonArray).orEmpty().mapNotNull { it.parseSong() }
    }

    fun artists(): List<SubArtist> {
        val (_, body) = runCatching { http.get(url("getArtists.view"), emptyMap()) }.getOrNull() ?: return emptyList()
        val root = ok(body) ?: return emptyList()
        val index = (root["artists"] as? JsonObject)?.get("index") as? JsonArray ?: return emptyList()
        return index.flatMap { group ->
            ((group as? JsonObject)?.get("artist") as? JsonArray).orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val id = o.str("id") ?: return@mapNotNull null
                SubArtist(id, o.str("name") ?: id, o.int("albumCount") ?: 0, o.str("coverArt"))
            }
        }
    }

    fun artistAlbums(artistId: String): List<SubAlbum> {
        val (_, body) = runCatching { http.get(url("getArtist.view", "id" to artistId), emptyMap()) }.getOrNull() ?: return emptyList()
        val artist = ok(body)?.get("artist") as? JsonObject ?: return emptyList()
        return (artist["album"] as? JsonArray).orEmpty().mapNotNull { it.parseAlbum() }
    }

    fun albumSongs(albumId: String): List<SubSong> {
        val (_, body) = runCatching { http.get(url("getAlbum.view", "id" to albumId), emptyMap()) }.getOrNull() ?: return emptyList()
        val album = ok(body)?.get("album") as? JsonObject ?: return emptyList()
        return (album["song"] as? JsonArray).orEmpty().mapNotNull { it.parseSong() }
    }

    fun streamUrl(id: String, maxBitRate: Int? = null, format: String? = null): String {
        val extra = buildList {
            maxBitRate?.let { add("maxBitRate" to it.toString()) }
            format?.let { add("format" to it) }
        }
        return url("stream.view", listOf("id" to id) + extra)
    }

    fun coverArtUrl(coverArtId: String, size: Int = 600): String =
        url("getCoverArt.view", listOf("id" to coverArtId, "size" to size.toString()))

    fun url(endpoint: String, params: List<Pair<String, String>> = emptyList()): String {
        val salt = (1..16).map { SALT_CHARS.random() }.joinToString("")
        val token = md5(pass + salt)
        val query = (params + listOf(
            "u" to user, "t" to token, "s" to salt,
            "v" to "1.16.1", "c" to "MochySubsonic", "f" to "json",
        )).joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        return "${base.trimEnd('/')}/rest/$endpoint?$query"
    }

    private fun url(endpoint: String, vararg params: Pair<String, String>): String =
        url(endpoint, params.toList())

    private fun ok(body: String): JsonObject? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val sub = root["subsonic-response"] as? JsonObject ?: return null
        if ((sub["status"] as? JsonPrimitive)?.contentOrNull != "ok") return null
        return sub
    }

    private fun Any?.parseSong(): SubSong? {
        val o = this as? JsonObject ?: return null
        val id = o.str("id") ?: return null
        val title = o.str("title") ?: return null
        return SubSong(
            id = id,
            title = title,
            artist = o.str("artist").orEmpty(),
            artistId = o.str("artistId"),
            album = o.str("album"),
            albumId = o.str("albumId"),
            durationSec = o.int("duration") ?: 0,
            year = o.str("year")?.toIntOrNull(),
            genre = o.str("genre"),
            coverArt = o.str("coverArt"),
            track = o.str("track")?.toIntOrNull(),
        )
    }

    private fun Any?.parseAlbum(): SubAlbum? {
        val o = this as? JsonObject ?: return null
        val id = o.str("id") ?: return null
        return SubAlbum(
            id = id,
            name = o.str("name") ?: id,
            artist = o.str("artist"),
            artistId = o.str("artistId"),
            year = o.str("year")?.toIntOrNull(),
            genre = o.str("genre"),
            coverArt = o.str("coverArt"),
            songCount = o.int("songCount") ?: 0,
        )
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.int(key: String) = str(key)?.toIntOrNull()

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun enc(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    companion object {
        private const val SALT_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
    }
}

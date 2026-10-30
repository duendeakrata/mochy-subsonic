package com.mochyy.mochymusic.subsonic

import android.content.Context
import com.mochyy.mochymusic.db.InternalDatabase
import com.mochyy.mochymusic.db.entities.AlbumArtistMap
import com.mochyy.mochymusic.db.entities.AlbumEntity
import com.mochyy.mochymusic.db.entities.ArtistEntity
import com.mochyy.mochymusic.db.entities.SongAlbumMap
import com.mochyy.mochymusic.db.entities.SongArtistMap
import com.mochyy.mochymusic.db.entities.SongEntity
import com.mochyy.mochymusic.module.subsonic.HttpExecutor
import com.mochyy.mochymusic.module.subsonic.SubsonicApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-way library sync: server artists/albums/songs into Mochy's Room DB,
 * prefixed with "sub:" so they never collide with local or YouTube entries
 * and a resync can wipe them cleanly. Cover art points at the server's
 * getCoverArt (works for external ext-* entries too, via Octo-Fiesta).
 */
@Singleton
class SubsonicSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: InternalDatabase,
) {
    data class Progress(val done: Boolean, val artists: Int, val albums: Int, val songs: Int, val error: String? = null)

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun api(): SubsonicApi? {
        val prefs = context.getSharedPreferences("module_subsonic", Context.MODE_PRIVATE)
        val base = prefs.getString("server", null)?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
        val user = prefs.getString("user", null)?.takeIf { it.isNotBlank() } ?: return null
        val pass = prefs.getString("pass", null)?.takeIf { it.isNotBlank() } ?: return null
        return SubsonicApi(base, user, pass, HttpExecutor { url, _ ->
            val req = okhttp3.Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { r ->
                r.code to (r.body?.string().orEmpty())
            }
        })
    }

    suspend fun sync(): Progress = withContext(Dispatchers.IO) {
        val api = api()
        if (api == null) {
            val p = Progress(true, 0, 0, 0, "No server configured")
            _progress.value = p
            return@withContext p
        }
        try {
            val dao = database.dao
            val now = LocalDateTime.now()
            // wipe previous server entries before a fresh full sync
            dao.deleteSongsByPrefix("$PREFIX%")
            dao.deleteAlbumsByPrefix("$PREFIX%")
            dao.deleteArtistsByPrefix("$PREFIX%")
            var artistCount = 0
            var albumCount = 0
            var songCount = 0
            val artists = api.artists()
            for (artist in artists) {
                val artistId = PREFIX + artist.id
                dao.insert(ArtistEntity(id = artistId, name = artist.name))
                artistCount++
                val albums = runCatching { api.artistAlbums(artist.id) }.getOrDefault(emptyList())
                for (album in albums) {
                    val albumId = PREFIX + album.id
                    dao.insert(
                        AlbumEntity(
                            id = albumId,
                            title = album.name,
                            year = album.year,
                            thumbnailUrl = album.coverArt?.let { api.coverArtUrl(it) },
                            songCount = album.songCount,
                            duration = 0,
                            inLibrary = now,
                        )
                    )
                    dao.insert(AlbumArtistMap(albumId = albumId, artistId = artistId, order = 0))
                    albumCount++
                    val songs = runCatching { api.albumSongs(album.id) }.getOrDefault(emptyList())
                    songs.forEachIndexed { index, song ->
                        val songId = PREFIX + song.id
                        dao.upsert(
                            SongEntity(
                                id = songId,
                                title = song.title,
                                duration = song.durationSec,
                                thumbnailUrl = song.coverArt?.let { api.coverArtUrl(it) },
                                albumId = albumId,
                                albumName = album.name,
                                year = song.year,
                                inLibrary = now,
                            )
                        )
                        val songArtistId = song.artistId?.let { PREFIX + it } ?: artistId
                        if (dao.getArtistById(songArtistId) == null) {
                            dao.insert(ArtistEntity(id = songArtistId, name = song.artist.ifBlank { artist.name }))
                        }
                        dao.insert(SongArtistMap(songId = songId, artistId = songArtistId, position = 0))
                        dao.insert(SongAlbumMap(songId = songId, albumId = albumId, index = song.track ?: index))
                        songCount++
                    }
                }
                _progress.value = Progress(false, artistCount, albumCount, songCount)
            }
            val done = Progress(true, artistCount, albumCount, songCount)
            _progress.value = done
            done
        } catch (e: Exception) {
            Timber.tag("SubsonicSync").w(e, "sync failed")
            val failed = Progress(true, 0, 0, 0, e.message ?: "Sync failed")
            _progress.value = failed
            failed
        }
    }

    companion object {
        const val PREFIX = "sub:"
    }
}

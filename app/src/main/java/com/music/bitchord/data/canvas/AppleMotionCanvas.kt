package com.music.bitchord.data.canvas

import com.music.bitchord.data.DebugLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import kotlin.math.abs

/**
 * The Apple motion-artwork providers PlayBeat shipped before the merge —
 * ported unchanged in behaviour, down to the playlist walker.
 *
 * BitChord's own [AppleMusicCanvas] reaches Apple's catalogue through
 * amp-api, which is the same art the paid catalogue has. These two community
 * mirrors cover the releases that path misses: **m8tec** indexes
 * artist + album -> looping artwork, and **boidu** indexes song + artist,
 * both ultimately serving Apple's `mvod.itunes.apple.com` clips. They are
 * tried as a fallback after the amp-api provider rather than instead of it,
 * and only URLs on Apple's own artwork host are ever accepted — a mirror
 * that starts answering with a redirect to anywhere else is dropped, not
 * played.
 *
 * Both hand back `.m3u8` playlists; the original resolver walked those to
 * the progressive MP4 behind them, because a plain MP4 is what the canvas
 * player's disk cache can loop offline. [playlistMp4] is that walker: a
 * master playlist's smallest sane variant, or the fragment MP4 a media
 * playlist maps, bounded to 960px and 3 Mbps like the original.
 */
object AppleMotionCanvas {

    private const val TAG = "AppleMotionCanvas"
    private const val M8TEC_SEARCH = "https://artwork.m8tec.top/api/v1/artwork/search"
    private const val BOIDU_SEARCH = "https://artwork.boidu.dev/"
    private const val ITUNES_SEARCH = "https://itunes.apple.com/search"

    /** Same bounds the original PlayBeat resolver enforced. */
    private const val MAX_DIMENSION = 960
    private const val TARGET_DIMENSION = 720
    private const val MAX_BANDWIDTH = 3_000_000L

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Albums a source-free YouTube Music track resolves to, which never have artwork. */
    private val NOT_A_REAL_ALBUM = Regex(
        """^(?:youtube music|unknown album|single)$""",
        RegexOption.IGNORE_CASE,
    )

    /** Editions whose art is not the track's art. */
    private val ALTERNATE_ALBUM_REJECT = Regex(
        """\b(?:remix|live|acoustic|instrumental|karaoke)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** `#EXT-X-MAP:URI="....mp4"` — a media playlist's fragment MP4. */
    private val MAP_URI = Regex("#EXT-X-MAP:URI=\"([^\"]+\\.mp4)\"")

    /**
     * The clip for a track: m8tec's album index when the album is known, then
     * boidu's song index, then boidu again for up to two verified alternate
     * editions the iTunes catalogue knows for the same track.
     */
    suspend fun search(title: String, artist: String, album: String?): CanvasArtwork? =
        withContext(Dispatchers.IO) {
            if (title.isBlank() || artist.isBlank()) return@withContext null
            val knownAlbum = !album.isNullOrBlank() && !NOT_A_REAL_ALBUM.containsMatchIn(album!!)
            if (knownAlbum) {
                m8tec(artist, album!!)?.let { return@withContext it }
            }

            boidu(title, artist, if (knownAlbum) album else null)?.let { return@withContext it }

            // YouTube often supplies a video or single's album, or none at
            // all. Consult Apple's song catalogue when direct lookups miss,
            // or a valid animated album can never be reached. Bounded to two
            // verified editions, as the original was.
            for (alternative in alternateAlbums(title, artist, album)) {
                boidu(title, artist, alternative)?.let { return@withContext it }
            }
            null
        }

    /**
     * The clip for a release's header artwork — the album-level index only,
     * since that is the one that is keyed by album rather than by track.
     */
    suspend fun searchAlbum(album: String, artist: String): CanvasArtwork? =
        withContext(Dispatchers.IO) {
            if (album.isBlank() || artist.isBlank()) return@withContext null
            m8tec(artist, album)
        }

    /** m8tec's artist + album index, portrait rendition first. */
    private fun m8tec(credit: String, album: String): CanvasArtwork? {
        // The index is keyed by the lead credit, but the answer is validated
        // against the full credit so a collaboration still checks out.
        val lead = primaryArtist(credit)
        val root = runCatching {
            canvasGet(
                "$M8TEC_SEARCH?artist=${urlEncode(lead)}&album=${urlEncode(album)}",
            )?.let { json.parseToJsonElement(it).jsonObject }
        }.getOrNull() ?: run {
            Log.d(TAG, "m8tec unreachable")
            return null
        }

        val listed = root["artist"]?.jsonPrimitive?.contentOrNull ?: return null
        val listedAlbum = root["album"]?.jsonPrimitive?.contentOrNull
            ?: return null
        if (!sameText(album, listedAlbum) || !sameArtist(credit, listed)) {
            Log.d(TAG, "m8tec answered a different release: '$listedAlbum' by '$listed'")
            return null
        }

        val portrait = root["url_tall"]?.jsonPrimitive?.contentOrNull
        val square = root["url"]?.jsonPrimitive?.contentOrNull
        val mp4 = portrait?.let { playlistMp4(it) } ?: square?.let { playlistMp4(it) }
        val fallback = square?.let { playlistMp4(it) } ?: portrait?.let { playlistMp4(it) }
        if (mp4 == null) {
            Log.d(TAG, "no m8tec clip for '$album' by '$lead'")
            return null
        }
        Log.d(TAG, "m8tec clip for '$album' by '$listed'")
        return CanvasArtwork(
            url = mp4,
            fallbackUrl = fallback?.takeIf { it != mp4 },
            artist = credit,
            album = album,
        )
    }

    /** boidu's song index: playlist rendition first, direct MP4 as fallback. */
    private fun boidu(title: String, artist: String, album: String?): CanvasArtwork? {
        val query = buildString {
            append(BOIDU_SEARCH)
            append("?s=").append(urlEncode(title))
            append("&a=").append(urlEncode(artist))
            if (album != null) append("&al=").append(urlEncode(album))
        }
        val root = runCatching {
            canvasGet(query)?.let { json.parseToJsonElement(it).jsonObject }
        }.getOrNull() ?: run {
            Log.d(TAG, "boidu unreachable")
            return null
        }

        val listed = root["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val listedArtist = root["artist"]?.jsonPrimitive?.contentOrNull ?: return null
        if (!sameText(title, listed) || !sameArtist(artist, listedArtist)) {
            Log.d(TAG, "boidu answered a different track: '$listed' by '$listedArtist'")
            return null
        }

        val animated = root["animatedVertical"]?.jsonPrimitive?.contentOrNull
            ?: root["animated"]?.jsonPrimitive?.contentOrNull
        val mp4 = animated?.let { playlistMp4(it) }
            ?: boundedDirectMp4(root["videoUrlVertical"]?.jsonPrimitive?.contentOrNull)
            ?: boundedDirectMp4(root["videoUrl"]?.jsonPrimitive?.contentOrNull)
        if (mp4 == null) {
            Log.d(TAG, "no boidu clip for '$title' by '$artist'")
            return null
        }
        val fallback = boundedDirectMp4(root["videoUrl"]?.jsonPrimitive?.contentOrNull)
            ?: boundedDirectMp4(root["videoUrlVertical"]?.jsonPrimitive?.contentOrNull)
        Log.d(TAG, "boidu clip for '$listed' by '$listedArtist'")
        return CanvasArtwork(
            url = mp4,
            fallbackUrl = fallback?.takeIf { it != mp4 },
            title = title,
            artist = artist,
            album = album,
        )
    }

    /** Up to two other editions Apple's catalogue holds for the same track. */
    private fun alternateAlbums(title: String, artist: String, playingAlbum: String?): List<String> {
        val root = runCatching {
            canvasGet(
                "$ITUNES_SEARCH?term=${urlEncode("$title $artist")}&entity=song&limit=25",
            )?.let { json.parseToJsonElement(it).jsonObject }
        }.getOrNull() ?: return emptyList()
        val results = runCatching { root["results"]?.jsonArray }.getOrNull() ?: return emptyList()

        val albums = mutableListOf<String>()
        val original = playingAlbum?.normalizeForMatch()
        for (element in results) {
            val item = runCatching { element.jsonObject }.getOrNull() ?: continue
            val name = item["trackName"]?.jsonPrimitive?.contentOrNull ?: continue
            val performer = item["artistName"]?.jsonPrimitive?.contentOrNull ?: continue
            val collection = item["collectionName"]?.jsonPrimitive?.contentOrNull ?: continue
            if (!sameText(title, name) || !sameArtist(artist, performer)) continue
            if (ALTERNATE_ALBUM_REJECT.containsMatchIn(collection)) continue
            val normalized = collection.normalizeForMatch()
            if (normalized == original) continue
            if (albums.any { it.normalizeForMatch() == normalized }) continue
            albums.add(collection)
            if (albums.size == 2) break
        }
        return albums
    }

    /**
     * The MP4 behind one of Apple's artwork playlists — the walker the
     * original resolver used, verbatim in behaviour.
     *
     * A media playlist maps its fragments through `#EXT-X-MAP` to a single
     * MP4, which only counts when the playlist itself was chosen by size or
     * already carries bounded dimensions in its path. A master playlist
     * offers variants; the one closest to 720p under the size and bandwidth
     * ceilings wins, and gets one recursion to become a media playlist.
     */
    private fun playlistMp4(master: String, depth: Int = 0, selectedDimension: Int? = null): String? {
        if (depth > 2) return null
        val text = canvasGet(master) ?: return null
        if (!text.startsWith("#EXTM3U")) return null

        if (text.contains("#EXT-X-MAP:")) {
            if (selectedDimension == null && !isBoundedVideoPath(URI(master).path)) return null
            val mapped = MAP_URI.find(text)?.groupValues?.get(1) ?: return null
            return appleUrl(resolveUrl(master, mapped), ".mp4")
        }

        val lines = text.split(Regex("\r?\n"))
        val variants = mutableListOf<Triple<Int, Long, String>>() // longest side, bandwidth, url
        for (i in 0 until lines.size - 1) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF:") || !line.contains("""CODECS="avc1.""")) continue
            val resolution = Regex("""RESOLUTION=(\d+)x(\d+)""").find(line) ?: continue
            val width = resolution.groupValues[1].toIntOrNull() ?: 0
            val height = resolution.groupValues[2].toIntOrNull() ?: 0
            val longestSide = maxOf(width, height)
            val bandwidth = Regex("""(?:^|[:,])BANDWIDTH=(\d+)""").find(line)
                ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val uri = appleUrl(resolveUrl(master, lines[i + 1].trim()), ".m3u8")
            if (uri != null && width > 0 && height > 0 &&
                longestSide <= MAX_DIMENSION && bandwidth in 1..MAX_BANDWIDTH
            ) {
                variants.add(Triple(longestSide, bandwidth, uri))
            }
        }
        val best = variants.minByOrNull { abs(it.first - TARGET_DIMENSION) } ?: return null
        return playlistMp4(best.third, depth + 1, best.first)
    }

    /**
     * Only Apple's own artwork host, over TLS, ending in the extension a
     * mirror claims — a community index that answers with anything else is
     * a broken or hostile mirror, not a clip.
     */
    private fun appleUrl(url: String, extension: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val path = uri.path?.lowercase() ?: return null
        if (uri.scheme != "https" || uri.host != "mvod.itunes.apple.com") return null
        if (!path.endsWith(extension)) return null
        return url
    }

    /** A direct MP4 whose own path declares sane dimensions. */
    private fun boundedDirectMp4(url: String?): String? {
        val bounded = url?.let { appleUrl(it, ".mp4") } ?: return null
        return if (isBoundedVideoPath(URI(bounded).path)) bounded else null
    }

    /** `_960x960.` style dimensions in Apple artwork paths, within player bounds. */
    private fun isBoundedVideoPath(path: String?): Boolean {
        val match = path?.let { Regex("""_(\d+)x(\d+)(?:-|\.)""").find(it) } ?: return false
        val width = match.groupValues[1].toIntOrNull() ?: 0
        val height = match.groupValues[2].toIntOrNull() ?: 0
        return width >= 240 && height >= 240 &&
            width <= MAX_DIMENSION && height <= MAX_DIMENSION
    }

    /** The lead credit, which is who a release's artwork is indexed under. */
    private fun primaryArtist(artist: String): String = splitRaw(artist).firstOrNull() ?: artist

    /** The raw credits before normalization, so searches carry original casing. */
    private fun splitRaw(raw: String): List<String> =
        raw.split(Regex("""\s*,\s*|\s*&\s*|\s+×\s+|\s+x\s+|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b|\bwith\b""", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotBlank() }

    /** Case- and accent-folded containment either way, as PlayBeat compared. */
    private fun sameText(want: String, listed: String): Boolean {
        val a = want.normalizeForMatch()
        val b = listed.normalizeForMatch()
        return a.isNotBlank() && b.isNotBlank() && (a.contains(b) || b.contains(a))
    }

    private fun sameArtist(want: String, listed: String): Boolean {
        val ours = splitArtists(want)
        val theirs = splitArtists(listed)
        if (ours.isEmpty() || theirs.isEmpty()) return false
        return ours.any { a -> theirs.any { b -> a == b || a.contains(b) || b.contains(a) } }
    }

    private fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, "utf-8")

    /** `URI.resolve` for playlist children, which may be absolute or relative. */
    private fun resolveUrl(base: String, child: String): String =
        runCatching { URI(base).resolve(child).toString() }.getOrDefault(child)
}

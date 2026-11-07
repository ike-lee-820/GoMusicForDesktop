package androidx.media3.common

import android.net.Uri

class Timeline

object C {
    const val TIME_UNSET = Long.MIN_VALUE
    const val WAKE_MODE_NETWORK = 2
    const val WAKE_MODE_LOCAL = 1
}

data class VideoSize(val width: Int, val height: Int)

class AudioAttributes private constructor() {
    class Builder {
        fun setUsage(u: Int): Builder = this
        fun setContentType(t: Int): Builder = this
        fun build(): AudioAttributes = AudioAttributes()
    }
    companion object { const val USAGE_MEDIA = 1; const val CONTENT_TYPE_MUSIC = 2 }
}

class MediaMetadata private constructor(
    val title: CharSequence? = null,
    val artist: CharSequence? = null,
    val albumTitle: CharSequence? = null,
    val artworkUri: Uri? = null,
) {
    class Builder {
        private var title: CharSequence? = null
        private var artist: CharSequence? = null
        private var albumTitle: CharSequence? = null
        private var artworkUri: Uri? = null
        fun setTitle(v: CharSequence?): Builder { title = v; return this }
        fun setArtist(v: CharSequence?): Builder { artist = v; return this }
        fun setAlbumTitle(v: CharSequence?): Builder { albumTitle = v; return this }
        fun setArtworkUri(v: Uri?): Builder { artworkUri = v; return this }
        fun build(): MediaMetadata = MediaMetadata(title, artist, albumTitle, artworkUri)
    }
}

class MediaItem private constructor(
    val mediaId: String = "",
    val uri: Uri? = null,
    val mediaMetadata: MediaMetadata = MediaMetadata.Builder().build(),
) {
    val localConfiguration: LocalConfiguration? = uri?.let { LocalConfiguration(it) }
    class LocalConfiguration(val uri: Uri)
    fun buildUpon(): Builder = Builder().setMediaId(mediaId).setUri(uri).setMediaMetadata(mediaMetadata)
    class Builder {
        private var mediaId: String = ""
        private var uri: Uri? = null
        private var metadata: MediaMetadata = MediaMetadata.Builder().build()
        fun setMediaId(v: String): Builder { mediaId = v; return this }
        fun setUri(v: Uri?): Builder { uri = v; return this }
        fun setUri(v: String): Builder { uri = Uri.parse(v); return this }
        fun setMediaMetadata(v: MediaMetadata): Builder { metadata = v; return this }
        fun setRequestMetadata(v: Any?): Builder = this
        fun build(): MediaItem = MediaItem(mediaId, uri, metadata)
    }
    companion object {
        fun fromUri(u: String): MediaItem = Builder().setUri(Uri.parse(u)).build()
        fun fromUri(u: Uri): MediaItem = Builder().setUri(u).build()
    }
}

interface Player {
    interface Listener {
        fun onIsPlayingChanged(isPlaying: Boolean) {}
        fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {}
        fun onTimelineChanged(timeline: Timeline, reason: Int) {}
        fun onPlaybackStateChanged(playbackState: Int) {}
        fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {}
        fun onVideoSizeChanged(videoSize: VideoSize) {}
        fun onPositionDiscontinuity(reason: Int) {}
        fun onRepeatModeChanged(repeatMode: Int) {}
        fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {}
        fun onPlayerError(error: Exception) {}
    }
    companion object {
        const val REPEAT_MODE_OFF = 0
        const val REPEAT_MODE_ONE = 1
        const val REPEAT_MODE_ALL = 2
        const val STATE_IDLE = 1
        const val STATE_BUFFERING = 2
        const val STATE_READY = 3
        const val STATE_ENDED = 4
    }
}

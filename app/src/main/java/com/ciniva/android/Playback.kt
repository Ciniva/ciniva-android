package com.ciniva.android

import android.content.Context
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities

/**
 * What this device plays, asked from its own decoders (MediaCodecList), its audio output (passthrough to a soundbar
 * or receiver) and its display (HDR). Direct play when the file plays as is; otherwise Ciniva converts it to HLS,
 * copying whatever the device does decode.
 */
class Playback(context: Context, display: Display?) {

    data class Quality(val id: String, val label: String, val height: Int = 0, val kbps: Int = 0)

    companion object {
        val QUALITIES = listOf(
            Quality("auto", "Original"),
            Quality("2160", "4K · 40 Mbps", 2160, 40000),
            Quality("1080", "1080p · 10 Mbps", 1080, 10000),
            Quality("720", "720p · 4 Mbps", 720, 4000),
            Quality("480", "480p · 1.5 Mbps", 480, 1500),
        )

        /** Containers Media3's extractors read. */
        private val CONTAINERS = setOf("mkv", "webm", "mp4", "m4v", "mov", "ts", "m2ts", "mts")

        private val VIDEO_MIME = mapOf(
            "h264" to "video/avc",
            "hevc" to "video/hevc",
            "av1" to "video/av01",
            "vp9" to "video/x-vnd.on2.vp9",
        )

        private val AUDIO_MIME = mapOf(
            "aac" to "audio/mp4a-latm",
            "ac3" to "audio/ac3",
            "eac3" to "audio/eac3",
            "mp3" to "audio/mpeg",
            "opus" to "audio/opus",
            "flac" to "audio/flac",
            "vorbis" to "audio/vorbis",
            "truehd" to "audio/true-hd",
            "dts" to "audio/vnd.dts",
        )

        private val PASSTHROUGH = mapOf(
            "ac3" to C.ENCODING_AC3,
            "eac3" to C.ENCODING_E_AC3,
            "dts" to C.ENCODING_DTS,
            "truehd" to C.ENCODING_DOLBY_TRUEHD,
        )

        val TEXT_SUBS = setOf("subrip", "srt", "ass", "ssa", "mov_text", "webvtt", "text")
    }

    private val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }

    @OptIn(UnstableApi::class)
    private val audioOut: AudioCapabilities = AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null)

    @Suppress("DEPRECATION")
    val hdrDisplay: Boolean = display?.let { d ->
        if (Build.VERSION.SDK_INT >= 26) d.isHdr else d.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true
    } ?: false

    val dolbyVision = hasDecoder("video/dolby-vision")
    private val hevc = hasDecoder("video/hevc")
    private val hevc10 = hasProfile("video/hevc", CodecProfileLevel.HEVCProfileMain10)
    private val av1 = hasDecoder("video/av01")

    private fun hasDecoder(mime: String, width: Int = 0, height: Int = 0): Boolean = decoders.any { info ->
        info.supportedTypes.any { it.equals(mime, ignoreCase = true) } && (width == 0 || runCatching {
            info.getCapabilitiesForType(mime).videoCapabilities?.let { v ->
                // Allow either orientation; some decoders list portrait sizes only.
                v.isSizeSupported(width, height) || v.isSizeSupported(height, width)
            } ?: true
        }.getOrDefault(false))
    }

    private fun hasProfile(mime: String, profile: Int): Boolean = decoders.any { info ->
        info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
            runCatching { info.getCapabilitiesForType(mime).profileLevels.any { it.profile == profile } }.getOrDefault(false)
    }

    /**
     * Audio formats whose decoder claimed support and then failed here (the Pixel 8's Dolby E-AC-3 decoder fails on
     * Atmos tracks). Remembered, so those files go straight to the server, which re-encodes just the audio.
     */
    private val broken: Set<String> get() = Session.pref("audio.broken", "").split(',').filter { it.isNotEmpty() }.toSet()

    /** Records that this device failed to decode [mime]; false if it was already known or isn't a format we track. */
    fun markAudioBroken(mime: String?): Boolean {
        val codec = codecOf(mime) ?: return false
        if (codec in broken) return false
        Session.setPref("audio.broken", (broken + codec).joinToString(","))
        return true
    }

    private fun codecOf(mime: String?): String? = when (mime) {
        null -> null
        "audio/eac3-joc" -> "eac3"
        else -> AUDIO_MIME.entries.firstOrNull { it.value == mime }?.key
    }

    @OptIn(UnstableApi::class)
    fun playsAudio(codec: String): Boolean = codec !in broken &&
        (AUDIO_MIME[codec]?.let { hasDecoder(it) } == true || PASSTHROUGH[codec]?.let { audioOut.supportsEncoding(it) } == true)

    fun defaultAudio(probe: MediaInfo?): AudioInfo? = probe?.audio?.let { a -> a.firstOrNull { it.isDefault } ?: a.firstOrNull() }

    /** Null when the file plays as is; otherwise why not (shown in the player's info line). */
    fun directBlocker(file: FileView, audio: AudioInfo?): String? {
        val p = file.probe ?: return "not probed yet"
        val v = p.video ?: return "no video"
        val ext = file.path.substringAfterLast('.', "").lowercase()
        if (ext !in CONTAINERS) return "$ext container"
        val mime = VIDEO_MIME[v.codec] ?: return "${v.codec} video"
        if (!hasDecoder(mime, v.width, v.height)) return "${v.codec} ${v.height}p video"
        val tenBit = (v.bitDepth ?: 8) > 8
        if (v.codec == "h264" && tenBit) return "10-bit H.264"
        if (v.codec == "hevc" && tenBit && !hevc10) return "10-bit HEVC"
        // Dolby Vision profiles 8 and 9 carry an HDR10/SDR base layer Media3 plays on a plain decoder; 5 and 7 don't.
        val dv = v.dolbyVisionProfile
        if (dv != null && !dolbyVision && dv != 8 && dv != 9) return "Dolby Vision profile $dv"
        if (v.hdr != null && !hdrDisplay) return "HDR on an SDR screen"
        if (audio != null && !playsAudio(audio.codec)) return "${audio.codec} audio"
        return null
    }

    fun transcodeRequest(file: FileView, quality: String, audio: AudioInfo?): TranscodeRequest {
        val v = file.probe?.video
        val srcHeight = v?.height?.takeIf { it > 0 } ?: 1080
        val q = QUALITIES.firstOrNull { it.id == quality }
        val height = minOf(q?.height?.takeIf { it > 0 } ?: srcHeight, maxOf(srcHeight, 480))
        val kbps = q?.kbps?.takeIf { it > 0 } ?: when {
            srcHeight >= 1600 -> 40000
            srcHeight >= 1000 -> 12000
            else -> 5000
        }
        val keepHdr = v?.hdr != null && hdrDisplay && hevc10
        val copyAudio = listOf("aac", "ac3", "eac3", "mp3", "opus", "flac").filter(::playsAudio)
        val copyVideo = buildList {
            add("h264")
            if (hevc && (hevc10 || (v?.bitDepth ?: 8) <= 8)) add("hevc")
            if (av1) add("av1")
        }
        return TranscodeRequest(
            maxHeight = height,
            bitrateKbps = kbps,
            codec = if (hevc && (keepHdr || height > 1080)) "hevc" else "h264",
            hdr = keepHdr,
            audioStream = audio?.index,
            audioChannels = if ((audio?.channels ?: 2) >= 6) 6 else 2,
            audioCopy = copyAudio.joinToString(",").ifEmpty { null },
            // A copy keeps HDR, and profile-5 Dolby Vision has no base layer that looks right without its metadata.
            videoCopy = if (quality == "auto" && (v?.hdr == null || keepHdr) && (v?.dolbyVisionProfile != 5 || dolbyVision)) copyVideo.joinToString(",") else null,
            dolbyVision = dolbyVision,
            container = "mp4",
        )
    }

    /** One line for Settings → About: what this device decodes. */
    fun summary(): String = buildList {
        add("H.264")
        if (hevc) add(if (hevc10) "HEVC 10-bit" else "HEVC")
        if (av1) add("AV1")
        if (dolbyVision) add("Dolby Vision")
        if (hdrDisplay) add("HDR screen")
        listOf("eac3" to "E-AC-3", "truehd" to "TrueHD", "dts" to "DTS").filter { playsAudio(it.first) }.forEach { add(it.second) }
    }.joinToString(" · ") + broken.takeIf { it.isNotEmpty() }?.let { b -> "\nThe server converts: " + b.joinToString { it.uppercase() } + " (failed here)" }.orEmpty()
}

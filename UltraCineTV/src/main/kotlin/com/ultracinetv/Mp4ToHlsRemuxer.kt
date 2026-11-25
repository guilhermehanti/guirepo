package com.ultracinetv

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.ceil

/**
 * Pure-Kotlin on-the-fly progressive MP4 (ISO BMFF) -> HLS v7 fragmented MP4 (fMP4) packager.
 *
 * Allows a single CloudStream `ExtractorLinkType.M3U8` endpoint to seamlessly serve both native HLS
 * playlists and progressive MP4 streams (such as SuperFlix / WarezCDN `/player/native/media-source/`)
 * without re-encoding or splitting sources in the UI.
 */
internal object Mp4ToHlsRemuxer {

    private const val MAX_MOOV_SIZE = 32 * 1024 * 1024
    private const val TARGET_SEG_SECONDS = 5.0
    private const val COALESCE_GAP_BYTES = 256 * 1024L
    private const val MAX_FETCH_SPAN_BYTES = 6 * 1024 * 1024L

    internal interface ByteRangeReader {
        fun readRange(offset: Long, length: Int): ByteArray
    }

    internal data class TrackInfo(
        val trackId: Int,
        val handlerType: String, // "vide" or "soun"
        val timescale: Long,
        val tkhdBox: ByteArray,
        val mdhdBox: ByteArray,
        val hdlrBox: ByteArray,
        val mediaHeaderBox: ByteArray, // vmhd or smhd
        val dinfBox: ByteArray,
        val stsdBox: ByteArray,
        val sampleSizes: IntArray,
        val sampleOffsets: LongArray,
        val sampleDurations: IntArray,
        val sampleDts: LongArray,
        val hasCtts: Boolean,
        val cttsOffsets: IntArray, // length 0 when !hasCtts
        val syncSamples: IntArray, // 0-based indices; empty means all sync
        val mediaTimeOffset: Long, // from elst (in track timescale)
        val initialEmptyTicks: Long // from elst empty edit (in track timescale)
    ) {
        val sampleCount: Int get() = sampleSizes.size
        val totalDurationTicks: Long
            get() = if (sampleCount == 0) 0L else sampleDts[sampleCount - 1] + sampleDurations[sampleCount - 1].toLong()
    }

    internal data class SegmentSpec(
        val index: Int,
        val durationSeconds: Double,
        val videoStart: Int,
        val videoEnd: Int,
        val audioStart: Int,
        val audioEnd: Int
    )

    internal class RemuxedMp4(
        val initSegment: ByteArray,
        val videoTrack: TrackInfo?,
        val audioTrack: TrackInfo?,
        val segments: List<SegmentSpec>
    ) {
        val targetDuration: Int = segments.maxOfOrNull { ceil(it.durationSeconds).toInt() }?.coerceAtLeast(1) ?: 6

        fun buildPlaylist(initUrl: String, segmentUrl: (Int) -> String): String = buildString {
            append("#EXTM3U\n")
            append("#EXT-X-VERSION:7\n")
            append("#EXT-X-TARGETDURATION:").append(targetDuration).append('\n')
            append("#EXT-X-MEDIA-SEQUENCE:0\n")
            append("#EXT-X-PLAYLIST-TYPE:VOD\n")
            append("#EXT-X-INDEPENDENT-SEGMENTS\n")
            append("#EXT-X-MAP:URI=\"").append(initUrl).append("\"\n")
            for (seg in segments) {
                append("#EXTINF:")
                append(String.format(Locale.US, "%.6f", seg.durationSeconds))
                append(",\n")
                append(segmentUrl(seg.index)).append('\n')
            }
            append("#EXT-X-ENDLIST\n")
        }

        fun buildMediaSegment(index: Int, reader: ByteRangeReader): ByteArray {
            val seg = segments.getOrNull(index) ?: throw IllegalArgumentException("Segment $index out of range")
            val activeTracks = buildList {
                if (videoTrack != null && seg.videoEnd > seg.videoStart) {
                    add(Triple(videoTrack, seg.videoStart, seg.videoEnd))
                }
                if (audioTrack != null && seg.audioEnd > seg.audioStart) {
                    add(Triple(audioTrack, seg.audioStart, seg.audioEnd))
                }
            }
            require(activeTracks.isNotEmpty()) { "Segment $index has no samples" }

            // Collect all (offset, size) spans across active tracks so interleaved audio/video
            // gets fetched in as few HTTP Range requests as possible.
            val spans = ArrayList<LongArray>()
            for ((track, start, end) in activeTracks) {
                for (s in start until end) {
                    val sz = track.sampleSizes[s]
                    if (sz > 0) spans.add(longArrayOf(track.sampleOffsets[s], sz.toLong()))
                }
            }
            spans.sortBy { it[0] }

            val mergedRanges = ArrayList<LongArray>()
            for (sp in spans) {
                val spStart = sp[0]
                val spEnd = spStart + sp[1]
                if (mergedRanges.isEmpty()) {
                    mergedRanges.add(longArrayOf(spStart, spEnd))
                } else {
                    val last = mergedRanges[mergedRanges.size - 1]
                    val newEnd = maxOf(last[1], spEnd)
                    if (spStart - last[1] <= COALESCE_GAP_BYTES && newEnd - last[0] <= MAX_FETCH_SPAN_BYTES) {
                        last[1] = newEnd
                    } else {
                        mergedRanges.add(longArrayOf(spStart, spEnd))
                    }
                }
            }

            // Fetch each coalesced byte range once.
            val fetchedBuffers = ArrayList<Pair<Long, ByteArray>>(mergedRanges.size)
            for (mr in mergedRanges) {
                val len = (mr[1] - mr[0]).toInt()
                val data = reader.readRange(mr[0], len)
                require(data.size >= len) { "Short read for segment $index: got ${data.size}, expected $len" }
                fetchedBuffers.add(mr[0] to data)
            }

            // Pack contiguous sample payloads per track.
            val trackPayloads = ArrayList<ByteArray>(activeTracks.size)
            for ((track, start, end) in activeTracks) {
                var totalBytes = 0
                for (s in start until end) totalBytes += track.sampleSizes[s]
                val out = ByteArray(totalBytes)
                var dst = 0
                for (s in start until end) {
                    val sz = track.sampleSizes[s]
                    if (sz <= 0) continue
                    val off = track.sampleOffsets[s]
                    val chunk = fetchedBuffers.firstOrNull { (base, bytes) ->
                        off >= base && (off - base) + sz <= bytes.size
                    } ?: error("Missing sample byte range @$off ($sz bytes)")
                    System.arraycopy(chunk.second, (off - chunk.first).toInt(), out, dst, sz)
                    dst += sz
                }
                trackPayloads.add(out)
            }

            // Pre-compute traf sizes so we know exact moof size before writing trun.data_offset.
            val mfhdSize = 16
            val trafSpecs = activeTracks.mapIndexed { idx, (track, start, end) ->
                val count = end - start
                val hasCts = track.hasCtts || track.mediaTimeOffset != 0L
                val trunEntrySize = if (hasCts) 16 else 12
                val trunSize = 20 + count * trunEntrySize
                val trafSize = 8 + 16 + 20 + trunSize // traf header + tfhd(16) + tfdt(20) + trun
                TrafBuildSpec(track, start, end, hasCts, trunSize, trafSize, trackPayloads[idx])
            }
            val moofSize = 8 + mfhdSize + trafSpecs.sumOf { it.trafSize }
            val mdatPayloadSize = trackPayloads.sumOf { it.size }
            val mdatSize = 8 + mdatPayloadSize

            val buf = ByteBuffer.allocate(moofSize + mdatSize).order(ByteOrder.BIG_ENDIAN)

            // moof
            buf.putInt(moofSize)
            buf.putAscii("moof")

            // mfhd
            buf.putInt(mfhdSize)
            buf.putAscii("mfhd")
            buf.putInt(0) // version + flags
            buf.putInt(index + 1) // sequence_number (1-based)

            var mdatTrackOffset = 0
            for (spec in trafSpecs) {
                val track = spec.track
                val count = spec.end - spec.start
                val isVideo = track.handlerType == "vide"
                val syncSet = if (isVideo && track.syncSamples.isNotEmpty()) {
                    // Build small lookup for this segment's range
                    val set = HashSet<Int>()
                    for (syncIdx in track.syncSamples) {
                        if (syncIdx in spec.start until spec.end) set.add(syncIdx)
                    }
                    set
                } else null

                // traf
                buf.putInt(spec.trafSize)
                buf.putAscii("traf")

                // tfhd: flags = 0x020000 (default-base-is-moof)
                buf.putInt(16)
                buf.putAscii("tfhd")
                buf.putInt(0x020000)
                buf.putInt(track.trackId)

                // tfdt: version 1 (64-bit baseMediaDecodeTime)
                buf.putInt(20)
                buf.putAscii("tfdt")
                buf.putInt(0x01000000)
                val baseDts = track.sampleDts[spec.start] + track.initialEmptyTicks
                buf.putLong(baseDts)

                // trun
                val trunVersion = if (spec.hasCts) 1 else 0
                val trunFlags = 0x000001 or 0x000100 or 0x000200 or 0x000400 or (if (spec.hasCts) 0x000800 else 0)
                buf.putInt(spec.trunSize)
                buf.putAscii("trun")
                buf.putInt((trunVersion shl 24) or trunFlags)
                buf.putInt(count)
                buf.putInt(moofSize + 8 + mdatTrackOffset)

                for (s in spec.start until spec.end) {
                    buf.putInt(track.sampleDurations[s])
                    buf.putInt(track.sampleSizes[s])
                    val sampleFlags = if (!isVideo || syncSet == null || syncSet.contains(s)) {
                        0x02000000 // sync sample (sample_depends_on = 2)
                    } else {
                        0x01010000 // non-sync sample (sample_depends_on = 1, is_non_sync = 1)
                    }
                    buf.putInt(sampleFlags)
                    if (spec.hasCts) {
                        val rawCts = if (track.hasCtts) track.cttsOffsets[s].toLong() else 0L
                        buf.putInt((rawCts - track.mediaTimeOffset).toInt())
                    }
                }
                mdatTrackOffset += spec.payload.size
            }

            // mdat
            buf.putInt(mdatSize)
            buf.putAscii("mdat")
            for (spec in trafSpecs) {
                buf.put(spec.payload)
            }
            return buf.array()
        }
    }

    private data class TrafBuildSpec(
        val track: TrackInfo,
        val start: Int,
        val end: Int,
        val hasCts: Boolean,
        val trunSize: Int,
        val trafSize: Int,
        val payload: ByteArray
    )

    fun looksLikeMp4(prefix: ByteArray): Boolean {
        if (prefix.size < 12) return false
        val box = String(prefix, 4, 4, Charsets.US_ASCII)
        return box == "ftyp" || box == "moov" || box == "mdat" || box == "free" || box == "wide"
    }

    /**
     * Locates and parses the `moov` box from an MP4 stream (supporting both fast-start `moov`
     * at the beginning and trailing `moov` after `mdat`) and builds `init.mp4` + segment table.
     */
    fun parse(reader: ByteRangeReader, initialChunk: ByteArray, totalFileSize: Long): RemuxedMp4 {
        require(looksLikeMp4(initialChunk)) { "Not a valid ISO BMFF / MP4 stream" }

        val moovBytes = locateAndReadMoov(reader, initialChunk, totalFileSize)
        return parseFromMoov(moovBytes)
    }

    /**
     * Parses directly from raw `moov` box bytes (including its 8-byte header). Exposed for unit tests.
     */
    internal fun parseFromMoov(moovBox: ByteArray): RemuxedMp4 {
        val moovHeader = readBoxHeader(moovBox, 0, moovBox.size)
            ?: error("Invalid moov header")
        require(moovHeader.type == "moov") { "Expected moov box, got ${moovHeader.type}" }

        var mvhdBox: ByteArray? = null
        var movieTimescale = 1000L
        var videoTrack: TrackInfo? = null
        var audioTrack: TrackInfo? = null

        forEachChildBox(moovBox, moovHeader.headerSize, moovBox.size) { child ->
            when (child.type) {
                "mvhd" -> {
                    mvhdBox = moovBox.copyOfRange(child.offset, child.end)
                    val v = moovBox[child.offset + child.headerSize].toInt() and 0xFF
                    movieTimescale = if (v == 1) {
                        readUInt32(moovBox, child.offset + child.headerSize + 20)
                    } else {
                        readUInt32(moovBox, child.offset + child.headerSize + 12)
                    }.coerceAtLeast(1L)
                }
                "trak" -> {
                    val parsed = parseTrack(moovBox, child, movieTimescale)
                    if (parsed != null) {
                        if (parsed.handlerType == "vide" && videoTrack == null) {
                            videoTrack = parsed
                        } else if (parsed.handlerType == "soun" && audioTrack == null) {
                            audioTrack = parsed
                        }
                    }
                }
            }
        }

        val mvhd = mvhdBox ?: error("MP4 missing mvhd box")
        require(videoTrack != null || audioTrack != null) { "MP4 contains no supported video or audio track" }

        val initSegment = buildInitSegment(mvhd, listOfNotNull(videoTrack, audioTrack))
        val segments = buildSegments(videoTrack, audioTrack)
        require(segments.isNotEmpty()) { "MP4 produced zero segments" }

        return RemuxedMp4(initSegment, videoTrack, audioTrack, segments)
    }

    private fun locateAndReadMoov(
        reader: ByteRangeReader,
        initialChunk: ByteArray,
        totalFileSize: Long
    ): ByteArray {
        var offset = 0L
        var hops = 0
        while (hops++ < 32) {
            val headerBytes = if (offset + 16 <= initialChunk.size) {
                initialChunk.copyOfRange(offset.toInt(), (offset + 16).toInt())
            } else {
                if (totalFileSize > 0 && offset >= totalFileSize) break
                val want = if (totalFileSize > 0) minOf(16L, totalFileSize - offset).toInt() else 16
                if (want < 8) break
                reader.readRange(offset, want)
            }
            if (headerBytes.size < 8) break
            var size = readUInt32(headerBytes, 0)
            val type = String(headerBytes, 4, 4, Charsets.US_ASCII)
            if (size == 1L) {
                require(headerBytes.size >= 16) { "Truncated 64-bit box header for $type" }
                size = readUInt64(headerBytes, 8)
            } else if (size == 0L) {
                if (totalFileSize > offset) size = totalFileSize - offset else break
            }
            require(size >= 8) { "Invalid box size $size for $type at $offset" }

            if (type == "moov") {
                require(size <= MAX_MOOV_SIZE) { "moov box too large ($size bytes)" }
                val intSize = size.toInt()
                if (offset + intSize <= initialChunk.size) {
                    return initialChunk.copyOfRange(offset.toInt(), (offset + intSize).toInt())
                }
                // Reuse any prefix already in initialChunk to avoid re-fetching.
                val out = ByteArray(intSize)
                var filled = 0
                if (offset < initialChunk.size) {
                    val avail = minOf(intSize, (initialChunk.size - offset).toInt())
                    System.arraycopy(initialChunk, offset.toInt(), out, 0, avail)
                    filled = avail
                }
                while (filled < intSize) {
                    val chunkLen = minOf(intSize - filled, MAX_FETCH_SPAN_BYTES.toInt())
                    val part = reader.readRange(offset + filled, chunkLen)
                    require(part.isNotEmpty()) { "Unexpected EOF while reading moov" }
                    val copy = minOf(part.size, intSize - filled)
                    System.arraycopy(part, 0, out, filled, copy)
                    filled += copy
                }
                return out
            }
            offset += size
        }
        error("moov box not found in MP4 stream")
    }

    private fun parseTrack(data: ByteArray, trak: BoxRef, movieTimescale: Long): TrackInfo? {
        var tkhdBox: ByteArray? = null
        var trackId = 0
        var edtsBox: BoxRef? = null
        var mdiaBox: BoxRef? = null

        forEachChildBox(data, trak.contentOffset, trak.end) { child ->
            when (child.type) {
                "tkhd" -> {
                    tkhdBox = data.copyOfRange(child.offset, child.end)
                    val v = data[child.contentOffset].toInt() and 0xFF
                    trackId = if (v == 1) {
                        readUInt32(data, child.contentOffset + 20).toInt()
                    } else {
                        readUInt32(data, child.contentOffset + 12).toInt()
                    }
                }
                "edts" -> edtsBox = child
                "mdia" -> mdiaBox = child
            }
        }
        val tkhd = tkhdBox ?: return null
        val mdia = mdiaBox ?: return null

        var mdhdBox: ByteArray? = null
        var timescale = 0L
        var hdlrBox: ByteArray? = null
        var handlerType = ""
        var minfBox: BoxRef? = null

        forEachChildBox(data, mdia.contentOffset, mdia.end) { child ->
            when (child.type) {
                "mdhd" -> {
                    mdhdBox = data.copyOfRange(child.offset, child.end)
                    val v = data[child.contentOffset].toInt() and 0xFF
                    timescale = if (v == 1) {
                        readUInt32(data, child.contentOffset + 20)
                    } else {
                        readUInt32(data, child.contentOffset + 12)
                    }
                }
                "hdlr" -> {
                    hdlrBox = data.copyOfRange(child.offset, child.end)
                    if (child.contentOffset + 12 <= child.end) {
                        handlerType = String(data, child.contentOffset + 8, 4, Charsets.US_ASCII)
                    }
                }
                "minf" -> minfBox = child
            }
        }
        if (handlerType != "vide" && handlerType != "soun") return null
        if (timescale <= 0L) return null
        val mdhd = mdhdBox ?: return null
        val hdlr = hdlrBox ?: return null
        val minf = minfBox ?: return null

        var mediaHeaderBox: ByteArray? = null
        var dinfBox: ByteArray? = null
        var stblBox: BoxRef? = null

        forEachChildBox(data, minf.contentOffset, minf.end) { child ->
            when (child.type) {
                "vmhd", "smhd" -> mediaHeaderBox = data.copyOfRange(child.offset, child.end)
                "dinf" -> dinfBox = data.copyOfRange(child.offset, child.end)
                "stbl" -> stblBox = child
            }
        }
        val mhdr = mediaHeaderBox ?: return null
        val dinf = dinfBox ?: return null
        val stbl = stblBox ?: return null

        var stsdBox: ByteArray? = null
        var sttsBox: BoxRef? = null
        var cttsBox: BoxRef? = null
        var stssBox: BoxRef? = null
        var stscBox: BoxRef? = null
        var stszBox: BoxRef? = null
        var stcoBox: BoxRef? = null
        var co64Box: BoxRef? = null

        forEachChildBox(data, stbl.contentOffset, stbl.end) { child ->
            when (child.type) {
                "stsd" -> stsdBox = data.copyOfRange(child.offset, child.end)
                "stts" -> sttsBox = child
                "ctts" -> cttsBox = child
                "stss" -> stssBox = child
                "stsc" -> stscBox = child
                "stsz" -> stszBox = child
                "stco" -> stcoBox = child
                "co64" -> co64Box = child
            }
        }
        val stsd = stsdBox ?: return null
        val stts = sttsBox ?: return null
        val stsc = stscBox ?: return null
        val stsz = stszBox ?: return null
        if (stcoBox == null && co64Box == null) return null

        // Parse stsz
        val fixedSize = readUInt32(data, stsz.contentOffset + 4).toInt()
        val sampleCount = readUInt32(data, stsz.contentOffset + 8).toInt()
        if (sampleCount <= 0) return null
        val sampleSizes = IntArray(sampleCount)
        if (fixedSize > 0) {
            sampleSizes.fill(fixedSize)
        } else {
            var p = stsz.contentOffset + 12
            for (i in 0 until sampleCount) {
                sampleSizes[i] = readUInt32(data, p).toInt()
                p += 4
            }
        }

        // Parse stts -> sampleDurations & cumulative sampleDts
        val sampleDurations = IntArray(sampleCount)
        val sttsEntries = readUInt32(data, stts.contentOffset + 4).toInt()
        var sttsPos = stts.contentOffset + 8
        var sIdx = 0
        for (e in 0 until sttsEntries) {
            val cnt = readUInt32(data, sttsPos).toInt()
            val delta = readUInt32(data, sttsPos + 4).toInt()
            sttsPos += 8
            val limit = minOf(sIdx + cnt, sampleCount)
            while (sIdx < limit) {
                sampleDurations[sIdx++] = delta
            }
        }
        val sampleDts = LongArray(sampleCount)
        var accDts = 0L
        for (i in 0 until sampleCount) {
            sampleDts[i] = accDts
            accDts += sampleDurations[i].toLong()
        }

        // Parse ctts (optional)
        val hasCtts = cttsBox != null
        val cttsOffsets = if (cttsBox != null) {
            val box = cttsBox!!
            val v = data[box.contentOffset].toInt() and 0xFF
            val entries = readUInt32(data, box.contentOffset + 4).toInt()
            val arr = IntArray(sampleCount)
            var p = box.contentOffset + 8
            var idx = 0
            for (e in 0 until entries) {
                val cnt = readUInt32(data, p).toInt()
                val off = if (v == 1) readInt32(data, p + 4) else readUInt32(data, p + 4).toInt()
                p += 8
                val limit = minOf(idx + cnt, sampleCount)
                while (idx < limit) {
                    arr[idx++] = off
                }
            }
            arr
        } else IntArray(0)

        // Parse stss (optional, 1-based -> 0-based)
        val syncSamples = if (stssBox != null) {
            val box = stssBox!!
            val entries = readUInt32(data, box.contentOffset + 4).toInt()
            val arr = IntArray(entries)
            var p = box.contentOffset + 8
            for (i in 0 until entries) {
                arr[i] = (readUInt32(data, p).toInt() - 1).coerceIn(0, sampleCount - 1)
                p += 4
            }
            arr
        } else IntArray(0)

        // Parse stco or co64 -> chunk offsets
        val chunkOffsets: LongArray = if (co64Box != null) {
            val box = co64Box!!
            val entries = readUInt32(data, box.contentOffset + 4).toInt()
            val arr = LongArray(entries)
            var p = box.contentOffset + 8
            for (i in 0 until entries) {
                arr[i] = readUInt64(data, p)
                p += 8
            }
            arr
        } else {
            val box = stcoBox!!
            val entries = readUInt32(data, box.contentOffset + 4).toInt()
            val arr = LongArray(entries)
            var p = box.contentOffset + 8
            for (i in 0 until entries) {
                arr[i] = readUInt32(data, p)
                p += 4
            }
            arr
        }

        // Parse stsc + chunkOffsets + sampleSizes -> sampleOffsets
        val stscEntries = readUInt32(data, stsc.contentOffset + 4).toInt()
        val firstChunks = IntArray(stscEntries)
        val samplesPerChunk = IntArray(stscEntries)
        var stscPos = stsc.contentOffset + 8
        for (i in 0 until stscEntries) {
            firstChunks[i] = readUInt32(data, stscPos).toInt() // 1-based
            samplesPerChunk[i] = readUInt32(data, stscPos + 4).toInt()
            stscPos += 12
        }

        val sampleOffsets = LongArray(sampleCount)
        var curSample = 0
        for (e in 0 until stscEntries) {
            val chunkStart = firstChunks[e] - 1
            val chunkEnd = if (e + 1 < stscEntries) firstChunks[e + 1] - 1 else chunkOffsets.size
            val spc = samplesPerChunk[e]
            for (c in chunkStart until minOf(chunkEnd, chunkOffsets.size)) {
                var byteOff = chunkOffsets[c]
                for (k in 0 until spc) {
                    if (curSample >= sampleCount) break
                    sampleOffsets[curSample] = byteOff
                    byteOff += sampleSizes[curSample].toLong()
                    curSample++
                }
                if (curSample >= sampleCount) break
            }
            if (curSample >= sampleCount) break
        }

        // Parse edts/elst (optional) to keep exact A/V sync without needing edts in init.mp4.
        var mediaTimeOffset = 0L
        var initialEmptyTicks = 0L
        edtsBox?.let { edts ->
            forEachChildBox(data, edts.contentOffset, edts.end) { child ->
                if (child.type == "elst") {
                    val v = data[child.contentOffset].toInt() and 0xFF
                    val count = readUInt32(data, child.contentOffset + 4).toInt()
                    var p = child.contentOffset + 8
                    var sawMedia = false
                    for (i in 0 until count) {
                        val segDur: Long
                        val mediaTime: Long
                        if (v == 1) {
                            segDur = readUInt64(data, p)
                            mediaTime = readInt64(data, p + 8)
                            p += 20
                        } else {
                            segDur = readUInt32(data, p)
                            mediaTime = readInt32(data, p + 4).toLong()
                            p += 12
                        }
                        if (!sawMedia && mediaTime == -1L && segDur > 0L) {
                            initialEmptyTicks += (segDur * timescale) / movieTimescale
                        } else if (!sawMedia && mediaTime >= 0L) {
                            mediaTimeOffset = mediaTime
                            sawMedia = true
                        }
                    }
                }
            }
        }

        return TrackInfo(
            trackId = trackId,
            handlerType = handlerType,
            timescale = timescale,
            tkhdBox = tkhd,
            mdhdBox = mdhd,
            hdlrBox = hdlr,
            mediaHeaderBox = mhdr,
            dinfBox = dinf,
            stsdBox = stsd,
            sampleSizes = sampleSizes,
            sampleOffsets = sampleOffsets,
            sampleDurations = sampleDurations,
            sampleDts = sampleDts,
            hasCtts = hasCtts,
            cttsOffsets = cttsOffsets,
            syncSamples = syncSamples,
            mediaTimeOffset = mediaTimeOffset,
            initialEmptyTicks = initialEmptyTicks
        )
    }

    private fun buildInitSegment(mvhdRaw: ByteArray, tracks: List<TrackInfo>): ByteArray {
        val out = ByteArrayOutputStream()

        // ftyp: isom, iso6, mp41
        out.write(box("ftyp") {
            writeAscii("isom")
            writeInt(0x200)
            writeAscii("isom")
            writeAscii("iso6")
            writeAscii("mp41")
        })

        // moov
        out.write(box("moov") {
            write(zeroDuration(mvhdRaw, v0Offset = 16, v1Offset = 24))

            for (t in tracks) {
                write(box("trak") {
                    write(zeroDuration(t.tkhdBox, v0Offset = 20, v1Offset = 28))
                    write(box("mdia") {
                        write(zeroDuration(t.mdhdBox, v0Offset = 16, v1Offset = 24))
                        write(t.hdlrBox)
                        write(box("minf") {
                            write(t.mediaHeaderBox)
                            write(t.dinfBox)
                            write(box("stbl") {
                                write(t.stsdBox)
                                write(emptyFullBox("stts", extraZeroInts = 1))
                                write(emptyFullBox("stsc", extraZeroInts = 1))
                                write(emptyFullBox("stsz", extraZeroInts = 2))
                                write(emptyFullBox("stco", extraZeroInts = 1))
                            })
                        })
                    })
                })
            }

            // mvex + trex per track
            write(box("mvex") {
                for (t in tracks) {
                    write(box("trex") {
                        writeInt(0) // version + flags
                        writeInt(t.trackId)
                        writeInt(1) // default_sample_description_index
                        writeInt(0) // default_sample_duration
                        writeInt(0) // default_sample_size
                        writeInt(0) // default_sample_flags
                    })
                }
            })
        })

        return out.toByteArray()
    }

    private fun buildSegments(video: TrackInfo?, audio: TrackInfo?): List<SegmentSpec> {
        val segments = ArrayList<SegmentSpec>()
        if (video == null) {
            // Audio-only fallback
            val a = audio ?: return emptyList()
            var start = 0
            var idx = 0
            while (start < a.sampleCount) {
                val startDts = a.sampleDts[start]
                val targetEnd = startDts + (TARGET_SEG_SECONDS * a.timescale).toLong()
                var end = start + 1
                while (end < a.sampleCount && a.sampleDts[end] < targetEnd) end++
                val endDts = if (end < a.sampleCount) a.sampleDts[end] else a.totalDurationTicks
                val dur = (endDts - startDts).toDouble() / a.timescale.toDouble()
                segments.add(SegmentSpec(idx++, dur.coerceAtLeast(0.01), 0, 0, start, end))
                start = end
            }
            return segments
        }

        // Build list of candidate keyframe boundaries (0-based video sample indices).
        val keyIndices = if (video.syncSamples.isNotEmpty()) {
            val list = ArrayList<Int>(video.syncSamples.size + 1)
            if (video.syncSamples[0] != 0) list.add(0)
            for (k in video.syncSamples) {
                if (list.isEmpty() || k > list[list.size - 1]) list.add(k)
            }
            list
        } else {
            // Every sample is a keyframe (e.g., intra-only)
            val step = maxOf(1, (video.timescale * TARGET_SEG_SECONDS / maxOf(1, video.sampleDurations[0])).toInt())
            (0 until video.sampleCount step step).toList()
        }

        // Group GOPs into ~TARGET_SEG_SECONDS segments.
        val vBounds = ArrayList<Pair<Int, Int>>()
        var segStart = 0
        for (i in 1 until keyIndices.size) {
            val cand = keyIndices[i]
            val durSec = (video.sampleDts[cand] - video.sampleDts[segStart]).toDouble() / video.timescale.toDouble()
            if (durSec >= TARGET_SEG_SECONDS - 0.5) {
                vBounds.add(segStart to cand)
                segStart = cand
            }
        }
        if (segStart < video.sampleCount) {
            vBounds.add(segStart to video.sampleCount)
        }

        var audioCursor = 0
        for ((idx, pair) in vBounds.withIndex()) {
            val (vStart, vEnd) = pair
            val vStartDts = video.sampleDts[vStart]
            val vEndDts = if (vEnd < video.sampleCount) video.sampleDts[vEnd] else video.totalDurationTicks
            val durSec = ((vEndDts - vStartDts).toDouble() / video.timescale.toDouble()).coerceAtLeast(0.01)

            val aStart = audioCursor
            val aEnd = if (audio == null) {
                0
            } else if (idx == vBounds.size - 1) {
                audio.sampleCount
            } else {
                val targetAudioDts = ((vEndDts.toDouble() / video.timescale.toDouble()) * audio.timescale.toDouble()).toLong()
                var pos = audioCursor
                while (pos < audio.sampleCount && audio.sampleDts[pos] < targetAudioDts) {
                    pos++
                }
                pos
            }
            audioCursor = aEnd
            segments.add(SegmentSpec(idx, durSec, vStart, vEnd, aStart, aEnd))
        }
        return segments
    }

    private fun zeroDuration(boxBytes: ByteArray, v0Offset: Int, v1Offset: Int): ByteArray {
        val copy = boxBytes.copyOf()
        val hdr = readBoxHeader(copy, 0, copy.size) ?: return copy
        val c = hdr.headerSize
        if (c >= copy.size) return copy
        val version = copy[c].toInt() and 0xFF
        val off = c + (if (version == 1) v1Offset else v0Offset)
        val len = if (version == 1) 8 else 4
        if (off + len <= copy.size) {
            for (i in 0 until len) copy[off + i] = 0
        }
        return copy
    }

    private fun emptyFullBox(type: String, extraZeroInts: Int): ByteArray = box(type) {
        writeInt(0) // version + flags
        repeat(extraZeroInts) { writeInt(0) }
    }

    private class BoxWriter : ByteArrayOutputStream() {
        fun writeAscii(s: String) = write(s.toByteArray(Charsets.US_ASCII))
        fun writeInt(v: Int) {
            write((v ushr 24) and 0xFF)
            write((v ushr 16) and 0xFF)
            write((v ushr 8) and 0xFF)
            write(v and 0xFF)
        }
    }

    private fun box(type: String, block: BoxWriter.() -> Unit): ByteArray {
        val payload = BoxWriter().apply(block).toByteArray()
        val total = 8 + payload.size
        val out = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        out.putInt(total)
        out.putAscii(type)
        out.put(payload)
        return out.array()
    }

    private data class BoxRef(val type: String, val offset: Int, val headerSize: Int, val end: Int) {
        val contentOffset: Int get() = offset + headerSize
    }

    private fun readBoxHeader(data: ByteArray, offset: Int, limit: Int): BoxRef? {
        if (offset + 8 > limit) return null
        var size = readUInt32(data, offset)
        val type = String(data, offset + 4, 4, Charsets.US_ASCII)
        var hdr = 8
        if (size == 1L) {
            if (offset + 16 > limit) return null
            size = readUInt64(data, offset + 8)
            hdr = 16
        } else if (size == 0L) {
            size = (limit - offset).toLong()
        }
        if (size < hdr || offset + size > limit) return null
        return BoxRef(type, offset, hdr, (offset + size).toInt())
    }

    private inline fun forEachChildBox(data: ByteArray, start: Int, end: Int, action: (BoxRef) -> Unit) {
        var pos = start
        while (pos + 8 <= end) {
            val box = readBoxHeader(data, pos, end) ?: break
            action(box)
            if (box.end <= pos) break
            pos = box.end
        }
    }

    private fun ByteBuffer.putAscii(s: String) {
        for (c in s) put(c.code.toByte())
    }

    private fun readInt32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or
            ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or
            (b[o + 3].toInt() and 0xFF)

    private fun readUInt32(b: ByteArray, o: Int): Long = readInt32(b, o).toLong() and 0xFFFFFFFFL

    private fun readInt64(b: ByteArray, o: Int): Long =
        (readUInt32(b, o) shl 32) or readUInt32(b, o + 4)

    private fun readUInt64(b: ByteArray, o: Int): Long = readInt64(b, o)
}

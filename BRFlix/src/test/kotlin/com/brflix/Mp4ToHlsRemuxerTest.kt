package com.brflix

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Mp4ToHlsRemuxerTest {

    private class MemoryReader(private val data: ByteArray) : Mp4ToHlsRemuxer.ByteRangeReader {
        var readCalls = 0
        override fun readRange(offset: Long, length: Int): ByteArray {
            readCalls++
            val start = offset.toInt().coerceIn(0, data.size)
            val end = (offset + length).toInt().coerceIn(start, data.size)
            return data.copyOfRange(start, end)
        }
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(8 + payload.size)
        for (c in type) buf.put(c.code.toByte())
        buf.put(payload)
        return buf.array()
    }

    private fun fullBox(type: String, version: Int, flags: Int, body: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(4 + body.size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt((version shl 24) or flags)
        buf.put(body)
        return box(type, buf.array())
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (p in parts) out.write(p)
        return out.toByteArray()
    }

    private fun ints(vararg values: Int): ByteArray {
        val buf = ByteBuffer.allocate(values.size * 4).order(ByteOrder.BIG_ENDIAN)
        for (v in values) buf.putInt(v)
        return buf.array()
    }

    private fun buildSyntheticMp4(trailingMoov: Boolean, useCo64: Boolean = false): Pair<ByteArray, Map<String, ByteArray>> {
        val ftyp = box("ftyp", concat("isom".toByteArray(), ints(0x200), "isommp41".toByteArray()))

        // 4 video samples (3s each = 12s total, keyframes at sample 1 and 3 -> 2 HLS segments of 6s each)
        val vPayloads = (0 until 4).map { i -> ByteArray(16) { b -> (0xA0 + i * 16 + b).toByte() } }
        // 6 audio samples (2s each = 12s total)
        val aPayloads = (0 until 6).map { i -> ByteArray(12) { b -> (0x10 + i * 12 + b).toByte() } }

        val mdatBody = concat(
            vPayloads[0], vPayloads[1], aPayloads[0], aPayloads[1], aPayloads[2],
            vPayloads[2], vPayloads[3], aPayloads[3], aPayloads[4], aPayloads[5]
        )
        val mdat = box("mdat", mdatBody)

        fun buildMoov(mdatContentOffset: Long): ByteArray {
            val vChunk0Off = mdatContentOffset
            val aChunk0Off = vChunk0Off + 32
            val vChunk1Off = aChunk0Off + 36
            val aChunk1Off = vChunk1Off + 32

            val mvhdBody = ByteBuffer.allocate(96).order(ByteOrder.BIG_ENDIAN).apply {
                putInt(0); putInt(0) // creation, mod
                putInt(1000) // timescale
                putInt(12000) // duration = 12s
                putInt(0x00010000); putShort(0x0100); putShort(0)
                putInt(0); putInt(0)
                putInt(0x00010000); putInt(0); putInt(0)
                putInt(0); putInt(0x00010000); putInt(0)
                putInt(0); putInt(0); putInt(0x40000000)
                repeat(6) { putInt(0) }
                putInt(3) // next_track_ID
            }.array()
            val mvhd = fullBox("mvhd", 0, 0, mvhdBody)

            fun makeTkhd(id: Int): ByteArray {
                val b = ByteBuffer.allocate(80).order(ByteOrder.BIG_ENDIAN).apply {
                    putInt(0); putInt(0)
                    putInt(id); putInt(0)
                    putInt(12000) // duration
                    putInt(0); putInt(0)
                    putShort(0); putShort(0); putShort(0); putShort(0)
                    putInt(0x00010000); putInt(0); putInt(0)
                    putInt(0); putInt(0x00010000); putInt(0)
                    putInt(0); putInt(0); putInt(0x40000000)
                    putInt(1280 shl 16); putInt(720 shl 16)
                }.array()
                return fullBox("tkhd", 0, 3, b)
            }

            fun makeMdhd(timescale: Int, duration: Int): ByteArray {
                val b = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN).apply {
                    putInt(0); putInt(0); putInt(timescale); putInt(duration); putShort(0x55C4); putShort(0)
                }.array()
                return fullBox("mdhd", 0, 0, b)
            }

            fun makeHdlr(handler: String): ByteArray {
                val b = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN).apply {
                    putInt(0)
                    for (c in handler) put(c.code.toByte())
                    putInt(0); putInt(0); putInt(0)
                    put("test".toByteArray())
                }.array()
                return fullBox("hdlr", 0, 0, b)
            }

            val dinf = box("dinf", fullBox("dref", 0, 0, concat(ints(1), fullBox("url ", 0, 1, ByteArray(0)))))

            fun chunkOffsetBox(o0: Long, o1: Long): ByteArray {
                return if (useCo64) {
                    val b = ByteBuffer.allocate(4 + 16).order(ByteOrder.BIG_ENDIAN).apply {
                        putInt(2); putLong(o0); putLong(o1)
                    }.array()
                    fullBox("co64", 0, 0, b)
                } else {
                    fullBox("stco", 0, 0, ints(2, o0.toInt(), o1.toInt()))
                }
            }

            // Video track: timescale=1000, 4 samples @ 3000 ticks each, ctts=[200, 0, 200, 0], elst mediaTime=200
            val vElst = box("edts", fullBox("elst", 0, 0, ints(1, 12000, 200, 0x00010000)))
            val vStsd = fullBox("stsd", 0, 0, concat(ints(1), box("avc1", ByteArray(24))))
            val vStts = fullBox("stts", 0, 0, ints(1, 4, 3000))
            val vCtts = fullBox("ctts", 0, 0, ints(4, 1, 200, 1, 0, 1, 200, 1, 0))
            val vStss = fullBox("stss", 0, 0, ints(2, 1, 3))
            val vStsc = fullBox("stsc", 0, 0, ints(1, 1, 2, 1))
            val vStsz = fullBox("stsz", 0, 0, ints(0, 4, 16, 16, 16, 16))
            val vStco = chunkOffsetBox(vChunk0Off, vChunk1Off)
            val vStbl = box("stbl", concat(vStsd, vStts, vCtts, vStss, vStsc, vStsz, vStco))
            val vMinf = box("minf", concat(fullBox("vmhd", 0, 1, ByteArray(8)), dinf, vStbl))
            val vTrak = box("trak", concat(makeTkhd(1), vElst, box("mdia", concat(makeMdhd(1000, 12000), makeHdlr("vide"), vMinf))))

            // Audio track: timescale=1000, 6 samples @ 2000 ticks each
            val aStsd = fullBox("stsd", 0, 0, concat(ints(1), box("mp4a", ByteArray(20))))
            val aStts = fullBox("stts", 0, 0, ints(1, 6, 2000))
            val aStsc = fullBox("stsc", 0, 0, ints(1, 1, 3, 1))
            val aStsz = fullBox("stsz", 0, 0, ints(12, 6))
            val aStco = chunkOffsetBox(aChunk0Off, aChunk1Off)
            val aStbl = box("stbl", concat(aStsd, aStts, aStsc, aStsz, aStco))
            val aMinf = box("minf", concat(fullBox("smhd", 0, 0, ByteArray(4)), dinf, aStbl))
            val aTrak = box("trak", concat(makeTkhd(2), box("mdia", concat(makeMdhd(1000, 12000), makeHdlr("soun"), aMinf))))

            return box("moov", concat(mvhd, vTrak, aTrak))
        }

        val fullMp4 = if (trailingMoov) {
            val mdatContentOffset = (ftyp.size + 8).toLong()
            val moov = buildMoov(mdatContentOffset)
            concat(ftyp, mdat, moov)
        } else {
            val probeMoov = buildMoov(0L)
            val mdatContentOffset = (ftyp.size + probeMoov.size + 8).toLong()
            val moov = buildMoov(mdatContentOffset)
            concat(ftyp, moov, mdat)
        }

        return fullMp4 to mapOf(
            "v0" to concat(vPayloads[0], vPayloads[1]),
            "a0" to concat(aPayloads[0], aPayloads[1], aPayloads[2]),
            "v1" to concat(vPayloads[2], vPayloads[3]),
            "a1" to concat(aPayloads[3], aPayloads[4], aPayloads[5])
        )
    }

    @Test
    fun parseFastStartAndTrailingMoov_generatesValidHlsAndExactSampleBytes() {
        for (trailing in listOf(false, true)) {
            for (useCo64 in listOf(false, true)) {
                val (mp4, expected) = buildSyntheticMp4(trailingMoov = trailing, useCo64 = useCo64)
                assertTrue(Mp4ToHlsRemuxer.looksLikeMp4(mp4))
                val reader = MemoryReader(mp4)
                val initial = mp4.copyOfRange(0, minOf(64, mp4.size))
                val remux = Mp4ToHlsRemuxer.parse(reader, initial, mp4.size.toLong())

                assertNotNull(remux.videoTrack)
                assertNotNull(remux.audioTrack)
                assertEquals(2, remux.segments.size)
                assertEquals(6.0, remux.segments[0].durationSeconds, 0.001)
                assertEquals(6.0, remux.segments[1].durationSeconds, 0.001)

                // Verify HLS v7 playlist
                val m3u8 = remux.buildPlaylist("http://127.0.0.1:9/init.mp4") { "http://127.0.0.1:9/seg-$it.m4s" }
                assertTrue(m3u8.startsWith("#EXTM3U\n"))
                assertTrue(m3u8.contains("#EXT-X-VERSION:7"))
                assertTrue(m3u8.contains("#EXT-X-MAP:URI=\"http://127.0.0.1:9/init.mp4\""))
                assertTrue(m3u8.contains("http://127.0.0.1:9/seg-0.m4s"))
                assertTrue(m3u8.contains("http://127.0.0.1:9/seg-1.m4s"))
                assertTrue(m3u8.contains("#EXT-X-ENDLIST"))

                // Verify init.mp4 has ftyp + moov + mvex/trex
                val initStr = String(remux.initSegment, Charsets.ISO_8859_1)
                assertTrue(initStr.contains("ftyp"))
                assertTrue(initStr.contains("moov"))
                assertTrue(initStr.contains("mvex"))
                assertTrue(initStr.contains("trex"))

                // Verify segment 0 moof + mdat exact bytes & trun data_offset
                val seg0 = remux.buildMediaSegment(0, reader)
                val bb0 = ByteBuffer.wrap(seg0).order(ByteOrder.BIG_ENDIAN)
                val moofSize0 = bb0.getInt(0)
                assertEquals("moof", String(seg0, 4, 4, Charsets.US_ASCII))
                assertEquals("mdat", String(seg0, moofSize0 + 4, 4, Charsets.US_ASCII))
                val mdatBytes0 = seg0.copyOfRange(moofSize0 + 8, seg0.size)
                assertArrayEquals(concat(expected["v0"]!!, expected["a0"]!!), mdatBytes0)

                // Verify segment 1 moof + mdat exact bytes
                val seg1 = remux.buildMediaSegment(1, reader)
                val bb1 = ByteBuffer.wrap(seg1).order(ByteOrder.BIG_ENDIAN)
                val moofSize1 = bb1.getInt(0)
                assertEquals("moof", String(seg1, 4, 4, Charsets.US_ASCII))
                assertEquals("mdat", String(seg1, moofSize1 + 4, 4, Charsets.US_ASCII))
                val mdatBytes1 = seg1.copyOfRange(moofSize1 + 8, seg1.size)
                assertArrayEquals(concat(expected["v1"]!!, expected["a1"]!!), mdatBytes1)
            }
        }
    }

    @Test
    fun looksLikeMp4_rejectsHtmlAndM3u8() {
        assertFalse(Mp4ToHlsRemuxer.looksLikeMp4("#EXTM3U\n#EXT-X-VERSION:3\n".toByteArray()))
        assertFalse(Mp4ToHlsRemuxer.looksLikeMp4("<!DOCTYPE html><html></html>".toByteArray()))
    }
}

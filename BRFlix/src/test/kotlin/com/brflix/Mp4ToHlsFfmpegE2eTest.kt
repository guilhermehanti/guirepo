package com.brflix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class Mp4ToHlsFfmpegE2eTest {

    private fun findFfmpeg(): String? {
        val candidates = listOf("ffmpeg", "ffmpeg.exe")
        for (cmd in candidates) {
            try {
                val p = ProcessBuilder(cmd, "-version").redirectErrorStream(true).start()
                if (p.waitFor() == 0) return cmd
            } catch (_: Exception) { }
        }
        return null
    }

    @Test
    fun remuxRealH264BFramesAacMp4_decodesCleanlyWithZeroFfmpegErrors() {
        val ffmpeg = findFfmpeg()
        assumeTrue("ffmpeg not installed on CI", ffmpeg != null)

        val tmpDir = Files.createTempDirectory("brflix-remux-e2e").toFile()
        try {
            for (fastStart in listOf(true, false)) {
                val label = if (fastStart) "faststart" else "trailing"
                val srcMp4 = File(tmpDir, "src_$label.mp4")
                val args = mutableListOf(
                    ffmpeg!!, "-y", "-v", "error",
                    "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=24:duration=12",
                    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=12",
                    "-c:v", "libx264", "-profile:v", "high", "-bf", "2", "-g", "48", "-pix_fmt", "yuv420p",
                    "-c:a", "aac", "-b:a", "96k"
                )
                if (fastStart) {
                    args.addAll(listOf("-movflags", "+faststart"))
                }
                args.add(srcMp4.absolutePath)

                val gen = ProcessBuilder(args).redirectErrorStream(true).start()
                val genOut = gen.inputStream.bufferedReader().readText()
                assertEquals("ffmpeg generate $label failed: $genOut", 0, gen.waitFor())

                val mp4Bytes = srcMp4.readBytes()
                val reader = object : Mp4ToHlsRemuxer.ByteRangeReader {
                    override fun readRange(offset: Long, length: Int): ByteArray {
                        val s = offset.toInt().coerceIn(0, mp4Bytes.size)
                        val e = (offset + length).toInt().coerceIn(s, mp4Bytes.size)
                        return mp4Bytes.copyOfRange(s, e)
                    }
                }
                val remux = Mp4ToHlsRemuxer.parse(reader, mp4Bytes.copyOfRange(0, minOf(65536, mp4Bytes.size)), mp4Bytes.size.toLong())
                assertTrue("Expected >=2 segments for $label, got ${remux.segments.size}", remux.segments.size >= 2)

                val hlsDir = File(tmpDir, "hls_$label").apply { mkdirs() }
                File(hlsDir, "init.mp4").writeBytes(remux.initSegment)
                for (seg in remux.segments) {
                    File(hlsDir, "seg-${seg.index}.m4s").writeBytes(remux.buildMediaSegment(seg.index, reader))
                }
                val playlist = remux.buildPlaylist("init.mp4") { idx -> "seg-$idx.m4s" }
                val m3u8File = File(hlsDir, "master.m3u8").apply { writeText(playlist) }

                // Decode the ENTIRE generated HLS fMP4 playlist with ffmpeg -v error!
                val verify = ProcessBuilder(
                    ffmpeg, "-v", "error",
                    "-allowed_extensions", "ALL",
                    "-i", m3u8File.absolutePath,
                    "-f", "null", "-"
                ).redirectErrorStream(true).start()
                val verifyOut = verify.inputStream.bufferedReader().readText().trim()
                assertEquals("ffmpeg decode $label exited non-zero: $verifyOut", 0, verify.waitFor())
                assertEquals("ffmpeg decode $label reported errors/warnings: $verifyOut", "", verifyOut)
            }
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}

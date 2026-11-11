package com.ejemplo.app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale

data class AudioChunk(
    val file: File,
    val startOffsetSeconds: Double
)

object AudioExtractor {

    // Groq accepts up to 25 MB. Keep a safety margin for both single files and chunks.
    private const val MAX_GROQ_FILE_SIZE_BYTES = 24 * 1024 * 1024L
    private const val TARGET_CHUNK_BYTES = 18 * 1024 * 1024L
    private const val WAV_HEADER_BYTES = 44

    private val AUDIO_EXTENSIONS = setOf("m4a", "mp3", "wav", "ogg", "flac", "aac", "opus")

    /**
     * Returns one or more audio files, each safely below the Groq upload limit.
     * Every chunk carries the offset (in seconds) of its first sample in the original media,
     * so transcription timestamps can be restored after processing all parts.
     */
    fun prepareAudioChunks(context: Context, sourceUri: Uri, originalFileName: String?): List<AudioChunk> {
        val cacheDir = context.cacheDir
        val ext = originalFileName?.substringAfterLast('.', "")?.lowercase(Locale.US) ?: ""
        val stamp = System.currentTimeMillis()

        // Already an audio file: copy once and decide whether it needs splitting.
        if (ext in AUDIO_EXTENSIONS) {
            val copy = File(cacheDir, "audio_input_$stamp.$ext")
            try {
                copyUriToFile(context, sourceUri, copy)
                if (copy.exists() && copy.length() > 0) {
                    if (copy.length() <= MAX_GROQ_FILE_SIZE_BYTES) {
                        return listOf(AudioChunk(copy, 0.0))
                    }

                    val chunks = when (ext) {
                        "wav" -> splitWavFile(copy, cacheDir, stamp)
                        "m4a" -> chunkAacPath(copy.absolutePath, cacheDir, stamp)
                        else -> decodeChunksPath(copy.absolutePath, cacheDir, stamp)
                    }
                    if (!chunks.isNullOrEmpty()) {
                        copy.delete()
                        return chunks
                    }
                }
                copy.delete()
            } catch (_: Exception) {
                copy.delete()
            }
        }

        // Video or unknown container: try a fast AAC remux first.
        chunkAacUri(context, sourceUri, cacheDir, stamp)?.let { chunks ->
            if (chunks.isNotEmpty()) return chunks
        }

        // Otherwise decode any supported audio track to PCM WAV parts.
        decodeChunksUri(context, sourceUri, cacheDir, stamp)?.let { chunks ->
            if (chunks.isNotEmpty()) return chunks
        }

        // Last resort: copy the original file if it fits in one request.
        val fallbackExt = if (ext.isNotEmpty()) ext else "mp4"
        val fallback = File(cacheDir, "media_fallback_$stamp.$fallbackExt")
        copyUriToFile(context, sourceUri, fallback)
        if (fallback.exists() && fallback.length() > 0) {
            if (fallback.length() <= MAX_GROQ_FILE_SIZE_BYTES) {
                return listOf(AudioChunk(fallback, 0.0))
            }
            fallback.delete()
            throw IllegalStateException(
                "Не удалось разбить аудио на части: формат не поддерживается. " +
                        "Выберите MP4/M4A/MP3/WAV или файл короче 25 МБ."
            )
        }

        throw IllegalStateException("Не удалось извлечь аудиодорожку из выбранного файла")
    }

    private fun copyUriToFile(context: Context, uri: Uri, dest: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output)
            }
        }
    }

    // ---------------------------------------------------------------- AAC remux

    private fun chunkAacUri(context: Context, uri: Uri, outputDir: File, stamp: Long): List<AudioChunk>? {
        var extractor: MediaExtractor? = null
        return try {
            extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)
            chunkAacCore(extractor, outputDir, stamp)
        } catch (_: Exception) {
            null
        } finally {
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    private fun chunkAacPath(path: String, outputDir: File, stamp: Long): List<AudioChunk>? {
        var extractor: MediaExtractor? = null
        return try {
            extractor = MediaExtractor()
            extractor.setDataSource(path)
            chunkAacCore(extractor, outputDir, stamp)
        } catch (_: Exception) {
            null
        } finally {
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    private fun chunkAacCore(extractor: MediaExtractor, outputDir: File, stamp: Long): List<AudioChunk>? {
        var audioTrackIndex = -1
        var audioFormat: MediaFormat? = null

        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            // MediaMuxer's MPEG_4 output only supports AAC (and AMR).
            if (mime.equals("audio/mp4a-latm", ignoreCase = true) || mime.startsWith("audio/mp4", ignoreCase = true)) {
                audioTrackIndex = i
                audioFormat = format
                break
            }
        }

        if (audioTrackIndex < 0 || audioFormat == null) return null
        extractor.selectTrack(audioTrackIndex)

        val maxBufferSize = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(64 * 1024)
        } else {
            256 * 1024
        }

        val buffer = ByteBuffer.allocate(maxBufferSize)
        val bufferInfo = MediaCodec.BufferInfo()
        val chunks = mutableListOf<AudioChunk>()

        var muxer: MediaMuxer? = null
        var muxerTrack = -1
        var chunkFile: File? = null
        var chunkStartUs = 0L
        var chunkBytes = 0L
        var chunkIndex = 0
        var lastTimeUs = -1L

        fun finishChunk() {
            val currentMuxer = muxer ?: return
            muxer = null
            try { currentMuxer.stop() } catch (_: Exception) {}
            try { currentMuxer.release() } catch (_: Exception) {}

            val file = chunkFile
            if (file != null && file.exists() && file.length() > 0) {
                chunks.add(AudioChunk(file, chunkStartUs / 1_000_000.0))
            } else {
                file?.delete()
            }
            chunkFile = null
            muxerTrack = -1
        }

        try {
            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                var sampleTime = extractor.sampleTime
                if (sampleTime < 0) sampleTime = lastTimeUs + 1000L
                if (sampleTime <= lastTimeUs) sampleTime = lastTimeUs + 1000L
                lastTimeUs = sampleTime

                if (muxer == null) {
                    chunkFile = File(outputDir, String.format(Locale.US, "part_%d_%03d.m4a", stamp, chunkIndex))
                    val newMuxer = MediaMuxer(chunkFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    muxerTrack = newMuxer.addTrack(audioFormat)
                    newMuxer.start()
                    muxer = newMuxer
                    chunkStartUs = sampleTime
                    chunkBytes = 0
                    chunkIndex++
                }

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = (sampleTime - chunkStartUs).coerceAtLeast(0L)
                bufferInfo.flags = extractor.sampleFlags

                muxer!!.writeSampleData(muxerTrack, buffer, bufferInfo)
                chunkBytes += sampleSize
                extractor.advance()

                if (chunkBytes >= TARGET_CHUNK_BYTES) finishChunk()
            }

            finishChunk()
            return chunks
        } catch (_: Exception) {
            try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            muxer = null
            chunks.forEach { it.file.delete() }
            chunkFile?.delete()
            return null
        }
    }

    // ---------------------------------------------------------------- WAV split

    private fun splitWavFile(file: File, outputDir: File, stamp: Long): List<AudioChunk>? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val fileLength = raf.length()
                val riff = ByteArray(12)
                if (raf.read(riff) != 12) return null
                if (String(riff, 0, 4, StandardCharsets.US_ASCII) != "RIFF") return null
                if (String(riff, 8, 4, StandardCharsets.US_ASCII) != "WAVE") return null

                var channels = 0
                var sampleRate = 0
                var bitsPerSample = 0
                var dataOffset = -1L
                var dataSize = 0L
                var pos = 12L

                while (pos + 8 <= fileLength) {
                    raf.seek(pos)
                    val chunkHeader = ByteArray(8)
                    if (raf.read(chunkHeader) != 8) break

                    val chunkId = String(chunkHeader, 0, 4, StandardCharsets.US_ASCII)
                    val chunkSize = readLeInt(chunkHeader, 4).toLong() and 0xFFFFFFFFL
                    val bodyOffset = pos + 8

                    if (chunkId == "fmt " && chunkSize >= 16) {
                        val fmt = ByteArray(16)
                        raf.seek(bodyOffset)
                        raf.readFully(fmt)
                        channels = readLeShort(fmt, 2)
                        sampleRate = readLeInt(fmt, 4)
                        bitsPerSample = readLeShort(fmt, 14)
                    } else if (chunkId == "data") {
                        dataOffset = bodyOffset
                        dataSize = minOf(chunkSize, fileLength - bodyOffset)
                    }

                    if (dataOffset >= 0 && channels > 0 && sampleRate > 0) break
                    pos = bodyOffset + chunkSize + (chunkSize and 1L)
                }

                if (channels <= 0 || sampleRate <= 0 || bitsPerSample != 16 || dataOffset < 0 || dataSize <= 0) {
                    return null
                }

                val blockAlign = channels * 2
                val bytesPerSecond = sampleRate * blockAlign
                val maxPayload = (TARGET_CHUNK_BYTES - WAV_HEADER_BYTES).toInt()
                val chunks = mutableListOf<AudioChunk>()
                var readPos = dataOffset
                var remaining = dataSize
                var index = 0

                while (remaining > 0) {
                    var payload = minOf(remaining, maxPayload.toLong()).toInt()
                    payload -= payload % blockAlign
                    if (payload <= 0) break

                    val outFile = File(outputDir, String.format(Locale.US, "part_%d_%03d.wav", stamp, index))
                    FileOutputStream(outFile).use { fos ->
                        fos.write(ByteArray(WAV_HEADER_BYTES))
                        raf.seek(readPos)
                        var toCopy = payload
                        val buffer = ByteArray(64 * 1024)
                        while (toCopy > 0) {
                            val read = raf.read(buffer, 0, minOf(toCopy, buffer.size))
                            if (read <= 0) break
                            fos.write(buffer, 0, read)
                            toCopy -= read
                        }
                    }

                    val audioBytes = outFile.length() - WAV_HEADER_BYTES
                    if (audioBytes <= 0) {
                        outFile.delete()
                        break
                    }

                    writeWavHeader(outFile, audioBytes, sampleRate, channels)
                    val offset = (readPos - dataOffset).toDouble() / bytesPerSecond
                    chunks.add(AudioChunk(outFile, offset))

                    readPos += payload
                    remaining -= payload
                    index++
                }

                if (chunks.isEmpty()) null else chunks
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---------------------------------------------------------- Decode to WAV

    private fun decodeChunksUri(context: Context, uri: Uri, outputDir: File, stamp: Long): List<AudioChunk>? {
        var extractor: MediaExtractor? = null
        return try {
            extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)
            decodeChunksCore(extractor, outputDir, stamp)
        } catch (_: Exception) {
            null
        } finally {
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    private fun decodeChunksPath(path: String, outputDir: File, stamp: Long): List<AudioChunk>? {
        var extractor: MediaExtractor? = null
        return try {
            extractor = MediaExtractor()
            extractor.setDataSource(path)
            decodeChunksCore(extractor, outputDir, stamp)
        } catch (_: Exception) {
            null
        } finally {
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    private fun decodeChunksCore(extractor: MediaExtractor, outputDir: File, stamp: Long): List<AudioChunk>? {
        var audioTrackIndex = -1
        var audioFormat: MediaFormat? = null

        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                audioFormat = format
                break
            }
        }

        if (audioTrackIndex < 0 || audioFormat == null) return null
        extractor.selectTrack(audioTrackIndex)
        val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return null

        var decoder: MediaCodec? = null
        var fos: FileOutputStream? = null
        var currentFile: File? = null

        var sampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else {
            44100
        }
        var channelCount = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else {
            2
        }
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

        var chunkStartPcmBytes = 0L
        var totalPcmBytes = 0L
        var chunkIndex = 0
        val chunks = mutableListOf<AudioChunk>()
        val bufferInfo = MediaCodec.BufferInfo()
        val timeoutUs = 10000L
        var extractorEos = false
        var decoderEos = false

        fun finishChunk() {
            val output = fos ?: return
            val file = currentFile ?: return
            try { output.flush() } catch (_: Exception) {}
            try { output.close() } catch (_: Exception) {}
            fos = null
            currentFile = null

            val audioBytes = file.length() - WAV_HEADER_BYTES
            if (audioBytes > 0) {
                writeWavHeader(file, audioBytes, sampleRate, channelCount)
                val bytesPerSecond = sampleRate * channelCount * 2
                val offset = if (bytesPerSecond > 0) chunkStartPcmBytes.toDouble() / bytesPerSecond else 0.0
                chunks.add(AudioChunk(file, offset))
            } else {
                file.delete()
            }
        }

        fun startChunk() {
            val file = File(outputDir, String.format(Locale.US, "part_%d_%03d.wav", stamp, chunkIndex))
            currentFile = file
            fos = FileOutputStream(file)
            fos!!.write(ByteArray(WAV_HEADER_BYTES))
            chunkStartPcmBytes = totalPcmBytes
            chunkIndex++
        }

        try {
            try {
                audioFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            } catch (_: Exception) {
                // Some decoders do not accept this key; use their default output.
            }

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(audioFormat, null, null, 0)
            decoder.start()

            while (!decoderEos) {
                if (!extractorEos) {
                    val inIdx = decoder.dequeueInputBuffer(timeoutUs)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val size = extractor.readSampleData(inBuf, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                extractorEos = true
                            } else {
                                decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outIdx >= 0) {
                    val outBuf = decoder.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        val raw = ByteArray(bufferInfo.size)
                        outBuf.get(raw)
                        val pcm = normalizeToPcm16(raw, pcmEncoding)

                        if (fos == null) startChunk()
                        fos!!.write(pcm)
                        totalPcmBytes += pcm.size

                        if (totalPcmBytes - chunkStartPcmBytes >= TARGET_CHUNK_BYTES - WAV_HEADER_BYTES) {
                            finishChunk()
                        }
                    }
                    decoder.releaseOutputBuffer(outIdx, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        decoderEos = true
                    }
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = decoder.outputFormat
                    if (fos == null) {
                        if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channelCount = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    }
                    if (newFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        pcmEncoding = newFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                }
            }

            finishChunk()
            return if (chunks.isEmpty()) null else chunks
        } catch (_: Exception) {
            try { fos?.close() } catch (_: Exception) {}
            currentFile?.delete()
            chunks.forEach { it.file.delete() }
            return null
        } finally {
            try { fos?.close() } catch (_: Exception) {}
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
        }
    }

    private fun normalizeToPcm16(bytes: ByteArray, encoding: Int): ByteArray {
        return when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val out = ByteBuffer.allocate(floats.remaining() * 2).order(ByteOrder.LITTLE_ENDIAN)
                while (floats.hasRemaining()) {
                    val sample = (floats.get().coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
                    out.putShort(sample.toShort())
                }
                out.array()
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                val out = ByteBuffer.allocate(bytes.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (b in bytes) {
                    val unsigned = b.toInt() and 0xFF
                    out.putShort(((unsigned - 128) shl 8).toShort())
                }
                out.array()
            }
            else -> bytes
        }
    }

    private fun writeWavHeader(file: File, totalAudioLen: Long, sampleRate: Int, channels: Int) {
        val totalDataLen = totalAudioLen + 36
        val byteRate = (sampleRate * channels * 16 / 8).toLong()
        val header = ByteArray(WAV_HEADER_BYTES)

        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xffL).toByte()
        header[5] = ((totalDataLen shr 8) and 0xffL).toByte()
        header[6] = ((totalDataLen shr 16) and 0xffL).toByte()
        header[7] = ((totalDataLen shr 24) and 0xffL).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()

        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xffL).toByte()
        header[29] = ((byteRate shr 8) and 0xffL).toByte()
        header[30] = ((byteRate shr 16) and 0xffL).toByte()
        header[31] = ((byteRate shr 24) and 0xffL).toByte()
        header[32] = (channels * 16 / 8).toByte()
        header[33] = 0
        header[34] = 16
        header[35] = 0

        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xffL).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xffL).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xffL).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xffL).toByte()

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(header)
        }
    }

    private fun readLeShort(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readLeInt(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }
}

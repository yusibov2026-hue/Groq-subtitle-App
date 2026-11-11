package com.ejemplo.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

object GroqApiClient {

    private const val TRANSCRIPTIONS_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
    private const val CHAT_COMPLETIONS_URL = "https://api.groq.com/openai/v1/chat/completions"
    private const val TRANSLATION_BATCH_SIZE = 40

    /**
     * Transcribes audio using Groq Whisper API
     */
    fun transcribeAudio(
        audioFile: File,
        apiKey: String,
        whisperModel: String,
        sourceLanguage: String?
    ): List<SubtitleSegment> {
        val cleanKey = apiKey.trim()
        // The boundary must be a valid HTTP token. Characters like '=' are separators,
        // and Groq rejects such Content-Type headers as "not multipart/form-data".
        val boundary = "----GroqFormBoundary" + System.currentTimeMillis() +
                UUID.randomUUID().toString().replace("-", "")
        val lineEnd = "\r\n"
        val twoHyphens = "--"

        val ext = audioFile.extension.lowercase()
        val (partFileName, partMimeType) = when (ext) {
            "m4a" -> "audio.m4a" to "audio/m4a"
            "mp3" -> "audio.mp3" to "audio/mpeg"
            "wav" -> "audio.wav" to "audio/wav"
            "ogg" -> "audio.ogg" to "audio/ogg"
            "flac" -> "audio.flac" to "audio/flac"
            "mp4" -> "video.mp4" to "video/mp4"
            else -> "audio.wav" to "audio/wav"
        }

        // Build header byte payload
        val headerBytesStream = ByteArrayOutputStream()

        fun writeFormField(name: String, value: String) {
            headerBytesStream.write((twoHyphens + boundary + lineEnd).toByteArray(StandardCharsets.UTF_8))
            headerBytesStream.write(("Content-Disposition: form-data; name=\"$name\"$lineEnd$lineEnd").toByteArray(StandardCharsets.UTF_8))
            headerBytesStream.write(value.toByteArray(StandardCharsets.UTF_8))
            headerBytesStream.write(lineEnd.toByteArray(StandardCharsets.UTF_8))
        }

        writeFormField("model", whisperModel)
        writeFormField("response_format", "verbose_json")
        writeFormField("temperature", "0")

        if (!sourceLanguage.isNullOrBlank() && sourceLanguage != "auto") {
            writeFormField("language", sourceLanguage)
        }

        // File part header
        headerBytesStream.write((twoHyphens + boundary + lineEnd).toByteArray(StandardCharsets.UTF_8))
        headerBytesStream.write(("Content-Disposition: form-data; name=\"file\"; filename=\"$partFileName\"$lineEnd").toByteArray(StandardCharsets.UTF_8))
        headerBytesStream.write(("Content-Type: $partMimeType$lineEnd$lineEnd").toByteArray(StandardCharsets.UTF_8))

        val headerBytes = headerBytesStream.toByteArray()
        val footerBytes = (lineEnd + twoHyphens + boundary + twoHyphens + lineEnd).toByteArray(StandardCharsets.UTF_8)

        val totalContentLength = headerBytes.size + audioFile.length() + footerBytes.size

        val url = URL(TRANSCRIPTIONS_URL)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doInput = true
        conn.doOutput = true
        conn.useCaches = false
        conn.connectTimeout = 45000
        conn.readTimeout = 180000
        conn.setRequestProperty("Authorization", "Bearer $cleanKey")
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.setRequestProperty("User-Agent", "GroqSubtitleAndroid/1.0")
        conn.setRequestProperty("Accept", "application/json")
        conn.setFixedLengthStreamingMode(totalContentLength)

        try {
            conn.outputStream.use { os ->
                os.write(headerBytes)

                FileInputStream(audioFile).use { fis ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        os.write(buffer, 0, bytesRead)
                    }
                }

                os.write(footerBytes)
                os.flush()
            }
        } catch (e: IOException) {
            // Check if server closed early with an HTTP error code (e.g. 401 Unauthorized, 413 File too large)
            val code = try { conn.responseCode } catch (_: Exception) { -1 }
            if (code >= 400) {
                val errorBody = try {
                    conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
                } catch (_: Exception) { null }
                throw IllegalStateException("Ошибка Groq API ($code): ${parseErrorMessage(errorBody)}")
            }
            throw IOException("Ошибка отправки (Broken pipe). Проверьте подключение к интернету и правильность Groq API ключа.", e)
        }

        val responseCode = try {
            conn.responseCode
        } catch (e: IOException) {
            throw IOException("Не удалось получить ответ от Groq API: ${e.message}", e)
        }

        val responseBody = if (responseCode in 200..299) {
            conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use(BufferedReader::readText)
        } else {
            val errorBody = conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use(BufferedReader::readText)
            throw IllegalStateException("Ошибка Groq API ($responseCode): ${parseErrorMessage(errorBody)}")
        }

        return parseTranscriptionResponse(responseBody)
    }

    private fun parseTranscriptionResponse(jsonStr: String): List<SubtitleSegment> {
        val root = JSONObject(jsonStr)
        val segments = mutableListOf<SubtitleSegment>()

        if (root.has("segments")) {
            val segArray = root.getJSONArray("segments")
            for (i in 0 until segArray.length()) {
                val segObj = segArray.getJSONObject(i)
                val id = segObj.optInt("id", i)
                val start = segObj.optDouble("start", 0.0)
                val end = segObj.optDouble("end", start + 2.0)
                val text = segObj.optString("text", "").trim()
                if (text.isNotEmpty()) {
                    segments.add(SubtitleSegment(id, start, end, text))
                }
            }
        }

        if (segments.isEmpty() && root.has("text")) {
            val fullText = root.getString("text").trim()
            if (fullText.isNotEmpty()) {
                val duration = root.optDouble("duration", 5.0)
                segments.add(SubtitleSegment(0, 0.0, duration, fullText))
            }
        }

        return segments
    }

    /**
     * Translates subtitle segments using Groq Chat Completions API
     */
    fun translateSegments(
        segments: List<SubtitleSegment>,
        targetLanguageName: String,
        llmModel: String,
        apiKey: String
    ): List<SubtitleSegment> {
        if (segments.isEmpty()) return segments

        // Long videos produce many segments; translate them in batches so the
        // prompt stays within the model context and partial failures are easier to retry.
        val result = mutableListOf<SubtitleSegment>()
        var start = 0
        while (start < segments.size) {
            val end = minOf(start + TRANSLATION_BATCH_SIZE, segments.size)
            result.addAll(
                translateBatch(
                    segments = segments.subList(start, end),
                    targetLanguageName = targetLanguageName,
                    llmModel = llmModel,
                    apiKey = apiKey
                )
            )
            start = end
        }
        return result
    }

    private fun translateBatch(
        segments: List<SubtitleSegment>,
        targetLanguageName: String,
        llmModel: String,
        apiKey: String
    ): List<SubtitleSegment> {
        val cleanKey = apiKey.trim()
        val promptBuilder = StringBuilder()
        promptBuilder.append("Target Language: ").append(targetLanguageName).append("\n\n")
        promptBuilder.append("Translate each segment. Preserve the exact [ID] format for each line.\n")
        promptBuilder.append("Segments:\n")
        for (seg in segments) {
            promptBuilder.append("[${seg.id}] ").append(seg.text.replace("\n", " ")).append("\n")
        }

        val url = URL(CHAT_COMPLETIONS_URL)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doInput = true
        conn.doOutput = true
        conn.connectTimeout = 45000
        conn.readTimeout = 120000
        conn.setRequestProperty("Authorization", "Bearer $cleanKey")
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        conn.setRequestProperty("User-Agent", "GroqSubtitleAndroid/1.0")

        val systemMessage = JSONObject().apply {
            put("role", "system")
            put(
                "content",
                "You are an expert subtitle translator. Translate each subtitle accurately into $targetLanguageName. " +
                        "Maintain the original tone, timing suitability, and brevity. " +
                        "Format your response strictly as:\n[ID] Translated subtitle text\nDo not output any other introduction, commentary or tags."
            )
        }

        val userMessage = JSONObject().apply {
            put("role", "user")
            put("content", promptBuilder.toString())
        }

        val requestJson = JSONObject().apply {
            put("model", llmModel)
            put("messages", JSONArray().apply {
                put(systemMessage)
                put(userMessage)
            })
            put("temperature", 0.2)
        }

        val bodyBytes = requestJson.toString().toByteArray(StandardCharsets.UTF_8)
        conn.setFixedLengthStreamingMode(bodyBytes.size)

        try {
            conn.outputStream.use { os ->
                os.write(bodyBytes)
                os.flush()
            }
        } catch (e: IOException) {
            val code = try { conn.responseCode } catch (_: Exception) { -1 }
            if (code >= 400) {
                val errBody = try {
                    conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
                } catch (_: Exception) { null }
                throw IllegalStateException("Ошибка перевода Groq API ($code): ${parseErrorMessage(errBody)}")
            }
            throw e
        }

        val responseCode = try {
            conn.responseCode
        } catch (e: IOException) {
            throw IOException("Не удалось получить ответ перевода: ${e.message}", e)
        }

        val responseBody = if (responseCode in 200..299) {
            conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use(BufferedReader::readText)
        } else {
            val errorBody = conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use(BufferedReader::readText)
            throw IllegalStateException("Ошибка перевода Groq API ($responseCode): ${parseErrorMessage(errorBody)}")
        }

        val translatedMap = parseTranslationLines(responseBody)
        return segments.map { seg ->
            val translatedText = translatedMap[seg.id] ?: seg.text
            seg.copy(text = translatedText)
        }
    }

    private fun parseTranslationLines(jsonStr: String): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        val root = JSONObject(jsonStr)
        val choices = root.optJSONArray("choices") ?: return result
        if (choices.length() == 0) return result

        val content = choices.getJSONObject(0).optJSONObject("message")?.optString("content") ?: ""
        val regex = Regex("""\[(\d+)\]\s*(.+)""")

        for (line in content.lines()) {
            val trimmed = line.trim()
            val match = regex.find(trimmed)
            if (match != null) {
                val id = match.groupValues[1].toIntOrNull()
                val text = match.groupValues[2].trim()
                if (id != null && text.isNotEmpty()) {
                    result[id] = text
                }
            }
        }
        return result
    }

    private fun parseErrorMessage(errorJson: String?): String {
        if (errorJson.isNullOrBlank()) return "Неизвестная ошибка"
        return try {
            val obj = JSONObject(errorJson)
            if (obj.has("error")) {
                val err = obj.getJSONObject("error")
                err.optString("message", errorJson)
            } else {
                errorJson
            }
        } catch (_: Exception) {
            errorJson
        }
    }
}

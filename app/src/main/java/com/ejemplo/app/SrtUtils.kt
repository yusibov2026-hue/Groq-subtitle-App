package com.ejemplo.app

object SrtUtils {

    fun toSrt(segments: List<SubtitleSegment>): String {
        val sb = StringBuilder()
        for ((index, segment) in segments.withIndex()) {
            sb.append(index + 1).append("\n")
            sb.append(segment.formatSrtTime(segment.start))
            sb.append(" --> ")
            sb.append(segment.formatSrtTime(segment.end)).append("\n")
            sb.append(segment.text.trim()).append("\n\n")
        }
        return sb.toString().trimEnd() + "\n"
    }

    fun toVtt(segments: List<SubtitleSegment>): String {
        val sb = StringBuilder()
        sb.append("WEBVTT\n\n")
        for ((index, segment) in segments.withIndex()) {
            sb.append(index + 1).append("\n")
            sb.append(segment.formatVttTime(segment.start))
            sb.append(" --> ")
            sb.append(segment.formatVttTime(segment.end)).append("\n")
            sb.append(segment.text.trim()).append("\n\n")
        }
        return sb.toString().trimEnd() + "\n"
    }
}

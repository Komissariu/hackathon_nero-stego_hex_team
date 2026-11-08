package com.example.fluffydisco.data.transcription

import java.io.File

internal class GigaAmCtcDecoder private constructor(
    private val tokens: List<String>
) {

    private val blankId = tokens.indexOf(BLANK_TOKEN).also {
        require(it >= 0) { "GigaAM vocabulary is missing the CTC blank token" }
    }

    constructor(tokenFile: File) : this(readTokens(tokenFile))

    fun decode(logProbabilities: Array<FloatArray>): String {
        val decoded = StringBuilder()
        var previousId = NO_PREVIOUS_TOKEN

        logProbabilities.forEach { frame ->
            require(frame.size == tokens.size) {
                "GigaAM output has ${frame.size} classes; expected ${tokens.size}"
            }
            var bestId = 0
            var bestScore = Float.NEGATIVE_INFINITY
            frame.forEachIndexed { index, score ->
                if (score > bestScore) {
                    bestId = index
                    bestScore = score
                }
            }
            check(bestScore.isFinite()) { "GigaAM output contains no finite token score" }
            if (bestId != previousId && bestId != blankId) {
                decoded.append(tokens[bestId])
            }
            previousId = bestId
        }

        return decoded.toString()
            .replace(SENTENCE_PIECE, ' ')
            .trim()
    }

    companion object {
        private const val BLANK_TOKEN = "<blk>"
        private const val NO_PREVIOUS_TOKEN = -1
        private const val SENTENCE_PIECE = '\u2581'

        internal fun fromTokens(tokens: List<String>): GigaAmCtcDecoder =
            GigaAmCtcDecoder(tokens)

        private fun readTokens(tokenFile: File): List<String> {
            require(tokenFile.isFile) { "GigaAM token vocabulary is missing" }
            val tokens = arrayOfNulls<String>(257)
            tokenFile.forEachLine(Charsets.UTF_8) { line ->
                val separator = line.lastIndexOf(' ')
                require(separator > 0) { "Invalid GigaAM token entry" }
                val token = line.substring(0, separator)
                val id = line.substring(separator + 1).toInt()
                require(id in tokens.indices && tokens[id] == null) {
                    "Invalid or duplicate GigaAM token ID: $id"
                }
                tokens[id] = token
            }
            require(tokens.all { it != null }) { "GigaAM vocabulary must contain 257 tokens" }
            return tokens.map { checkNotNull(it) }
        }
    }
}

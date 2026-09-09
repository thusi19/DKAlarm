package com.dkalarm.app.analysis

import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

interface RowDetector {
    fun detect(tokens: List<OcrToken>, imageWidth: Int, imageHeight: Int): List<Rect>
}

class HeuristicRowDetector : RowDetector {
    private val timeRegex = Regex("\\b(?:[01]?\\d|2[0-3]):[0-5]\\d:[0-5]\\d\\b")

    override fun detect(tokens: List<OcrToken>, imageWidth: Int, imageHeight: Int): List<Rect> {
        if (tokens.isEmpty()) return emptyList()
        val likely = tokens.filter { timeRegex.containsMatchIn(it.text) || it.text.contains("doraz", true) || it.text.contains("příchod", true) }
        val anchors = if (likely.isNotEmpty()) likely else tokens
        val sorted = anchors.sortedBy { it.bounds.centerY() }
        val groups = mutableListOf<MutableList<OcrToken>>()
        val tolerance = max(18, imageHeight / 80)
        for (token in sorted) {
            val g = groups.lastOrNull()
            if (g == null || kotlin.math.abs(g.map { it.bounds.centerY() }.average() - token.bounds.centerY()) > tolerance) {
                groups += mutableListOf(token)
            } else g += token
        }
        return groups.mapNotNull { group ->
            val cy = group.map { it.bounds.centerY() }.average().toInt()
            val rowH = max(38, group.maxOf { it.bounds.height() } * 3)
            val top = max(0, cy - rowH / 2)
            val bottom = min(imageHeight, cy + rowH / 2)
            Rect(0, top, imageWidth, bottom)
        }.distinctBy { it.centerY() }
            .filter { rect -> tokens.any { it.bounds.centerY() in rect.top..rect.bottom && timeRegex.containsMatchIn(it.text) } }
    }
}

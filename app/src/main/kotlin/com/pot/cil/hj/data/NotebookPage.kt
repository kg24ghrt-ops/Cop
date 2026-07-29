package com.pot.cil.hj.data

/**
 * Represents a full page of the notebook with all its lines.
 */
data class NotebookPage(
    val id: Int = 0,
    val lines: MutableList<NoteLine> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis()
) {
    fun getLineAtIndex(lineIndex: Int): NoteLine? {
        return lines.find { it.lineIndex == lineIndex }
    }

    fun addOrUpdateLine(lineIndex: Int, text: String, cursorPosition: Int = text.length) {
        val existing = getLineAtIndex(lineIndex)
        if (existing != null) {
            existing.text = text
            existing.cursorPosition = cursorPosition
        } else {
            lines.add(NoteLine(
                id = lines.size,
                lineIndex = lineIndex,
                text = text,
                cursorPosition = cursorPosition
            ))
        }
    }
}

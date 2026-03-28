package com.kartik.aistudyassistant.ui.home

import com.kartik.aistudyassistant.data.local.RecentActivityItem
import com.kartik.aistudyassistant.data.local.RecentActivityType
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentActivitySubtitleFormatterTest {

    @Test
    fun quiz_withPositiveScore_returnsScoreSubtitle() {
        val item = RecentActivityItem(type = RecentActivityType.QUIZ, topic = "DSA", scoreOrCount = 8, timestamp = 0L)

        val subtitle = RecentActivitySubtitleFormatter.buildContextualText(item)

        assertEquals("Quiz score: 8", subtitle)
    }

    @Test
    fun quiz_withZeroScore_returnsAttemptedSubtitle() {
        val item = RecentActivityItem(type = RecentActivityType.QUIZ, topic = "DSA", scoreOrCount = 0, timestamp = 0L)

        val subtitle = RecentActivitySubtitleFormatter.buildContextualText(item)

        assertEquals("Attempted quiz", subtitle)
    }

    @Test
    fun flashcard_withPositiveCount_returnsReviewedSubtitle() {
        val item = RecentActivityItem(type = RecentActivityType.FLASHCARD, topic = "Trees", scoreOrCount = 12, timestamp = 0L)

        val subtitle = RecentActivitySubtitleFormatter.buildContextualText(item)

        assertEquals("Reviewed 12 cards", subtitle)
    }

    @Test
    fun chat_withSpecificTopic_returnsAskedAboutSubtitle() {
        val item = RecentActivityItem(type = RecentActivityType.CHAT, topic = "Binary Search", scoreOrCount = 0, timestamp = 0L)

        val subtitle = RecentActivitySubtitleFormatter.buildContextualText(item)

        assertEquals("Asked about: Binary Search", subtitle)
    }

    @Test
    fun chat_withGeneralTopic_returnsFallbackSubtitle() {
        val item = RecentActivityItem(type = RecentActivityType.CHAT, topic = "General Chat", scoreOrCount = 0, timestamp = 0L)

        val subtitle = RecentActivitySubtitleFormatter.buildContextualText(item)

        assertEquals("Opened AI chat", subtitle)
    }

    @Test
    fun upload_pdf_returnsPdfSubtitle() {
        val item = RecentActivityItem(type = RecentActivityType.UPLOAD, topic = "notes.pdf", scoreOrCount = 0, timestamp = 0L)

        val subtitle = RecentActivitySubtitleFormatter.buildContextualText(item)

        assertEquals("Uploaded PDF", subtitle)
    }

    @Test
    fun upload_docx_returnsDocumentSubtitle() {
        val item = RecentActivityItem(type = RecentActivityType.UPLOAD, topic = "syllabus.docx", scoreOrCount = 0, timestamp = 0L)

        val subtitle = RecentActivitySubtitleFormatter.buildContextualText(item)

        assertEquals("Uploaded document", subtitle)
    }
}

package com.kartik.aistudyassistant.ui.home

import com.kartik.aistudyassistant.data.local.RecentActivityItem
import com.kartik.aistudyassistant.data.local.RecentActivityType
import java.util.Locale

object RecentActivitySubtitleFormatter {

    fun buildContextualText(item: RecentActivityItem): String {
        return when (item.type) {
            RecentActivityType.QUIZ -> buildQuizText(item.scoreOrCount)
            RecentActivityType.FLASHCARD -> buildFlashcardText(item.scoreOrCount)
            RecentActivityType.CHAT -> buildChatText(item.topic, item.scoreOrCount)
            RecentActivityType.UPLOAD -> buildUploadText(item.topic)
        }
    }

    private fun buildQuizText(score: Int): String {
        return if (score > 0) "Quiz score: $score" else "Attempted quiz"
    }

    private fun buildFlashcardText(cardsReviewed: Int): String {
        return if (cardsReviewed > 0) "Reviewed $cardsReviewed cards" else "Studied flashcards"
    }

    private fun buildChatText(topic: String, promptCount: Int): String {
        val trimmedTopic = topic.trim()
        val base = if (trimmedTopic.isNotEmpty() && !trimmedTopic.equals("General Chat", ignoreCase = true)) {
            "Asked about: $trimmedTopic"
        } else {
            "Opened AI chat"
        }

        return if (promptCount > 0) "$base ($promptCount prompts)" else base
    }

    private fun buildUploadText(fileName: String): String {
        val trimmedName = fileName.trim()
        if (trimmedName.isEmpty()) return "Uploaded file"

        val extension = trimmedName.substringAfterLast('.', "").lowercase(Locale.getDefault())
        return when (extension) {
            "pdf" -> "Uploaded PDF"
            "png", "jpg", "jpeg", "webp", "gif" -> "Uploaded image"
            "doc", "docx", "txt", "rtf" -> "Uploaded document"
            "ppt", "pptx" -> "Uploaded presentation"
            "xls", "xlsx", "csv" -> "Uploaded spreadsheet"
            "" -> "Uploaded file"
            else -> "Uploaded ${extension.uppercase(Locale.getDefault())}"
        }
    }
}

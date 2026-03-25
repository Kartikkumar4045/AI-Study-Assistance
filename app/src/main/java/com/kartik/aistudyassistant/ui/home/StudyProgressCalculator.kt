package com.kartik.aistudyassistant.ui.home

import com.kartik.aistudyassistant.data.local.RecentActivityItem
import com.kartik.aistudyassistant.data.local.RecentActivityType
import java.util.Locale

data class StudyProgressMetrics(
    val dayStreak: Int,
    val quizzes7d: Int,
    val topics7d: Int
)

object StudyProgressCalculator {
    private const val DAY_IN_MILLIS = 24L * 60L * 60L * 1000L
    private val genericTopics = setOf("general quiz", "general study")

    fun calculate(
        activities: List<RecentActivityItem>,
        nowMillis: Long = System.currentTimeMillis()
    ): StudyProgressMetrics {
        if (activities.isEmpty()) {
            return StudyProgressMetrics(dayStreak = 0, quizzes7d = 0, topics7d = 0)
        }

        val today = dayNumber(nowMillis)
        val startDay = today - 6

        var quizzes7d = 0
        val activeStudyDays = mutableSetOf<Long>()
        val topicKeys = mutableSetOf<String>()

        activities.forEach { item ->
            if (!isStudyType(item.type)) return@forEach

            val day = dayNumber(item.timestamp)
            if (day > today) return@forEach

            activeStudyDays.add(day)

            if (day in startDay..today) {
                if (item.type == RecentActivityType.QUIZ) {
                    quizzes7d++
                }

                normalizedTopicKey(item.topic)?.let { topicKeys.add(it) }
            }
        }

        return StudyProgressMetrics(
            dayStreak = calculateStreak(activeStudyDays, today),
            quizzes7d = quizzes7d,
            topics7d = topicKeys.size
        )
    }

    private fun calculateStreak(activeStudyDays: Set<Long>, today: Long): Int {
        var streak = 0
        var day = today

        while (activeStudyDays.contains(day)) {
            streak++
            day--
        }

        return streak
    }

    private fun isStudyType(type: RecentActivityType): Boolean {
        return type == RecentActivityType.QUIZ || type == RecentActivityType.FLASHCARD
    }

    private fun dayNumber(timestamp: Long): Long {
        return timestamp / DAY_IN_MILLIS
    }

    private fun normalizedTopicKey(topic: String): String? {
        val normalized = topic.trim().lowercase(Locale.getDefault())
        if (normalized.isEmpty()) return null
        if (normalized in genericTopics) return null
        return normalized
    }
}


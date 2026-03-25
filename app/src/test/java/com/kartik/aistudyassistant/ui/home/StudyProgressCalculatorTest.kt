package com.kartik.aistudyassistant.ui.home

import com.kartik.aistudyassistant.data.local.RecentActivityItem
import com.kartik.aistudyassistant.data.local.RecentActivityType
import org.junit.Assert.assertEquals
import org.junit.Test

class StudyProgressCalculatorTest {

    @Test
    fun calculate_withNoActivities_returnsAllZero() {
        val metrics = StudyProgressCalculator.calculate(emptyList(), nowMillis = DAY * 30)

        assertEquals(0, metrics.dayStreak)
        assertEquals(0, metrics.quizzes7d)
        assertEquals(0, metrics.topics7d)
    }

    @Test
    fun calculate_countsConsecutiveStudyDaysForStreak() {
        val now = DAY * 21
        val activities = listOf(
            studyItem(RecentActivityType.QUIZ, "Arrays", dayOffsetFromNow = 0, now = now),
            studyItem(RecentActivityType.FLASHCARD, "Trees", dayOffsetFromNow = -1, now = now),
            studyItem(RecentActivityType.QUIZ, "DP", dayOffsetFromNow = -2, now = now),
            studyItem(RecentActivityType.QUIZ, "Graphs", dayOffsetFromNow = -4, now = now)
        )

        val metrics = StudyProgressCalculator.calculate(activities, nowMillis = now)

        assertEquals(3, metrics.dayStreak)
    }

    @Test
    fun calculate_quizzes7d_countsOnlyQuizInWindow() {
        val now = DAY * 19
        val activities = listOf(
            studyItem(RecentActivityType.QUIZ, "Arrays", dayOffsetFromNow = 0, now = now),
            studyItem(RecentActivityType.QUIZ, "Trees", dayOffsetFromNow = -6, now = now),
            studyItem(RecentActivityType.QUIZ, "DP", dayOffsetFromNow = -7, now = now),
            studyItem(RecentActivityType.FLASHCARD, "Graphs", dayOffsetFromNow = -1, now = now)
        )

        val metrics = StudyProgressCalculator.calculate(activities, nowMillis = now)

        assertEquals(2, metrics.quizzes7d)
    }

    @Test
    fun calculate_topics7d_countsUniqueStudyTopics_excludesGenericAndChat() {
        val now = DAY * 20
        val activities = listOf(
            studyItem(RecentActivityType.QUIZ, "Arrays", dayOffsetFromNow = 0, now = now),
            studyItem(RecentActivityType.FLASHCARD, " arrays ", dayOffsetFromNow = -1, now = now),
            studyItem(RecentActivityType.QUIZ, "General Quiz", dayOffsetFromNow = -1, now = now),
            studyItem(RecentActivityType.FLASHCARD, "General Study", dayOffsetFromNow = -2, now = now),
            studyItem(RecentActivityType.CHAT, "System Design", dayOffsetFromNow = 0, now = now),
            studyItem(RecentActivityType.FLASHCARD, "Graphs", dayOffsetFromNow = -3, now = now)
        )

        val metrics = StudyProgressCalculator.calculate(activities, nowMillis = now)

        assertEquals(2, metrics.topics7d)
    }

    private fun studyItem(
        type: RecentActivityType,
        topic: String,
        dayOffsetFromNow: Int,
        now: Long
    ): RecentActivityItem {
        return RecentActivityItem(
            type = type,
            topic = topic,
            scoreOrCount = 0,
            timestamp = now + (dayOffsetFromNow * DAY),
            id = "${type.name}-$topic-$dayOffsetFromNow"
        )
    }

    companion object {
        private const val DAY = 24L * 60L * 60L * 1000L
    }
}

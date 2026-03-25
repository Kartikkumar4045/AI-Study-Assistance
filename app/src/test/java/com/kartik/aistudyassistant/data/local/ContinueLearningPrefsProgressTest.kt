package com.kartik.aistudyassistant.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContinueLearningPrefsProgressTest {

    @Test
    fun nextStreakValue_sameDay_keepsStreak() {
        val streak = ContinueLearningPrefs.nextStreakValue(
            currentStreak = 3,
            lastActiveDay = 100L,
            activityDay = 100L
        )

        assertEquals(3, streak)
    }

    @Test
    fun nextStreakValue_consecutiveDay_incrementsStreak() {
        val streak = ContinueLearningPrefs.nextStreakValue(
            currentStreak = 3,
            lastActiveDay = 100L,
            activityDay = 101L
        )

        assertEquals(4, streak)
    }

    @Test
    fun nextStreakValue_gap_resetsToOne() {
        val streak = ContinueLearningPrefs.nextStreakValue(
            currentStreak = 5,
            lastActiveDay = 100L,
            activityDay = 103L
        )

        assertEquals(1, streak)
    }

    @Test
    fun nextStreakValue_noLastActiveDay_startsAtOne() {
        val streak = ContinueLearningPrefs.nextStreakValue(
            currentStreak = 0,
            lastActiveDay = null,
            activityDay = 100L
        )

        assertEquals(1, streak)
    }

    @Test
    fun normalizedTopicForProgress_trimsAndLowercases() {
        val topic = ContinueLearningPrefs.normalizedTopicForProgress("  Binary Search  ")

        assertEquals("binary search", topic)
    }

    @Test
    fun normalizedTopicForProgress_ignoresGenericTopic() {
        val topic = ContinueLearningPrefs.normalizedTopicForProgress("General Quiz")

        assertNull(topic)
    }
}


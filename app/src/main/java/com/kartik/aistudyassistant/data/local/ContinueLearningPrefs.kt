package com.kartik.aistudyassistant.data.local

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class ContinueLearningSnapshot(
    val flashcardTopic: String,
    val flashcardCount: Int,
    val quizTopic: String,
    val quizScore: Int
)

@Serializable
enum class SessionType {
    FLASHCARD,
    QUIZ
}

enum class SessionStatus {
    NOT_STARTED,
    IN_PROGRESS,
    COMPLETED
}

@Serializable
enum class RecentActivityType {
    QUIZ,
    FLASHCARD,
    CHAT,
    UPLOAD
}

@Serializable
data class RecentActivityItem(
    val type: RecentActivityType,
    val topic: String,
    val scoreOrCount: Int,
    val totalCount: Int = 0, // Added to store total questions/cards for percentage
    val timestamp: Long,
    val id: String = "",
    val sessionId: String = ""
)

data class SessionOverview(
    val topicId: String,
    val type: SessionType,
    val currentIndex: Int,
    val totalQuestions: Int,
    val status: SessionStatus
)

data class StudyProgressSnapshot(
    val dayStreak: Int,
    val totalQuizzes: Int,
    val totalTopics: Int
)

data class ProfileLearningSnapshot(
    val dayStreak: Int,
    val totalStudyTimeMs: Long,
    val totalQuizzes: Int,
    val lastActiveTimestamp: Long?
)

@Serializable
data class QuizAttemptRecord(
    val topic: String,
    val score: Int,
    val totalQuestions: Int = 0, // Added for percentage calculation
    val timestamp: Long
)

@Serializable
data class TopicMasteryRecord(
    val topicKey: String,
    val displayTopic: String,
    val quizCount: Int,
    val flashcardCount: Int,
    val chatCount: Int,
    val lastActivityTimestamp: Long,
    val lastSource: String = "topic",
    val lastNoteName: String = ""
)

data class StudyStreakDetails(
    val dayStreak: Int,
    val lastActiveDaysAgo: Int?,
    val recentSevenDaysActive: List<Boolean>
)

data class QuizPerformanceSnapshot(
    val totalQuizzes: Int,
    val averageScore: Int,
    val bestScore: Int,
    val recentAttempts: List<QuizAttemptRecord>,
    val availableTopics: List<String>
)

object ContinueLearningPrefs {
    private const val DEFAULT_SOURCE_TOPIC = "topic"
    private const val PREF_FILE = "continue_learning_prefs"
    private const val DAY_IN_MILLIS = 24L * 60L * 60L * 1000L
    const val SOURCE_TOPIC = "topic"
    const val SOURCE_NOTES = "notes"
    const val KEY_LAST_FLASHCARD_TOPIC = "last_flashcard_topic"
    const val KEY_LAST_FLASHCARD_COUNT = "last_flashcard_count"
    const val KEY_LAST_QUIZ_TOPIC = "last_quiz_topic"
    const val KEY_LAST_QUIZ_SCORE = "last_quiz_score"
    private const val KEY_RECENT_ACTIVITY_JSON = "recent_activity_json"
    private const val MAX_RECENT_ACTIVITY_ITEMS = 20
    private const val KEY_PROGRESS_DAY_STREAK = "progress_day_streak"
    private const val KEY_PROGRESS_LAST_ACTIVE_DAY = "progress_last_active_day"
    private const val KEY_PROGRESS_TOTAL_QUIZZES = "progress_total_quizzes"
    private const val KEY_PROGRESS_TOTAL_STUDY_TIME_MS = "progress_total_study_time_ms"
    private const val KEY_PROGRESS_TOPICS_JSON = "progress_topics_json"
    private const val KEY_PROGRESS_ACTIVE_DAYS_JSON = "progress_active_days_json"
    private const val KEY_PROGRESS_QUIZ_ATTEMPTS_JSON = "progress_quiz_attempts_json"
    private const val KEY_PROGRESS_TOPIC_MASTERY_JSON = "progress_topic_mastery_json"
    private const val UNSET_DAY = Long.MIN_VALUE
    private const val MAX_ACTIVE_DAYS = 120
    private const val MAX_QUIZ_ATTEMPTS = 200
    private const val ALL_TOPICS_FILTER = "All Topics"
    private val ignoredProgressTopics = setOf(
        "general quiz",
        "general study",
        "general chat",
        "study file"
    )

    // Quiz in-progress keys.
    const val KEY_QUIZ_TOPIC = "quiz_topic"
    const val KEY_QUIZ_TOTAL_QUESTIONS = "quiz_total_questions"
    const val KEY_QUIZ_CURRENT_INDEX = "quiz_current_index"
    private const val KEY_QUIZ_SOURCE = "quiz_source"
    private const val KEY_QUIZ_SELECTED_NOTE = "quiz_selected_note"
    private const val KEY_QUIZ_ANSWERS = "quiz_answers"
    private const val KEY_QUIZ_QUESTIONS_JSON = "quiz_questions_json"
    private const val KEY_QUIZ_IN_PROGRESS = "quiz_in_progress"
    private const val KEY_QUIZ_STATUS = "quiz_status"

    // Flashcard in-progress keys.
    const val KEY_FLASHCARD_TOPIC = "flashcard_topic"
    const val KEY_FLASHCARD_TOTAL = "flashcard_total"
    const val KEY_FLASHCARD_CURRENT_INDEX = "flashcard_current_index"
    const val KEY_FLASHCARD_REVEALED_STATES = "flashcard_revealed_states"
    const val KEY_FLASHCARD_DIFFICULTY_STATES = "flashcard_difficulty_states"
    const val KEY_FLASHCARD_IN_PROGRESS = "flashcard_in_progress"
    private const val KEY_FLASHCARD_ANSWER_REVEALED_STATES = "flashcard_answer_revealed_states"
    private const val KEY_FLASHCARD_SOURCE = "flashcard_source"
    private const val KEY_FLASHCARD_SELECTED_NOTE = "flashcard_selected_note"
    private const val KEY_FLASHCARD_FLASHCARDS_JSON = "flashcard_items_json"
    private const val KEY_FLASHCARD_STATUS = "flashcard_status"

    private val json = Json { ignoreUnknownKeys = true }

    private fun prefs(context: Context) =
        context.getSharedPreferences(resolvePrefsFileName(), Context.MODE_PRIVATE)

    private fun resolvePrefsFileName(): String {
        val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty().trim()
        return if (uid.isBlank()) PREF_FILE else "${PREF_FILE}_$uid"
    }

    data class QuizProgressSnapshot(
        val topic: String,
        val source: String,
        val selectedNoteId: String,
        val totalQuestions: Int,
        val currentIndex: Int,
        val answers: List<Int>,
        val questionsJson: String,
        val inProgress: Boolean
    )

    data class FlashcardProgressSnapshot(
        val topic: String,
        val source: String,
        val selectedNote: String,
        val totalCards: Int,
        val currentIndex: Int,
        val revealedStates: List<Boolean>,
        val answerRevealedStates: List<Boolean>,
        val difficultyStates: List<Int>,
        val flashcardsJson: String,
        val inProgress: Boolean
    )

    fun saveFlashcardActivity(
        context: Context,
        topic: String,
        cardCount: Int,
        source: String = SOURCE_TOPIC,
        noteName: String = ""
    ) {
        val safeTopic = topic.ifBlank { "General Study" }
        val now = System.currentTimeMillis()
        prefs(context)
            .edit()
            .putString(KEY_LAST_FLASHCARD_TOPIC, safeTopic)
            .putInt(KEY_LAST_FLASHCARD_COUNT, cardCount.coerceAtLeast(0))
            .apply()

        updateStudyProgress(
            context = context,
            topic = safeTopic,
            incrementQuizCount = false,
            trackTopic = true,
            timestamp = now
        )

        updateTopicMastery(
            context = context,
            topic = safeTopic,
            type = RecentActivityType.FLASHCARD,
            timestamp = now,
            source = source,
            noteName = noteName
        )

        appendRecentActivity(
            context = context,
            item = RecentActivityItem(
                type = RecentActivityType.FLASHCARD,
                topic = safeTopic,
                scoreOrCount = cardCount.coerceAtLeast(0),
                totalCount = cardCount.coerceAtLeast(0),
                timestamp = now,
                id = UUID.randomUUID().toString()
            )
        )
    }

    fun saveQuizActivity(
        context: Context,
        topic: String,
        score: Int,
        totalQuestions: Int = 0,
        source: String = SOURCE_TOPIC,
        noteName: String = ""
    ) {
        val safeTopic = topic.ifBlank { "General Quiz" }
        val now = System.currentTimeMillis()
        prefs(context)
            .edit()
            .putString(KEY_LAST_QUIZ_TOPIC, safeTopic)
            .putInt(KEY_LAST_QUIZ_SCORE, score.coerceAtLeast(0))
            .apply()

        updateStudyProgress(
            context = context,
            topic = safeTopic,
            incrementQuizCount = true,
            trackTopic = true,
            timestamp = now
        )

        appendQuizAttempt(
            context = context,
            attempt = QuizAttemptRecord(
                topic = safeTopic,
                score = score.coerceAtLeast(0),
                totalQuestions = totalQuestions.coerceAtLeast(0),
                timestamp = now
            )
        )

        updateTopicMastery(
            context = context,
            topic = safeTopic,
            type = RecentActivityType.QUIZ,
            timestamp = now,
            source = source,
            noteName = noteName
        )

        appendRecentActivity(
            context = context,
            item = RecentActivityItem(
                type = RecentActivityType.QUIZ,
                topic = safeTopic,
                scoreOrCount = score.coerceAtLeast(0),
                totalCount = totalQuestions.coerceAtLeast(0),
                timestamp = now,
                id = UUID.randomUUID().toString()
            )
        )
    }

    fun saveChatActivity(
        context: Context,
        topic: String,
        sessionId: String = "",
        messageCount: Int = 0,
        source: String = SOURCE_TOPIC,
        noteName: String = ""
    ) {
        val safeTopic = topic.ifBlank { "General Chat" }
        val now = System.currentTimeMillis()

        updateStudyProgress(
            context = context,
            topic = safeTopic,
            incrementQuizCount = false,
            trackTopic = true,
            timestamp = now
        )

        updateTopicMastery(
            context = context,
            topic = safeTopic,
            type = RecentActivityType.CHAT,
            timestamp = now,
            source = source,
            noteName = noteName
        )

        val newItem = RecentActivityItem(
            type = RecentActivityType.CHAT,
            topic = safeTopic,
            scoreOrCount = messageCount.coerceAtLeast(0),
            timestamp = now,
            id = UUID.randomUUID().toString(),
            sessionId = sessionId
        )

        val prefs = prefs(context)
        val existing = readRawRecentActivities(prefs)

        val updated = if (sessionId.isBlank()) {
            listOf(newItem) + existing
        } else {
            val filtered = existing.filterNot {
                it.type == RecentActivityType.CHAT && it.sessionId == sessionId
            }
            listOf(newItem) + filtered
        }

        persistRecentActivities(
            prefs = prefs,
            values = updated.take(MAX_RECENT_ACTIVITY_ITEMS)
        )
    }

    fun saveUploadActivity(context: Context, fileName: String) {
        val safeFileName = fileName.ifBlank { "Study File" }

        updateStudyProgress(
            context = context,
            topic = safeFileName,
            incrementQuizCount = false,
            trackTopic = false
        )

        appendRecentActivity(
            context = context,
            item = RecentActivityItem(
                type = RecentActivityType.UPLOAD,
                topic = safeFileName,
                scoreOrCount = 0,
                timestamp = System.currentTimeMillis(),
                id = UUID.randomUUID().toString()
            )
        )
    }

    fun readStudyProgress(context: Context): StudyProgressSnapshot {
        val prefs = prefs(context)
        val topics = decodeStringList(prefs.getString(KEY_PROGRESS_TOPICS_JSON, "[]").orEmpty())

        return StudyProgressSnapshot(
            dayStreak = prefs.getInt(KEY_PROGRESS_DAY_STREAK, 0).coerceAtLeast(0),
            totalQuizzes = prefs.getInt(KEY_PROGRESS_TOTAL_QUIZZES, 0).coerceAtLeast(0),
            totalTopics = topics.size
        )
    }

    fun addStudyTime(context: Context, durationMs: Long, timestamp: Long = System.currentTimeMillis()) {
        val safeDuration = durationMs.coerceAtLeast(0L)
        if (safeDuration == 0L) return

        val prefs = prefs(context)
        val existingTotal = prefs.getLong(KEY_PROGRESS_TOTAL_STUDY_TIME_MS, 0L)
        prefs.edit()
            .putLong(KEY_PROGRESS_TOTAL_STUDY_TIME_MS, existingTotal + safeDuration)
            .apply()

        updateStudyProgress(
            context = context,
            topic = "",
            incrementQuizCount = false,
            trackTopic = false,
            timestamp = timestamp
        )
    }

    fun readProfileLearningSnapshot(context: Context): ProfileLearningSnapshot {
        val prefs = prefs(context)
        val progress = readStudyProgress(context)
        val details = readStudyStreakDetails(context)
        val lastActivityTs = readRawRecentActivities(prefs)
            .maxOfOrNull { it.timestamp }

        return ProfileLearningSnapshot(
            dayStreak = details.dayStreak,
            totalStudyTimeMs = prefs.getLong(KEY_PROGRESS_TOTAL_STUDY_TIME_MS, 0L).coerceAtLeast(0L),
            totalQuizzes = progress.totalQuizzes,
            lastActiveTimestamp = lastActivityTs
        )
    }

    fun readStudyStreakDetails(context: Context, nowMillis: Long = System.currentTimeMillis()): StudyStreakDetails {
        val prefs = prefs(context)
        val streak = prefs.getInt(KEY_PROGRESS_DAY_STREAK, 0).coerceAtLeast(0)
        val today = localDayNumber(nowMillis)
        val lastActiveDay = prefs.getLong(KEY_PROGRESS_LAST_ACTIVE_DAY, UNSET_DAY)
            .takeIf { it != UNSET_DAY }

        val activeDays = decodeLongList(
            prefs.getString(KEY_PROGRESS_ACTIVE_DAYS_JSON, "[]").orEmpty()
        ).toSet()

        val recent = (6L downTo 0L).map { offset ->
            activeDays.contains(today - offset)
        }

        return StudyStreakDetails(
            dayStreak = streak,
            lastActiveDaysAgo = lastActiveDay?.let { (today - it).coerceAtLeast(0L).toInt() },
            recentSevenDaysActive = recent
        )
    }

    fun readQuizPerformance(
        context: Context,
        daysFilter: Int?,
        topicFilter: String?,
        nowMillis: Long = System.currentTimeMillis()
    ): QuizPerformanceSnapshot {
        val attempts = readQuizAttempts(context)
        val normalizedFilter = topicFilter
            ?.takeIf { it.isNotBlank() && !it.equals(ALL_TOPICS_FILTER, ignoreCase = true) }
            ?.trim()
            ?.lowercase(Locale.getDefault())
        val minTime = daysFilter?.let { nowMillis - (it.toLong() * DAY_IN_MILLIS) }

        val filtered = attempts
            .filter { attempt ->
                val topicMatches = normalizedFilter == null ||
                    attempt.topic.trim().lowercase(Locale.getDefault()) == normalizedFilter
                val timeMatches = minTime == null || attempt.timestamp >= minTime
                topicMatches && timeMatches
            }
            .sortedByDescending { it.timestamp }

        val total = filtered.size
        
        // Correct percentage-wise average calculation
        val average = if (total > 0) {
            val totalPercentage = filtered.sumOf { 
                if (it.totalQuestions > 0) (it.score.toFloat() / it.totalQuestions * 100).toInt() else 0 
            }
            totalPercentage / total
        } else 0
        
        // Correct best percentage calculation
        val best = filtered.maxOfOrNull { 
            if (it.totalQuestions > 0) (it.score.toFloat() / it.totalQuestions * 100).toInt() else 0 
        } ?: 0

        val topics = attempts
            .map { it.topic.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.getDefault()) }
            .sortedBy { it.lowercase(Locale.getDefault()) }

        return QuizPerformanceSnapshot(
            totalQuizzes = total,
            averageScore = average,
            bestScore = best,
            recentAttempts = filtered.take(5),
            availableTopics = topics
        )
    }

    fun readTopicMastery(context: Context): List<TopicMasteryRecord> {
        val prefs = prefs(context)
        return decodeTopicMasteryList(
            prefs.getString(KEY_PROGRESS_TOPIC_MASTERY_JSON, "[]").orEmpty()
        ).sortedByDescending { it.lastActivityTimestamp }
    }

    fun allTopicsFilterLabel(): String = ALL_TOPICS_FILTER

    fun nextStreakValue(currentStreak: Int, lastActiveDay: Long?, activityDay: Long): Int {
        val safeStreak = currentStreak.coerceAtLeast(0)
        if (lastActiveDay == null) return 1
        return when {
            activityDay == lastActiveDay -> safeStreak
            activityDay == lastActiveDay + 1L -> safeStreak + 1
            else -> 1
        }
    }

    fun normalizedTopicForProgress(topic: String): String? {
        val normalized = topic.trim().lowercase(Locale.getDefault())
        if (normalized.isBlank()) return null
        if (normalized in ignoredProgressTopics) return null
        return normalized
    }

    fun readRecentActivities(context: Context, limit: Int = MAX_RECENT_ACTIVITY_ITEMS): List<RecentActivityItem> {
        if (limit <= 0) return emptyList()
        val prefs = prefs(context)
        return readRawRecentActivities(prefs)
            .sortedByDescending { it.timestamp }
            .take(limit)
    }

    fun removeRecentActivity(context: Context, activityId: String): Boolean {
        if (activityId.isBlank()) return false
        val prefs = prefs(context)
        val existing = readRawRecentActivities(prefs)
        val updated = existing.filterNot { it.id == activityId }
        if (updated.size == existing.size) return false

        persistRecentActivities(prefs, updated)
        return true
    }

    fun clearRecentActivities(context: Context) {
        val prefs = prefs(context)
        prefs.edit().putString(KEY_RECENT_ACTIVITY_JSON, "[]").apply()
    }

    fun saveQuizProgress(
        context: Context,
        topic: String,
        source: String,
        selectedNoteId: String,
        totalQuestions: Int,
        currentIndex: Int,
        answers: List<Int>,
        questionsJson: String,
        inProgress: Boolean
    ) {
        prefs(context)
            .edit()
            .putString(KEY_QUIZ_TOPIC, topic)
            .putString(KEY_QUIZ_SOURCE, source)
            .putString(KEY_QUIZ_SELECTED_NOTE, selectedNoteId)
            .putInt(KEY_QUIZ_TOTAL_QUESTIONS, totalQuestions.coerceAtLeast(0))
            .putInt(KEY_QUIZ_CURRENT_INDEX, currentIndex.coerceAtLeast(0))
            .putString(KEY_QUIZ_ANSWERS, encodeIntList(answers))
            .putString(KEY_QUIZ_QUESTIONS_JSON, questionsJson)
            .putBoolean(KEY_QUIZ_IN_PROGRESS, inProgress)
            .putString(
                KEY_QUIZ_STATUS,
                if (inProgress) SessionStatus.IN_PROGRESS.name else SessionStatus.NOT_STARTED.name
            )
            .apply()
    }

    fun readQuizProgress(context: Context): QuizProgressSnapshot {
        val prefs = prefs(context)
        return QuizProgressSnapshot(
            topic = prefs.getString(KEY_QUIZ_TOPIC, "").orEmpty(),
            source = prefs.getString(KEY_QUIZ_SOURCE, DEFAULT_SOURCE_TOPIC)
                .orEmpty()
                .ifBlank { DEFAULT_SOURCE_TOPIC },
            selectedNoteId = prefs.getString(KEY_QUIZ_SELECTED_NOTE, "").orEmpty(),
            totalQuestions = prefs.getInt(KEY_QUIZ_TOTAL_QUESTIONS, 0),
            currentIndex = prefs.getInt(KEY_QUIZ_CURRENT_INDEX, 0),
            answers = decodeIntList(prefs.getString(KEY_QUIZ_ANSWERS, "[]").orEmpty()),
            questionsJson = prefs.getString(KEY_QUIZ_QUESTIONS_JSON, "").orEmpty(),
            inProgress = prefs.getBoolean(KEY_QUIZ_IN_PROGRESS, false)
        )
    }

    fun clearQuizProgress(context: Context) {
        prefs(context)
            .edit()
            .remove(KEY_QUIZ_TOPIC)
            .remove(KEY_QUIZ_SOURCE)
            .remove(KEY_QUIZ_SELECTED_NOTE)
            .remove(KEY_QUIZ_TOTAL_QUESTIONS)
            .remove(KEY_QUIZ_CURRENT_INDEX)
            .remove(KEY_QUIZ_ANSWERS)
            .remove(KEY_QUIZ_QUESTIONS_JSON)
            .putBoolean(KEY_QUIZ_IN_PROGRESS, false)
            .putString(KEY_QUIZ_STATUS, SessionStatus.NOT_STARTED.name)
            .apply()
    }

    fun markQuizCompleted(context: Context) {
        val prefs = prefs(context)
        val totalQuestions = prefs.getInt(KEY_QUIZ_TOTAL_QUESTIONS, 0)
        prefs.edit()
            .putInt(KEY_QUIZ_CURRENT_INDEX, totalQuestions.coerceAtLeast(0))
            .putBoolean(KEY_QUIZ_IN_PROGRESS, false)
            .putString(KEY_QUIZ_STATUS, SessionStatus.COMPLETED.name)
            .remove(KEY_QUIZ_ANSWERS)
            .remove(KEY_QUIZ_QUESTIONS_JSON)
            .apply()
    }

    fun saveFlashcardProgress(
        context: Context,
        topic: String,
        source: String,
        selectedNote: String,
        totalCards: Int,
        currentIndex: Int,
        revealedStates: List<Boolean>,
        answerRevealedStates: List<Boolean>,
        difficultyStates: List<Int>,
        flashcardsJson: String,
        inProgress: Boolean
    ) {
        prefs(context)
            .edit()
            .putString(KEY_FLASHCARD_TOPIC, topic)
            .putString(KEY_FLASHCARD_SOURCE, source)
            .putString(KEY_FLASHCARD_SELECTED_NOTE, selectedNote)
            .putInt(KEY_FLASHCARD_TOTAL, totalCards.coerceAtLeast(0))
            .putInt(KEY_FLASHCARD_CURRENT_INDEX, currentIndex.coerceAtLeast(0))
            .putString(KEY_FLASHCARD_REVEALED_STATES, encodeBooleanList(revealedStates))
            .putString(KEY_FLASHCARD_ANSWER_REVEALED_STATES, encodeBooleanList(answerRevealedStates))
            .putString(KEY_FLASHCARD_DIFFICULTY_STATES, encodeIntList(difficultyStates))
            .putString(KEY_FLASHCARD_FLASHCARDS_JSON, flashcardsJson)
            .putBoolean(KEY_FLASHCARD_IN_PROGRESS, inProgress)
            .putString(
                KEY_FLASHCARD_STATUS,
                if (inProgress) SessionStatus.IN_PROGRESS.name else SessionStatus.NOT_STARTED.name
            )
            .apply()
    }

    fun readFlashcardProgress(context: Context): FlashcardProgressSnapshot {
        val prefs = prefs(context)
        return FlashcardProgressSnapshot(
            topic = prefs.getString(KEY_FLASHCARD_TOPIC, "").orEmpty(),
            source = prefs.getString(KEY_FLASHCARD_SOURCE, DEFAULT_SOURCE_TOPIC)
                .orEmpty()
                .ifBlank { DEFAULT_SOURCE_TOPIC },
            selectedNote = prefs.getString(KEY_FLASHCARD_SELECTED_NOTE, "").orEmpty(),
            totalCards = prefs.getInt(KEY_FLASHCARD_TOTAL, 0),
            currentIndex = prefs.getInt(KEY_FLASHCARD_CURRENT_INDEX, 0),
            revealedStates = decodeBooleanList(
                prefs.getString(KEY_FLASHCARD_REVEALED_STATES, "[]").orEmpty()
            ),
            answerRevealedStates = decodeBooleanList(
                prefs.getString(KEY_FLASHCARD_ANSWER_REVEALED_STATES, "[]").orEmpty()
            ),
            difficultyStates = decodeIntList(
                prefs.getString(KEY_FLASHCARD_DIFFICULTY_STATES, "[]").orEmpty()
            ),
            flashcardsJson = prefs.getString(KEY_FLASHCARD_FLASHCARDS_JSON, "").orEmpty(),
            inProgress = prefs.getBoolean(KEY_FLASHCARD_IN_PROGRESS, false)
        )
    }

    fun clearFlashcardProgress(context: Context) {
        prefs(context)
            .edit()
            .remove(KEY_FLASHCARD_TOPIC)
            .remove(KEY_FLASHCARD_SOURCE)
            .remove(KEY_FLASHCARD_SELECTED_NOTE)
            .remove(KEY_FLASHCARD_TOTAL)
            .remove(KEY_FLASHCARD_CURRENT_INDEX)
            .remove(KEY_FLASHCARD_REVEALED_STATES)
            .remove(KEY_FLASHCARD_ANSWER_REVEALED_STATES)
            .remove(KEY_FLASHCARD_DIFFICULTY_STATES)
            .remove(KEY_FLASHCARD_FLASHCARDS_JSON)
            .putBoolean(KEY_FLASHCARD_IN_PROGRESS, false)
            .putString(KEY_FLASHCARD_STATUS, SessionStatus.NOT_STARTED.name)
            .apply()
    }

    fun markFlashcardCompleted(context: Context) {
        val prefs = prefs(context)
        val totalCards = prefs.getInt(KEY_FLASHCARD_TOTAL, 0)
        prefs.edit()
            .putInt(KEY_FLASHCARD_CURRENT_INDEX, totalCards.coerceAtLeast(0))
            .putBoolean(KEY_FLASHCARD_IN_PROGRESS, false)
            .putString(KEY_FLASHCARD_STATUS, SessionStatus.COMPLETED.name)
            .remove(KEY_FLASHCARD_REVEALED_STATES)
            .remove(KEY_FLASHCARD_ANSWER_REVEALED_STATES)
            .remove(KEY_FLASHCARD_DIFFICULTY_STATES)
            .remove(KEY_FLASHCARD_FLASHCARDS_JSON)
            .apply()
    }

    fun readActiveSessions(context: Context): List<SessionOverview> {
        val prefs = prefs(context)
        val sessions = mutableListOf<SessionOverview>()

        val flashStatus = parseStatus(
            prefs.getString(KEY_FLASHCARD_STATUS, SessionStatus.NOT_STARTED.name)
        )
        if (flashStatus == SessionStatus.IN_PROGRESS) {
            sessions.add(
                SessionOverview(
                    topicId = prefs.getString(KEY_FLASHCARD_TOPIC, "").orEmpty(),
                    type = SessionType.FLASHCARD,
                    currentIndex = prefs.getInt(KEY_FLASHCARD_CURRENT_INDEX, 0),
                    totalQuestions = prefs.getInt(KEY_FLASHCARD_TOTAL, 0),
                    status = flashStatus
                )
            )
        }

        val quizStatus = parseStatus(
            prefs.getString(KEY_QUIZ_STATUS, SessionStatus.NOT_STARTED.name)
        )
        if (quizStatus == SessionStatus.IN_PROGRESS) {
            sessions.add(
                SessionOverview(
                    topicId = prefs.getString(KEY_QUIZ_TOPIC, "").orEmpty(),
                    type = SessionType.QUIZ,
                    currentIndex = prefs.getInt(KEY_QUIZ_CURRENT_INDEX, 0),
                    totalQuestions = prefs.getInt(KEY_QUIZ_TOTAL_QUESTIONS, 0),
                    status = quizStatus
                )
            )
        }

        return sessions
    }

    fun shouldLogChatForSession(existingLoggedSessionIds: Set<String>, sessionId: String): Boolean {
        if (sessionId.isBlank()) return true
        return !existingLoggedSessionIds.contains(sessionId)
    }

    fun updatedLoggedSessionIds(existingLoggedSessionIds: Set<String>, sessionId: String): Set<String> {
        if (sessionId.isBlank()) return existingLoggedSessionIds
        return existingLoggedSessionIds + sessionId
    }

    private fun appendRecentActivity(context: Context, item: RecentActivityItem) {
        val prefs = prefs(context)
        val existing = readRawRecentActivities(prefs)
        val updated = listOf(item) + existing
        persistRecentActivities(prefs, updated.take(MAX_RECENT_ACTIVITY_ITEMS))
    }

    private fun updateStudyProgress(
        context: Context,
        topic: String,
        incrementQuizCount: Boolean,
        trackTopic: Boolean,
        timestamp: Long = System.currentTimeMillis()
    ) {
        val prefs = prefs(context)
        val currentDay = localDayNumber(timestamp)
        val previousDay = prefs.getLong(KEY_PROGRESS_LAST_ACTIVE_DAY, UNSET_DAY)
            .takeIf { it != UNSET_DAY }

        val currentStreak = prefs.getInt(KEY_PROGRESS_DAY_STREAK, 0)
        val nextStreak = nextStreakValue(currentStreak, previousDay, currentDay)

        val edit = prefs.edit()
            .putInt(KEY_PROGRESS_DAY_STREAK, nextStreak)
            .putLong(KEY_PROGRESS_LAST_ACTIVE_DAY, currentDay)

        val activeDays = decodeLongList(
            prefs.getString(KEY_PROGRESS_ACTIVE_DAYS_JSON, "[]").orEmpty()
        ).toMutableSet()
        activeDays.add(currentDay)
        val trimmedDays = activeDays
            .sortedDescending()
            .take(MAX_ACTIVE_DAYS)
            .sorted()
        edit.putString(KEY_PROGRESS_ACTIVE_DAYS_JSON, encodeLongList(trimmedDays))

        if (incrementQuizCount) {
            val totalQuizzes = prefs.getInt(KEY_PROGRESS_TOTAL_QUIZZES, 0)
            edit.putInt(KEY_PROGRESS_TOTAL_QUIZZES, totalQuizzes + 1)
        }

        if (trackTopic) {
            val existingTopics = decodeStringList(
                prefs.getString(KEY_PROGRESS_TOPICS_JSON, "[]").orEmpty()
            ).toMutableSet()

            normalizedTopicForProgress(topic)?.let { existingTopics.add(it) }
            edit.putString(KEY_PROGRESS_TOPICS_JSON, encodeStringList(existingTopics.toList()))
        }

        edit.apply()
    }

    private fun appendQuizAttempt(context: Context, attempt: QuizAttemptRecord) {
        val prefs = prefs(context)
        val existing = decodeQuizAttemptList(
            prefs.getString(KEY_PROGRESS_QUIZ_ATTEMPTS_JSON, "[]").orEmpty()
        )
        val updated = (listOf(attempt) + existing)
            .sortedByDescending { it.timestamp }
            .take(MAX_QUIZ_ATTEMPTS)

        prefs.edit()
            .putString(KEY_PROGRESS_QUIZ_ATTEMPTS_JSON, encodeQuizAttemptList(updated))
            .apply()
    }

    private fun readQuizAttempts(context: Context): List<QuizAttemptRecord> {
        val prefs = prefs(context)
        return decodeQuizAttemptList(
            prefs.getString(KEY_PROGRESS_QUIZ_ATTEMPTS_JSON, "[]").orEmpty()
        )
    }

    private fun updateTopicMastery(
        context: Context,
        topic: String,
        type: RecentActivityType,
        timestamp: Long,
        source: String = SOURCE_TOPIC,
        noteName: String = ""
    ) {
        val normalizedKey = normalizedTopicForProgress(topic) ?: return
        val prefs = prefs(context)
        val entries = decodeTopicMasteryList(
            prefs.getString(KEY_PROGRESS_TOPIC_MASTERY_JSON, "[]").orEmpty()
        ).toMutableList()

        val index = entries.indexOfFirst { it.topicKey == normalizedKey }
        val previous = if (index >= 0) entries[index] else null
        val safeSource = source.takeIf { it == SOURCE_TOPIC || it == SOURCE_NOTES }
            ?: previous?.lastSource
            ?: SOURCE_TOPIC
        val safeNoteName = noteName.trim().ifBlank {
            if (safeSource == SOURCE_NOTES) previous?.lastNoteName.orEmpty() else ""
        }

        val updated = TopicMasteryRecord(
            topicKey = normalizedKey,
            displayTopic = topic.trim().ifBlank { normalizedKey },
            quizCount = (previous?.quizCount ?: 0) + if (type == RecentActivityType.QUIZ) 1 else 0,
            flashcardCount = (previous?.flashcardCount ?: 0) + if (type == RecentActivityType.FLASHCARD) 1 else 0,
            chatCount = (previous?.chatCount ?: 0) + if (type == RecentActivityType.CHAT) 1 else 0,
            lastActivityTimestamp = timestamp,
            lastSource = safeSource,
            lastNoteName = if (safeSource == SOURCE_NOTES) safeNoteName else ""
        )

        if (index >= 0) {
            entries[index] = updated
        } else {
            entries.add(updated)
        }

        prefs.edit()
            .putString(KEY_PROGRESS_TOPIC_MASTERY_JSON, encodeTopicMasteryList(entries))
            .apply()
    }

    private fun localDayNumber(timestamp: Long): Long {
        val offsetMillis = TimeZone.getDefault().getOffset(timestamp).toLong()
        return (timestamp + offsetMillis) / DAY_IN_MILLIS
    }

    private fun readRawRecentActivities(prefs: android.content.SharedPreferences): List<RecentActivityItem> {
        val raw = prefs.getString(KEY_RECENT_ACTIVITY_JSON, "[]").orEmpty()
        val decoded = decodeRecentActivityList(raw)
        return decoded.mapIndexed { index, item ->
            if (item.id.isNotBlank()) {
                item
            } else {
                item.copy(id = buildFallbackRecentId(item, index))
            }
        }
    }

    private fun buildFallbackRecentId(item: RecentActivityItem, index: Int): String {
        return "${item.type.name}_${item.timestamp}_${item.topic.hashCode()}_$index"
    }

    private fun persistRecentActivities(
        prefs: android.content.SharedPreferences,
        values: List<RecentActivityItem>
    ) {
        prefs.edit()
            .putString(KEY_RECENT_ACTIVITY_JSON, encodeRecentActivityList(values))
            .apply()
    }

    private fun encodeIntList(values: List<Int>): String {
        return try {
            json.encodeToString(values)
        } catch (_: Exception) {
            "[]"
        }
    }

    private fun encodeRecentActivityList(values: List<RecentActivityItem>): String {
        return try {
            json.encodeToString(values)
        } catch (_: Exception) {
            "[]"
        }
    }

    private fun encodeBooleanList(values: List<Boolean>): String {
        return try {
            json.encodeToString(values)
        } catch (_: Exception) {
            "[]"
        }
    }

    private fun encodeStringList(values: List<String>): String {
        return try {
            json.encodeToString(values)
        } catch (_: Exception) {
            "[]"
        }
    }

    private fun encodeLongList(values: List<Long>): String {
        return try {
            json.encodeToString(values)
        } catch (_: Exception) {
            "[]"
        }
    }

    private fun encodeQuizAttemptList(values: List<QuizAttemptRecord>): String {
        return try {
            json.encodeToString(values)
        } catch (_: Exception) {
            "[]"
        }
    }

    private fun encodeTopicMasteryList(values: List<TopicMasteryRecord>): String {
        return try {
            json.encodeToString(values)
        } catch (_: Exception) {
            "[]"
        }
    }

    private fun decodeIntList(raw: String): List<Int> {
        return try {
            json.decodeFromString(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun decodeBooleanList(raw: String): List<Boolean> {
        return try {
            json.decodeFromString(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun decodeStringList(raw: String): List<String> {
        return try {
            json.decodeFromString(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun decodeLongList(raw: String): List<Long> {
        return try {
            json.decodeFromString(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun decodeQuizAttemptList(raw: String): List<QuizAttemptRecord> {
        return try {
            json.decodeFromString(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun decodeTopicMasteryList(raw: String): List<TopicMasteryRecord> {
        return try {
            json.decodeFromString(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun decodeRecentActivityList(raw: String): List<RecentActivityItem> {
        return try {
            json.decodeFromString(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseStatus(raw: String?): SessionStatus {
        return try {
            SessionStatus.valueOf(raw.orEmpty())
        } catch (_: Exception) {
            SessionStatus.NOT_STARTED
        }
    }
}

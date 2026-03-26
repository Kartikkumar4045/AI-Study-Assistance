package com.kartik.aistudyassistant.ui.home

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.kartik.aistudyassistant.R
import com.kartik.aistudyassistant.data.local.ContinueLearningPrefs
import com.kartik.aistudyassistant.data.local.TopicMasteryRecord
import com.kartik.aistudyassistant.ui.flashcard.FlashcardSetupActivity
import com.kartik.aistudyassistant.ui.quiz.QuizSetupActivity

class TopicsMasteryActivity : AppCompatActivity() {

    private lateinit var tvTopicMasteryCount: TextView
    private lateinit var tvTopicsEmpty: TextView
    private lateinit var llTopicMastery: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_topics_mastery)

        tvTopicMasteryCount = findViewById(R.id.tvTopicMasteryCount)
        tvTopicsEmpty = findViewById(R.id.tvTopicsEmpty)
        llTopicMastery = findViewById(R.id.llTopicMastery)

        findViewById<ImageView>(R.id.ivTopicsBack).setOnClickListener { finish() }

        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val topics = ContinueLearningPrefs.readTopicMastery(this)
        tvTopicMasteryCount.text = if (topics.size == 1) {
            "1 unique topic"
        } else {
            "${topics.size} unique topics"
        }

        llTopicMastery.removeAllViews()
        if (topics.isEmpty()) {
            tvTopicsEmpty.visibility = View.VISIBLE
            return
        }

        tvTopicsEmpty.visibility = View.GONE
        topics.forEach { item ->
            llTopicMastery.addView(createTopicItem(item))
        }
    }

    private fun createTopicItem(item: TopicMasteryRecord): View {
        val view = LayoutInflater.from(this).inflate(
            R.layout.item_topic_mastery,
            llTopicMastery,
            false
        )

        val safeTopic = item.displayTopic.trim().ifBlank { item.topicKey }
        val isNoteBased = item.lastSource == ContinueLearningPrefs.SOURCE_NOTES && item.lastNoteName.isNotBlank()
        val progress = calculateMasteryProgress(item)
        val level = masteryLevel(progress)

        view.findViewById<TextView>(R.id.tvMasteryTopicTitle).text = safeTopic
        view.findViewById<TextView>(R.id.tvMasteryTopicLevel).apply {
            text = level
            setTextColor(ContextCompat.getColor(this@TopicsMasteryActivity, levelColorRes(progress)))
        }
        view.findViewById<TextView>(R.id.tvMasteryQuizCount).text = item.quizCount.toString()
        view.findViewById<TextView>(R.id.tvMasteryFlashcardCount).text = item.flashcardCount.toString()
        view.findViewById<TextView>(R.id.tvMasteryChatCount).text = item.chatCount.toString()
        view.findViewById<LinearProgressIndicator>(R.id.progressMasteryLevel).apply {
            setProgressCompat(progress, true)
            setIndicatorColor(ContextCompat.getColor(this@TopicsMasteryActivity, levelColorRes(progress)))
            contentDescription = getString(R.string.topic_mastery_progress_a11y, progress)
        }
        view.findViewById<TextView>(R.id.tvMasteryProgressLabel).text =
            getString(R.string.topic_mastery_progress_label, progress)
        view.findViewById<TextView>(R.id.tvMasteryLastActive).text =
            if (item.lastActivityTimestamp > 0L) {
                "Last active: " + DateUtils.getRelativeTimeSpanString(
                    item.lastActivityTimestamp,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE
                )
            } else {
                "Last active: Never"
            }

        view.findViewById<MaterialButton>(R.id.btnMasteryPracticeQuiz).setOnClickListener {
            openQuizFromMastery(item, safeTopic, isNoteBased)
        }

        view.findViewById<MaterialButton>(R.id.btnMasteryOpenFlashcards).setOnClickListener {
            openFlashcardsFromMastery(item, safeTopic, isNoteBased)
        }

        return view
    }

    private fun openQuizFromMastery(item: TopicMasteryRecord, safeTopic: String, isNoteBased: Boolean) {
        startActivity(
            Intent(this, QuizSetupActivity::class.java).apply {
                if (isNoteBased) {
                    putExtra(QuizSetupActivity.EXTRA_PREFILL_SOURCE, ContinueLearningPrefs.SOURCE_NOTES)
                    putExtra(QuizSetupActivity.EXTRA_PREFILL_NOTE_NAME, item.lastNoteName)
                    putExtra(QuizSetupActivity.EXTRA_PREFILL_TOPIC, safeTopic)
                } else {
                    putExtra(QuizSetupActivity.EXTRA_PREFILL_SOURCE, ContinueLearningPrefs.SOURCE_TOPIC)
                    putExtra(QuizSetupActivity.EXTRA_PREFILL_TOPIC, safeTopic)
                }
            }
        )
    }

    private fun openFlashcardsFromMastery(item: TopicMasteryRecord, safeTopic: String, isNoteBased: Boolean) {
        startActivity(
            Intent(this, FlashcardSetupActivity::class.java).apply {
                if (isNoteBased) {
                    putExtra(FlashcardSetupActivity.EXTRA_SOURCE, ContinueLearningPrefs.SOURCE_NOTES)
                    putExtra(FlashcardSetupActivity.EXTRA_PREFILL_NOTE_NAME, item.lastNoteName)
                    putExtra(FlashcardSetupActivity.EXTRA_TOPIC_TEXT, safeTopic)
                } else {
                    putExtra(FlashcardSetupActivity.EXTRA_SOURCE, ContinueLearningPrefs.SOURCE_TOPIC)
                    putExtra(FlashcardSetupActivity.EXTRA_TOPIC_TEXT, safeTopic)
                }
            }
        )
    }

    private fun calculateMasteryProgress(item: TopicMasteryRecord): Int {
        val quizContribution = (item.quizCount * 12).coerceAtMost(50)
        val flashcardContribution = (item.flashcardCount * 9).coerceAtMost(35)
        val chatContribution = (item.chatCount * 2).coerceAtMost(10)

        val totalSessions = item.quizCount + item.flashcardCount + item.chatCount
        val engagementBonus = (totalSessions / 3).coerceAtMost(5)
        val mixedPracticeBonus = if (
            item.quizCount > 0 &&
            item.flashcardCount > 0 &&
            item.chatCount > 0
        ) 5 else 0

        return (quizContribution + flashcardContribution + chatContribution + engagementBonus + mixedPracticeBonus)
            .coerceIn(0, 100)
    }

    private fun masteryLevel(progress: Int): String {
        return when {
            progress >= 80 -> getString(R.string.topic_mastery_level_expert)
            progress >= 55 -> getString(R.string.topic_mastery_level_advanced)
            progress >= 25 -> getString(R.string.topic_mastery_level_intermediate)
            else -> getString(R.string.topic_mastery_level_beginner)
        }
    }

    private fun levelColorRes(progress: Int): Int {
        return when {
            progress >= 80 -> R.color.accent_green
            progress >= 55 -> R.color.primary
            progress >= 25 -> R.color.accent_orange
            else -> R.color.text_hint
        }
    }
}

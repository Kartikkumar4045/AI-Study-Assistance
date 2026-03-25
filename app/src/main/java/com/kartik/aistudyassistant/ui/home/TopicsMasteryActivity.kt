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
import com.google.android.material.button.MaterialButton
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
        tvTopicMasteryCount.text = "${topics.size} unique topics"

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

        view.findViewById<TextView>(R.id.tvMasteryTopicTitle).text = safeTopic
        view.findViewById<TextView>(R.id.tvMasteryTopicStats).text =
            "Quiz ${item.quizCount} • Flashcards ${item.flashcardCount} • Chat ${item.chatCount}"
        view.findViewById<TextView>(R.id.tvMasteryTopicLast).text =
            "Last active: " + DateUtils.getRelativeTimeSpanString(
                item.lastActivityTimestamp,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            )

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
}

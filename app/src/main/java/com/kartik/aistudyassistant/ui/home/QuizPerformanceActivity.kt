package com.kartik.aistudyassistant.ui.home

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.kartik.aistudyassistant.R
import com.kartik.aistudyassistant.data.local.ContinueLearningPrefs
import com.kartik.aistudyassistant.data.local.QuizAttemptRecord
import com.kartik.aistudyassistant.ui.quiz.QuizSetupActivity

class QuizPerformanceActivity : AppCompatActivity() {

    private lateinit var tvPerfTotal: TextView
    private lateinit var tvPerfAverage: TextView
    private lateinit var tvPerfBest: TextView
    private lateinit var tvPerfEmpty: TextView
    private lateinit var llQuizAttempts: LinearLayout
    private lateinit var spinnerTimeRange: Spinner
    private lateinit var spinnerTopicFilter: Spinner

    private val timeRanges = listOf(
        "7 days" to 7,
        "30 days" to 30,
        "All time" to null
    )

    private var selectedDaysFilter: Int? = null
    private var selectedTopicFilter: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_quiz_performance)

        tvPerfTotal = findViewById(R.id.tvPerfTotal)
        tvPerfAverage = findViewById(R.id.tvPerfAverage)
        tvPerfBest = findViewById(R.id.tvPerfBest)
        tvPerfEmpty = findViewById(R.id.tvPerfEmpty)
        llQuizAttempts = findViewById(R.id.llQuizAttempts)
        spinnerTimeRange = findViewById(R.id.spinnerTimeRange)
        spinnerTopicFilter = findViewById(R.id.spinnerTopicFilter)

        findViewById<ImageView>(R.id.ivQuizPerformanceBack).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnTakeAnotherQuiz).setOnClickListener {
            startActivity(Intent(this, QuizSetupActivity::class.java))
        }

        setupFilters()
    }

    private fun setupFilters() {
        val timeAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            timeRanges.map { it.first }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerTimeRange.adapter = timeAdapter
        spinnerTimeRange.setSelection(2)

        spinnerTimeRange.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedDaysFilter = timeRanges[position].second
                render()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        refreshTopicFilter()
    }

    private fun refreshTopicFilter() {
        val allTopicsLabel = ContinueLearningPrefs.allTopicsFilterLabel()
        val allTopics = ContinueLearningPrefs.readQuizPerformance(
            context = this,
            daysFilter = null,
            topicFilter = null
        ).availableTopics

        val options = listOf(allTopicsLabel) + allTopics
        val topicAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            options
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        spinnerTopicFilter.adapter = topicAdapter
        spinnerTopicFilter.setSelection(0)
        spinnerTopicFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedTopicFilter = options[position]
                render()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun render() {
        val snapshot = ContinueLearningPrefs.readQuizPerformance(
            context = this,
            daysFilter = selectedDaysFilter,
            topicFilter = selectedTopicFilter
        )

        tvPerfTotal.text = "Total Quizzes: ${snapshot.totalQuizzes}"
        tvPerfAverage.text = "Average Score: ${snapshot.averageScore}"
        tvPerfBest.text = "Best Score: ${snapshot.bestScore}"

        llQuizAttempts.removeAllViews()
        if (snapshot.recentAttempts.isEmpty()) {
            tvPerfEmpty.visibility = View.VISIBLE
            return
        }

        tvPerfEmpty.visibility = View.GONE
        snapshot.recentAttempts.forEach { attempt ->
            llQuizAttempts.addView(createAttemptItem(attempt))
        }
    }

    private fun createAttemptItem(attempt: QuizAttemptRecord): View {
        val item = LayoutInflater.from(this).inflate(
            R.layout.item_quiz_attempt,
            llQuizAttempts,
            false
        )

        item.findViewById<TextView>(R.id.tvAttemptTopic).text = attempt.topic
        item.findViewById<TextView>(R.id.tvAttemptScore).text = "Score: ${attempt.score}"
        item.findViewById<TextView>(R.id.tvAttemptTime).text = DateUtils.getRelativeTimeSpanString(
            attempt.timestamp,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        )

        return item
    }
}


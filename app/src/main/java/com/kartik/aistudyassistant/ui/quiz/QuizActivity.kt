package com.kartik.aistudyassistant.ui.quiz

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.kartik.aistudyassistant.data.local.ContinueLearningPrefs
import com.kartik.aistudyassistant.data.model.QuizQuestion
import com.kartik.aistudyassistant.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class QuizActivity : AppCompatActivity() {

    private lateinit var tvQuizTitle: TextView
    private lateinit var tvProgress: TextView
    private lateinit var tvQuestion: TextView
    private lateinit var radioGroup: RadioGroup
    private lateinit var rbOptionA: RadioButton
    private lateinit var rbOptionB: RadioButton
    private lateinit var rbOptionC: RadioButton
    private lateinit var rbOptionD: RadioButton
    private lateinit var btnPrevious: MaterialButton
    private lateinit var btnNext: MaterialButton
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var toolbar: MaterialToolbar

    private var currentQuestionIndex = 0
    private var quizSource = ""
    private var topicText = ""
    private var selectedNoteId = ""
    private var questionCount = 5
    private val userAnswers = mutableListOf<Int>() // Store selected option index (0-3)
    private var questionsJsonRaw: String = ""
    private var quizCompleted = false
    private var sessionStartElapsedRealtime = 0L

    private var quizQuestions = listOf<QuizQuestion>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_quiz)

        loadQuizState()
        if (quizQuestions.isEmpty()) {
            Toast.makeText(this, "Quiz session data is unavailable. Please start a new quiz.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initViews()
        setupQuiz()
        displayQuestion()
        persistQuizProgress()
        sessionStartElapsedRealtime = SystemClock.elapsedRealtime()
    }

    private fun loadQuizState() {
        val intentJson = intent.getStringExtra("quizDataJson").orEmpty()
        if (intentJson.isNotBlank()) {
            quizSource = intent.getStringExtra("quizSource") ?: "topic"
            topicText = intent.getStringExtra("topicText") ?: ""
            selectedNoteId = intent.getStringExtra("selectedNoteId") ?: ""

            quizQuestions = try {
                Json.decodeFromString(intentJson)
            } catch (_: Exception) {
                emptyList()
            }
            questionsJsonRaw = if (quizQuestions.isNotEmpty()) intentJson else ""
            questionCount = quizQuestions.size
            currentQuestionIndex = 0
            userAnswers.clear()
            repeat(questionCount) { userAnswers.add(-1) }
            return
        }

        val saved = ContinueLearningPrefs.readQuizProgress(this)
        if (!saved.inProgress || saved.questionsJson.isBlank()) {
            quizQuestions = emptyList()
            return
        }

        val restoredQuestions = try {
            Json.decodeFromString<List<QuizQuestion>>(saved.questionsJson)
        } catch (_: Exception) {
            emptyList()
        }
        if (restoredQuestions.isEmpty()) {
            quizQuestions = emptyList()
            return
        }

        quizQuestions = restoredQuestions
        questionsJsonRaw = saved.questionsJson
        quizSource = saved.source.ifBlank { "topic" }
        topicText = saved.topic
        selectedNoteId = saved.selectedNoteId
        questionCount = quizQuestions.size
        currentQuestionIndex = saved.currentIndex.coerceIn(0, questionCount - 1)

        userAnswers.clear()
        repeat(questionCount) { index ->
            userAnswers.add(saved.answers.getOrElse(index) { -1 })
        }
    }

    private fun initViews() {
        tvQuizTitle = findViewById(R.id.tvQuizTitle)
        tvProgress = findViewById(R.id.tvProgress)
        tvQuestion = findViewById(R.id.tvQuestion)
        radioGroup = findViewById(R.id.radioGroup)
        rbOptionA = findViewById(R.id.rbOptionA)
        rbOptionB = findViewById(R.id.rbOptionB)
        rbOptionC = findViewById(R.id.rbOptionC)
        rbOptionD = findViewById(R.id.rbOptionD)
        btnPrevious = findViewById(R.id.btnPrevious)
        btnNext = findViewById(R.id.btnNext)
        progressBar = findViewById(R.id.progressBar)
        toolbar = findViewById(R.id.toolbar)

        // Set quiz title
        val title = if (quizSource == "topic") "Quiz: $topicText" else "Quiz: $selectedNoteId"
        tvQuizTitle.text = title

        btnPrevious.setOnClickListener {
            if (currentQuestionIndex > 0) {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                saveCurrentAnswer()
                currentQuestionIndex--
                displayQuestion()
            }
        }

        btnNext.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            saveCurrentAnswer()
            if (currentQuestionIndex < quizQuestions.size - 1) {
                currentQuestionIndex++
                displayQuestion()
            } else {
                // Submit quiz
                submitQuiz()
            }
        }

        radioGroup.setOnCheckedChangeListener { group, checkedId ->
            if (checkedId == View.NO_ID) return@setOnCheckedChangeListener
            group.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val selectedView = group.findViewById<RadioButton>(checkedId)
            selectedView?.animate()?.scaleX(1.01f)?.scaleY(1.01f)?.setDuration(120L)
                ?.withEndAction {
                    selectedView.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
                }?.start()
        }

        toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupQuiz() {
        // Clear radio group selection
        radioGroup.clearCheck()
    }

    private fun displayQuestion() {
        if (quizQuestions.isEmpty() || currentQuestionIndex !in quizQuestions.indices) return
        val question = quizQuestions[currentQuestionIndex]

        tvProgress.text = "Question ${currentQuestionIndex + 1} / ${quizQuestions.size}"
        tvQuestion.text = question.question

        // Keep the session playable even if an upstream response has fewer than 4 options.
        val safeOptions = List(4) { index ->
            question.options.getOrNull(index)?.takeIf { it.isNotBlank() } ?: "Option ${index + 1}"
        }

        rbOptionA.text = "A. ${safeOptions[0]}"
        rbOptionB.text = "B. ${safeOptions[1]}"
        rbOptionC.text = "C. ${safeOptions[2]}"
        rbOptionD.text = "D. ${safeOptions[3]}"

        // Restore previous answer
        val selectedOption = userAnswers[currentQuestionIndex]
        when (selectedOption) {
            0 -> rbOptionA.isChecked = true
            1 -> rbOptionB.isChecked = true
            2 -> rbOptionC.isChecked = true
            3 -> rbOptionD.isChecked = true
            else -> radioGroup.clearCheck()
        }

        // Update button text
        btnNext.text = if (currentQuestionIndex == quizQuestions.size - 1) "Submit Quiz" else "Next"

        // Enable/disable previous button
        btnPrevious.isEnabled = currentQuestionIndex > 0

        // Update progress bar
        val progress = ((currentQuestionIndex + 1) / quizQuestions.size.toFloat()) * 100
        progressBar.setProgressCompat(progress.toInt(), true)
        persistQuizProgress()
    }

    private fun saveCurrentAnswer() {
        val checkedId = radioGroup.checkedRadioButtonId
        val selectedIndex = when (checkedId) {
            R.id.rbOptionA -> 0
            R.id.rbOptionB -> 1
            R.id.rbOptionC -> 2
            R.id.rbOptionD -> 3
            else -> -1
        }
        userAnswers[currentQuestionIndex] = selectedIndex
        persistQuizProgress()
    }

    private fun persistQuizProgress() {
        if (quizCompleted || quizQuestions.isEmpty()) return
        val safeIndex = currentQuestionIndex.coerceIn(0, quizQuestions.lastIndex)
        val topic = topicText.ifBlank { selectedNoteId }
        ContinueLearningPrefs.saveQuizProgress(
            context = this,
            topic = topic,
            source = quizSource.ifBlank { "topic" },
            selectedNoteId = selectedNoteId,
            totalQuestions = quizQuestions.size,
            currentIndex = safeIndex,
            answers = userAnswers.toList(),
            questionsJson = questionsJsonRaw.ifBlank {
                try {
                    Json.encodeToString<List<QuizQuestion>>(quizQuestions)
                } catch (_: Exception) {
                    ""
                }
            },
            inProgress = true
        )
    }

    private fun submitQuiz() {
        // Calculate score
        var correctAnswers = 0
        for (i in quizQuestions.indices) {
            if (userAnswers[i] == quizQuestions[i].correctAnswer) {
                correctAnswers++
            }
        }

        quizCompleted = true
        val summaryTopic = topicText.ifBlank { selectedNoteId }.ifBlank { "General Quiz" }
        ContinueLearningPrefs.saveQuizActivity(
            context = this,
            topic = summaryTopic,
            score = correctAnswers,
            totalQuestions = quizQuestions.size,
            source = if (quizSource == ContinueLearningPrefs.SOURCE_NOTES) ContinueLearningPrefs.SOURCE_NOTES else ContinueLearningPrefs.SOURCE_TOPIC,
            noteName = selectedNoteId
        )
        ContinueLearningPrefs.markQuizCompleted(this)

        // Navigate to result
        val intent = Intent(this, QuizResultActivity::class.java)
        intent.putExtra("score", correctAnswers)
        intent.putExtra("total", quizQuestions.size)
        intent.putExtra("quizSource", quizSource)
        intent.putExtra("topicText", topicText)
        intent.putExtra("selectedNoteId", selectedNoteId)
        intent.putExtra("questionCount", quizQuestions.size)
        // Pass questions and answers for review
        val questionsList = ArrayList<String>()
        val userAnswersList = ArrayList<Int>()
        val correctAnswersList = ArrayList<Int>()
        val explanationsList = ArrayList<String>()
        for (i in quizQuestions.indices) {
            questionsList.add(quizQuestions[i].question)
            userAnswersList.add(userAnswers[i])
            correctAnswersList.add(quizQuestions[i].correctAnswer)
            explanationsList.add(quizQuestions[i].explanation)
        }
        intent.putStringArrayListExtra("questions", questionsList)
        intent.putIntegerArrayListExtra("userAnswers", userAnswersList)
        intent.putIntegerArrayListExtra("correctAnswers", correctAnswersList)
        intent.putStringArrayListExtra("explanations", explanationsList)
        startActivity(intent)
        finish()
    }

    override fun onPause() {
        val now = SystemClock.elapsedRealtime()
        if (sessionStartElapsedRealtime > 0L) {
            val elapsed = (now - sessionStartElapsedRealtime).coerceAtLeast(0L)
            ContinueLearningPrefs.addStudyTime(this, elapsed)
            sessionStartElapsedRealtime = 0L
        }
        super.onPause()
        persistQuizProgress()
    }

    override fun onResume() {
        super.onResume()
        sessionStartElapsedRealtime = SystemClock.elapsedRealtime()
    }
}

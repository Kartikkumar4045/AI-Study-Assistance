package com.example.aistudyassistance.Activity

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.example.aistudyassistance.QuizQuestion
import com.example.aistudyassistance.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.serialization.decodeFromString
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

    private var currentQuestionIndex = 0
    private var quizSource = ""
    private var topicText = ""
    private var selectedNoteId = ""
    private var questionCount = 5
    private val userAnswers = mutableListOf<Int>() // Store selected option index (0-3)

    private var quizQuestions = listOf<QuizQuestion>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_quiz)

        // Get extras
        quizSource = intent.getStringExtra("quizSource") ?: "topic"
        topicText = intent.getStringExtra("topicText") ?: ""
        selectedNoteId = intent.getStringExtra("selectedNoteId") ?: ""
        questionCount = intent.getIntExtra("questionCount", 5)

        // Get quiz questions from intent
        val json = intent.getStringExtra("quizDataJson")
        quizQuestions = if (json != null) {
            Json.decodeFromString(json)
        } else {
            emptyList()
        }

        initViews()
        setupQuiz()
        displayQuestion()
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

        // Set quiz title
        val title = if (quizSource == "topic") "Quiz: $topicText" else "Quiz: $selectedNoteId"
        tvQuizTitle.text = title

        // Initialize user answers
        for (i in 0 until questionCount) {
            userAnswers.add(-1) // -1 means not answered
        }

        btnPrevious.setOnClickListener {
            if (currentQuestionIndex > 0) {
                saveCurrentAnswer()
                currentQuestionIndex--
                displayQuestion()
            }
        }

        btnNext.setOnClickListener {
            saveCurrentAnswer()
            if (currentQuestionIndex < quizQuestions.size - 1) {
                currentQuestionIndex++
                displayQuestion()
            } else {
                // Submit quiz
                submitQuiz()
            }
        }

        findViewById<ImageView>(R.id.ivBack).setOnClickListener {
            finish()
        }
    }

    private fun setupQuiz() {
        // Clear radio group selection
        radioGroup.clearCheck()
    }

    private fun displayQuestion() {
        val question = quizQuestions[currentQuestionIndex]

        tvProgress.text = "Question ${currentQuestionIndex + 1} / ${quizQuestions.size}"
        tvQuestion.text = question.question

        rbOptionA.text = "A. ${question.options[0]}"
        rbOptionB.text = "B. ${question.options[1]}"
        rbOptionC.text = "C. ${question.options[2]}"
        rbOptionD.text = "D. ${question.options[3]}"

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
    }

    private fun submitQuiz() {
        // Calculate score
        var correctAnswers = 0
        for (i in 0 until questionCount) {
            if (userAnswers[i] == quizQuestions[i].correctAnswer) {
                correctAnswers++
            }
        }

        // Navigate to result
        val intent = Intent(this, QuizResultActivity::class.java)
        intent.putExtra("score", correctAnswers)
        intent.putExtra("total", questionCount)
        intent.putExtra("quizSource", quizSource)
        intent.putExtra("topicText", topicText)
        intent.putExtra("selectedNoteId", selectedNoteId)
        intent.putExtra("questionCount", questionCount)
        // Pass questions and answers for review
        val questionsList = ArrayList<String>()
        val userAnswersList = ArrayList<Int>()
        val correctAnswersList = ArrayList<Int>()
        val explanationsList = ArrayList<String>()
        for (i in 0 until questionCount) {
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
}

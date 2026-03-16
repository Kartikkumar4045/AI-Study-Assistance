package com.example.aistudyassistance.Activity

import android.app.ProgressDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.example.aistudyassistance.*
import com.example.aistudyassistance.R
import com.google.android.material.button.MaterialButton
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class QuizSetupActivity : AppCompatActivity() {

    private lateinit var cardTopicQuiz: CardView
    private lateinit var cardMyNotes: CardView
    private lateinit var etTopic: EditText
    private lateinit var spinnerNotes: Spinner
    private lateinit var seekBarQuestions: SeekBar
    private lateinit var tvQuestionCount: TextView
    private lateinit var btnGenerateQuiz: MaterialButton
    private lateinit var tvSelectedNotePreview: TextView
    private lateinit var progressDialog: ProgressDialog
    private lateinit var quizGenerator: QuizGenerator

    private var selectedSource = "topic" // "topic" or "notes"
    private var questionCount = 5

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_quiz_setup)

        // Initialize Gemini Helper
        val apiKey = com.example.aistudyassistance.BuildConfig.apiKeySafe
        val geminiHelper = GeminiHelper(apiKey, "gemini-2.5-flash")
        quizGenerator = QuizGenerator(geminiHelper)

        // Initialize progress dialog
        progressDialog = ProgressDialog(this).apply {
            setMessage("Generating Quiz...")
            setCancelable(false)
        }

        initViews()
        setupListeners()
        updateUI()
    }

    private fun initViews() {
        cardTopicQuiz = findViewById(R.id.cardTopicQuiz)
        cardMyNotes = findViewById(R.id.cardMyNotes)
        etTopic = findViewById(R.id.etTopic)
        spinnerNotes = findViewById(R.id.spinnerNotes)
        seekBarQuestions = findViewById(R.id.seekBarQuestions)
        tvQuestionCount = findViewById(R.id.tvQuestionCount)
        btnGenerateQuiz = findViewById(R.id.btnGenerateQuiz)
        tvSelectedNotePreview = findViewById(R.id.tvSelectedNotePreview)

        // Setup spinner with placeholder notes
        val notes = arrayOf("Note 1: Data Structures", "Note 2: Algorithms", "Note 3: Operating Systems", "Note 4: Database Systems")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, notes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerNotes.adapter = adapter

        // Setup seekbar
        seekBarQuestions.max = 9
        seekBarQuestions.progress = 4
        tvQuestionCount.text = "Questions: 5"
    }

    private fun setupListeners() {
        cardTopicQuiz.setOnClickListener {
            selectedSource = "topic"
            updateUI()
        }

        cardMyNotes.setOnClickListener {
            selectedSource = "notes"
            updateUI()
        }

        seekBarQuestions.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                questionCount = progress + 1
                tvQuestionCount.text = "Questions: ${progress + 1}"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}

        })

        btnGenerateQuiz.setOnClickListener {
            generateQuiz()
        }

        findViewById<ImageView>(R.id.ivBack).setOnClickListener {
            finish()
        }

        etTopic.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                validateInputs()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        spinnerNotes.setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                val selectedNote = parent.getItemAtPosition(position).toString()
                tvSelectedNotePreview.text = "Selected Note: $selectedNote"
                validateInputs()
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                tvSelectedNotePreview.text = ""
                validateInputs()
            }
        })
    }

    private fun updateUI() {
        if (selectedSource == "topic") {
            cardTopicQuiz.setCardBackgroundColor(getColor(R.color.primary))
            cardMyNotes.setCardBackgroundColor(getColor(R.color.white))
            etTopic.visibility = View.VISIBLE
            spinnerNotes.visibility = View.GONE
            tvSelectedNotePreview.visibility = View.GONE
        } else {
            cardTopicQuiz.setCardBackgroundColor(getColor(R.color.white))
            cardMyNotes.setCardBackgroundColor(getColor(R.color.primary))
            etTopic.visibility = View.GONE
            spinnerNotes.visibility = View.VISIBLE
            tvSelectedNotePreview.visibility = View.VISIBLE
        }
        validateInputs()
    }

    private fun validateInputs() {
        val isValid = if (selectedSource == "topic") {
            etTopic.text.toString().trim().isNotEmpty()
        } else {
            spinnerNotes.selectedItem != null
        }
        btnGenerateQuiz.isEnabled = isValid
        btnGenerateQuiz.alpha = if (isValid) 1.0f else 0.5f
    }

    private fun generateQuiz() {
        // Show progress dialog
        progressDialog.show()

        // Debug: Show question count
        Toast.makeText(this, "Generating $questionCount questions", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val quizData = if (selectedSource == "topic") {
                    // Generate quiz from topic
                    val topic = etTopic.text.toString().trim()
                    quizGenerator.generateQuizFromTopic(topic, questionCount)
                } else {
                    // Generate quiz from notes
                    val selectedNote = spinnerNotes.selectedItem.toString()
                    val noteText = getNoteText(selectedNote) // Placeholder for now
                    quizGenerator.generateQuizFromNotes(noteText, questionCount)
                }

                withContext(Dispatchers.Main) {
                    // Hide progress dialog
                    progressDialog.dismiss()

                    // Start QuizActivity with the generated quiz data
                    val intent = Intent(this@QuizSetupActivity, QuizActivity::class.java)
                    val json = Json.encodeToString(quizData)
                    intent.putExtra("quizDataJson", json)
                    intent.putExtra("questionCount", quizData.size)
                    startActivity(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    // Hide progress dialog
                    progressDialog.dismiss()
                    Toast.makeText(this@QuizSetupActivity, "Error generating quiz: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun getNoteText(selectedNote: String): String {
        // Placeholder function to get note text for the selected note
        // In the future, this could fetch the note content from a database or file
        return "Sample content for $selectedNote"
    }
}

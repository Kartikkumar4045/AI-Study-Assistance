package com.example.aistudyassistance.Activity

import android.app.ProgressDialog
import android.content.Intent

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.example.aistudyassistance.*
import com.example.aistudyassistance.R
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

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
    private lateinit var storage: FirebaseStorage

    private var selectedSource = "topic" // "topic" or "notes"
    private var questionCount = 5
    private var notesList = mutableListOf<String>()

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
        PDFBoxResourceLoader.init(applicationContext)
        storage = FirebaseStorage.getInstance()
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
            loadNotes()
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

    private fun loadNotes() {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            Toast.makeText(this, "Please login to access notes", Toast.LENGTH_SHORT).show()
            return
        }
        val userId = user.uid
        val storageRef = storage.reference.child("user_uploads/$userId")
        storageRef.listAll()
            .addOnSuccessListener { listResult ->
                notesList.clear()
                for (item in listResult.items) {
                    notesList.add(item.name)
                }
                if (notesList.isEmpty()) {
                    notesList.add("No notes uploaded")
                }
                val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, notesList)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinnerNotes.adapter = adapter
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to load notes: ${e.message}", Toast.LENGTH_SHORT).show()
            }
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
                    val noteText = downloadAndExtractText(selectedNote)
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

    private suspend fun downloadAndExtractText(selectedNote: String): String {
        val channel = Channel<String>()
        downloadAndExtractTextCallback(selectedNote) { text ->
            channel.trySend(text)
        }
        return channel.receive()
    }

    private fun downloadAndExtractTextCallback(selectedNote: String, callback: (String) -> Unit) {
        if (selectedNote == "No notes uploaded") {
            callback("")
            return
        }

        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            callback("")
            return
        }

        val userId = user.uid
        val storageRef = storage.reference.child("user_uploads/$userId/$selectedNote")

        val localFile = File.createTempFile("temp", selectedNote.substringAfterLast('.'))

        storageRef.getFile(localFile)
            .addOnSuccessListener {
                if (selectedNote.endsWith(".pdf", ignoreCase = true)) {
                    val text = extractTextFromPDF(localFile)
                    localFile.delete()
                    callback(text)
                } else if (selectedNote.endsWith(".jpg", ignoreCase = true) || selectedNote.endsWith(".jpeg", ignoreCase = true) || selectedNote.endsWith(".png", ignoreCase = true)) {
                    extractTextFromImage(localFile) { text ->
                        localFile.delete()
                        callback(text)
                    }
                } else {
                    localFile.delete()
                    callback("Unsupported file type. Only PDF and image files (JPG, PNG) are supported.")
                }
            }
            .addOnFailureListener { e ->
                callback("Failed to download file: ${e.message}")
            }
    }

    private fun extractTextFromPDF(file: File): String {
        val document = PDDocument.load(file)
        return try {
            val pdfStripper = PDFTextStripper()
            pdfStripper.getText(document)
        } catch (e: Exception) {
            "Error extracting text from PDF: ${e.message}"
        } finally {
            document.close()
        }
    }

    private fun extractTextFromImage(file: File, callback: (String) -> Unit) {
        try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap == null) {
                callback("Failed to decode image")
                return
            }
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    callback(visionText.text)
                }
                .addOnFailureListener { e ->
                    callback("Error recognizing text: ${e.message}")
                }
        } catch (e: Exception) {
            callback("Error processing image: ${e.message}")
        }
    }
}

package com.kartik.aistudyassistant.ui.quiz

import android.app.ProgressDialog
import android.content.Intent

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import com.kartik.aistudyassistant.BuildConfig
import com.kartik.aistudyassistant.R
import com.kartik.aistudyassistant.core.utils.GeminiHelper
import com.kartik.aistudyassistant.domain.quiz.QuizGenerator
import com.kartik.aistudyassistant.ui.upload.UploadActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.Timestamp
import com.google.firebase.storage.FirebaseStorage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.net.URL
import java.util.Date
import kotlin.coroutines.resume

class QuizSetupActivity : AppCompatActivity() {

    private lateinit var cardTopicQuiz: CardView
    private lateinit var cardMyNotes: CardView
    private lateinit var etTopic: EditText
    private lateinit var spinnerNotes: Spinner
    private lateinit var sliderQuestions: Slider
    private lateinit var tvQuestionCount: TextView
    private lateinit var btnGenerateQuiz: MaterialButton
    private lateinit var tvSelectedNotePreview: TextView
    private lateinit var progressDialog: ProgressDialog
    private lateinit var quizGenerator: QuizGenerator
    private lateinit var storage: FirebaseStorage
    private val firestore = FirebaseFirestore.getInstance()

    private var selectedSource = "topic" // "topic" or "notes"
    private var questionCount = 5
    private var notesList = mutableListOf<NoteItem>()
    private var selectedNote: NoteItem? = null
    private var isGenerating = false
    private var prefillNoteName = ""
    private var prefillTopic = ""
    private var hasHandledMissingPrefillNote = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_quiz_setup)

        // Initialize Gemini Helper
        val apiKey = BuildConfig.apiKeySafe
        val geminiHelper = GeminiHelper(apiKey, "gemini-2.5-flash")
        quizGenerator = QuizGenerator(geminiHelper)

        // Initialize progress dialog
        progressDialog = ProgressDialog(this).apply {
            setMessage("Generating Quiz...")
            setCancelable(false)
        }

        initViews()
        setupEmptyNotesSpinner()
        selectedSource = intent.getStringExtra(EXTRA_PREFILL_SOURCE)
            ?.takeIf { it == "topic" || it == "notes" }
            ?: selectedSource
        prefillNoteName = intent.getStringExtra(EXTRA_PREFILL_NOTE_NAME).orEmpty()
        prefillTopic = intent.getStringExtra(EXTRA_PREFILL_TOPIC).orEmpty().trim()
        if (selectedSource == "topic") {
            prefillTopic
                .takeIf { it.isNotBlank() }
                ?.let { etTopic.setText(it) }
        }
        PDFBoxResourceLoader.init(applicationContext)
        storage = FirebaseStorage.getInstance()
        setupListeners()
        if (selectedSource == "notes") {
            loadNotes()
        }
        updateUI()
    }

    private fun initViews() {
        cardTopicQuiz = findViewById(R.id.cardTopicQuiz)
        cardMyNotes = findViewById(R.id.cardMyNotes)
        etTopic = findViewById(R.id.etTopic)
        spinnerNotes = findViewById(R.id.spinnerNotes)
        sliderQuestions = findViewById(R.id.sliderQuestions)
        tvQuestionCount = findViewById(R.id.tvQuestionCount)
        btnGenerateQuiz = findViewById(R.id.btnGenerateQuiz)
        tvSelectedNotePreview = findViewById(R.id.tvSelectedNotePreview)

        // Slider range is defined in XML (1..15); keep default in sync.
        questionCount = sliderQuestions.value.toInt()
        tvQuestionCount.text = "Questions: $questionCount"

        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }
    }

    private fun setupEmptyNotesSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, listOf("Select Note"))
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerNotes.adapter = adapter
        selectedNote = null
        tvSelectedNotePreview.text = "Selected Note: -"
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

        sliderQuestions.addOnChangeListener { _, value, _ ->
            questionCount = value.toInt()
            tvQuestionCount.text = "Questions: $questionCount"
            validateInputs()
        }

        btnGenerateQuiz.setOnClickListener {
            generateQuiz()
        }


        etTopic.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                validateInputs()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        spinnerNotes.setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                selectedNote = if (position <= 0 || notesList.isEmpty()) {
                    null
                } else {
                    notesList[position - 1]
                }

                tvSelectedNotePreview.text = if (!selectedNote?.name.isNullOrBlank()) {
                    "Selected Note: ${selectedNote?.name}"
                } else {
                    "Selected Note: -"
                }
                validateInputs()
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                selectedNote = null
                tvSelectedNotePreview.text = "Selected Note: -"
                validateInputs()
            }
        })
    }

    private fun updateUI() {
        if (selectedSource == "topic") {
            cardTopicQuiz.setCardBackgroundColor(getColor(R.color.primary))
            cardMyNotes.setCardBackgroundColor(getColor(R.color.card_bg))
            etTopic.visibility = View.VISIBLE
            spinnerNotes.visibility = View.GONE
            tvSelectedNotePreview.visibility = View.GONE
        } else {
            cardTopicQuiz.setCardBackgroundColor(getColor(R.color.card_bg))
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

        firestore.collection("Notes").document(userId).collection("UserNotes")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { result ->
                notesList.clear()
                result.documents.forEach { doc ->
                    mapNoteItem(doc.data)?.let { notesList.add(it) }
                }
                if (notesList.isNotEmpty()) {
                    bindNotesToSpinner()
                } else {
                    loadNotesFromStorageFallback(userId)
                }
            }
            .addOnFailureListener {
                loadNotesFromStorageFallback(userId, "Could not load uploaded notes metadata")
            }
    }

    private fun loadNotesFromStorageFallback(userId: String, prefixMessage: String? = null) {
        storage.reference.child("user_uploads/$userId")
            .listAll()
            .addOnSuccessListener { listResult ->
                notesList.clear()
                listResult.items.forEach { item ->
                    val name = item.name
                    val type = if (name.endsWith(".pdf", ignoreCase = true)) "pdf" else "image"
                    notesList.add(NoteItem(name = name, type = type, url = "", timestamp = System.currentTimeMillis()))
                }
                bindNotesToSpinner()
                if (!prefixMessage.isNullOrBlank()) {
                    Toast.makeText(this, "$prefixMessage. Loaded from storage.", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to load notes: ${e.message}", Toast.LENGTH_SHORT).show()
                setupEmptyNotesSpinner()
                validateInputs()
            }
    }

    private fun bindNotesToSpinner() {
        val spinnerItems = mutableListOf("Select Note")
        spinnerItems.addAll(notesList.map { it.name })
        if (notesList.isEmpty()) {
            spinnerItems.add("No notes uploaded")
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, spinnerItems)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerNotes.adapter = adapter
        selectedNote = null
        tvSelectedNotePreview.text = "Selected Note: -"

        if (prefillNoteName.isNotBlank()) {
            val index = notesList.indexOfFirst { it.name.equals(prefillNoteName, ignoreCase = true) }
            if (index >= 0) {
                spinnerNotes.setSelection(index + 1)
            } else if (!hasHandledMissingPrefillNote) {
                hasHandledMissingPrefillNote = true
                showMissingPrefilledNoteDialog(prefillNoteName)
             }
         }
         validateInputs()
    }

    private fun showMissingPrefilledNoteDialog(requestedNoteName: String) {
        AlertDialog.Builder(this)
            .setTitle("File not available")
            .setMessage(
                "We could not find \"$requestedNoteName\" in your uploaded notes. " +
                    "It may have come from a device-only attachment.\n\n" +
                    "You can upload it now, or skip and continue with topic mode."
            )
            .setPositiveButton("Upload") { _, _ ->
                startActivity(Intent(this, UploadActivity::class.java))
            }
            .setNegativeButton("Skip") { _, _ ->
                selectedSource = "topic"
                val fallbackTopic = prefillTopic.ifBlank { requestedNoteName }
                if (etTopic.text.toString().trim().isBlank()) {
                    etTopic.setText(fallbackTopic)
                }
                updateUI()
            }
            .show()
    }

    private fun validateInputs() {
        val isValid = if (selectedSource == "topic") {
            etTopic.text.toString().trim().isNotEmpty()
        } else {
            selectedNote != null
        }
        btnGenerateQuiz.isEnabled = isValid && !isGenerating
        btnGenerateQuiz.alpha = if (btnGenerateQuiz.isEnabled) 1.0f else 0.5f
    }

    private fun generateQuiz() {
        if (isGenerating) return
        val topicInput = etTopic.text.toString().trim()
        val noteInput = selectedNote

        if (selectedSource == "topic" && topicInput.isBlank()) {
            Toast.makeText(this, "Please enter a topic", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedSource == "notes" && noteInput == null) {
            Toast.makeText(this, "Please select a note", Toast.LENGTH_SHORT).show()
            return
        }

        isGenerating = true
        validateInputs()
        progressDialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val quizData = if (selectedSource == "topic") {
                    quizGenerator.generateQuizFromTopic(topicInput, questionCount)
                } else {
                    val noteText = downloadAndExtractText(noteInput!!)
                    if (noteText.isBlank() || noteText.startsWith("Error:", ignoreCase = true)) {
                        throw Exception("Could not read selected note")
                    }
                    quizGenerator.generateQuizFromNotes(noteText, questionCount)
                }

                withContext(Dispatchers.Main) {
                    dismissProgressSafe()
                    isGenerating = false
                    validateInputs()

                    if (quizData.isEmpty()) {
                        Toast.makeText(
                            this@QuizSetupActivity,
                            "Could not generate quiz for this topic. Please try again.",
                            Toast.LENGTH_LONG
                        ).show()
                        return@withContext
                    }

                    if (quizData.size < questionCount) {
                        Toast.makeText(
                            this@QuizSetupActivity,
                            "Generated ${quizData.size}/$questionCount questions. Showing what we have.",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    val intent = Intent(this@QuizSetupActivity, QuizActivity::class.java)
                    val json = Json.encodeToString(quizData)
                    val topicValue = if (selectedSource == "topic") {
                        topicInput
                    } else {
                        prefillTopic.ifBlank { noteInput?.name.orEmpty() }
                    }
                    intent.putExtra("quizDataJson", json)
                    intent.putExtra("questionCount", questionCount)
                    intent.putExtra("quizSource", selectedSource)
                    intent.putExtra("topicText", topicValue)
                    intent.putExtra("selectedNoteId", noteInput?.name.orEmpty())
                    startActivity(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    dismissProgressSafe()
                    isGenerating = false
                    validateInputs()
                    Toast.makeText(this@QuizSetupActivity, "Error generating quiz: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun downloadAndExtractText(note: NoteItem): String {
        val noteUrl = note.url.trim()
        if (noteUrl.isNotBlank()) {
            return if (note.type.equals("pdf", ignoreCase = true) || note.name.endsWith(".pdf", ignoreCase = true)) {
                extractTextFromPdfUrl(noteUrl)
            } else {
                extractTextFromImageUrl(noteUrl)
            }
        }
        return downloadAndExtractTextFromStorage(note.name)
    }

    private suspend fun downloadAndExtractTextFromStorage(selectedNote: String): String = suspendCancellableCoroutine { continuation ->
        if (selectedNote.isBlank() || selectedNote == "No notes uploaded") {
            if (continuation.isActive) continuation.resume("")
            return@suspendCancellableCoroutine
        }

        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            if (continuation.isActive) continuation.resume("")
            return@suspendCancellableCoroutine
        }

        val userId = user.uid
        val storageRef = storage.reference.child("user_uploads/$userId/$selectedNote")

        val suffix = ".${selectedNote.substringAfterLast('.', "tmp")}"
        val localFile = File.createTempFile("quiz_", suffix)

        storageRef.getFile(localFile)
            .addOnSuccessListener {
                if (selectedNote.endsWith(".pdf", ignoreCase = true)) {
                    val text = extractTextFromPDF(localFile)
                    localFile.delete()
                    if (continuation.isActive) continuation.resume(text)
                } else if (selectedNote.endsWith(".jpg", ignoreCase = true) || selectedNote.endsWith(".jpeg", ignoreCase = true) || selectedNote.endsWith(".png", ignoreCase = true)) {
                    extractTextFromImage(localFile) { text ->
                        localFile.delete()
                        if (continuation.isActive) continuation.resume(text)
                    }
                } else {
                    localFile.delete()
                    if (continuation.isActive) {
                        continuation.resume("Error: Unsupported file type. Only PDF and image files (JPG, PNG) are supported.")
                    }
                }
            }
            .addOnFailureListener { e ->
                if (continuation.isActive) continuation.resume("Error: Failed to download file: ${e.message}")
            }
    }

    private suspend fun extractTextFromPdfUrl(url: String): String = withContext(Dispatchers.IO) {
        try {
            URL(url).openStream().use { input ->
                PDDocument.load(input).use { document ->
                    PDFTextStripper().getText(document)
                }
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private suspend fun extractTextFromImageUrl(url: String): String = withContext(Dispatchers.IO) {
        try {
            val bytes = URL(url).openStream().use { it.readBytes() }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bitmapDecodeOptions())
                ?: return@withContext "Error: Failed to decode image"
            extractTextFromImageBitmap(bitmap)
        } catch (e: OutOfMemoryError) {
            "Error: Image is too large to process"
        } catch (e: Exception) {
            "Error: ${e.message}"
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
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, bitmapDecodeOptions())
            if (bitmap == null) {
                callback("Failed to decode image")
                return
            }
            extractTextFromImageBitmap(bitmap, callback)
        } catch (e: OutOfMemoryError) {
            callback("Error processing image: image is too large")
        } catch (e: Exception) {
            callback("Error processing image: ${e.message}")
        }
    }

    private suspend fun extractTextFromImageBitmap(bitmap: android.graphics.Bitmap): String = suspendCancellableCoroutine { continuation ->
        extractTextFromImageBitmap(bitmap) {
            if (continuation.isActive) continuation.resume(it)
        }
    }

    private fun bitmapDecodeOptions(): BitmapFactory.Options {
        return BitmapFactory.Options().apply {
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }
    }

    private fun dismissProgressSafe() {
        if (this::progressDialog.isInitialized && progressDialog.isShowing && !isFinishing && !isDestroyed) {
            progressDialog.dismiss()
        }
    }

    override fun onDestroy() {
        dismissProgressSafe()
        super.onDestroy()
    }

    private fun extractTextFromImageBitmap(bitmap: android.graphics.Bitmap, callback: (String) -> Unit) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { visionText -> callback(visionText.text) }
            .addOnFailureListener { e -> callback("Error recognizing text: ${e.message}") }
    }

    private fun mapNoteItem(raw: Map<String, Any>?): NoteItem? {
        if (raw.isNullOrEmpty()) return null
        val name = (raw["name"] as? String)
            ?: (raw["fileName"] as? String)
            ?: return null
        val url = (raw["url"] as? String)
            ?: (raw["downloadUrl"] as? String)
            ?: (raw["fileUrl"] as? String)
            ?: ""
        val type = (raw["type"] as? String)
            ?: (raw["fileType"] as? String)
            ?: if (name.endsWith(".pdf", ignoreCase = true)) "pdf" else "image"
        val tsAny = raw["timestamp"]
        val timestamp = when (tsAny) {
            is Long -> tsAny
            is Int -> tsAny.toLong()
            is Timestamp -> tsAny.toDate().time
            is Date -> tsAny.time
            else -> System.currentTimeMillis()
        }
        return NoteItem(name = name, type = type, url = url, timestamp = timestamp)
    }

    data class NoteItem(
        val name: String,
        val type: String,
        val url: String,
        val timestamp: Long
    )

    companion object {
        const val EXTRA_PREFILL_TOPIC = "prefillTopic"
        const val EXTRA_PREFILL_SOURCE = "prefillSource"
        const val EXTRA_PREFILL_NOTE_NAME = "prefillNoteName"
    }
}


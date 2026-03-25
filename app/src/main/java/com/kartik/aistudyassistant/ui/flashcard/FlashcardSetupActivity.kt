package com.kartik.aistudyassistant.ui.flashcard

import android.app.ProgressDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.kartik.aistudyassistant.BuildConfig
import com.kartik.aistudyassistant.domain.flashcard.FlashcardGenerator
import com.kartik.aistudyassistant.core.utils.GeminiHelper
import com.kartik.aistudyassistant.R
import com.kartik.aistudyassistant.data.model.Flashcard
import com.kartik.aistudyassistant.ui.upload.UploadActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.coroutines.resume

class FlashcardSetupActivity : AppCompatActivity() {

    private lateinit var cardTopic: CardView
    private lateinit var cardMyNotes: CardView
    private lateinit var etTopic: EditText
    private lateinit var spinnerNotes: Spinner
    private lateinit var tvSelectedNotePreview: TextView
    private lateinit var sliderCards: Slider
    private lateinit var tvCardCount: TextView
    private lateinit var btnGenerateFlashcards: MaterialButton
    private lateinit var progressDialog: ProgressDialog
    private lateinit var flashcardGenerator: FlashcardGenerator
    private lateinit var storage: FirebaseStorage
    private lateinit var firestore: FirebaseFirestore

    private var selectedSource = SOURCE_TOPIC
    private var cardCount = 10
    private var selectedNoteName = ""
    private var selectedNote: NoteItem? = null
    private var isGenerating = false
    private var prefillNoteName = ""
    private var prefillTopicText = ""
    private var hasHandledMissingPrefillNote = false
    private val notesList = mutableListOf<NoteItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_flashcard_setup)

        val apiKey = BuildConfig.apiKeySafe
        val geminiHelper = GeminiHelper(apiKey, "gemini-2.5-flash")
        flashcardGenerator = FlashcardGenerator(geminiHelper)
        storage = FirebaseStorage.getInstance()
        firestore = FirebaseFirestore.getInstance()
        PDFBoxResourceLoader.init(applicationContext)

        progressDialog = ProgressDialog(this).apply {
            setMessage(getString(R.string.flashcard_setup_generating))
            setCancelable(false)
        }

        initViews()
        setupEmptyNotesSpinner()

        selectedSource = intent.getStringExtra(EXTRA_SOURCE)
            ?.takeIf { it == SOURCE_TOPIC || it == SOURCE_NOTES }
            ?: selectedSource
        prefillNoteName = intent.getStringExtra(EXTRA_PREFILL_NOTE_NAME).orEmpty()
        prefillTopicText = intent.getStringExtra(EXTRA_TOPIC_TEXT).orEmpty().trim()
        if (selectedSource == SOURCE_TOPIC) {
            prefillTopicText
                .takeIf { it.isNotBlank() }
                ?.let { etTopic.setText(it) }
        }

        setupListeners()
        if (selectedSource == SOURCE_NOTES) {
            loadNotes()
        }
        updateUI()
    }

    private fun initViews() {
        cardTopic = findViewById(R.id.cardTopic)
        cardMyNotes = findViewById(R.id.cardMyNotes)
        etTopic = findViewById(R.id.etFlashcardTopic)
        spinnerNotes = findViewById(R.id.spinnerFlashcardNotes)
        tvSelectedNotePreview = findViewById(R.id.tvFlashcardSelectedNotePreview)
        sliderCards = findViewById(R.id.sliderFlashcardCount)
        tvCardCount = findViewById(R.id.tvFlashcardCount)
        btnGenerateFlashcards = findViewById(R.id.btnGenerateFlashcards)

        cardCount = sliderCards.value.toInt()
        tvCardCount.text = getString(R.string.flashcard_setup_card_count_format, cardCount)
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }
    }

    private fun setupEmptyNotesSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            listOf(getString(R.string.flashcard_setup_select_note))
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerNotes.adapter = adapter
    }

    private fun setupListeners() {
        cardTopic.setOnClickListener {
            selectedSource = SOURCE_TOPIC
            updateUI()
        }

        cardMyNotes.setOnClickListener {
            selectedSource = SOURCE_NOTES
            loadNotes()
            updateUI()
        }

        etTopic.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                validateInputs()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        spinnerNotes.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (position <= 0 || notesList.isEmpty()) {
                    selectedNote = null
                    selectedNoteName = ""
                } else {
                    selectedNote = notesList[position - 1]
                    selectedNoteName = selectedNote?.name.orEmpty()
                }

                tvSelectedNotePreview.text = if (selectedNoteName.isNotBlank()) {
                    getString(R.string.flashcard_setup_selected_note_format, selectedNoteName)
                } else {
                    getString(R.string.flashcard_setup_selected_note_empty)
                }
                validateInputs()
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                selectedNoteName = ""
                tvSelectedNotePreview.text = getString(R.string.flashcard_setup_selected_note_empty)
                validateInputs()
            }
        }

        sliderCards.addOnChangeListener { _, value, _ ->
                cardCount = value.toInt()
                tvCardCount.text = getString(R.string.flashcard_setup_card_count_format, cardCount)
                validateInputs()
        }

        btnGenerateFlashcards.setOnClickListener {
            generateFlashcards()
        }
    }

    private fun updateUI() {
        val selectedColor = ContextCompat.getColor(this, R.color.primary_light)
        val defaultColor = ContextCompat.getColor(this, R.color.card_bg)

        val isTopic = selectedSource == SOURCE_TOPIC
        cardTopic.setCardBackgroundColor(if (isTopic) selectedColor else defaultColor)
        cardMyNotes.setCardBackgroundColor(if (isTopic) defaultColor else selectedColor)

        etTopic.visibility = if (isTopic) View.VISIBLE else View.GONE
        spinnerNotes.visibility = if (isTopic) View.GONE else View.VISIBLE
        tvSelectedNotePreview.visibility = if (isTopic) View.GONE else View.VISIBLE

        validateInputs()
    }

    private fun validateInputs() {
        val isValid = if (selectedSource == SOURCE_TOPIC) {
            etTopic.text.toString().trim().isNotEmpty()
        } else {
            selectedNote != null
        }

        btnGenerateFlashcards.isEnabled = isValid && !isGenerating
        btnGenerateFlashcards.alpha = if (btnGenerateFlashcards.isEnabled) 1f else 0.5f
    }

    private fun loadNotes() {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            Toast.makeText(this, getString(R.string.flashcard_setup_login_required), Toast.LENGTH_SHORT).show()
            return
        }

        firestore.collection("Notes")
            .document(user.uid)
            .collection("UserNotes")
            .get()
            .addOnSuccessListener { result ->
                notesList.clear()
                result.documents.forEach { doc ->
                    val name = doc.getString("name").orEmpty().trim()
                    val type = doc.getString("type").orEmpty().trim()
                    val url = doc.getString("url").orEmpty().trim()
                    if (name.isNotEmpty()) {
                        notesList.add(NoteItem(name = name, type = type, downloadUrl = url))
                    }
                }

                val spinnerItems = mutableListOf(getString(R.string.flashcard_setup_select_note))
                spinnerItems.addAll(notesList.map { it.name })
                if (notesList.isEmpty()) {
                    spinnerItems.add(getString(R.string.flashcard_setup_no_notes_uploaded))
                }

                val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, spinnerItems)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinnerNotes.adapter = adapter

                selectedNote = null
                selectedNoteName = ""
                tvSelectedNotePreview.text = getString(R.string.flashcard_setup_selected_note_empty)

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
            .addOnFailureListener { e ->
                Toast.makeText(
                    this,
                    getString(R.string.flashcard_setup_load_notes_failed, e.message.orEmpty()),
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

    private fun showMissingPrefilledNoteDialog(requestedNoteName: String) {
        AlertDialog.Builder(this)
            .setTitle("File not available")
            .setMessage(
                "We could not find \"$requestedNoteName\" in your uploaded notes. " +
                    "It may have been studied from a device-only attachment.\n\n" +
                    "You can upload it now, or skip and continue with topic mode."
            )
            .setPositiveButton("Upload") { _, _ ->
                startActivity(Intent(this, UploadActivity::class.java))
            }
            .setNegativeButton("Skip") { _, _ ->
                selectedSource = SOURCE_TOPIC
                val fallbackTopic = prefillTopicText.ifBlank { requestedNoteName }
                if (etTopic.text.toString().trim().isBlank()) {
                    etTopic.setText(fallbackTopic)
                }
                updateUI()
            }
            .show()
    }

    private fun generateFlashcards() {
        if (isGenerating) return

        val topicText = etTopic.text.toString().trim()
        val note = selectedNote
        val masteryTopic = if (selectedSource == SOURCE_NOTES) {
            prefillTopicText.ifBlank { topicText.ifBlank { note?.name.orEmpty() } }
        } else {
            topicText
        }

        setGeneratingState(true)
        lifecycleScope.launch {
            try {
                val generated = withContext(Dispatchers.IO) {
                    if (selectedSource == SOURCE_TOPIC) {
                        flashcardGenerator.generateFromTopic(topicText, cardCount)
                    } else {
                        if (note == null) return@withContext emptyList<Flashcard>()
                        val extractedText = downloadAndExtractText(note).take(12000)
                        if (extractedText.isBlank()) return@withContext emptyList<Flashcard>()
                        flashcardGenerator.generateFromNotes(extractedText, cardCount)
                    }
                }

                if (generated.isEmpty()) {
                    Toast.makeText(
                        this@FlashcardSetupActivity,
                        getString(R.string.flashcard_setup_generation_failed),
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                val intent = Intent(this@FlashcardSetupActivity, FlashcardActivity::class.java).apply {
                    putExtra(EXTRA_SOURCE, selectedSource)
                    putExtra(EXTRA_TOPIC_TEXT, masteryTopic)
                    putExtra(EXTRA_SELECTED_NOTE, note?.name.orEmpty())
                    putExtra(EXTRA_CARD_COUNT, generated.size)
                    putExtra(EXTRA_FLASHCARDS_JSON, Json.encodeToString(generated))
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(
                    this@FlashcardSetupActivity,
                    getString(R.string.flashcard_setup_generation_failed),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                setGeneratingState(false)
            }
        }
    }

    private fun setGeneratingState(generating: Boolean) {
        isGenerating = generating
        cardTopic.isEnabled = !generating
        cardMyNotes.isEnabled = !generating
        etTopic.isEnabled = !generating
        spinnerNotes.isEnabled = !generating
        sliderCards.isEnabled = !generating
        validateInputs()

        if (generating) {
            progressDialog.show()
        } else if (progressDialog.isShowing) {
            progressDialog.dismiss()
        }
    }

    private suspend fun downloadAndExtractText(note: NoteItem): String {
        val localFile = withContext(Dispatchers.IO) {
            val extension = note.name.substringAfterLast('.', "tmp")
            File.createTempFile("flashcard_note_", ".$extension", cacheDir)
        }

        return try {
            val downloaded = downloadNoteFile(note, localFile)
            if (!downloaded || localFile.length() == 0L) return ""
            when {
                note.name.endsWith(".pdf", ignoreCase = true) -> extractTextFromPdf(localFile)
                note.name.endsWith(".jpg", ignoreCase = true) ||
                    note.name.endsWith(".jpeg", ignoreCase = true) ||
                    note.name.endsWith(".png", ignoreCase = true) -> extractTextFromImage(localFile)
                else -> ""
            }
        } finally {
            localFile.delete()
        }
    }

    private suspend fun downloadNoteFile(note: NoteItem, destination: File): Boolean {
        return suspendCancellableCoroutine { continuation ->
            val fileRef = when {
                note.downloadUrl.isNotBlank() -> storage.getReferenceFromUrl(note.downloadUrl)
                else -> {
                    val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
                    storage.reference.child("user_uploads/$uid/${note.name}")
                }
            }

            fileRef.getFile(destination)
                .addOnSuccessListener {
                    if (!continuation.isCompleted) continuation.resume(true)
                }
                .addOnFailureListener {
                    if (!continuation.isCompleted) continuation.resume(false)
                }
        }
    }

    private fun extractTextFromPdf(file: File): String {
        val document = PDDocument.load(file)
        return try {
            PDFTextStripper().getText(document)
        } catch (_: Exception) {
            ""
        } finally {
            document.close()
        }
    }

    private suspend fun extractTextFromImage(file: File): String {
        return suspendCancellableCoroutine { continuation ->
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap == null) {
                continuation.resume("")
                return@suspendCancellableCoroutine
            }

            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

            recognizer.process(image)
                .addOnSuccessListener { text ->
                    if (!continuation.isCompleted) continuation.resume(text.text.orEmpty())
                }
                .addOnFailureListener {
                    if (!continuation.isCompleted) continuation.resume("")
                }
        }
    }

    data class NoteItem(
        val name: String,
        val type: String,
        val downloadUrl: String
    )

    companion object {
        const val SOURCE_TOPIC = "topic"
        const val SOURCE_NOTES = "notes"

        const val EXTRA_SOURCE = "source"
        const val EXTRA_TOPIC_TEXT = "topicText"
        const val EXTRA_SELECTED_NOTE = "selectedNote"
        const val EXTRA_PREFILL_NOTE_NAME = "prefillNoteName"
        const val EXTRA_CARD_COUNT = "cardCount"
        const val EXTRA_FLASHCARDS_JSON = "flashcardsJson"
    }
}




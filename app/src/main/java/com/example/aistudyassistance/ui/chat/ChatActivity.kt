package com.example.aistudyassistance.ui.chat

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.aistudyassistance.BuildConfig
import com.example.aistudyassistance.core.utils.GeminiHelper
import com.example.aistudyassistance.R
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.Timestamp
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatActivity : AppCompatActivity() {

    companion object {
        private const val STATE_MESSAGES = "state_messages"
        private const val STATE_MODE = "state_mode"
        private const val STATE_ACTIVE_DOC_TEXT = "state_active_doc_text"
        private const val STATE_ACTIVE_DOC_NAME = "state_active_doc_name"
        private const val STATE_ACTIVE_IMAGE_SOURCE = "state_active_image_source"
        private const val STATE_LAST_TOPIC = "state_last_topic"
        private const val STATE_DRAFT_TEXT = "state_draft_text"
    }

    private lateinit var rvChat: RecyclerView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: FloatingActionButton
    private lateinit var btnAttach: ImageButton
    private lateinit var layoutAttachmentBanner: CardView
    private lateinit var tvAttachmentInfo: TextView
    private lateinit var chatAdapter: ChatAdapter
    private val messages = mutableListOf<ChatMessage>()
    private lateinit var geminiHelper: GeminiHelper
    private var currentMode = StudyMode.EXPLAIN
    private var activeDocumentText: String? = null
    private var activeDocumentName: String? = null
    private var activeImageBitmap: Bitmap? = null
    private var activeImageSource: String? = null
    private var lastTopicQuery: String? = null
    private var pendingExamPaperGeneration: Boolean = false
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    enum class StudyMode {
        EXPLAIN, SHORT_NOTES, EXAM_ANSWER, QUIZ, FLASHCARDS
    }

    enum class MessageType {
        USER, AI, SYSTEM
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        // Initialize PDFBox
        PDFBoxResourceLoader.init(applicationContext)

        // Initialize Gemini Helper
        val apiKey = BuildConfig.apiKeySafe
        geminiHelper = GeminiHelper(apiKey, "gemini-2.5-flash")

        initViews()
        setupChat(showGreeting = savedInstanceState == null)
        setupStudyModes()

        if (savedInstanceState != null) {
            restoreChatState(savedInstanceState)
        } else {
            // Handle file-based prompts from UploadActivity only once for a fresh Activity launch.
            val prompt = intent.getStringExtra("PREFILLED_PROMPT")
            val fileUrl = intent.getStringExtra("FILE_URL")
            val fileType = intent.getStringExtra("FILE_TYPE")

            if (fileUrl != null && fileType == "pdf") {
                lifecycleScope.launch {
                    activeDocumentText = extractTextFromPdf(fileUrl)
                    activeDocumentName = intent.getStringExtra("FILE_NAME") ?: "Uploaded PDF"
                    updateAttachmentBanner()
                }
            }

            if (prompt != null) {
                if (fileUrl != null) {
                    processFileAndSendMessage(prompt, fileUrl, fileType ?: "")
                } else {
                    sendMessage(prompt)
                }
            }
        }
    }

    private fun initViews() {
        rvChat = findViewById(R.id.rvChat)
        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)
        btnAttach = findViewById(R.id.btnAttach)
        layoutAttachmentBanner = findViewById(R.id.layoutAttachmentBanner)
        tvAttachmentInfo = findViewById(R.id.tvAttachmentInfo)

        findViewById<ImageView>(R.id.ivBack).setOnClickListener {
            finish()
        }

        findViewById<ImageView>(R.id.ivRemoveAttachment).setOnClickListener {
            clearAttachment()
        }

        btnSend.setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                sendMessage(text)
                etMessage.text.clear()
            }
        }

        btnAttach.setOnClickListener {
            showAttachmentBottomSheet()
        }
    }

    private fun setupChat(showGreeting: Boolean) {
        chatAdapter = ChatAdapter(messages)
        rvChat.adapter = chatAdapter
        rvChat.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }

        if (showGreeting && !intent.hasExtra("PREFILLED_PROMPT")) {
            addMessage(ChatMessage("Hello! I'm your AI Study Assistant. How can I help you today?", false, messageType = MessageType.SYSTEM))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putSerializable(STATE_MESSAGES, ArrayList(messages))
        outState.putString(STATE_MODE, currentMode.name)
        outState.putString(STATE_ACTIVE_DOC_TEXT, activeDocumentText)
        outState.putString(STATE_ACTIVE_DOC_NAME, activeDocumentName)
        outState.putString(STATE_ACTIVE_IMAGE_SOURCE, activeImageSource)
        outState.putString(STATE_LAST_TOPIC, lastTopicQuery)
        outState.putString(STATE_DRAFT_TEXT, etMessage.text?.toString().orEmpty())
    }

    private fun restoreChatState(savedInstanceState: Bundle) {
        val restored = (savedInstanceState.getSerializable(STATE_MESSAGES) as? ArrayList<*>)
            ?.filterIsInstance<ChatMessage>()
            .orEmpty()

        messages.clear()
        messages.addAll(restored)
        chatAdapter.notifyDataSetChanged()
        if (messages.isNotEmpty()) {
            rvChat.scrollToPosition(messages.size - 1)
        }

        currentMode = savedInstanceState.getString(STATE_MODE)
            ?.let { modeName ->
                runCatching { StudyMode.valueOf(modeName) }.getOrNull()
            } ?: StudyMode.EXPLAIN
        applySelectedModeChip()

        activeDocumentText = savedInstanceState.getString(STATE_ACTIVE_DOC_TEXT)
        activeDocumentName = savedInstanceState.getString(STATE_ACTIVE_DOC_NAME)
        activeImageSource = savedInstanceState.getString(STATE_ACTIVE_IMAGE_SOURCE)
        lastTopicQuery = savedInstanceState.getString(STATE_LAST_TOPIC)
        etMessage.setText(savedInstanceState.getString(STATE_DRAFT_TEXT).orEmpty())

        // Bitmap bytes are not persisted in Bundle; restore from source if needed.
        activeImageBitmap = null
        updateAttachmentBanner()
        if (activeDocumentText == null && !activeImageSource.isNullOrBlank()) {
            lifecycleScope.launch {
                activeImageBitmap = loadBitmapFromSource(activeImageSource)
                if (activeImageBitmap == null) {
                    Toast.makeText(this@ChatActivity, "Reattach image to continue with image context", Toast.LENGTH_SHORT).show()
                    clearAttachment()
                }
            }
        }
    }

    private fun sendMessage(text: String, useAttachment: Boolean = true) {
        if (!useAttachment) {
            // User chose topic-only generation even if a file was attached.
            activeDocumentText = null
            activeDocumentName = null
            activeImageBitmap = null
            activeImageSource = null
            updateAttachmentBanner()
        }
        if (text.isNotBlank() && activeDocumentName == null) {
            lastTopicQuery = text
        }

        addMessage(ChatMessage(text, true, messageType = MessageType.USER))
        val typingMessage = ChatMessage("AI is thinking...", false, isTyping = true, messageType = MessageType.SYSTEM)
        addMessage(typingMessage)

        lifecycleScope.launch {
            val index = messages.lastIndexOf(typingMessage)
            if (index < 0) return@launch
            val context = buildContext()
            val systemPrompt = """
You are an AI study assistant.
Explain concepts clearly.
Use bullet points.
Avoid markdown formatting.
Keep answers concise.
""".trimIndent()
            val modePrompt = getModePrompt(currentMode, text)
            if (useAttachment && activeDocumentText == null && activeImageBitmap == null && !activeImageSource.isNullOrBlank()) {
                activeImageBitmap = loadBitmapFromSource(activeImageSource)
            }
            val fullPrompt = if (useAttachment && activeDocumentText != null) {
                "$systemPrompt\n\nUse the following study material to answer.\n\nMaterial:\n$activeDocumentText\n\nQuestion:\n$modePrompt"
            } else {
                "$systemPrompt\n\nContext:\n$context\n\n$modePrompt"
            }

            try {
                if (useAttachment && activeImageBitmap != null) {
                    geminiHelper.getResponseWithImageStream(fullPrompt, activeImageBitmap!!).collect { partialResponse ->
                        val cleaned = cleanResponse(partialResponse)
                        withContext(Dispatchers.Main) {
                            if (index in messages.indices) {
                                messages[index] = ChatMessage(cleaned, false, isTyping = true)
                                chatAdapter.notifyItemChanged(index)
                            }
                        }
                    }
                } else {
                    geminiHelper.getResponseStream(fullPrompt).collect { partialResponse ->
                        val cleaned = cleanResponse(partialResponse)
                        withContext(Dispatchers.Main) {
                            if (index in messages.indices) {
                                messages[index] = ChatMessage(cleaned, false, isTyping = true)
                                chatAdapter.notifyItemChanged(index)
                            }
                        }
                    }
                }

                val finalResponse = messages.getOrNull(index)?.text.orEmpty().ifBlank {
                    "Sorry, I could not generate a response. Please try again."
                }
                val suggestions = generateSuggestions(finalResponse)
                if (index in messages.indices) {
                    messages[index] = ChatMessage(finalResponse, false, suggestions = suggestions, messageType = MessageType.AI)
                    chatAdapter.notifyItemChanged(index)
                    rvChat.scrollToPosition(messages.size - 1)
                }
            } catch (e: Exception) {
                if (index in messages.indices) {
                    messages[index] = ChatMessage("Error: ${e.message ?: "Something went wrong"}", false, messageType = MessageType.AI)
                    chatAdapter.notifyItemChanged(index)
                }
            }
        }
    }

    private fun processFileAndSendMessage(prompt: String, url: String, type: String) {
        addMessage(ChatMessage(prompt, true, messageType = MessageType.USER))
        val typingMessage = ChatMessage("Reading file and thinking...", false, isTyping = true, messageType = MessageType.SYSTEM)
        addMessage(typingMessage)

        lifecycleScope.launch {
            try {
                val index = messages.indexOf(typingMessage)
                val systemPrompt = "You are an AI study assistant."
                val modePrompt = getModePrompt(currentMode, prompt)
                if (type == "pdf") {
                    val textContent = activeDocumentText ?: extractTextFromPdf(url)
                    val fullPrompt = "$systemPrompt\n\nMaterial:\n$textContent\n\nQuestion:\n$modePrompt"
                    geminiHelper.getResponseStream(fullPrompt).collect { partialResponse ->
                        val cleaned = cleanResponse(partialResponse)
                        withContext(Dispatchers.Main) {
                            messages[index] = ChatMessage(cleaned, false, isTyping = true)
                            chatAdapter.notifyItemChanged(index)
                        }
                    }
                } else {
                    val bitmap = downloadImage(url)
                    if (bitmap != null) {
                        geminiHelper.getResponseWithImageStream(modePrompt, bitmap).collect { partialResponse ->
                            val cleaned = cleanResponse(partialResponse)
                            withContext(Dispatchers.Main) {
                                messages[index] = ChatMessage(cleaned, false, isTyping = true)
                                chatAdapter.notifyItemChanged(index)
                            }
                        }
                    } else {
                        messages[index] = ChatMessage("Error: Could not process image.", false, messageType = MessageType.AI)
                        chatAdapter.notifyItemChanged(index)
                        return@launch
                    }
                }
                val finalResponse = messages[index].text
                val suggestions = generateSuggestions(finalResponse)
                messages[index] = ChatMessage(finalResponse, false, suggestions = suggestions, messageType = MessageType.AI)
                chatAdapter.notifyItemChanged(index)
                rvChat.scrollToPosition(messages.size - 1)
            } catch (e: Exception) {
                val index = messages.indexOf(typingMessage)
                if (index != -1) {
                    messages[index] = ChatMessage("Error: ${e.message}", false, messageType = MessageType.AI)
                    chatAdapter.notifyItemChanged(index)
                }
            }
        }
    }

    private suspend fun extractTextFromPdf(pdfUrl: String): String = withContext(Dispatchers.IO) {
        try {
            val url = URL(pdfUrl)
            url.openStream().use { inputStream ->
                PDDocument.load(inputStream).use { document ->
                    val stripper = PDFTextStripper()
                    stripper.getText(document).take(12000)
                }
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private suspend fun downloadImage(imageUrl: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val loader = ImageLoader(this@ChatActivity)
            val request = ImageRequest.Builder(this@ChatActivity)
                .data(imageUrl)
                .allowHardware(false)
                .build()
            val result = (loader.execute(request) as SuccessResult).drawable
            (result as BitmapDrawable).bitmap
        } catch (e: Exception) {
            null
        }
    }

    private fun addMessage(message: ChatMessage) {
        messages.add(message)
        chatAdapter.notifyItemInserted(messages.size - 1)
        rvChat.scrollToPosition(messages.size - 1)
    }

    private fun buildContext(): String {
        val recentMessages = messages.filter { !it.isTyping }.takeLast(6)
        return recentMessages.joinToString("\n") { if (it.isUser) "User: ${it.text}" else "AI: ${it.text}" }
    }

    private fun cleanResponse(response: String): String {
        return response.replace(Regex("\\*\\*"), "").replace(Regex("###"), "").replace(Regex("---"), "")
    }

    private fun setupStudyModes() {
        val chipExplain = findViewById<Chip>(R.id.chipExplain)
        val chipShortNotesMode = findViewById<Chip>(R.id.chipShortNotesMode)
        val chipExamAnswer = findViewById<Chip>(R.id.chipExamAnswer)
        val chipQuiz = findViewById<Chip>(R.id.chipQuiz)
        val chipFlashcards = findViewById<Chip>(R.id.chipFlashcards)

        applySelectedModeChip()

        chipExplain.setOnClickListener {
            currentMode = StudyMode.EXPLAIN
            pendingExamPaperGeneration = false
            showGuidedGenerationSheet(StudyMode.EXPLAIN)
        }
        chipShortNotesMode.setOnClickListener {
            currentMode = StudyMode.SHORT_NOTES
            pendingExamPaperGeneration = false
            showGuidedGenerationSheet(StudyMode.SHORT_NOTES)
        }
        chipExamAnswer.setOnClickListener {
            currentMode = StudyMode.EXAM_ANSWER
            pendingExamPaperGeneration = true
            showExamAnswerAttachmentSheet()
        }
        chipQuiz.setOnClickListener {
            currentMode = StudyMode.QUIZ
            pendingExamPaperGeneration = false
        }
        chipFlashcards.setOnClickListener {
            currentMode = StudyMode.FLASHCARDS
            pendingExamPaperGeneration = false
        }
    }

    private fun showExamAnswerAttachmentSheet() {
        showModeBottomSheet(
            title = "Attach question paper",
            message = "Choose where your question paper is.",
            primaryText = "From Device",
            secondaryText = "From Uploads",
            onPrimary = {
                pickFile("*/*")
            },
            onSecondary = {
                showNotesBottomSheet { maybeAutoGenerateExamAnswers() }
            }
        )
    }

    private fun maybeAutoGenerateExamAnswers() {
        if (!pendingExamPaperGeneration || currentMode != StudyMode.EXAM_ANSWER) return
        pendingExamPaperGeneration = false
        sendMessage("Generate complete answers for all questions from this attached question paper.")
    }

    private fun applySelectedModeChip() {
        findViewById<Chip>(R.id.chipExplain).isChecked = currentMode == StudyMode.EXPLAIN
        findViewById<Chip>(R.id.chipShortNotesMode).isChecked = currentMode == StudyMode.SHORT_NOTES
        findViewById<Chip>(R.id.chipExamAnswer).isChecked = currentMode == StudyMode.EXAM_ANSWER
        findViewById<Chip>(R.id.chipQuiz).isChecked = currentMode == StudyMode.QUIZ
        findViewById<Chip>(R.id.chipFlashcards).isChecked = currentMode == StudyMode.FLASHCARDS
    }

    private fun showGuidedGenerationSheet(mode: StudyMode) {
        val modeLabel = if (mode == StudyMode.SHORT_NOTES) "short notes" else "explanation"
        val currentTopic = lastTopicQuery?.takeIf { it.isNotBlank() }
        val currentDoc = activeDocumentName?.takeIf { it.isNotBlank() }

        when {
            currentDoc != null -> {
                showModeBottomSheet(
                    title = "Use this file?",
                    message = "Create $modeLabel from '$currentDoc'?",
                    primaryText = "Yes",
                    secondaryText = "No",
                    onPrimary = {
                        sendMessage("Generate $modeLabel from the selected file.")
                    },
                    onSecondary = {
                        promptTopicForMode(mode)
                    }
                )
            }
            currentTopic != null -> {
                showModeBottomSheet(
                    title = "Use same topic?",
                    message = "Create $modeLabel for '$currentTopic'?",
                    primaryText = "Yes",
                    secondaryText = "No",
                    onPrimary = {
                        sendMessage(currentTopic, useAttachment = false)
                    },
                    onSecondary = {
                        promptTopicForMode(mode)
                    }
                )
            }
            else -> {
                showModeBottomSheet(
                    title = "Topic or file needed",
                    message = "Add a topic or pick a file first.",
                    primaryText = "Enter Topic",
                    secondaryText = "Upload File",
                    onPrimary = {
                        promptTopicForMode(mode)
                    },
                    onSecondary = {
                        showAttachmentBottomSheet()
                    }
                )
            }
        }
    }

    private fun promptTopicForMode(mode: StudyMode) {
        val modeLabel = if (mode == StudyMode.SHORT_NOTES) "short notes" else "explanation"
        showModeBottomSheet(
            title = "Enter topic",
            message = "Type a topic to create $modeLabel.",
            primaryText = "Generate",
            secondaryText = "Cancel",
            showTopicInput = true,
            onPrimary = { topic ->
                lastTopicQuery = topic
                sendMessage(topic, useAttachment = false)
            },
            onSecondary = { }
        )
    }

    private fun showModeBottomSheet(
        title: String,
        message: String,
        primaryText: String,
        secondaryText: String,
        showTopicInput: Boolean = false,
        onPrimary: (String) -> Unit,
        onSecondary: () -> Unit
    ) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_mode_prompt, null)
        dialog.setContentView(view)

        val tvTitle = view.findViewById<TextView>(R.id.tvModeTitle)
        val tvMessage = view.findViewById<TextView>(R.id.tvModeMessage)
        val etTopic = view.findViewById<EditText>(R.id.etModeTopic)
        val btnPrimary = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnModePrimary)
        val btnSecondary = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnModeSecondary)

        tvTitle.text = title
        tvMessage.text = message
        etTopic.visibility = if (showTopicInput) View.VISIBLE else View.GONE
        btnPrimary.text = primaryText
        btnSecondary.text = secondaryText

        btnPrimary.setOnClickListener {
            val topicValue = etTopic.text?.toString()?.trim().orEmpty()
            if (showTopicInput && topicValue.isBlank()) {
                etTopic.error = "Topic is required"
                return@setOnClickListener
            }
            dialog.dismiss()
            try {
                onPrimary(topicValue)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not continue: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        btnSecondary.setOnClickListener {
            dialog.dismiss()
            onSecondary()
        }

        dialog.show()
    }

    private fun getModePrompt(mode: StudyMode, question: String): String {
        return when (mode) {
            StudyMode.EXPLAIN -> "Explain this clearly: $question"
            StudyMode.SHORT_NOTES -> "Provide short revision notes: $question"
            StudyMode.EXAM_ANSWER -> "Write a detailed exam answer: $question"
            StudyMode.QUIZ -> "Generate 5 MCQs with answers: $question"
            StudyMode.FLASHCARDS -> "Create flashcards (Q&A): $question"
        }
    }

    private suspend fun generateSuggestions(response: String): List<String> {
        return try {
            val prompt = "Generate 2 follow-up questions for: $response"
            val suggestionsText = geminiHelper.getResponse(prompt)
            suggestionsText.lines().filter { it.isNotBlank() }.take(2)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun showAttachmentBottomSheet() {
        val bottomSheetDialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_attachment, null)
        bottomSheetDialog.setContentView(view)

        view.findViewById<CardView>(R.id.cardUploadPdf).setOnClickListener {
            pickFile("application/pdf")
            bottomSheetDialog.dismiss()
        }

        view.findViewById<CardView>(R.id.cardUploadImage).setOnClickListener {
            pickFile("image/*")
            bottomSheetDialog.dismiss()
        }

        view.findViewById<CardView>(R.id.cardMyUploadedNotes).setOnClickListener {
            showNotesBottomSheet()
            bottomSheetDialog.dismiss()
        }

        bottomSheetDialog.show()
    }

    private fun showNotesBottomSheet(onNoteAttached: (() -> Unit)? = null) {
        val bottomSheetDialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_notes, null)
        bottomSheetDialog.setContentView(view)

        val rvNotes = view.findViewById<RecyclerView>(R.id.rvNotes)
        val notesList = mutableListOf<StudyNote>()
        val notesAdapter = NotesAdapter(notesList) { note ->
            selectNote(note, onAttached = onNoteAttached)
            bottomSheetDialog.dismiss()
        }
        rvNotes.adapter = notesAdapter
        rvNotes.layoutManager = LinearLayoutManager(this)

        val userId = auth.currentUser?.uid
        if (userId.isNullOrBlank()) {
            Toast.makeText(this, "Please sign in to access notes", Toast.LENGTH_SHORT).show()
            bottomSheetDialog.dismiss()
            return
        }

        firestore.collection("Notes").document(userId).collection("UserNotes")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { result ->
                notesList.clear()
                for (doc in result.documents) {
                    mapStudyNote(doc.data).also { mapped ->
                        if (mapped != null) notesList.add(mapped)
                    }
                }

                if (notesList.isEmpty()) {
                    Toast.makeText(this, "No uploaded notes found", Toast.LENGTH_SHORT).show()
                }
                notesAdapter.notifyDataSetChanged()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to load notes: ${e.message}", Toast.LENGTH_SHORT).show()
            }

        bottomSheetDialog.show()
    }

    private fun selectNote(note: StudyNote, onAttached: (() -> Unit)? = null) {
        lifecycleScope.launch {
            try {
                val noteUrl = note.url.trim()
                if (noteUrl.isBlank()) {
                    Toast.makeText(this@ChatActivity, "This note is missing a file URL", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val isPdf = note.type.equals("pdf", ignoreCase = true) ||
                    note.name.endsWith(".pdf", ignoreCase = true)

                if (isPdf) {
                    val extracted = extractTextFromPdf(noteUrl)
                    if (extracted.startsWith("Error:", ignoreCase = true) || extracted.isBlank()) {
                        Toast.makeText(this@ChatActivity, "Could not read this PDF note", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    activeDocumentText = extracted
                    activeDocumentName = note.name.ifBlank { "Selected PDF" }
                    activeImageBitmap = null
                    activeImageSource = null
                } else {
                    val bitmap = downloadImage(noteUrl)
                    if (bitmap == null) {
                        Toast.makeText(this@ChatActivity, "Could not open selected image note", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    activeImageBitmap = bitmap
                    activeImageSource = noteUrl
                    activeDocumentText = null
                    activeDocumentName = note.name.ifBlank { "Selected Image" }
                }
                updateAttachmentBanner()
                Toast.makeText(this@ChatActivity, "Attached: ${activeDocumentName}", Toast.LENGTH_SHORT).show()
                onAttached?.invoke()
            } catch (e: Exception) {
                Toast.makeText(this@ChatActivity, "Could not attach note: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun pickFile(type: String) {
        filePickerLauncher.launch(type)
    }

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) {
            pendingExamPaperGeneration = false
            return@registerForActivityResult
        }
        handleFileSelection(uri)
    }

    private fun handleFileSelection(uri: Uri) {
        val fileName = getFileName(uri)
        val mimeType = contentResolver.getType(uri).orEmpty()

        lifecycleScope.launch {
            val isPdf = mimeType.equals("application/pdf", ignoreCase = true) ||
                fileName.endsWith(".pdf", ignoreCase = true)
            val isImage = mimeType.startsWith("image/")

            if (!isPdf && !isImage) {
                Toast.makeText(this@ChatActivity, "Only PDF or image files are supported", Toast.LENGTH_SHORT).show()
                return@launch
            }

            if (isPdf) {
                val extracted = extractTextFromLocalPdf(uri)
                if (extracted.startsWith("Error:", ignoreCase = true) || extracted.isBlank()) {
                    Toast.makeText(this@ChatActivity, "Could not read selected PDF", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                activeDocumentText = extracted
                activeDocumentName = fileName
                activeImageBitmap = null
                activeImageSource = null
            } else {
                val bitmap = getBitmapFromUri(uri)
                if (bitmap == null) {
                    Toast.makeText(this@ChatActivity, "Could not open selected image", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                activeImageBitmap = bitmap
                activeImageSource = uri.toString()
                activeDocumentText = null
                activeDocumentName = fileName
            }

            updateAttachmentBanner()
            Toast.makeText(this@ChatActivity, "Attached: $fileName", Toast.LENGTH_SHORT).show()
            maybeAutoGenerateExamAnswers()
        }
    }

    private suspend fun extractTextFromLocalPdf(uri: Uri): String = withContext(Dispatchers.IO) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                PDDocument.load(inputStream).use { document ->
                    val stripper = PDFTextStripper()
                    stripper.getText(document).take(12000)
                }
            } ?: "Error: Could not open file"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private suspend fun getBitmapFromUri(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val loader = ImageLoader(this@ChatActivity)
            val request = ImageRequest.Builder(this@ChatActivity)
                .data(uri)
                .allowHardware(false)
                .build()
            val result = (loader.execute(request) as SuccessResult).drawable
            (result as BitmapDrawable).bitmap
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun loadBitmapFromSource(source: String?): Bitmap? {
        if (source.isNullOrBlank()) return null
        return if (source.startsWith("content://") || source.startsWith("file://")) {
            getBitmapFromUri(Uri.parse(source))
        } else {
            downloadImage(source)
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = "Unknown"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        if (name == "Unknown") {
            uri.lastPathSegment?.substringAfterLast('/')?.let { segment ->
                if (segment.isNotBlank()) name = segment
            }
        }
        return name
    }

    private fun mapStudyNote(raw: Map<String, Any>?): StudyNote? {
        if (raw.isNullOrEmpty()) return null

        val name = (raw["name"] as? String)
            ?: (raw["fileName"] as? String)
            ?: "Uploaded file"

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

        return StudyNote(
            id = (raw["id"] as? String).orEmpty(),
            name = name,
            type = type,
            url = url,
            timestamp = timestamp,
            userId = (raw["userId"] as? String).orEmpty()
        )
    }

    private fun updateAttachmentBanner() {
        if (activeDocumentName != null) {
            tvAttachmentInfo.text = "Attached: $activeDocumentName"
            layoutAttachmentBanner.visibility = View.VISIBLE
        } else {
            tvAttachmentInfo.text = ""
            layoutAttachmentBanner.visibility = View.GONE
        }
    }

    private fun clearAttachment() {
        activeDocumentText = null
        activeDocumentName = null
        activeImageBitmap = null
        activeImageSource = null
        updateAttachmentBanner()
    }

    inner class ChatAdapter(private val chatMessages: List<ChatMessage>) :
        RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

        inner class ChatViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvMessage: TextView = view.findViewById(R.id.tvMessage)
            val cardMessage: CardView = view.findViewById(R.id.cardMessage)
            val layoutContainer: LinearLayout = view.findViewById(R.id.layoutMessageContainer)
            val chipGroupSuggestions: ChipGroup = view.findViewById(R.id.chipGroupSuggestions)
            val tvShowMore: TextView = view.findViewById(R.id.tvShowMore)
            val layoutActionToolbar: LinearLayout = view.findViewById(R.id.layoutActionToolbar)
            val btnCopy: TextView = view.findViewById(R.id.btnCopy)
            val btnRegenerate: TextView = view.findViewById(R.id.btnRegenerate)
            val btnHelpful: ImageView = view.findViewById(R.id.btnHelpful)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_message, parent, false)
            return ChatViewHolder(view)
        }

        override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
            val message = chatMessages[position]
            holder.tvMessage.text = message.text
            holder.tvShowMore.visibility = View.GONE
            val params = holder.cardMessage.layoutParams as LinearLayout.LayoutParams
            if (message.isUser) {
                holder.layoutContainer.gravity = Gravity.END
                params.gravity = Gravity.END
                holder.cardMessage.setCardBackgroundColor(getColor(R.color.primary))
                holder.tvMessage.setTextColor(getColor(R.color.white))
                params.marginStart = 100
                params.marginEnd = 0
                holder.tvMessage.alpha = 1f
                holder.layoutActionToolbar.visibility = View.GONE
                holder.chipGroupSuggestions.visibility = View.GONE
            } else {
                holder.layoutContainer.gravity = Gravity.START
                params.gravity = Gravity.START
                holder.cardMessage.setCardBackgroundColor(getColor(R.color.card_bg))
                holder.tvMessage.setTextColor(getColor(R.color.text_main))
                params.marginStart = 0
                params.marginEnd = 100
                holder.tvMessage.alpha = if (message.isTyping) 0.5f else 1.0f

                if (!message.isTyping && message.messageType == MessageType.AI) {
                    holder.layoutActionToolbar.visibility = View.VISIBLE
                    holder.btnCopy.setOnClickListener {
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AI Response", message.text))
                        Toast.makeText(holder.itemView.context, "Copied", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    holder.layoutActionToolbar.visibility = View.GONE
                }

                if (message.suggestions.isNotEmpty()) {
                    holder.chipGroupSuggestions.visibility = View.VISIBLE
                    holder.chipGroupSuggestions.removeAllViews()
                    for (suggestion in message.suggestions) {
                        val chip = Chip(holder.itemView.context).apply {
                            text = suggestion
                            setOnClickListener { sendMessage(suggestion) }
                        }
                        holder.chipGroupSuggestions.addView(chip)
                    }
                } else {
                    holder.chipGroupSuggestions.visibility = View.GONE
                }
            }
            holder.cardMessage.layoutParams = params
        }

        override fun getItemCount() = chatMessages.size
    }

    data class ChatMessage(
        val text: String,
        val isUser: Boolean,
        val isTyping: Boolean = false,
        val suggestions: List<String> = emptyList(),
        val messageType: MessageType = MessageType.AI
    ) : java.io.Serializable

    data class StudyNote(
        val id: String = "",
        val name: String = "",
        val type: String = "",
        val url: String = "",
        val timestamp: Long = 0,
        val userId: String = ""
    )

    inner class NotesAdapter(private val notes: List<StudyNote>, private val onClick: (StudyNote) -> Unit) :
        RecyclerView.Adapter<NotesAdapter.NoteViewHolder>() {

        inner class NoteViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvNoteName)
            val tvDate: TextView = view.findViewById(R.id.tvNoteDate)
            val ivIcon: ImageView = view.findViewById(R.id.ivNoteIcon)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_note, parent, false)
            return NoteViewHolder(view)
        }

        override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
            val note = notes[position]
            holder.tvName.text = note.name
            holder.tvDate.text = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(note.timestamp))
            holder.itemView.setOnClickListener { onClick(note) }
        }

        override fun getItemCount() = notes.size
    }
}

package com.example.aistudyassistance.Activity

import android.content.ClipboardManager
import android.content.Intent
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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.aistudyassistance.BuildConfig
import com.example.aistudyassistance.GeminiHelper
import com.example.aistudyassistance.R
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatActivity : AppCompatActivity() {

    private lateinit var rvChat: RecyclerView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: MaterialButton
    private lateinit var btnAttach: MaterialButton
    private lateinit var chatAdapter: ChatAdapter
    private val messages = mutableListOf<ChatMessage>()
    private lateinit var geminiHelper: GeminiHelper
    private var currentMode = StudyMode.EXPLAIN
    private var activeDocumentText: String? = null
    private var activeDocumentName: String? = null
    private var activeImageBitmap: Bitmap? = null
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance().reference

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

        // Initialize Gemini Helper using API key from BuildConfig
        val apiKey = BuildConfig.apiKeySafe
        geminiHelper = GeminiHelper(apiKey, "gemini-2.5-flash")

        initViews()
        setupChat()
        setupQuickActions()
        setupStudyModes()

        // Handle file-based prompts from UploadActivity
        val prompt = intent.getStringExtra("PREFILLED_PROMPT")
        val fileUrl = intent.getStringExtra("FILE_URL")
        val fileType = intent.getStringExtra("FILE_TYPE")

        if (fileUrl != null && fileType == "pdf") {
            lifecycleScope.launch {
                activeDocumentText = extractTextFromPdf(fileUrl)
                activeDocumentName = "Uploaded PDF" // or get from intent
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

    private fun initViews() {
        rvChat = findViewById(R.id.rvChat)
        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)
        btnAttach = findViewById(R.id.btnAttach)

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

    private fun setupChat() {
        chatAdapter = ChatAdapter(messages)
        rvChat.adapter = chatAdapter
        rvChat.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }

        if (!intent.hasExtra("PREFILLED_PROMPT")) {
            addMessage(ChatMessage("Hello! I'm your AI Study Assistant. How can I help you today?", false, messageType = MessageType.SYSTEM))
        }
    }

    private fun setupQuickActions() {
        findViewById<Chip>(R.id.chipExamQuestions).setOnClickListener {
            showInputDialog("Exam Questions", "Enter the topic or subject") { topic ->
                sendMessage("Generate important exam questions about $topic with answers.")
            }
        }

        findViewById<Chip>(R.id.chipSummarize).setOnClickListener {
            showInputDialog("Summarize", "Paste the content you want to summarize") { content ->
                sendMessage("Summarize the following content in simple and clear points for study revision:\n\n$content")
            }
        }

        findViewById<Chip>(R.id.chipShortNotes).setOnClickListener {
            showInputDialog("Short Notes", "Enter the topic for short notes") { topic ->
                sendMessage("Create short revision notes for $topic in bullet points suitable for exam preparation.")
            }
        }
    }

    private fun showInputDialog(title: String, hint: String, onConfirm: (String) -> Unit) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(title)
        val input = EditText(this)
        input.hint = hint
        val container = FrameLayout(this)
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.setMargins(48, 24, 48, 24)
        input.layoutParams = params
        container.addView(input)
        builder.setView(container)
        builder.setPositiveButton("Generate") { dialog, _ ->
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) onConfirm(text)
            dialog.dismiss()
        }
        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
        builder.show()
    }

    private fun sendMessage(text: String) {
        addMessage(ChatMessage(text, true, messageType = MessageType.USER))
        val typingMessage = ChatMessage("AI is thinking...", false, isTyping = true, messageType = MessageType.SYSTEM)
        addMessage(typingMessage)

        lifecycleScope.launch {
            val index = messages.indexOf(typingMessage)
            val context = buildContext()
            val systemPrompt = """
You are an AI study assistant.
Explain concepts clearly.
Use bullet points.
Avoid markdown formatting such as ### or **.
Keep answers concise (around 8–10 lines).
Include a simple example when useful.
""".trimIndent()
            val modePrompt = getModePrompt(currentMode, text)
            val fullPrompt = if (activeDocumentText != null) {
                "$systemPrompt\n\nUse the following study material to answer the student's question.\n\nStudy Material:\n$activeDocumentText\n\nStudent Question:\n$modePrompt"
            } else {
                "$systemPrompt\n\nConversation context:\n$context\n\n$modePrompt"
            }

            if (activeImageBitmap != null) {
                geminiHelper.getResponseWithImageStream(fullPrompt, activeImageBitmap!!).collect { partialResponse ->
                    val cleaned = cleanResponse(partialResponse)
                    withContext(Dispatchers.Main) {
                        messages[index] = ChatMessage(cleaned, false, isTyping = true)
                        chatAdapter.notifyItemChanged(index)
                    }
                }
            } else {
                geminiHelper.getResponseStream(fullPrompt).collect { partialResponse ->
                    val cleaned = cleanResponse(partialResponse)
                    withContext(Dispatchers.Main) {
                        messages[index] = ChatMessage(cleaned, false, isTyping = true)
                        chatAdapter.notifyItemChanged(index)
                    }
                }
            }
            // After streaming, generate suggestions
            val finalResponse = messages[index].text
            val suggestions = generateSuggestions(finalResponse)
            messages[index] = ChatMessage(finalResponse, false, suggestions = suggestions, messageType = MessageType.AI)
            chatAdapter.notifyItemChanged(index)
            rvChat.scrollToPosition(messages.size - 1)
        }
    }

    private fun processFileAndSendMessage(prompt: String, url: String, type: String) {
        addMessage(ChatMessage(prompt, true, messageType = MessageType.USER))
        val typingMessage = ChatMessage("Reading file and thinking...", false, isTyping = true, messageType = MessageType.SYSTEM)
        addMessage(typingMessage)

        lifecycleScope.launch {
            try {
                val index = messages.indexOf(typingMessage)
                val context = buildContext()
                val systemPrompt = """
You are an AI study assistant.
Explain concepts clearly.
Use bullet points.
Avoid markdown formatting such as ### or **.
Keep answers concise (around 8–10 lines).
Include a simple example when useful.
""".trimIndent()
                val modePrompt = getModePrompt(currentMode, prompt)
                if (type == "pdf") {
                    val textContent = activeDocumentText ?: extractTextFromPdf(url)
                    val fullPrompt = "$systemPrompt\n\nUse the following study material to answer the student's question.\n\nStudy Material:\n$textContent\n\nStudent Question:\n$modePrompt"
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
                        val fullPrompt = "$systemPrompt\n\nConversation context:\n$context\n\n$modePrompt"
                        geminiHelper.getResponseWithImageStream(fullPrompt, bitmap).collect { partialResponse ->
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
                // After streaming, generate suggestions
                val finalResponse = messages[index].text
                val suggestions = generateSuggestions(finalResponse)
                messages[index] = ChatMessage(finalResponse, false, suggestions = suggestions, messageType = MessageType.AI)
                chatAdapter.notifyItemChanged(index)
                rvChat.scrollToPosition(messages.size - 1)
            } catch (e: Exception) {
                val index = messages.indexOf(typingMessage)
                if (index != -1) {
                    messages[index] = ChatMessage("Error processing file: ${e.message}", false, messageType = MessageType.AI)
                    chatAdapter.notifyItemChanged(index)
                    rvChat.scrollToPosition(messages.size - 1)
                }
            }
        }
    }

    private suspend fun extractTextFromPdf(pdfUrl: String): String = withContext(Dispatchers.IO) {
        try {
            val url = URL(pdfUrl)
            val inputStream = url.openStream()
            val document = PDDocument.load(inputStream)
            val stripper = PDFTextStripper()
            val text = stripper.getText(document)
            document.close()
            text.take(12000)
        } catch (e: Exception) {
            "Could not extract text from PDF: ${e.message}"
        }
    }

    private suspend fun downloadImage(imageUrl: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val loader = ImageLoader(this@ChatActivity)
            val request = ImageRequest.Builder(this@ChatActivity)
                .data(imageUrl)
                .allowHardware(false) // Important for Gemini
                .build()
            val result = (loader.execute(request) as SuccessResult).drawable
            (result as BitmapDrawable).bitmap
        } catch (e: Exception) {
            null
        }
    }

    private fun updateAiResponse(typingMessage: ChatMessage, response: String) {
        val index = messages.indexOf(typingMessage)
        if (index != -1) {
            messages[index] = ChatMessage(response, false)
            chatAdapter.notifyItemChanged(index)
            rvChat.scrollToPosition(messages.size - 1)
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

        chipExplain.isChecked = true

        chipExplain.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                currentMode = StudyMode.EXPLAIN
                chipShortNotesMode.isChecked = false
                chipExamAnswer.isChecked = false
                chipQuiz.isChecked = false
                chipFlashcards.isChecked = false
            }
        }
        chipShortNotesMode.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                currentMode = StudyMode.SHORT_NOTES
                chipExplain.isChecked = false
                chipExamAnswer.isChecked = false
                chipQuiz.isChecked = false
                chipFlashcards.isChecked = false
            }
        }
        chipExamAnswer.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                currentMode = StudyMode.EXAM_ANSWER
                chipExplain.isChecked = false
                chipShortNotesMode.isChecked = false
                chipQuiz.isChecked = false
                chipFlashcards.isChecked = false
            }
        }
        chipQuiz.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                currentMode = StudyMode.QUIZ
                chipExplain.isChecked = false
                chipShortNotesMode.isChecked = false
                chipExamAnswer.isChecked = false
                chipFlashcards.isChecked = false
            }
        }
        chipFlashcards.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                currentMode = StudyMode.FLASHCARDS
                chipExplain.isChecked = false
                chipShortNotesMode.isChecked = false
                chipExamAnswer.isChecked = false
                chipQuiz.isChecked = false
            }
        }
    }

    private fun getModePrompt(mode: StudyMode, question: String): String {
        return when (mode) {
            StudyMode.EXPLAIN -> "Explain this concept clearly with examples: $question"
            StudyMode.SHORT_NOTES -> "Provide short revision notes in bullet points: $question"
            StudyMode.EXAM_ANSWER -> "Write the answer as if it were a 5-mark exam answer: $question"
            StudyMode.QUIZ -> "Generate 5 multiple-choice questions with answers: $question"
            StudyMode.FLASHCARDS -> "Create flashcards in Q/A format: $question"
        }
    }

    private suspend fun generateSuggestions(response: String): List<String> {
        val prompt = "Based on this AI response, generate 2 short follow-up questions that a student might ask. Keep them concise and relevant.\n\nResponse: $response\n\nQuestions:"
        val suggestionsText = geminiHelper.getResponse(prompt)
        return suggestionsText.lines().filter { it.isNotBlank() && it.trim().isNotEmpty() }.take(3).map { it.trim() }
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

    private fun showNotesBottomSheet() {
        val bottomSheetDialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_notes, null)
        bottomSheetDialog.setContentView(view)

        val rvNotes = view.findViewById<RecyclerView>(R.id.rvNotes)
        val notesList = mutableListOf<StudyNote>()
        val notesAdapter = NotesAdapter(notesList) { note ->
            selectNote(note)
            bottomSheetDialog.dismiss()
        }
        rvNotes.adapter = notesAdapter
        rvNotes.layoutManager = LinearLayoutManager(this)

        val userId = auth.currentUser?.uid ?: return
        firestore.collection("Notes").document(userId).collection("UserNotes")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { result ->
                notesList.clear()
                for (doc in result.documents) {
                    val note = doc.toObject(StudyNote::class.java)
                    if (note != null) notesList.add(note)
                }
                notesAdapter.notifyDataSetChanged()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to load notes: ${e.message}", Toast.LENGTH_SHORT).show()
            }

        bottomSheetDialog.show()
    }

    private fun selectNote(note: StudyNote) {
        lifecycleScope.launch {
            if (note.type == "pdf") {
                activeDocumentText = extractTextFromPdf(note.url)
                activeDocumentName = note.name
                activeImageBitmap = null
            } else {
                activeImageBitmap = downloadImage(note.url)
                activeDocumentText = null
                activeDocumentName = note.name
            }
            updateAttachmentBanner()
        }
    }

    private fun pickFile(type: String) {
        filePickerLauncher.launch(type)
    }

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handleFileSelection(it) }
    }

    private fun handleFileSelection(uri: Uri) {
        val fileName = getFileName(uri)
        val mimeType = contentResolver.getType(uri)

        lifecycleScope.launch {
            if (mimeType == "application/pdf") {
                activeDocumentText = extractTextFromLocalPdf(uri)
                activeDocumentName = fileName
                activeImageBitmap = null
            } else if (mimeType?.startsWith("image/") == true) {
                activeImageBitmap = getBitmapFromUri(uri)
                activeDocumentText = null
                activeDocumentName = fileName
            }
            updateAttachmentBanner()
        }
    }

    private suspend fun extractTextFromLocalPdf(uri: Uri): String = withContext(Dispatchers.IO) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val document = PDDocument.load(inputStream)
            val stripper = PDFTextStripper()
            val text = stripper.getText(document)
            document.close()
            text.take(12000)
        } catch (e: Exception) {
            "Could not extract text from PDF: ${e.message}"
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

    private fun getFileName(uri: Uri): String {
        var name = "Unknown"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    private fun updateAttachmentBanner() {
        val layoutBanner = findViewById<LinearLayout>(R.id.layoutAttachmentBanner)
        val tvInfo = findViewById<TextView>(R.id.tvAttachmentInfo)

        if (activeDocumentName != null) {
            tvInfo.text = "Using document: $activeDocumentName"
            layoutBanner.visibility = View.VISIBLE
        } else {
            layoutBanner.visibility = View.GONE
        }
    }

    private fun clearAttachment() {
        activeDocumentText = null
        activeDocumentName = null
        activeImageBitmap = null
        updateAttachmentBanner()
    }

    inner class ChatAdapter(private val chatMessages: List<ChatMessage>) :
        RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

        inner class ChatViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvMessage: TextView = view.findViewById(R.id.tvMessage)
            val cardMessage: CardView = view.findViewById(R.id.cardMessage)
            val layoutContainer: LinearLayout = view as LinearLayout
            val chipGroupSuggestions: ChipGroup = view.findViewById(R.id.chipGroupSuggestions)
            val tvShowMore: TextView = view.findViewById(R.id.tvShowMore)
            val layoutActionToolbar: LinearLayout = view.findViewById(R.id.layoutActionToolbar)
            val btnCopy: TextView = view.findViewById(R.id.btnCopy)
            val btnRegenerate: TextView = view.findViewById(R.id.btnRegenerate)
            val btnHelpful: TextView = view.findViewById(R.id.btnHelpful)
            val btnNotHelpful: TextView = view.findViewById(R.id.btnNotHelpful)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_message, parent, false)
            return ChatViewHolder(view)
        }

        override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
            val message = chatMessages[position]
            holder.tvMessage.text = message.text
            val params = holder.cardMessage.layoutParams as LinearLayout.LayoutParams
            if (message.isUser) {
                holder.layoutContainer.gravity = Gravity.END
                holder.cardMessage.setCardBackgroundColor(getColor(R.color.primary))
                holder.tvMessage.setTextColor(getColor(R.color.white))
                params.marginStart = 100
                params.marginEnd = 0
                holder.chipGroupSuggestions.visibility = View.GONE
                holder.layoutActionToolbar.visibility = View.GONE
                holder.tvShowMore.visibility = View.GONE
            } else {
                holder.layoutContainer.gravity = Gravity.START
                holder.cardMessage.setCardBackgroundColor(getColor(R.color.white))
                holder.tvMessage.setTextColor(getColor(R.color.text_main))
                params.marginStart = 0
                params.marginEnd = 100
                holder.tvMessage.alpha = if (message.isTyping) 0.5f else 1.0f

                // Collapsible text
                val isLongText = message.text.length > 150
                if (isLongText && !message.isTyping) {
                    holder.tvMessage.maxLines = 15
                    holder.tvShowMore.visibility = View.VISIBLE
                    holder.tvShowMore.text = "Show More"
                    holder.tvShowMore.setOnClickListener {
                        if (holder.tvMessage.maxLines == 15) {
                            holder.tvMessage.maxLines = Int.MAX_VALUE
                            holder.tvShowMore.text = "Show Less"
                        } else {
                            holder.tvMessage.maxLines = 15
                            holder.tvShowMore.text = "Show More"
                        }
                    }
                } else {
                    holder.tvMessage.maxLines = Int.MAX_VALUE
                    holder.tvShowMore.visibility = View.GONE
                }

                // Action toolbar
                if (!message.isTyping && message.messageType == MessageType.AI) {
                    holder.layoutActionToolbar.visibility = View.VISIBLE
                    holder.btnCopy.setOnClickListener {
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("AI Response", message.text)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(holder.itemView.context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                    holder.btnRegenerate.setOnClickListener {
                        // Find the previous user message
                        for (i in position - 1 downTo 0) {
                            if (chatMessages[i].isUser) {
                                sendMessage(chatMessages[i].text)
                                break
                            }
                        }
                    }
                    holder.btnHelpful.setOnClickListener {
                        Toast.makeText(holder.itemView.context, "Thank you for the feedback!", Toast.LENGTH_SHORT).show()
                    }
                    holder.btnNotHelpful.setOnClickListener {
                        Toast.makeText(holder.itemView.context, "We'll work on improving!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    holder.layoutActionToolbar.visibility = View.GONE
                }

                if (message.isTyping || message.suggestions.isEmpty()) {
                    holder.chipGroupSuggestions.visibility = View.GONE
                } else {
                    holder.chipGroupSuggestions.visibility = View.VISIBLE
                    holder.chipGroupSuggestions.removeAllViews()
                    for (suggestion in message.suggestions) {
                        val chip = Chip(holder.itemView.context).apply {
                            text = suggestion
                            setChipBackgroundColorResource(R.color.primary_light)
                            setTextColor(getColor(R.color.primary))
                            setOnClickListener {
                                sendMessage(suggestion)
                            }
                        }
                        holder.chipGroupSuggestions.addView(chip)
                    }
                }
            }
            holder.cardMessage.layoutParams = params
        }

        override fun getItemCount() = chatMessages.size
    }

    data class ChatMessage(val text: String, val isUser: Boolean, val isTyping: Boolean = false, val suggestions: List<String> = emptyList(), val messageType: MessageType = MessageType.AI)

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

            val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
            holder.tvDate.text = "Uploaded: ${sdf.format(Date(note.timestamp))}"

            if (note.type == "pdf") {
                holder.ivIcon.setImageResource(android.R.drawable.ic_menu_save)
                holder.ivIcon.setColorFilter(getColor(R.color.accent_purple))
            } else {
                holder.ivIcon.setImageResource(android.R.drawable.ic_menu_camera)
                holder.ivIcon.setColorFilter(getColor(R.color.accent_orange))
            }

            holder.itemView.setOnClickListener { onClick(note) }
        }

        override fun getItemCount() = notes.size
    }
}

package com.kartik.aistudyassistant.ui.chat

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.content.Intent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
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
import com.kartik.aistudyassistant.AIStudyAssistanceApp
import com.kartik.aistudyassistant.BuildConfig
import com.kartik.aistudyassistant.core.utils.GeminiHelper
import com.kartik.aistudyassistant.data.local.ChatSessionPrefs
import com.kartik.aistudyassistant.data.local.ContinueLearningPrefs
import com.kartik.aistudyassistant.data.local.PersistedChatMessage
import com.kartik.aistudyassistant.data.local.PersistedChatSession
import com.kartik.aistudyassistant.data.local.PersistedChatSessionSummary
import com.kartik.aistudyassistant.R
import com.kartik.aistudyassistant.ui.flashcard.FlashcardSetupActivity
import com.kartik.aistudyassistant.ui.quiz.QuizSetupActivity
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
import java.util.concurrent.TimeUnit

class ChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "extra_chat_session_id"
        private const val STATE_MESSAGES = "state_messages"
        private const val STATE_MODE = "state_mode"
        private const val STATE_ACTIVE_DOC_TEXT = "state_active_doc_text"
        private const val STATE_ACTIVE_DOC_NAME = "state_active_doc_name"
        private const val STATE_ACTIVE_IMAGE_SOURCE = "state_active_image_source"
        private const val STATE_LAST_TOPIC = "state_last_topic"
        private const val STATE_DRAFT_TEXT = "state_draft_text"

        private var hasOpenedChatInCurrentProcess: Boolean = false
    }

    private lateinit var rvChat: RecyclerView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: FloatingActionButton
    private lateinit var btnAttach: ImageButton
    private lateinit var tvChatSubtitle: TextView
    private lateinit var ivChatMenu: ImageView
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
    private var explicitTopic: String? = null
    private var pendingExamPaperGeneration: Boolean = false
    private var pendingGuidedGenerationMode: StudyMode? = null
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
        setupChat(showGreeting = false)
        setupStudyModes()

        val hasIntentPrefill = hasIntentPrefillPayload()
        val requestedSessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        val isFirstChatOpenInProcess = !hasOpenedChatInCurrentProcess
        hasOpenedChatInCurrentProcess = true

        explicitTopic = intent.getStringExtra("PREFILLED_TOPIC")
        if (!explicitTopic.isNullOrBlank()) {
            lastTopicQuery = explicitTopic
        }

        if (savedInstanceState != null) {
            restoreChatState(savedInstanceState)
            if (messages.isEmpty()) {
                showInitialGreetingIfNeeded()
            }
        } else if (requestedSessionId.isNotBlank()) {
            openRequestedSessionOrFallback(requestedSessionId)
        } else if (hasIntentPrefill) {
            val topicToSearch = explicitTopic.orEmpty()
            val existingId = if (topicToSearch.isNotBlank()) ChatSessionPrefs.findSessionIdByTopic(this, topicToSearch) else null
            
            if (existingId != null) {
                openRequestedSessionOrFallback(existingId)
            } else {
                startFreshSession(createNewSession = true)
            }
            // Populate intent content into the session (new or existing)
            handlePrefilledIntentPayload()
        } else if (isFirstChatOpenInProcess) {
            showSessionChoiceDialogIfNeeded()
        } else {
            restoreLastSessionOrStartFresh()
        }
    }

    private fun openRequestedSessionOrFallback(sessionId: String) {
        val changed = ChatSessionPrefs.setActiveSession(this, sessionId)
        if (!changed) {
            Toast.makeText(this, "Selected chat session is unavailable", Toast.LENGTH_SHORT).show()
            startFreshSession(createNewSession = true)
            return
        }

        val snapshot = ChatSessionPrefs.readSession(this, sessionId)
        if (snapshot == null) {
            Toast.makeText(this, "Selected chat session is unavailable", Toast.LENGTH_SHORT).show()
            startFreshSession(createNewSession = true)
            return
        }

        restorePersistedSession(snapshot)
    }

    override fun onPause() {
        super.onPause()
        persistSessionSnapshot()
    }

    private fun initViews() {
        rvChat = findViewById(R.id.rvChat)
        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)
        btnAttach = findViewById(R.id.btnAttach)
        tvChatSubtitle = findViewById(R.id.tvChatSubtitle)
        layoutAttachmentBanner = findViewById(R.id.layoutAttachmentBanner)
        tvAttachmentInfo = findViewById(R.id.tvAttachmentInfo)
        ivChatMenu = findViewById(R.id.ivChatMenu)

        findViewById<ImageView>(R.id.ivBack).setOnClickListener {
            finish()
        }

        findViewById<ImageView>(R.id.ivRemoveAttachment).setOnClickListener {
            clearAttachment()
        }

        ivChatMenu.setOnClickListener {
            showChatActionMenu()
        }

        btnSend.setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                sendMessage(text)
                etMessage.text.clear()
            }
        }

        etMessage.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                persistSessionSnapshot()
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })

        btnAttach.setOnClickListener {
            pendingGuidedGenerationMode = null
            showAttachmentBottomSheet()
        }
    }

    private fun setupChat(showGreeting: Boolean) {
        chatAdapter = ChatAdapter(messages)
        rvChat.adapter = chatAdapter
        rvChat.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }

        if (showGreeting) {
            showInitialGreetingIfNeeded()
        }
    }

    private fun hasIntentPrefillPayload(): Boolean {
        return !intent.getStringExtra("PREFILLED_PROMPT").isNullOrBlank() ||
            !intent.getStringExtra("FILE_URL").isNullOrBlank()
    }

    private fun handlePrefilledIntentPayload() {
        val prompt = intent.getStringExtra("PREFILLED_PROMPT")
        val fileUrl = intent.getStringExtra("FILE_URL")
        val fileType = intent.getStringExtra("FILE_TYPE")

        if (fileUrl != null) {
            if (fileType == "pdf") {
                lifecycleScope.launch {
                    activeDocumentText = extractTextFromPdf(fileUrl)
                    activeDocumentName = intent.getStringExtra("FILE_NAME") ?: "Uploaded PDF"
                    activeImageSource = fileUrl // Store the URL reference
                    updateAttachmentBanner()
                    persistSessionSnapshot()
                }
            } else {
                lifecycleScope.launch {
                    activeImageSource = fileUrl
                    activeDocumentName = intent.getStringExtra("FILE_NAME") ?: "Uploaded Image"
                    activeImageBitmap = downloadImage(fileUrl)
                    updateAttachmentBanner()
                    persistSessionSnapshot()
                }
            }
        }

        if (prompt != null) {
            etMessage.setText(prompt)
        } 
        
        if (messages.isEmpty()) {
            showInitialGreetingIfNeeded()
        }
    }

    private fun showInitialGreetingIfNeeded() {
        if (messages.isEmpty()) {
            addMessage(
                ChatMessage(
                    "Hello! I'm your AI Study Assistant. How can I help you today?",
                    false,
                    messageType = MessageType.SYSTEM
                )
            )
        }
    }

    private fun showSessionChoiceDialogIfNeeded() {
        val snapshot = ChatSessionPrefs.read(this)
        if (snapshot == null || snapshot.messages.isEmpty()) {
            askForNewSessionTopic()
            return
        }

        val ageMinutes = if (snapshot.updatedAtEpochMs > 0L) {
            TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - snapshot.updatedAtEpochMs)
                .coerceAtLeast(0L)
        } else {
            0L
        }
        val detailParts = mutableListOf<String>()
        detailParts.add("Messages: ${snapshot.messages.size}")
        if (snapshot.mode.isNotBlank()) {
            detailParts.add("Mode: ${snapshot.mode}")
        }
        if (snapshot.activeDocumentName.isNotBlank()) {
            detailParts.add("File: ${snapshot.activeDocumentName}")
        }
        if (snapshot.lastTopicQuery.isNotBlank()) {
            detailParts.add("Topic: ${snapshot.lastTopicQuery}")
        }
        if (ageMinutes > 0L) {
            detailParts.add("Updated: ${ageMinutes}m ago")
        }

        showResumeSessionBottomSheet(
            summaryText = detailParts.joinToString("\n"),
            onContinue = {
                restorePersistedSession(snapshot)
            },
            onNewChat = {
                askForNewSessionTopic()
            }
        )
    }

    private fun restoreLastSessionOrStartFresh() {
        val snapshot = ChatSessionPrefs.read(this)
        if (snapshot != null && snapshot.messages.isNotEmpty()) {
            restorePersistedSession(snapshot)
        } else {
            askForNewSessionTopic()
        }
    }

    private fun startFreshSession(createNewSession: Boolean) {
        if (createNewSession) {
            ChatSessionPrefs.createNewSession(this)
        }

        messages.clear()
        chatAdapter.notifyDataSetChanged()

        currentMode = StudyMode.EXPLAIN
        pendingExamPaperGeneration = false
        pendingGuidedGenerationMode = null
        
        // Preserve explicitTopic for mastery binding if present
        lastTopicQuery = if (!explicitTopic.isNullOrBlank()) explicitTopic else null
        
        etMessage.setText("")
        clearAttachment()
        applySelectedModeChip()
        showInitialGreetingIfNeeded()
        persistSessionSnapshot()
    }

    private fun showChatActionMenu() {
        val candidates = ChatSessionPrefs.getRecentSessions(this, includeActive = false)
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_chat_actions, null)
        dialog.setContentView(view)
        (application as? AIStudyAssistanceApp)?.bindSensorUiToBottomSheet(this, dialog)

        val btnNewChat = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnStartNewChat)
        val btnOpenPrevious = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnOpenPreviousChats)
        val tvHint = view.findViewById<TextView>(R.id.tvPreviousChatHint)

        val count = candidates.size
        tvHint.text = if (count > 0) {
            getString(R.string.chat_actions_previous_available, count)
        } else {
            getString(R.string.chat_actions_previous_empty)
        }
        btnOpenPrevious.isEnabled = count > 0
        btnOpenPrevious.alpha = if (count > 0) 1f else 0.5f

        btnNewChat.setOnClickListener {
            dialog.dismiss()
            askForNewSessionTopic()
        }

        btnOpenPrevious.setOnClickListener {
            dialog.dismiss()
            openPreviousChatSession()
        }

        dialog.show()
    }

    private fun openPreviousChatSession(): Boolean {
        val candidates = ChatSessionPrefs.getRecentSessions(this, includeActive = false)
        if (candidates.isEmpty()) {
            Toast.makeText(this, "No previous chat session found", Toast.LENGTH_SHORT).show()
            return false
        }

        showSessionPickerBottomSheet(candidates)

        return true
    }

    private fun showSessionPickerBottomSheet(candidates: List<PersistedChatSessionSummary>) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_chat_sessions, null)
        dialog.setContentView(view)
        (application as? AIStudyAssistanceApp)?.bindSensorUiToBottomSheet(this, dialog)

        val rvSessions = view.findViewById<RecyclerView>(R.id.rvSessionChooser)
        val tvEmpty = view.findViewById<TextView>(R.id.tvSessionChooserEmpty)
        val sessionItems = candidates.toMutableList()

        fun updateEmptyState() {
            tvEmpty.visibility = if (sessionItems.isEmpty()) View.VISIBLE else View.GONE
            rvSessions.visibility = if (sessionItems.isEmpty()) View.GONE else View.VISIBLE
        }

        rvSessions.layoutManager = LinearLayoutManager(this)
        rvSessions.adapter = SessionChooserAdapter(
            sessions = sessionItems,
            onOpen = { selected ->
                val changed = ChatSessionPrefs.setActiveSession(this, selected.id)
                if (!changed) {
                    Toast.makeText(this, "Could not open this session", Toast.LENGTH_SHORT).show()
                    return@SessionChooserAdapter
                }

                val snapshot = ChatSessionPrefs.readSession(this, selected.id)
                if (snapshot == null) {
                    Toast.makeText(this, "Selected session is unavailable", Toast.LENGTH_SHORT).show()
                    return@SessionChooserAdapter
                }

                dialog.dismiss()
                restorePersistedSession(snapshot)
            },
            onDelete = { selected, position ->
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.chat_delete_session_title))
                    .setMessage(getString(R.string.chat_delete_session_message, selected.title))
                    .setPositiveButton(getString(R.string.chat_delete_session_confirm)) { _, _ ->
                        val deleted = ChatSessionPrefs.deleteSession(this, selected.id)
                        if (!deleted) {
                            Toast.makeText(this, getString(R.string.chat_delete_session_failed), Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                        sessionItems.removeAt(position)
                        rvSessions.adapter?.notifyItemRemoved(position)
                        updateEmptyState()
                    }
                    .setNegativeButton(getString(R.string.chat_delete_session_cancel), null)
                    .show()
            }
        )

        updateEmptyState()

        dialog.show()
    }

    private fun showResumeSessionBottomSheet(
        summaryText: String,
        onContinue: () -> Unit,
        onNewChat: () -> Unit
    ) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_chat_resume, null)
        dialog.setContentView(view)
        (application as? AIStudyAssistanceApp)?.bindSensorUiToBottomSheet(this, dialog)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)

        view.findViewById<TextView>(R.id.tvChatResumeSummary).text = summaryText
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnResumeChat).setOnClickListener {
            dialog.dismiss()
            onContinue()
        }
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnStartFreshChat).setOnClickListener {
            dialog.dismiss()
            onNewChat()
        }

        dialog.show()
    }

    private fun buildSessionChooserLine(summary: PersistedChatSessionSummary): String {
        val dateText = if (summary.updatedAtEpochMs > 0L) {
            SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(summary.updatedAtEpochMs))
        } else {
            "Unknown time"
        }

        val modeText = summary.mode.ifBlank { "EXPLAIN" }
        return "${summary.title}\n${summary.messageCount} msgs • $modeText • $dateText"
    }

    inner class SessionChooserAdapter(
        private val sessions: MutableList<PersistedChatSessionSummary>,
        private val onOpen: (PersistedChatSessionSummary) -> Unit,
        private val onDelete: (PersistedChatSessionSummary, Int) -> Unit
    ) : RecyclerView.Adapter<SessionChooserAdapter.SessionChooserViewHolder>() {

        inner class SessionChooserViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvTitle: TextView = view.findViewById(R.id.tvSessionTitle)
            val tvMeta: TextView = view.findViewById(R.id.tvSessionMeta)
            val btnDelete: com.google.android.material.button.MaterialButton = view.findViewById(R.id.btnDeleteSession)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionChooserViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chat_session_option, parent, false)
            return SessionChooserViewHolder(view)
        }

        override fun onBindViewHolder(holder: SessionChooserViewHolder, position: Int) {
            val item = sessions[position]
            holder.tvTitle.text = item.title
            holder.tvMeta.text = buildSessionChooserLine(item).substringAfter("\n", "")
            holder.itemView.setOnClickListener {
                onOpen(item)
            }
            holder.btnDelete.setOnClickListener {
                val adapterPosition = holder.bindingAdapterPosition
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    onDelete(sessions[adapterPosition], adapterPosition)
                }
            }
        }

        override fun getItemCount(): Int = sessions.size
    }

    private fun restorePersistedSession(snapshot: PersistedChatSession) {
        messages.clear()
        messages.addAll(snapshot.messages.map { it.toRuntimeMessage() })
        chatAdapter.notifyDataSetChanged()
        if (messages.isNotEmpty()) {
            rvChat.scrollToPosition(messages.size - 1)
        }

        currentMode = runCatching { StudyMode.valueOf(snapshot.mode) }.getOrElse { StudyMode.EXPLAIN }
        pendingExamPaperGeneration = false
        pendingGuidedGenerationMode = null
        lastTopicQuery = snapshot.lastTopicQuery.takeIf { it.isNotBlank() }
        etMessage.setText(snapshot.draftText)

        activeDocumentText = snapshot.activeDocumentText.takeIf { it.isNotBlank() }
        activeDocumentName = snapshot.activeDocumentName.takeIf { it.isNotBlank() }
        activeImageSource = snapshot.activeImageSource.takeIf { it.isNotBlank() }
        activeImageBitmap = null

        applySelectedModeChip()
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

        if (messages.isEmpty()) {
            showInitialGreetingIfNeeded()
        }
        persistSessionSnapshot()
    }

    private fun updateChatTopicSubtitle() {
        if (!::tvChatSubtitle.isInitialized) return
        val currentTopic = resolveRecentChatTopic(messages.filter { !it.isTyping }.map { it.toPersistedMessage() })
        tvChatSubtitle.text = currentTopic
        tvChatSubtitle.visibility = if (currentTopic.isNotBlank()) View.VISIBLE else View.GONE
    }

    private fun persistSessionSnapshot() {
        if (!::chatAdapter.isInitialized) return
        updateChatTopicSubtitle()

        val persistedMessages = messages
            .filter { !it.isTyping }
            .map { it.toPersistedMessage() }

        val hasAnyState = persistedMessages.isNotEmpty() ||
            !etMessage.text.isNullOrBlank() ||
            !activeDocumentName.isNullOrBlank() ||
            !activeImageSource.isNullOrBlank()

        if (!hasAnyState) return

        ChatSessionPrefs.save(
            context = this,
            snapshot = PersistedChatSession(
                mode = currentMode.name,
                lastTopicQuery = lastTopicQuery.orEmpty(),
                draftText = etMessage.text?.toString().orEmpty(),
                activeDocumentText = activeDocumentText.orEmpty(),
                activeDocumentName = activeDocumentName.orEmpty(),
                activeImageSource = activeImageSource.orEmpty(),
                updatedAtEpochMs = System.currentTimeMillis(),
                messages = persistedMessages
            )
        )

        val hasUserMessage = persistedMessages.any { it.isUser }
        if (hasUserMessage) {
            ContinueLearningPrefs.saveChatActivity(
                context = this,
                topic = resolveRecentChatTopic(persistedMessages),
                sessionId = ChatSessionPrefs.getActiveSessionId(this).orEmpty(),
                messageCount = persistedMessages.count { it.isUser },
                source = if (!activeDocumentName.isNullOrBlank()) ContinueLearningPrefs.SOURCE_NOTES else ContinueLearningPrefs.SOURCE_TOPIC,
                noteName = activeDocumentName.orEmpty(),
                noteUrl = activeImageSource ?: activeDocumentName.orEmpty()
            )
        }
    }

    private fun resolveRecentChatTopic(persistedMessages: List<PersistedChatMessage>): String {
        if (!lastTopicQuery.isNullOrBlank()) {
            return lastTopicQuery.orEmpty().trim().take(50)
        }

        if (!explicitTopic.isNullOrBlank()) {
            return explicitTopic.orEmpty()
        }

        val firstUserMessage = persistedMessages
            .firstOrNull { it.isUser }
            ?.text
            .orEmpty()
            .trim()

        if (firstUserMessage.isNotBlank()) {
            return firstUserMessage.take(50)
        }

        if (!activeDocumentName.isNullOrBlank()) {
            return activeDocumentName.orEmpty().trim().take(50)
        }

        return "General Chat"
    }

    private fun ChatMessage.toPersistedMessage(): PersistedChatMessage {
        return PersistedChatMessage(
            text = text,
            isUser = isUser,
            messageType = messageType.name,
            suggestions = suggestions,
            isHelpful = isHelpful
        )
    }

    private fun PersistedChatMessage.toRuntimeMessage(): ChatMessage {
        val mappedType = runCatching { MessageType.valueOf(messageType) }.getOrElse {
            if (isUser) MessageType.USER else MessageType.AI
        }
        return ChatMessage(
            text = text,
            isUser = isUser,
            isTyping = false,
            suggestions = suggestions,
            messageType = mappedType,
            isHelpful = isHelpful
        )
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

        updateChatTopicSubtitle()

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

    private fun sendMessage(
        text: String,
        useAttachment: Boolean = true,
        questionOverride: String? = null
    ) {
        if (!useAttachment) {
            // User chose topic-only generation even if a file was attached.
            activeDocumentText = null
            activeDocumentName = null
            activeImageBitmap = null
            activeImageSource = null
            updateAttachmentBanner()
        }
        if (lastTopicQuery.isNullOrBlank() && text.isNotBlank() && activeDocumentName == null) {
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
            val modePrompt = getModePrompt(currentMode, questionOverride ?: text)
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
                if (index in messages.indices) {
                    messages[index] = ChatMessage(finalResponse, false, messageType = MessageType.AI)
                    chatAdapter.notifyItemChanged(index)
                    rvChat.scrollToPosition(messages.size - 1)
                    persistSessionSnapshot()
                }
            } catch (e: Exception) {
                if (index in messages.indices) {
                    messages[index] = ChatMessage("Error: ${e.message ?: "Something went wrong"}", false, messageType = MessageType.AI)
                    chatAdapter.notifyItemChanged(index)
                    persistSessionSnapshot()
                }
            }
        }
    }

    private fun regenerateResponseAt(targetIndex: Int) {
        if (targetIndex !in messages.indices) return

        val target = messages[targetIndex]
        if (target.isUser || target.isTyping || target.messageType != MessageType.AI) return

        val prompt = findLatestUserPromptBefore(targetIndex)
        if (prompt.isNullOrBlank()) {
            Toast.makeText(this, "No prompt found to regenerate", Toast.LENGTH_SHORT).show()
            return
        }

        // Regenerate in-place so the same AI bubble gets refreshed.
        messages[targetIndex] = ChatMessage("AI is thinking...", false, isTyping = true, messageType = MessageType.SYSTEM)
        chatAdapter.notifyItemChanged(targetIndex)

        lifecycleScope.launch {
            val context = buildContext()
            val systemPrompt = """
You are an AI study assistant.
Explain concepts clearly.
Use bullet points.
Avoid markdown formatting.
Keep answers concise.
""".trimIndent()
            val modePrompt = getModePrompt(currentMode, prompt)

            if (activeDocumentText == null && activeImageBitmap == null && !activeImageSource.isNullOrBlank()) {
                activeImageBitmap = loadBitmapFromSource(activeImageSource)
            }

            val fullPrompt = if (activeDocumentText != null) {
                "$systemPrompt\n\nUse the following study material to answer.\n\nMaterial:\n$activeDocumentText\n\nQuestion:\n$modePrompt"
            } else {
                "$systemPrompt\n\nContext:\n$context\n\n$modePrompt"
            }

            try {
                if (activeImageBitmap != null) {
                    geminiHelper.getResponseWithImageStream(fullPrompt, activeImageBitmap!!).collect { partialResponse ->
                        val cleaned = cleanResponse(partialResponse)
                        withContext(Dispatchers.Main) {
                            if (targetIndex in messages.indices) {
                                messages[targetIndex] = ChatMessage(cleaned, false, isTyping = true, messageType = MessageType.AI)
                                chatAdapter.notifyItemChanged(targetIndex)
                            }
                        }
                    }
                } else {
                    geminiHelper.getResponseStream(fullPrompt).collect { partialResponse ->
                        val cleaned = cleanResponse(partialResponse)
                        withContext(Dispatchers.Main) {
                            if (targetIndex in messages.indices) {
                                messages[targetIndex] = ChatMessage(cleaned, false, isTyping = true, messageType = MessageType.AI)
                                chatAdapter.notifyItemChanged(targetIndex)
                            }
                        }
                    }
                }

                val finalResponse = messages.getOrNull(targetIndex)?.text.orEmpty().ifBlank {
                    "Sorry, I could not generate a response. Please try again."
                }
                if (targetIndex in messages.indices) {
                    messages[targetIndex] = ChatMessage(finalResponse, false, messageType = MessageType.AI)
                    chatAdapter.notifyItemChanged(targetIndex)
                    rvChat.scrollToPosition(messages.size - 1)
                    persistSessionSnapshot()
                }
            } catch (e: Exception) {
                if (targetIndex in messages.indices) {
                    messages[targetIndex] = ChatMessage("Error: ${e.message ?: "Something went wrong"}", false, messageType = MessageType.AI)
                    chatAdapter.notifyItemChanged(targetIndex)
                    persistSessionSnapshot()
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
                        persistSessionSnapshot()
                        return@launch
                    }
                }
                val finalResponse = messages[index].text
                messages[index] = ChatMessage(finalResponse, false, messageType = MessageType.AI)
                chatAdapter.notifyItemChanged(index)
                rvChat.scrollToPosition(messages.size - 1)
                persistSessionSnapshot()
            } catch (e: Exception) {
                val index = messages.indexOf(typingMessage)
                if (index != -1) {
                    messages[index] = ChatMessage("Error: ${e.message}", false, messageType = MessageType.AI)
                    chatAdapter.notifyItemChanged(index)
                    persistSessionSnapshot()
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
        persistSessionSnapshot()
    }

    private fun buildContext(): String {
        val recentMessages = messages.filter { !it.isTyping }.takeLast(6)
        return recentMessages.joinToString("\n") { if (it.isUser) "User: ${it.text}" else "AI: ${it.text}" }
    }

    private fun findLatestUserPromptBefore(index: Int): String? {
        if (index <= 0 || index > messages.lastIndex) return null
        for (i in index - 1 downTo 0) {
            val message = messages[i]
            if (message.isUser && message.text.isNotBlank()) {
                return message.text
            }
        }
        return null
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
            pendingGuidedGenerationMode = null
            currentMode = StudyMode.EXPLAIN
            pendingExamPaperGeneration = false
            showGuidedGenerationSheet(StudyMode.EXPLAIN)
            persistSessionSnapshot()
        }
        chipShortNotesMode.setOnClickListener {
            pendingGuidedGenerationMode = null
            currentMode = StudyMode.SHORT_NOTES
            pendingExamPaperGeneration = false
            showGuidedGenerationSheet(StudyMode.SHORT_NOTES)
            persistSessionSnapshot()
        }
        chipExamAnswer.setOnClickListener {
            pendingGuidedGenerationMode = null
            currentMode = StudyMode.EXAM_ANSWER
            pendingExamPaperGeneration = true
            showExamAnswerAttachmentSheet()
            persistSessionSnapshot()
        }
        chipQuiz.setOnClickListener {
            pendingGuidedGenerationMode = null
            currentMode = StudyMode.QUIZ
            pendingExamPaperGeneration = false
            showGuidedGenerationSheet(StudyMode.QUIZ)
            persistSessionSnapshot()
        }
        chipFlashcards.setOnClickListener {
            pendingGuidedGenerationMode = null
            currentMode = StudyMode.FLASHCARDS
            pendingExamPaperGeneration = false
            showGuidedGenerationSheet(StudyMode.FLASHCARDS)
            persistSessionSnapshot()
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

    private fun maybeAutoGenerateGuidedModeFromAttachment() {
        val mode = pendingGuidedGenerationMode ?: return
        if (activeDocumentName.isNullOrBlank()) return
        
        if (mode == StudyMode.QUIZ || mode == StudyMode.FLASHCARDS) {
            val fileName = activeDocumentName ?: "Attached File"
            pendingGuidedGenerationMode = null
            startModeIntent(mode, "notes", fileName)
            return
        }

        pendingGuidedGenerationMode = null
        currentMode = mode
        applySelectedModeChip()
        val displayText = when (mode) {
            StudyMode.EXPLAIN -> "Explain this attached file."
            StudyMode.SHORT_NOTES -> "Provide short revision notes from this attached file."
            else -> "Generate content from this attached file."
        }
        sendMessage(displayText, questionOverride = "attached file")
    }

    private fun sendModeTopicMessage(mode: StudyMode, topic: String) {
        val visibleText = getModePrompt(mode, topic)
        sendMessage(visibleText, useAttachment = false, questionOverride = topic)
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
                        if (mode == StudyMode.QUIZ || mode == StudyMode.FLASHCARDS) {
                            startModeIntent(mode, "notes", currentDoc)
                        } else {
                            pendingGuidedGenerationMode = null
                            sendMessage("Generate $modeLabel from the selected file.", questionOverride = "attached file")
                        }
                    },
                    onSecondary = {
                        showTopicOrUploadChooser(mode)
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
                        if (mode == StudyMode.QUIZ || mode == StudyMode.FLASHCARDS) {
                            startModeIntent(mode, "topic", currentTopic)
                        } else {
                            sendModeTopicMessage(mode, currentTopic)
                        }
                    },
                    onSecondary = {
                        showTopicOrUploadChooser(mode)
                    }
                )
            }
            else -> {
                showTopicOrUploadChooser(mode)
            }
        }
    }

    private fun showTopicOrUploadChooser(mode: StudyMode) {
        val (title, message) = when (mode) {
            StudyMode.EXPLAIN -> "Create explanation" to "Choose a topic or upload a file to generate an explanation."
            StudyMode.SHORT_NOTES -> "Create short notes" to "Choose a topic or upload a file to generate short revision notes."
            else -> "Topic or file needed" to "Add a topic or pick a file first."
        }
        showModeBottomSheet(
            title = title,
            message = message,
            primaryText = "Enter Topic",
            secondaryText = "Upload File",
            onPrimary = {
                pendingGuidedGenerationMode = null
                promptTopicForMode(mode)
            },
            onSecondary = {
                pendingGuidedGenerationMode = mode
                showAttachmentBottomSheet()
            }
        )
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
                pendingGuidedGenerationMode = null
                lastTopicQuery = topic
                if (mode == StudyMode.QUIZ || mode == StudyMode.FLASHCARDS) {
                    startModeIntent(mode, "topic", topic)
                } else {
                    sendModeTopicMessage(mode, topic)
                }
            },
            onSecondary = { }
        )
    }

    private fun startModeIntent(mode: StudyMode, source: String, value: String) {
        persistSessionSnapshot()
        val intent = when (mode) {
            StudyMode.QUIZ -> Intent(this, QuizSetupActivity::class.java).apply {
                putExtra(QuizSetupActivity.EXTRA_PREFILL_SOURCE, source) // "topic" or "notes"
                if (source == "topic") {
                    putExtra(QuizSetupActivity.EXTRA_PREFILL_TOPIC, value)
                } else {
                    putExtra(QuizSetupActivity.EXTRA_PREFILL_NOTE_NAME, value)
                }
            }
            StudyMode.FLASHCARDS -> Intent(this, FlashcardSetupActivity::class.java).apply {
                putExtra(FlashcardSetupActivity.EXTRA_SOURCE, source) // "topic" or "notes"
                if (source == "topic") {
                    putExtra(FlashcardSetupActivity.EXTRA_TOPIC_TEXT, value)
                } else {
                    putExtra(FlashcardSetupActivity.EXTRA_PREFILL_NOTE_NAME, value)
                }
            }
            else -> return
        }
        startActivity(intent)
    }

    private fun askForNewSessionTopic() {
        showModeBottomSheet(
            title = "New Chat Topic",
            message = "What topic do you want to learn about? (Used for mastery tracking)",
            primaryText = "Start",
            secondaryText = "Skip (General)",
            showTopicInput = true,
            onPrimary = { topic ->
                explicitTopic = topic
                startFreshSession(createNewSession = true)
            },
            onSecondary = {
                explicitTopic = "General Chat"
                startFreshSession(createNewSession = true)
            },
            onCancel = {
                if (messages.isEmpty()) {
                    explicitTopic = "General Chat"
                    startFreshSession(createNewSession = true)
                }
            }
        )
    }

    private fun showModeBottomSheet(
        title: String,
        message: String,
        primaryText: String,
        secondaryText: String,
        showTopicInput: Boolean = false,
        onPrimary: (String) -> Unit,
        onSecondary: () -> Unit,
        onCancel: (() -> Unit)? = null
    ) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_mode_prompt, null)
        dialog.setContentView(view)
        (application as? AIStudyAssistanceApp)?.bindSensorUiToBottomSheet(this, dialog)

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

        dialog.setOnCancelListener {
            onCancel?.invoke()
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
            val prompt = """
Generate exactly 2 concise follow-up study questions based on the answer below.
Rules:
- Return plain text only.
- One question per line.
- Do not include numbering, bullets, labels, or explanations.
- Keep each question under 12 words.

Answer:
$response
""".trimIndent()
            val suggestionsText = geminiHelper.getResponse(prompt)
            suggestionsText
                .lines()
                .map { sanitizeSuggestion(it) }
                .filter { it.isNotBlank() }
                .distinct()
                .take(2)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun sanitizeSuggestion(raw: String): String {
        var value = raw.trim()
        if (value.isBlank()) return ""

        value = value
            .replace(Regex("^[0-9]+[.)]\\s*"), "")
            .replace(Regex("^[-*•]\\s*"), "")
            .trim()

        val lower = value.lowercase(Locale.US)
        val isMetaLine = value.endsWith(":") ||
            lower.startsWith("here are") ||
            lower.contains("follow-up question") ||
            lower.contains("follow up question") ||
            lower.startsWith("next questions")
        if (isMetaLine) return ""

        if (value.length < 6) return ""

        if (value.endsWith("?")) return value

        val startsLikeQuestion = Regex(
            "^(what|how|why|when|where|which|who|whom|whose|can|could|would|should|is|are|do|does|did|will|have|has|had)\\b",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(value)

        if (!startsLikeQuestion) return ""

        return "$value?"
    }

    private fun canRequestSuggestions(message: ChatMessage): Boolean {
        if (message.isUser || message.isTyping || message.messageType != MessageType.AI) return false
        val text = message.text.trim()
        if (text.isBlank()) return false
        if (text.startsWith("Error:", ignoreCase = true)) return false
        if (text.startsWith("Sorry, I could not generate", ignoreCase = true)) return false
        return true
    }

    private fun requestSuggestionsForMessage(targetIndex: Int) {
        if (targetIndex !in messages.indices) return

        val current = messages[targetIndex]
        if (!canRequestSuggestions(current) || current.isSuggestionsLoading) return

        messages[targetIndex] = current.copy(isSuggestionsLoading = true)
        chatAdapter.notifyItemChanged(targetIndex)

        lifecycleScope.launch {
            val suggestions = generateSuggestions(current.text)
            withContext(Dispatchers.Main) {
                if (targetIndex !in messages.indices) return@withContext

                val latest = messages[targetIndex]
                if (!canRequestSuggestions(latest)) return@withContext

                messages[targetIndex] = latest.copy(
                    suggestions = suggestions,
                    isSuggestionsLoading = false
                )
                chatAdapter.notifyItemChanged(targetIndex)
                persistSessionSnapshot()

                if (suggestions.isEmpty()) {
                    Toast.makeText(this@ChatActivity, getString(R.string.chat_no_suggestions_available), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showAttachmentBottomSheet() {
        val bottomSheetDialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_attachment, null)
        bottomSheetDialog.setContentView(view)
        (application as? AIStudyAssistanceApp)?.bindSensorUiToBottomSheet(this, bottomSheetDialog)

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
        (application as? AIStudyAssistanceApp)?.bindSensorUiToBottomSheet(this, bottomSheetDialog)

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
                maybeAutoGenerateGuidedModeFromAttachment()
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
            pendingGuidedGenerationMode = null
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
            maybeAutoGenerateGuidedModeFromAttachment()
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
        persistSessionSnapshot()
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
            val scrollSuggestions: HorizontalScrollView = view.findViewById(R.id.scrollSuggestions)
            val tvShowMore: TextView = view.findViewById(R.id.tvShowMore)
            val layoutActionToolbar: LinearLayout = view.findViewById(R.id.layoutActionToolbar)
            val btnCopy: TextView = view.findViewById(R.id.btnCopy)
            val btnRegenerate: TextView = view.findViewById(R.id.btnRegenerate)
            val btnSuggestNext: TextView = view.findViewById(R.id.btnSuggestNext)
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
                    holder.btnSuggestNext.visibility = if (canRequestSuggestions(message)) View.VISIBLE else View.GONE
                    holder.btnSuggestNext.isEnabled = !message.isSuggestionsLoading
                    holder.btnSuggestNext.text = if (message.isSuggestionsLoading) {
                        getString(R.string.chat_suggesting_questions)
                    } else if (message.suggestions.isEmpty()) {
                        getString(R.string.chat_suggest_next_questions)
                    } else {
                        getString(R.string.chat_refresh_questions)
                    }
                    holder.btnHelpful.setImageResource(
                        if (message.isHelpful) android.R.drawable.btn_star_big_on
                        else android.R.drawable.btn_star_big_off
                    )
                    holder.btnHelpful.setColorFilter(
                        getColor(if (message.isHelpful) R.color.accent_orange else R.color.text_secondary)
                    )

                    holder.btnCopy.setOnClickListener {
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AI Response", message.text))
                        Toast.makeText(holder.itemView.context, "Copied", Toast.LENGTH_SHORT).show()
                    }
                    holder.btnRegenerate.setOnClickListener {
                        val adapterPosition = holder.bindingAdapterPosition
                        if (adapterPosition == RecyclerView.NO_POSITION) return@setOnClickListener
                        regenerateResponseAt(adapterPosition)
                    }

                    holder.btnSuggestNext.setOnClickListener {
                        val adapterPosition = holder.bindingAdapterPosition
                        if (adapterPosition == RecyclerView.NO_POSITION) return@setOnClickListener
                        requestSuggestionsForMessage(adapterPosition)
                    }

                    holder.btnHelpful.setOnClickListener {
                        val adapterPosition = holder.bindingAdapterPosition
                        if (adapterPosition == RecyclerView.NO_POSITION) return@setOnClickListener

                        val current = messages.getOrNull(adapterPosition) ?: return@setOnClickListener
                        if (current.isUser || current.messageType != MessageType.AI) return@setOnClickListener

                        messages[adapterPosition] = current.copy(isHelpful = !current.isHelpful)
                        chatAdapter.notifyItemChanged(adapterPosition)
                        persistSessionSnapshot()
                    }
                } else {
                    holder.layoutActionToolbar.visibility = View.GONE
                }

                if (message.suggestions.isNotEmpty()) {
                    holder.scrollSuggestions.visibility = View.VISIBLE
                    holder.chipGroupSuggestions.removeAllViews()
                    for (suggestion in message.suggestions) {
                        val chip = Chip(holder.itemView.context).apply {
                            text = suggestion
                            isSingleLine = true
                            setEnsureMinTouchTargetSize(true)
                            chipMinHeight = resources.getDimension(R.dimen.space_32)
                            chipStartPadding = resources.getDimension(R.dimen.space_12)
                            chipEndPadding = resources.getDimension(R.dimen.space_12)
                            chipStrokeWidth = resources.getDimension(R.dimen.space_2)
                            chipStrokeColor = getColorStateList(R.color.primary)
                            chipBackgroundColor = getColorStateList(R.color.button_bg)
                            setTextColor(getColor(R.color.primary))
                            setOnClickListener { sendMessage(suggestion) }
                        }
                        holder.chipGroupSuggestions.addView(chip)
                    }
                } else {
                    holder.scrollSuggestions.visibility = View.GONE
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
        val isSuggestionsLoading: Boolean = false,
        val messageType: MessageType = MessageType.AI,
        val isHelpful: Boolean = false
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

package com.example.aistudyassistance.Activity

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aistudyassistance.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip

class ChatActivity : AppCompatActivity() {

    private lateinit var rvChat: RecyclerView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: MaterialButton
    private lateinit var chatAdapter: ChatAdapter
    private val messages = mutableListOf<ChatMessage>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        initViews()
        setupChat()
        setupQuickActions()
    }

    private fun initViews() {
        rvChat = findViewById(R.id.rvChat)
        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)

        findViewById<ImageView>(R.id.ivBack).setOnClickListener {
            finish()
        }

        btnSend.setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                sendMessage(text)
                etMessage.text.clear()
            }
        }
    }

    private fun setupChat() {
        chatAdapter = ChatAdapter(messages)
        rvChat.adapter = chatAdapter
        rvChat.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }

        // Add initial AI greeting
        addMessage(ChatMessage("Hello! I'm your AI Study Assistant. How can I help you today?", false))
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
            if (text.isNotEmpty()) {
                onConfirm(text)
            }
            dialog.dismiss()
        }
        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }

        builder.show()
    }

    private fun sendMessage(text: String) {
        addMessage(ChatMessage(text, true))
        
        // Simulate AI response
        rvChat.postDelayed({
            addMessage(ChatMessage("I'm processing your request for: \"$text\". (This is a placeholder response from your AI Assistant)", false))
        }, 1000)
    }

    private fun addMessage(message: ChatMessage) {
        messages.add(message)
        chatAdapter.notifyItemInserted(messages.size - 1)
        rvChat.scrollToPosition(messages.size - 1)
    }

    // --- Data Classes & Adapter ---

    data class ChatMessage(val text: String, val isUser: Boolean)

    inner class ChatAdapter(private val chatMessages: List<ChatMessage>) :
        RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

        inner class ChatViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvMessage: TextView = view.findViewById(R.id.tvMessage)
            val cardMessage: CardView = view.findViewById(R.id.cardMessage)
            val layoutContainer: LinearLayout = view as LinearLayout
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
            } else {
                holder.layoutContainer.gravity = Gravity.START
                holder.cardMessage.setCardBackgroundColor(getColor(R.color.white))
                holder.tvMessage.setTextColor(getColor(R.color.text_main))
                params.marginStart = 0
                params.marginEnd = 100
            }
            holder.cardMessage.layoutParams = params
        }

        override fun getItemCount() = chatMessages.size
    }
}
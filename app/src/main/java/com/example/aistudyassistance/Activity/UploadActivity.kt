package com.example.aistudyassistance.Activity

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aistudyassistance.R
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import java.text.SimpleDateFormat
import java.util.*

class UploadActivity : AppCompatActivity() {

    private lateinit var rvNotes: RecyclerView
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var layoutProgress: FrameLayout
    private lateinit var tvProgressStatus: TextView
    private lateinit var notesAdapter: NotesAdapter
    private val notesList = mutableListOf<StudyNote>()
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance().reference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_upload)

        initViews()
        setupRecyclerView()
        loadUploadedNotes()
    }

    private fun initViews() {
        rvNotes = findViewById(R.id.rvNotes)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        layoutProgress = findViewById(R.id.layoutProgress)
        tvProgressStatus = findViewById(R.id.tvProgressStatus)

        findViewById<ImageView>(R.id.ivBack).setOnClickListener { finish() }

        findViewById<CardView>(R.id.cardUploadPdf).setOnClickListener {
            pickFile("application/pdf")
        }

        findViewById<CardView>(R.id.cardUploadImage).setOnClickListener {
            pickFile("image/*")
        }

        findViewById<View>(R.id.btnUploadFirst).setOnClickListener {
            pickFile("application/pdf")
        }
    }

    private fun setupRecyclerView() {
        notesAdapter = NotesAdapter(notesList) { note ->
            showNoteOptions(note)
        }
        rvNotes.adapter = notesAdapter
        rvNotes.layoutManager = LinearLayoutManager(this)
    }

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { uploadFileToFirebase(it) }
    }

    private fun pickFile(type: String) {
        filePickerLauncher.launch(type)
    }

    private fun uploadFileToFirebase(uri: Uri) {
        val fileName = getFileName(uri)
        val userId = auth.currentUser?.uid ?: return
        
        showProgress("Uploading $fileName...")

        val fileRef = storage.child("user_uploads/$userId/$fileName")
        
        fileRef.putFile(uri)
            .addOnSuccessListener {
                fileRef.downloadUrl.addOnSuccessListener { downloadUrl ->
                    saveNoteMetadata(fileName, downloadUrl.toString())
                }
            }
            .addOnFailureListener { e ->
                hideProgress()
                Toast.makeText(this, "Upload failed: ${e.message}. Check Firebase Storage Rules!", Toast.LENGTH_LONG).show()
            }
            .addOnProgressListener { taskSnapshot ->
                val progress = if (taskSnapshot.totalByteCount > 0) {
                    (100.0 * taskSnapshot.bytesTransferred / taskSnapshot.totalByteCount).toInt()
                } else 0
                tvProgressStatus.text = "Uploading $fileName ($progress%)"
            }
    }

    private fun saveNoteMetadata(fileName: String, downloadUrl: String) {
        val userId = auth.currentUser?.uid ?: return
        val noteId = UUID.randomUUID().toString()
        
        val note = StudyNote(
            id = noteId,
            name = fileName,
            type = if (fileName.lowercase().endsWith(".pdf")) "pdf" else "image",
            url = downloadUrl,
            timestamp = System.currentTimeMillis(),
            userId = userId
        )

        firestore.collection("Notes").document(userId).collection("UserNotes").document(noteId)
            .set(note)
            .addOnSuccessListener {
                hideProgress()
                Toast.makeText(this, "File uploaded and saved to Firestore!", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                hideProgress()
                Toast.makeText(this, "Firestore save failed: ${e.message}. Check Firestore Rules!", Toast.LENGTH_LONG).show()
            }
    }

    private fun loadUploadedNotes() {
        val userId = auth.currentUser?.uid ?: return
        firestore.collection("Notes").document(userId).collection("UserNotes")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { value, error ->
                if (error != null) {
                    Toast.makeText(this, "Permission Denied: Check Firestore Rules!", Toast.LENGTH_LONG).show()
                    return@addSnapshotListener
                }

                notesList.clear()
                if (value != null) {
                    for (doc in value.documents) {
                        val note = doc.toObject(StudyNote::class.java)
                        note?.let { notesList.add(it) }
                    }
                }
                notesAdapter.notifyDataSetChanged()
                
                layoutEmpty.visibility = if (notesList.isEmpty()) View.VISIBLE else View.GONE
            }
    }

    private fun showNoteOptions(note: StudyNote) {
        val options = arrayOf("View File", "Summarize with AI", "Generate Exam Questions", "Create Short Notes")
        AlertDialog.Builder(this)
            .setTitle(note.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openFile(note.url)
                    1 -> openChatWithPrompt("Summarize the key concepts from the study material: ${note.name}. Provide clear bullet points for exam revision.")
                    2 -> openChatWithPrompt("Generate important exam questions with answers based on the study material: ${note.name}.")
                    3 -> openChatWithPrompt("Create short revision notes in bullet points based on the study material: ${note.name}.")
                }
            }
            .show()
    }

    private fun openFile(url: String) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = Uri.parse(url)
        startActivity(intent)
    }

    private fun openChatWithPrompt(prompt: String) {
        val intent = Intent(this, ChatActivity::class.java)
        intent.putExtra("PREFILLED_PROMPT", prompt)
        startActivity(intent)
    }

    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) result = cursor.getString(index)
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) result = result?.substring(cut + 1)
        }
        return result ?: "unnamed_file"
    }

    private fun showProgress(status: String) {
        tvProgressStatus.text = status
        layoutProgress.visibility = View.VISIBLE
    }

    private fun hideProgress() {
        layoutProgress.visibility = View.GONE
    }

    // --- Data Classes & Adapter ---

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
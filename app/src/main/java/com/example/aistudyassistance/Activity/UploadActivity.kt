package com.example.aistudyassistance.Activity

import android.app.Activity
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
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
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
    private val database = FirebaseDatabase.getInstance().reference

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

        // Since we don't have Storage dependency yet, we'll simulate the upload to Database
        // In a real app, you would upload to Firebase Storage first, get the URL, then save to DB.
        
        val noteId = database.child("Notes").child(userId).push().key ?: return
        val note = StudyNote(
            id = noteId,
            name = fileName,
            type = if (fileName.endsWith(".pdf")) "pdf" else "image",
            url = uri.toString(), // Real URL would come from Storage
            timestamp = System.currentTimeMillis(),
            userId = userId
        )

        // Simulated Delay for UX
        rvNotes.postDelayed({
            database.child("Notes").child(userId).child(noteId).setValue(note)
                .addOnSuccessListener {
                    hideProgress()
                    Toast.makeText(this, "Upload successful!", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener { e ->
                    hideProgress()
                    Toast.makeText(this, "Upload failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        }, 1500)
    }

    private fun loadUploadedNotes() {
        val userId = auth.currentUser?.uid ?: return
        database.child("Notes").child(userId).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                notesList.clear()
                for (noteSnapshot in snapshot.children) {
                    val note = noteSnapshot.getValue(StudyNote::class.java)
                    note?.let { notesList.add(it) }
                }
                notesList.sortByDescending { it.timestamp }
                notesAdapter.notifyDataSetChanged()
                
                layoutEmpty.visibility = if (notesList.isEmpty()) View.VISIBLE else View.GONE
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@UploadActivity, "Failed to load: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun showNoteOptions(note: StudyNote) {
        val options = arrayOf("View file", "Summarize with AI", "Generate exam questions", "Create short notes")
        AlertDialog.Builder(this)
            .setTitle(note.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this, "Opening file...", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this, "Generating summary...", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this, "Generating questions...", Toast.LENGTH_SHORT).show()
                    3 -> Toast.makeText(this, "Generating notes...", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
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
            val card: CardView = view as CardView
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
package com.kartik.aistudyassistant.ui.upload

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.widget.NestedScrollView
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.request.ImageResult
import com.kartik.aistudyassistant.R
import com.kartik.aistudyassistant.data.local.ContinueLearningPrefs
import com.kartik.aistudyassistant.ui.chat.ChatActivity
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

class UploadActivity : AppCompatActivity() {

    private lateinit var rvNotes: RecyclerView
    private lateinit var uploadScrollView: NestedScrollView
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var tvEmptyMessage: TextView
    private lateinit var btnUploadFirst: View
    private lateinit var cardActiveUpload: CardView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var progressLoadMore: LinearProgressIndicator
    private lateinit var tvProgressStatus: TextView
    private lateinit var etSearch: TextInputEditText
    private lateinit var chipAll: Chip
    private lateinit var chipPdf: Chip
    private lateinit var chipImages: Chip
    private lateinit var notesAdapter: NotesAdapter
    private val notesList = mutableListOf<StudyNote>()
    private val filteredNotesList = mutableListOf<StudyNote>()
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance().reference
    private val thumbnailExecutor = Executors.newFixedThreadPool(2)
    private val pendingPdfThumbnails = Collections.synchronizedSet(mutableSetOf<String>())
    private val deletingNoteKeys = Collections.synchronizedSet(mutableSetOf<String>())
    private var activeUploadCount = 0
    private var activeFilter = NoteFilter.ALL
    private var searchQuery = ""
    private var isLoadingPage = false
    private var hasMorePages = true
    private var lastVisibleDocument: DocumentSnapshot? = null
    private var isActivityAlive = true
    private val pdfThumbnailCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    companion object {
        private const val PAGE_SIZE = 20L
        private const val PAGINATION_TRIGGER_THRESHOLD_PX = 300
        private const val PAYLOAD_DELETE_STATE = "delete_state"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isActivityAlive = true
        setContentView(R.layout.activity_upload)

        initViews()
        setupRecyclerView()
        setupSearchAndFilters()
        setupLazyLoading()
        loadUploadedNotes()
    }

    private fun initViews() {
        rvNotes = findViewById(R.id.rvNotes)
        uploadScrollView = findViewById(R.id.uploadScrollView)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        tvEmptyMessage = findViewById(R.id.tvEmptyMessage)
        btnUploadFirst = findViewById(R.id.btnUploadFirst)
        cardActiveUpload = findViewById(R.id.cardActiveUpload)
        progressBar = findViewById(R.id.progressBar)
        progressLoadMore = findViewById(R.id.progressLoadMore)
        tvProgressStatus = findViewById(R.id.tvProgressStatus)
        etSearch = findViewById(R.id.etSearch)
        chipAll = findViewById(R.id.chipAll)
        chipPdf = findViewById(R.id.chipPdf)
        chipImages = findViewById(R.id.chipImages)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        findViewById<CardView>(R.id.cardUploadPdf).setOnClickListener {
            pickFile("application/pdf")
        }

        findViewById<CardView>(R.id.cardUploadImage).setOnClickListener {
            pickFile("image/*")
        }

        btnUploadFirst.setOnClickListener {
            pickFile("application/pdf")
        }
    }

    private fun setupLazyLoading() {
        uploadScrollView.setOnScrollChangeListener { view, _, scrollY, _, _ ->
            val scrollView = view as? NestedScrollView ?: return@setOnScrollChangeListener
            val content = scrollView.getChildAt(0) ?: return@setOnScrollChangeListener
            val isNearBottom = scrollY + scrollView.height >= content.measuredHeight - PAGINATION_TRIGGER_THRESHOLD_PX
            if (isNearBottom) {
                loadNextPage()
            }
        }
    }

    private fun setupRecyclerView() {
        notesAdapter = NotesAdapter(filteredNotesList) { note ->
            showNoteOptions(note)
        }
        rvNotes.adapter = notesAdapter
        rvNotes.layoutManager = GridLayoutManager(this, 2)
    }

    private fun setupSearchAndFilters() {
        etSearch.doAfterTextChanged {
            searchQuery = it?.toString()?.trim().orEmpty()
            applyFilters()
        }

        findViewById<ChipGroup>(R.id.chipGroupFilter).setOnCheckedStateChangeListener { _, checkedIds ->
            activeFilter = when (checkedIds.firstOrNull()) {
                chipPdf.id -> NoteFilter.PDF
                chipImages.id -> NoteFilter.IMAGE
                else -> NoteFilter.ALL
            }
            applyFilters(withAnimation = true)
        }
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
        
        showProgress(getString(R.string.upload_progress_uploading, fileName))
        progressBar.setProgressCompat(0, true)

        val fileRef = storage.child("user_uploads/$userId/$fileName")
        
        fileRef.putFile(uri)
            .addOnSuccessListener {
                fileRef.downloadUrl.addOnSuccessListener { downloadUrl ->
                    saveNoteMetadata(fileName, downloadUrl.toString())
                }
            }
            .addOnFailureListener { e ->
                hideProgress()
                Toast.makeText(
                    this,
                    getString(R.string.upload_error_upload_failed, e.message ?: getString(R.string.upload_unknown_error)),
                    Toast.LENGTH_LONG
                ).show()
            }
            .addOnProgressListener { taskSnapshot ->
                val progress = if (taskSnapshot.totalByteCount > 0) {
                    (100.0 * taskSnapshot.bytesTransferred / taskSnapshot.totalByteCount).toInt()
                } else 0
                tvProgressStatus.text = getString(R.string.upload_progress_percent, fileName, progress)
                progressBar.setProgressCompat(progress, true)
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
                ContinueLearningPrefs.saveUploadActivity(this, fileName)
                notesList.removeAll { it.id == note.id }
                notesList.add(0, note)
                applyFilters()
                hideProgress()
                Toast.makeText(this, getString(R.string.upload_success), Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                hideProgress()
                Toast.makeText(
                    this,
                    getString(R.string.upload_error_firestore_failed, e.message ?: getString(R.string.upload_unknown_error)),
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    private fun loadUploadedNotes() {
        notesList.clear()
        filteredNotesList.clear()
        notesAdapter.notifyDataSetChanged()
        lastVisibleDocument = null
        hasMorePages = true
        isLoadingPage = false
        updateEmptyState()
        loadNextPage()
    }

    private fun loadNextPage() {
        if (isLoadingPage || !hasMorePages) return

        val userId = auth.currentUser?.uid ?: return
        isLoadingPage = true
        progressLoadMore.visibility = View.VISIBLE

        var query = firestore.collection("Notes").document(userId).collection("UserNotes")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(PAGE_SIZE)

        lastVisibleDocument?.let { query = query.startAfter(it) }

        query.get()
            .addOnSuccessListener { snapshot ->
                val pageNotes = snapshot.documents.mapNotNull { it.toObject(StudyNote::class.java) }
                if (pageNotes.isNotEmpty()) {
                    notesList.addAll(pageNotes)
                    lastVisibleDocument = snapshot.documents.lastOrNull()
                }
                hasMorePages = snapshot.size().toLong() == PAGE_SIZE
                applyFilters()
                completePageLoading()
            }
            .addOnFailureListener {
                Toast.makeText(this, getString(R.string.upload_error_permission_denied), Toast.LENGTH_LONG).show()
                completePageLoading()
            }
    }

    private fun completePageLoading() {
        isLoadingPage = false
        progressLoadMore.visibility = View.GONE
    }

    private fun applyFilters(withAnimation: Boolean = false) {
        val loweredQuery = searchQuery.lowercase(Locale.getDefault())
        val newFilteredNotes = notesList.filter { note ->
            val matchesType = when (activeFilter) {
                NoteFilter.ALL -> true
                NoteFilter.PDF -> note.type == "pdf"
                NoteFilter.IMAGE -> note.type == "image"
            }

            val matchesSearch = loweredQuery.isBlank() ||
                note.name.lowercase(Locale.getDefault()).contains(loweredQuery)

            matchesType && matchesSearch
        }

        publishFilteredNotes(newFilteredNotes)
        if (withAnimation) {
            rvNotes.alpha = 0.9f
            rvNotes.animate().alpha(1f).setDuration(140L).start()
        }
        updateEmptyState()
    }

    private fun publishFilteredNotes(newFilteredNotes: List<StudyNote>) {
        val previousItems = filteredNotesList.toList()
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = previousItems.size

            override fun getNewListSize(): Int = newFilteredNotes.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return itemStableKey(previousItems[oldItemPosition]) == itemStableKey(newFilteredNotes[newItemPosition])
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return previousItems[oldItemPosition] == newFilteredNotes[newItemPosition]
            }
        })

        filteredNotesList.clear()
        filteredNotesList.addAll(newFilteredNotes)
        diffResult.dispatchUpdatesTo(notesAdapter)
    }

    private fun itemStableKey(note: StudyNote): String {
        return if (note.id.isNotBlank()) note.id else note.url
    }

    private fun updateEmptyState() {
        val hasAnyNotes = notesList.isNotEmpty()
        val hasFilteredNotes = filteredNotesList.isNotEmpty()
        layoutEmpty.visibility = if (hasFilteredNotes) View.GONE else View.VISIBLE
        btnUploadFirst.visibility = if (hasAnyNotes) View.GONE else View.VISIBLE
        tvEmptyMessage.text = if (hasAnyNotes) {
            getString(R.string.upload_empty_filtered)
        } else {
            getString(R.string.upload_empty_notes)
        }
    }

    private fun showNoteOptions(note: StudyNote) {
        val options = arrayOf(
            getString(R.string.upload_option_view_file),
            getString(R.string.upload_option_summarize),
            getString(R.string.upload_option_exam_questions),
            getString(R.string.upload_option_short_notes),
            getString(R.string.upload_option_delete)
        )
        AlertDialog.Builder(this)
            .setTitle(note.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openFile(note.url)
                    1 -> openChatWithFile(note, "Summarize the key concepts from the study material: ${note.name}. Provide clear bullet points for exam revision.")
                    2 -> openChatWithFile(note, "Generate important exam questions with answers based on the study material: ${note.name}.")
                    3 -> openChatWithFile(note, "Create short revision notes in bullet points based on the study material: ${note.name}.")
                    4 -> showDeleteConfirmation(note)
                }
            }
            .show()
    }

    private fun showDeleteConfirmation(note: StudyNote) {
        if (isNoteDeleting(note)) return

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.upload_delete_confirm_title))
            .setMessage(getString(R.string.upload_delete_confirm_message, note.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.upload_delete_confirm_action) { _, _ ->
                if (!markNoteDeleting(note)) return@setPositiveButton
                deleteNoteCompletely(note)
            }
            .show()
    }

    private fun deleteNoteCompletely(note: StudyNote) {
        val userId = auth.currentUser?.uid
        if (userId.isNullOrBlank()) {
            clearNoteDeleting(note)
            Toast.makeText(this, getString(R.string.upload_delete_error_auth), Toast.LENGTH_LONG).show()
            return
        }

        val deleteMetadata: () -> Unit = {
            deleteNoteMetadata(userId, note)
        }

        val storageRef = try {
            FirebaseStorage.getInstance().getReferenceFromUrl(note.url)
        } catch (_: Exception) {
            null
        }

        if (storageRef == null) {
            deleteMetadata()
            return
        }

        storageRef.delete()
            .addOnSuccessListener { deleteMetadata() }
            .addOnFailureListener { throwable ->
                val isObjectMissing = (throwable as? StorageException)?.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND
                if (isObjectMissing) {
                    deleteMetadata()
                } else {
                    clearNoteDeleting(note)
                    Toast.makeText(
                        this,
                        getString(
                            R.string.upload_delete_error_storage,
                            throwable.message ?: getString(R.string.upload_unknown_error)
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
    }

    private fun deleteNoteMetadata(userId: String, note: StudyNote) {
        val userNotesCollection = firestore.collection("Notes").document(userId).collection("UserNotes")

        if (note.id.isNotBlank()) {
            userNotesCollection.document(note.id)
                .delete()
                .addOnSuccessListener { onDeleteSuccess(note) }
                .addOnFailureListener { error ->
                    clearNoteDeleting(note)
                    Toast.makeText(
                        this,
                        getString(
                            R.string.upload_delete_error_metadata,
                            error.message ?: getString(R.string.upload_unknown_error)
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            return
        }

        userNotesCollection.whereEqualTo("url", note.url).limit(1).get()
            .addOnSuccessListener { querySnapshot ->
                val doc = querySnapshot.documents.firstOrNull()
                if (doc == null) {
                    clearNoteDeleting(note)
                    Toast.makeText(this, getString(R.string.upload_delete_error_not_found), Toast.LENGTH_LONG).show()
                    return@addOnSuccessListener
                }

                doc.reference.delete()
                    .addOnSuccessListener { onDeleteSuccess(note) }
                    .addOnFailureListener { error ->
                        clearNoteDeleting(note)
                        Toast.makeText(
                            this,
                            getString(
                                R.string.upload_delete_error_metadata,
                                error.message ?: getString(R.string.upload_unknown_error)
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    }
            }
            .addOnFailureListener { error ->
                clearNoteDeleting(note)
                Toast.makeText(
                    this,
                    getString(
                        R.string.upload_delete_error_metadata,
                        error.message ?: getString(R.string.upload_unknown_error)
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    private fun onDeleteSuccess(note: StudyNote) {
        clearNoteDeleting(note)
        val removed = notesList.removeAll {
            (note.id.isNotBlank() && it.id == note.id) || (note.id.isBlank() && it.url == note.url)
        }

        if (removed) {
            pdfThumbnailCache.remove(note.url)
            pendingPdfThumbnails.remove(note.url)
            applyFilters()
        }

        Toast.makeText(this, getString(R.string.upload_delete_success), Toast.LENGTH_SHORT).show()
    }

    private fun noteKey(note: StudyNote): String {
        return if (note.id.isNotBlank()) note.id else note.url
    }

    private fun isNoteDeleting(note: StudyNote): Boolean {
        return deletingNoteKeys.contains(noteKey(note))
    }

    private fun markNoteDeleting(note: StudyNote): Boolean {
        val wasAdded = deletingNoteKeys.add(noteKey(note))
        if (wasAdded) notifyDeleteStateChanged(note)
        return wasAdded
    }

    private fun clearNoteDeleting(note: StudyNote) {
        val wasRemoved = deletingNoteKeys.remove(noteKey(note))
        if (wasRemoved) notifyDeleteStateChanged(note)
    }

    private fun notifyDeleteStateChanged(note: StudyNote) {
        val index = filteredNotesList.indexOfFirst {
            (note.id.isNotBlank() && it.id == note.id) || (note.id.isBlank() && it.url == note.url)
        }
        if (index != -1) {
            notesAdapter.notifyItemChanged(index, PAYLOAD_DELETE_STATE)
        }
    }

    private fun openFile(url: String) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = Uri.parse(url)
        startActivity(intent)
    }

    private fun openChatWithFile(note: StudyNote, prompt: String) {
        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra("PREFILLED_PROMPT", prompt)
            putExtra("FILE_URL", note.url)
            putExtra("FILE_TYPE", note.type)
            putExtra("FILE_NAME", note.name)
        }
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
        activeUploadCount += 1
        cardActiveUpload.visibility = View.VISIBLE
    }

    private fun hideProgress() {
        activeUploadCount = (activeUploadCount - 1).coerceAtLeast(0)
        if (activeUploadCount == 0) {
            cardActiveUpload.visibility = View.GONE
        }
    }

    private fun loadPdfThumbnail(note: StudyNote, imageView: ImageView) {
        val cacheKey = note.url
        imageView.tag = cacheKey

        pdfThumbnailCache.get(cacheKey)?.let { bitmap ->
            imageView.scaleType = ImageView.ScaleType.CENTER_CROP
            imageView.setImageBitmap(bitmap)
            showThumbnailShimmer(imageView, false)
            return
        }

        if (!pendingPdfThumbnails.add(cacheKey)) {
            imageView.scaleType = ImageView.ScaleType.CENTER
            imageView.setImageResource(android.R.drawable.ic_menu_save)
            showThumbnailShimmer(imageView, false)
            return
        }

        showThumbnailShimmer(imageView, true)

        val tempFile = try {
            File.createTempFile("note_thumb_", ".pdf", cacheDir)
        } catch (_: Exception) {
            pendingPdfThumbnails.remove(cacheKey)
            imageView.scaleType = ImageView.ScaleType.CENTER
            imageView.setImageResource(android.R.drawable.ic_menu_save)
            showThumbnailShimmer(imageView, false)
            return
        }

        FirebaseStorage.getInstance().getReferenceFromUrl(note.url)
            .getFile(tempFile)
            .addOnSuccessListener {
                try {
                    thumbnailExecutor.execute {
                        val bitmap = renderPdfFirstPage(tempFile)
                        tempFile.delete()

                        runOnUiThread {
                            if (!isActivityAlive || isFinishing || isDestroyed) {
                                pendingPdfThumbnails.remove(cacheKey)
                                return@runOnUiThread
                            }

                            pendingPdfThumbnails.remove(cacheKey)
                            if (bitmap != null) {
                                pdfThumbnailCache.put(cacheKey, bitmap)
                                if (imageView.tag == cacheKey) {
                                    imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                                    imageView.setImageBitmap(bitmap)
                                    showThumbnailShimmer(imageView, false)
                                }
                                val index = filteredNotesList.indexOfFirst { it.url == cacheKey }
                                if (index != -1) notesAdapter.notifyItemChanged(index)
                            } else if (imageView.tag == cacheKey) {
                                imageView.scaleType = ImageView.ScaleType.CENTER
                                imageView.setImageResource(android.R.drawable.ic_menu_save)
                                showThumbnailShimmer(imageView, false)
                            }
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    pendingPdfThumbnails.remove(cacheKey)
                    tempFile.delete()
                }
            }
            .addOnFailureListener {
                pendingPdfThumbnails.remove(cacheKey)
                tempFile.delete()
                if (!isActivityAlive || isFinishing || isDestroyed) return@addOnFailureListener
                if (imageView.tag == cacheKey) {
                    imageView.scaleType = ImageView.ScaleType.CENTER
                    imageView.setImageResource(android.R.drawable.ic_menu_save)
                    showThumbnailShimmer(imageView, false)
                }
            }
    }

    private fun showThumbnailShimmer(imageView: ImageView, isLoading: Boolean) {
        val holder = imageView.parent as? ViewGroup ?: return
        val shimmer = holder.findViewById<ShimmerFrameLayout>(R.id.shimmerThumbnail) ?: return
        if (isLoading) {
            imageView.visibility = View.INVISIBLE
            shimmer.visibility = View.VISIBLE
            shimmer.startShimmer()
        } else {
            imageView.visibility = View.VISIBLE
            shimmer.stopShimmer()
            shimmer.visibility = View.GONE
        }
    }

    private fun renderPdfFirstPage(pdfFile: File): Bitmap? {
        return try {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { fileDescriptor ->
                PdfRenderer(fileDescriptor).use { renderer ->
                    if (renderer.pageCount == 0) return null
                    renderer.openPage(0).use { page ->
                        val width = (page.width * 0.42f).toInt().coerceAtLeast(220)
                        val height = (page.height * 0.42f).toInt().coerceAtLeast(300)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun onDestroy() {
        isActivityAlive = false
        super.onDestroy()
        thumbnailExecutor.shutdown()
        pdfThumbnailCache.evictAll()
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
            val ivThumbnail: ImageView = view.findViewById(R.id.ivNoteThumbnail)
            val tvTypeBadge: TextView = view.findViewById(R.id.tvTypeBadge)
            val shimmerThumbnail: ShimmerFrameLayout = view.findViewById(R.id.shimmerThumbnail)
            val deletingOverlay: View = view.findViewById(R.id.layoutDeletingOverlay)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_note_grid, parent, false)
            return NoteViewHolder(view)
        }

        override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
            val note = notes[position]
            holder.tvName.text = note.name
            holder.ivThumbnail.tag = note.url
            holder.ivThumbnail.scaleType = ImageView.ScaleType.CENTER_CROP
            holder.ivThumbnail.setImageDrawable(null)
            showThumbnailShimmer(holder.ivThumbnail, false)

            val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
            holder.tvDate.text = getString(R.string.upload_note_uploaded_date, sdf.format(Date(note.timestamp)))

            if (note.type == "pdf") {
                holder.tvTypeBadge.text = getString(R.string.upload_badge_pdf)
                loadPdfThumbnail(note, holder.ivThumbnail)
            } else {
                holder.tvTypeBadge.text = getString(R.string.upload_badge_image)
                holder.ivThumbnail.load(note.url) {
                    crossfade(true)
                    placeholder(android.R.drawable.ic_menu_gallery)
                    listener(
                        onStart = { showThumbnailShimmer(holder.ivThumbnail, true) },
                        onSuccess = { _: coil.request.ImageRequest, _: ImageResult ->
                            showThumbnailShimmer(holder.ivThumbnail, false)
                        },
                        onError = { _: coil.request.ImageRequest, _: ImageResult? ->
                            showThumbnailShimmer(holder.ivThumbnail, false)
                        }
                    )
                    error(android.R.drawable.ic_menu_report_image)
                }
            }

            bindDeleteState(holder, note)
            holder.itemView.setOnClickListener {
                if (!isNoteDeleting(note)) onClick(note)
            }
        }

        override fun onBindViewHolder(holder: NoteViewHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.contains(PAYLOAD_DELETE_STATE)) {
                bindDeleteState(holder, notes[position])
            } else {
                super.onBindViewHolder(holder, position, payloads)
            }
        }

        private fun bindDeleteState(holder: NoteViewHolder, note: StudyNote) {
            val deleting = isNoteDeleting(note)
            holder.deletingOverlay.visibility = if (deleting) View.VISIBLE else View.GONE
            holder.itemView.alpha = if (deleting) 0.6f else 1f
            if (deleting) {
                holder.shimmerThumbnail.stopShimmer()
            }
        }

        override fun getItemCount() = notes.size
    }

    enum class NoteFilter {
        ALL,
        PDF,
        IMAGE
    }
}
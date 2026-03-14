package com.example.aistudyassistance

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.example.aistudyassistance.Activity.ChatActivity
import com.example.aistudyassistance.Activity.UploadActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

class MainActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var tvWelcome: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        auth = FirebaseAuth.getInstance()
        tvWelcome = findViewById(R.id.tvWelcome)

        loadUserData()
        setupClickListeners()
        setupBottomNavigation()
    }

    private fun loadUserData() {
        val user = auth.currentUser
        if (user != null) {
            val userRef = FirebaseDatabase.getInstance().reference.child("Users").child(user.uid)
            userRef.child("email").get().addOnSuccessListener { snapshot ->
                val email = snapshot.value?.toString() ?: user.email ?: "Student"
                val name = email.substringBefore("@")
                tvWelcome.text = "Hello, ${name.replaceFirstChar { it.uppercase() }} 👋"
            }
        }
    }

    private fun setupClickListeners() {
        findViewById<CardView>(R.id.cvProfile).setOnClickListener {
            showToast("Opening Profile...")
        }

        findViewById<CardView>(R.id.cvAskAi).setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java))
        }

        findViewById<CardView>(R.id.cardChat).setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java))
        }

        findViewById<CardView>(R.id.cardUpload).setOnClickListener {
            startActivity(Intent(this, UploadActivity::class.java))
        }

        findViewById<CardView>(R.id.cardQuiz).setOnClickListener {
            showToast("Quiz feature coming soon!")
        }

        findViewById<CardView>(R.id.cardFlashcards).setOnClickListener {
            showToast("Flashcards feature coming soon!")
        }
    }

    private fun setupBottomNavigation() {
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNavigation)
        bottomNav.selectedItemId = R.id.nav_home

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> true
                R.id.nav_chat -> {
                    startActivity(Intent(this, ChatActivity::class.java))
                    true
                }
                R.id.nav_upload -> {
                    startActivity(Intent(this, UploadActivity::class.java))
                    true
                }
                R.id.nav_quiz -> {
                    showToast("Quiz feature coming soon!")
                    true
                }
                R.id.nav_profile -> {
                    showToast("Profile feature coming soon!")
                    true
                }
                else -> false
            }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
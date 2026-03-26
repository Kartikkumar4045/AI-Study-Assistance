package com.kartik.aistudyassistant.ui.launcher

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.kartik.aistudyassistant.R
import com.kartik.aistudyassistant.ui.auth.SignInActivity
import com.kartik.aistudyassistant.ui.home.MainActivity

class LaunchLoaderActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val navigateRunnable = Runnable {
        val destination = if (FirebaseAuth.getInstance().currentUser != null) {
            MainActivity::class.java
        } else {
            SignInActivity::class.java
        }

        startActivity(
            Intent(this, destination).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        )
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launch_loader)
        handler.postDelayed(navigateRunnable, LOADER_DELAY_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(navigateRunnable)
        super.onDestroy()
    }

    companion object {
        private const val LOADER_DELAY_MS = 900L
    }
}


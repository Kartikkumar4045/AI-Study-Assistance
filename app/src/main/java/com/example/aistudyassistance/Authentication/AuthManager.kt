package com.example.aistudyassistance.Authentication

import android.app.Activity
import android.content.Context
import androidx.credentials.*
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.*
import com.google.firebase.auth.*
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AuthManager(private val context: Context) {

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val credentialManager = CredentialManager.create(context)
    private val database = FirebaseDatabase.getInstance()

    // Google Sign-In - Uses Context (works with Credential Manager)
    fun signInWithGoogle(callback: (AuthResult) -> Unit) {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(
                context.getString(com.example.aistudyassistance.R.string.default_web_client_id)
            )
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = credentialManager.getCredential(
                    request = request,
                    context = context
                )

                val credential = result.credential

                if (credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val googleIdTokenCredential =
                        GoogleIdTokenCredential.createFrom(credential.data)

                    firebaseAuthWithProvider(
                        googleIdTokenCredential.idToken,
                        "google",
                        callback
                    )
                } else {
                    callback(AuthResult.Error("Invalid credential type"))
                }
            } catch (e: GetCredentialException) {
                callback(AuthResult.Error(e.message))
            }
        }
    }

    // GitHub Sign-In - Requires Activity
    fun signInWithGithub(activity: Activity, callback: (AuthResult) -> Unit) {
        val provider = OAuthProvider.newBuilder("github.com")
            .addCustomParameter("allow_signup", "false") // Optional: prevent signup if not allowed
            .build()

        auth.startActivityForSignInWithProvider(activity, provider)
            .addOnSuccessListener { authResult ->
                // After successful Firebase auth, save to database
                saveUserToDatabase(authResult.user, "github") { success ->
                    if (success) {
                        callback(AuthResult.Success(authResult.additionalUserInfo?.isNewUser ?: false))
                    } else {
                        callback(AuthResult.Error("Failed to save user data"))
                    }
                }
            }
            .addOnFailureListener { e ->
                callback(AuthResult.Error(e.message ?: "GitHub sign-in failed"))
            }
    }

    // Common method for Firebase authentication with providers
    private fun firebaseAuthWithProvider(
        idToken: String,
        provider: String,
        callback: (AuthResult) -> Unit
    ) {
        val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)

        auth.signInWithCredential(firebaseCredential)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    saveUserToDatabase(auth.currentUser, provider) { success ->
                        if (success) {
                            callback(AuthResult.Success(task.result?.additionalUserInfo?.isNewUser ?: false))
                        } else {
                            callback(AuthResult.Error("Failed to save user data"))
                        }
                    }
                } else {
                    callback(AuthResult.Error(task.exception?.message ?: "Authentication failed"))
                }
            }
    }

    // Unified database saving method
    private fun saveUserToDatabase(user: FirebaseUser?, provider: String, callback: (Boolean) -> Unit) {
        val userId = user?.uid ?: run {
            callback(false)
            return
        }

        // Check if user already exists in database
        database.reference.child("Users").child(userId).get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    // User already exists, no need to save again
                    callback(true)
                } else {
                    // New user, save to database
                    val userMap = HashMap<String, Any>().apply {
                        put("email", user.email?.lowercase() ?: "")
                        put("phone", "")
                        put("provider", provider)
                        put("createdAt", System.currentTimeMillis())
                    }

                    database.reference.child("Users").child(userId)
                        .setValue(userMap)
                        .addOnSuccessListener {
                            // Save email mapping for quick lookup
                            val encodedEmail = encodeEmail(user.email ?: "")
                            database.reference.child("Emails").child(encodedEmail)
                                .setValue(userId)
                                .addOnSuccessListener { callback(true) }
                                .addOnFailureListener {
                                    // Email mapping failed but user was created
                                    callback(true)
                                }
                        }
                        .addOnFailureListener {
                            callback(false)
                        }
                }
            }
            .addOnFailureListener {
                // Failed to check existence, try to save anyway
                val userMap = HashMap<String, Any>().apply {
                    put("email", user.email?.lowercase() ?: "")
                    put("phone", "")
                    put("provider", provider)
                    put("createdAt", System.currentTimeMillis())
                }

                database.reference.child("Users").child(userId)
                    .setValue(userMap)
                    .addOnSuccessListener {
                        val encodedEmail = encodeEmail(user.email ?: "")
                        database.reference.child("Emails").child(encodedEmail)
                            .setValue(userId)
                            .addOnSuccessListener { callback(true) }
                            .addOnFailureListener { callback(true) }
                    }
                    .addOnFailureListener { callback(false) }
            }
    }

    private fun encodeEmail(email: String): String {
        return email.lowercase()
            .replace(".", "_")
            .replace("@", "_")
    }

    fun signOut() {
        auth.signOut()
    }

    fun getCurrentUser(): FirebaseUser? {
        return auth.currentUser
    }

    fun isUserLoggedIn(): Boolean {
        return auth.currentUser != null
    }
}
package com.example.aistudyassistance.Authentication

import android.content.Context
import androidx.credentials.*
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.*
import com.google.firebase.auth.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AuthManager(private val context: Context) {

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val credentialManager = CredentialManager.create(context)

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

                    firebaseAuthWithGoogle(
                        googleIdTokenCredential.idToken,
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

    private fun firebaseAuthWithGoogle(
        idToken: String,
        callback: (AuthResult) -> Unit
    ) {

        val firebaseCredential =
            GoogleAuthProvider.getCredential(idToken, null)

        auth.signInWithCredential(firebaseCredential)
            .addOnCompleteListener { task ->

                if (task.isSuccessful) {

                    val isNewUser =
                        task.result?.additionalUserInfo?.isNewUser ?: false

                    callback(AuthResult.Success(isNewUser))

                } else {
                    callback(AuthResult.Error(task.exception?.message))
                }
            }
    }

    fun signOut() {
        auth.signOut()
    }
}
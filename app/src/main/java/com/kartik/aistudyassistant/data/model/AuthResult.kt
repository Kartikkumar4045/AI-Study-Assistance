package com.kartik.aistudyassistant.data.model

sealed class AuthResult {
    data class Success(val isNewUser: Boolean) : AuthResult()
    data class Error(val message: String?) : AuthResult()
    object Cancelled : AuthResult()
}

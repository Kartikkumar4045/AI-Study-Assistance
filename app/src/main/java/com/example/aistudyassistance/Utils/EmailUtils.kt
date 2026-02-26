package com.example.aistudyassistance.utils

object EmailUtils {
    fun encodeEmail(email: String): String {
        return email.lowercase()
            .replace(".", "_")
            .replace("@", "_")
    }
}
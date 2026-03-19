package com.example.aistudyassistance

import kotlinx.serialization.Serializable

@Serializable
data class Flashcard(
    val question: String,
    val answer: String
)


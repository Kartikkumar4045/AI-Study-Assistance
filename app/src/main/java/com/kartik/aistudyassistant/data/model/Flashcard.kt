package com.kartik.aistudyassistant.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Flashcard(
    val question: String,
    val answer: String
)



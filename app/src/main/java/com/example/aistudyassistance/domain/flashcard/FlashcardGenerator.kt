package com.example.aistudyassistance.domain.flashcard

import android.util.Log
import com.example.aistudyassistance.core.utils.GeminiHelper
import com.example.aistudyassistance.data.model.Flashcard
import org.json.JSONArray
import org.json.JSONException

class FlashcardGenerator(private val geminiHelper: GeminiHelper) {

    companion object {
        private const val TAG = "FlashcardGenerator"
        private const val MAX_NOTE_CHARS = 12000
    }

    suspend fun generateFromTopic(topic: String, count: Int): List<Flashcard> {
        val prompt = """
            Generate $count flashcards for the topic "$topic".

            Rules:

            * Each flashcard must have:

              * a short question
              * a short answer (maximum 2 sentences)
            * Keep language simple and easy to understand
            * Do not include long paragraphs

            Return ONLY valid JSON in this exact format:

            [
            {
            "question": "Question text",
            "answer": "Short answer"
            }
            ]

            Important:

            * Do NOT include markdown
            * Do NOT include explanations outside JSON
            * Do NOT include extra text
        """.trimIndent()

        return generate(prompt)
    }

    suspend fun generateFromNotes(noteText: String, count: Int): List<Flashcard> {
        val limitedText = noteText.take(MAX_NOTE_CHARS)

        val prompt = """
            Generate $count flashcards using the following study material.

            Study Material:
            $limitedText

            Rules:

            * Each flashcard must contain a short question and a short answer (max 2 sentences)
            * Focus on key concepts and definitions
            * Return ONLY JSON in the specified format
            * No markdown
            * No extra text

            Return ONLY valid JSON in this exact format:

            [
            {
            "question": "Question text",
            "answer": "Short answer"
            }
            ]
        """.trimIndent()

        return generate(prompt)
    }

    private suspend fun generate(prompt: String): List<Flashcard> {
        return try {
            val response = geminiHelper.getResponse(prompt)
            parseFlashcardsJson(response)
        } catch (e: Exception) {
            Log.e(TAG, "Flashcard generation failed", e)
            emptyList()
        }
    }

    private fun parseFlashcardsJson(jsonString: String): List<Flashcard> {
        return try {
            val jsonArray = JSONArray(jsonString)
            val flashcards = mutableListOf<Flashcard>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(i) ?: continue
                val question = obj.optString("question", "").trim()
                val answer = obj.optString("answer", "").trim()

                if (question.isBlank() || answer.isBlank()) continue
                flashcards.add(Flashcard(question = question, answer = answer))
            }

            flashcards
        } catch (e: JSONException) {
            Log.e(TAG, "Invalid flashcard JSON", e)
            emptyList()
        }
    }
}




package com.example.aistudyassistance

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class QuizGenerator(private val geminiHelper: GeminiHelper) {

    private val tag = "QuizGenerator"

    suspend fun generateQuizFromTopic(topic: String, questionCount: Int): List<QuizQuestion> {
        val prompt = """
            Create a quiz with exactly $questionCount multiple-choice questions on the topic "$topic".

            Each question must have exactly 4 options (A, B, C, D), and one correct answer.
            Provide a short explanation for each correct answer.

            Output ONLY a valid JSON array containing exactly $questionCount question objects.
            Do not include any text outside the JSON array.
            Do not generate more or fewer than $questionCount questions.
            No markdown code fences.

            JSON format:
            [
            {
            "question": "Question text here",
            "options": ["Option A", "Option B", "Option C", "Option D"],
            "correctAnswer": 0,
            "explanation": "Explanation here"
            }
            ]
        """.trimIndent()

        return generateQuiz(prompt)
    }

    suspend fun generateQuizFromNotes(noteText: String, questionCount: Int): List<QuizQuestion> {
        val limitedText = noteText.take(12000)

        val prompt = """
            Generate exactly $questionCount multiple-choice questions using the following study material.

            Study Material:
            $limitedText

            Rules:
            * Each question must have exactly 4 options.
            * Only one correct answer.
            * Provide explanation.
            * Return ONLY JSON in the exact format specified.
            * No markdown.
            * No text outside JSON.
            * The JSON must be an array containing exactly $questionCount question objects.

            JSON format:

            [
            {
            "question": "Question text",
            "options": ["Option A","Option B","Option C","Option D"],
            "correctAnswer": 1,
            "explanation": "Short explanation"
            }
            ]
        """.trimIndent()

        return generateQuiz(prompt)
    }

    private suspend fun generateQuiz(prompt: String): List<QuizQuestion> {
        return try {
            Log.d(tag, "Generating quiz with prompt: $prompt")
            val response = geminiHelper.getResponse(prompt)
            Log.d(tag, "Gemini response: $response")
            val expectedCount = getQuestionCountFromPrompt(prompt)
            parseQuizJson(response, expectedCount)
        } catch (e: Exception) {
            Log.e(tag, "Error generating quiz", e)
            throw Exception("Failed to generate quiz: ${e.message}")
        }
    }

    private fun getQuestionCountFromPrompt(prompt: String): Int {
        val regex = "exactly (\\d+)".toRegex()
        val match = regex.find(prompt)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 5
    }

    private fun parseQuizJson(jsonString: String, expectedCount: Int): List<QuizQuestion> {
        return try {
            val normalized = extractJsonPayload(jsonString)
            val jsonArray = parseAsJsonArray(normalized)
            val questions = mutableListOf<QuizQuestion>()

            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)

                val question = jsonObject.getString("question")
                val optionsJson = jsonObject.getJSONArray("options")
                val options = mutableListOf<String>()
                for (j in 0 until optionsJson.length()) {
                    options.add(optionsJson.getString(j))
                }
                val correctAnswer = jsonObject.getInt("correctAnswer")
                val explanation = jsonObject.getString("explanation")

                // Validate data
                if (options.size != 4) {
                    throw JSONException("Question $i does not have exactly 4 options")
                }
                if (correctAnswer < 0 || correctAnswer > 3) {
                    throw JSONException("Question $i has invalid correctAnswer index: $correctAnswer")
                }

                questions.add(QuizQuestion(question, options, correctAnswer, explanation))
            }

            if (questions.isEmpty()) {
                throw JSONException("Generated 0 valid questions")
            }

            if (questions.size != expectedCount) {
                Log.w(tag, "Generated ${questions.size} questions, expected $expectedCount. Proceeding with available questions.")
            }

            questions.take(expectedCount)
        } catch (e: JSONException) {
            Log.e(tag, "JSON parsing error", e)
            throw Exception("Invalid quiz format received from AI: ${e.message}")
        }
    }

    private fun extractJsonPayload(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) {
            throw JSONException("AI returned an empty response")
        }

        // Handles responses wrapped in ```json ... ```.
        val withoutFences = if (trimmed.startsWith("```")) {
            trimmed
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
        } else {
            trimmed
        }

        if (withoutFences.startsWith("[") || withoutFences.startsWith("{")) {
            return withoutFences
        }

        val arrayStart = withoutFences.indexOf('[')
        val arrayEnd = withoutFences.lastIndexOf(']')
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            return withoutFences.substring(arrayStart, arrayEnd + 1)
        }

        throw JSONException("No JSON payload found in AI response")
    }

    private fun parseAsJsonArray(payload: String): JSONArray {
        return try {
            JSONArray(payload)
        } catch (_: JSONException) {
            val jsonObject = JSONObject(payload)
            jsonObject.optJSONArray("questions")
                ?: throw JSONException("JSON object does not contain a 'questions' array")
        }
    }
}

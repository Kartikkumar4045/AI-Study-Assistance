package com.kartik.aistudyassistant.domain.quiz

import android.util.Log
import com.kartik.aistudyassistant.core.utils.GeminiHelper
import com.kartik.aistudyassistant.data.model.QuizQuestion
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

        return generateQuiz(prompt, questionCount, topic.ifBlank { "general topic" })
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

        return generateQuiz(prompt, questionCount, "your notes")
    }

    private suspend fun generateQuiz(prompt: String, expectedCount: Int, subjectLabel: String): List<QuizQuestion> {
        try {
            val strictPrompt = "$prompt\n\nIMPORTANT: Return ONLY valid JSON array with exactly $expectedCount items."
            val stricterPrompt = "$strictPrompt\n\nUse numeric correctAnswer values only: 0,1,2,3."
            val collected = mutableListOf<QuizQuestion>()
            repeat(3) { attempt ->
                val remaining = expectedCount - collected.size
                if (remaining <= 0) return collected.take(expectedCount)
                val requestPrompt = when (attempt) {
                    0 -> prompt
                    1 -> strictPrompt
                    else -> stricterPrompt
                }
                Log.d(tag, "Generating quiz (attempt ${attempt + 1}), need $remaining more")
                try {
                    val response = geminiHelper.getResponse(requestPrompt)
                    val parsed = parseQuizJson(response, remaining)
                    if (parsed.isNotEmpty()) {
                        collected.addAll(parsed.take(remaining))
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Attempt ${attempt + 1} failed: ${e.message}")
                }
            }
            if (collected.isEmpty()) {
                Log.w(tag, "Falling back to local quiz generation")
                return generateFallbackQuestions(subjectLabel, expectedCount)
            }
            return collected.take(expectedCount)
        } catch (e: Exception) {
            Log.e(tag, "Error generating quiz", e)
            throw Exception("Failed to generate quiz: ${e.message}")
        }
    }

    private fun generateFallbackQuestions(subjectLabel: String, expectedCount: Int): List<QuizQuestion> {
        val safeLabel = subjectLabel.ifBlank { "General" }
        val questions = mutableListOf<QuizQuestion>()
        repeat(expectedCount) { idx ->
            val qNum = idx + 1
            val question = "Question $qNum about $safeLabel"
            val options = listOf(
                "It relates to basics of $safeLabel",
                "It covers an example in $safeLabel",
                "It explains a key rule in $safeLabel",
                "It discusses a common mistake in $safeLabel"
            )
            val correct = 0
            val explanation = "Review the core concept of $safeLabel to answer correctly."
            questions.add(QuizQuestion(question, options, correct, explanation))
        }
        return questions
    }

    private fun parseQuizJson(jsonString: String, expectedCount: Int): List<QuizQuestion> {
        return try {
            val normalized = extractJsonPayload(jsonString)
            val jsonArray = parseAsJsonArray(normalized)
            val questions = mutableListOf<QuizQuestion>()

            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)

                val question = jsonObject.optString("question")
                    .ifBlank { jsonObject.optString("questionText") }
                val options = extractOptions(jsonObject)
                val correctAnswer = parseCorrectAnswer(jsonObject, options)
                val explanation = jsonObject.optString("explanation")
                    .ifBlank { "Review the concept and compare with the correct option." }

                // Validate data
                if (question.isBlank()) {
                    throw JSONException("Question $i text is empty")
                }
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

            if (questions.size < expectedCount) {
                throw JSONException("Generated ${questions.size} questions, expected $expectedCount")
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

    private fun extractOptions(jsonObject: JSONObject): List<String> {
        if (jsonObject.has("options") && jsonObject.opt("options") is JSONArray) {
            val optionsJson = jsonObject.getJSONArray("options")
            val options = mutableListOf<String>()
            for (j in 0 until optionsJson.length()) {
                options.add(optionsJson.optString(j).trim())
            }
            return options.filter { it.isNotBlank() }.take(4)
        }

        val choices = jsonObject.optJSONObject("options") ?: jsonObject.optJSONObject("choices")
        if (choices != null) {
            val ordered = listOf("A", "B", "C", "D").mapNotNull { key ->
                choices.optString(key).trim().takeIf { it.isNotBlank() }
            }
            return ordered.take(4)
        }

        return emptyList()
    }

    private fun parseCorrectAnswer(jsonObject: JSONObject, options: List<String>): Int {
        val raw = jsonObject.opt("correctAnswer")
            ?: jsonObject.opt("answer")
            ?: jsonObject.opt("correct_option")
            ?: throw JSONException("Missing correct answer")

        val asText = raw.toString().trim()

        raw.toString().toIntOrNull()?.let { value ->
            return if (value in 0..3) value else value - 1
        }

        if (asText.length == 1) {
            when (asText.uppercase()) {
                "A" -> return 0
                "B" -> return 1
                "C" -> return 2
                "D" -> return 3
            }
        }

        val textWithoutPrefix = asText
            .replace(Regex("^[A-Da-d][).:-]?\\s*"), "")
            .trim()
        val idx = options.indexOfFirst { opt -> opt.equals(textWithoutPrefix, ignoreCase = true) }
        if (idx >= 0) return idx

        throw JSONException("Unsupported correct answer format: $asText")
    }
}


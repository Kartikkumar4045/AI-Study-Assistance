package com.example.aistudyassistance

import android.graphics.Bitmap
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

class GeminiHelper(apiKey: String, private val modelName: String = "gemini-2.5-flash") {

    private val cleanApiKey = apiKey.trim()

    private val generativeModel = GenerativeModel(
        modelName = modelName,
        apiKey = cleanApiKey,
        generationConfig = generationConfig {
            temperature = 0.6f
            topK = 32
            topP = 0.9f
            // Increased max tokens to prevent truncation errors
            maxOutputTokens = 4096 
        }
    )

    // Standard text-only response
    suspend fun getResponse(prompt: String): String = withContext(Dispatchers.IO) {
        try {
            val response = generativeModel.generateContent(prompt)
            response.text ?: "The AI returned an empty response. Please try asking in a different way."
        } catch (e: Exception) {
            handleError(e)
        }
    }


    // Streaming text-only response
    fun getResponseStream(prompt: String): Flow<String> = flow {
        try {
            val responseFlow = generativeModel.generateContentStream(prompt)
            var accumulatedText = ""
            responseFlow.collect { chunk ->
                chunk.text?.let { text ->
                    accumulatedText += text
                    emit(accumulatedText)
                }
            }
            if (accumulatedText.isEmpty()) {
                emit("The AI returned an empty response. Please try asking in a different way.")
            }
        } catch (e: Exception) {
            emit(handleError(e))
        }
    }

    // Streaming multi-modal response (Text + Image)
    fun getResponseWithImageStream(prompt: String, image: Bitmap): Flow<String> = flow {
        try {
            val inputContent = content {
                image(image) // User Image input
                text(prompt)   //User question
            }
            val responseFlow = generativeModel.generateContentStream(inputContent)
            var accumulatedText = ""
            responseFlow.collect { chunk ->
                chunk.text?.let { text ->
                    accumulatedText += text
                    emit(accumulatedText)
                }
            }
            if (accumulatedText.isEmpty()) {
                emit("The AI could not analyze this image. Please ensure it contains clear study notes.")
            }
        } catch (e: Exception) {
            emit(handleError(e))
        }
    }

    private fun handleError(e: Exception): String {
        val errorMessage = e.message ?: "Unknown error"
        return when {
            errorMessage.contains("404") -> "Error 404: Model not found. Please verify the model name."
            errorMessage.contains("403") -> "Error 403: API Key issue or permission denied."
            errorMessage.contains("MAX_TOKENS") -> "Error: Response was too long and got cut off. Try asking a more specific question."
            errorMessage.contains("SAFETY") -> "Error: The AI blocked the response due to safety filters. Try rephrasing."
            else -> "AI Error: $errorMessage"
        }
    }
}
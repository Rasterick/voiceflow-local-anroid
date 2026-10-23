package com.voiceflow.local.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class CloudProvider(val id: String, val displayName: String) {
    GEMINI("gemini", "Google Gemini 1.5 Flash"),
    GROQ("groq", "Groq (Llama 3.1 Instant)")
}

enum class CloudFormatMode(val id: String, val displayName: String, val promptInstruction: String) {
    CLEAN(
        "clean",
        "Clean & Natural",
        "Clean up this raw voice dictation. Fix punctuation, capitalization, minor grammar, and remove verbal fillers like 'uh', 'um', 'you know'. Correct obvious speech-to-text acoustic mishearings based on sentence context. Preserve the speaker's original voice, tone, and intent. Output ONLY the polished text without any preamble."
    ),
    PROFESSIONAL(
        "professional",
        "Professional Message",
        "Rewrite this spoken dictation into clear, professional, well-punctuated communication suitable for an email or business message. Correct speech recognition acoustic errors and homophones (for example: 'Fear sir' -> 'Dear Sir', 'arrange alone' -> 'arrange a loan', 'spendage' -> 'spending') and fix grammar while preserving the intended message. Output ONLY the polished text without any preamble."
    ),
    BULLETS(
        "bullets",
        "Action Bullets",
        "Convert this spoken stream of thoughts into a clean, structured bullet point list. Correct any speech recognition errors or verbal filler words. Place each item or thought on its own line prefixed with '• '. Output ONLY the bulleted points without any preamble."
    )
}

object CloudLlmEngine {
    private const val TAG = "CloudLlmEngine"
    private const val PREFS_NAME = "voiceflow_settings"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    fun getApiKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString("cloud_api_key", "")?.trim() ?: ""
    }

    fun setApiKey(context: Context, key: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("cloud_api_key", key.trim()).apply()
    }

    fun getProvider(context: Context): CloudProvider {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val id = prefs.getString("cloud_provider", CloudProvider.GEMINI.id)
        return CloudProvider.entries.find { it.id == id } ?: CloudProvider.GEMINI
    }

    fun setProvider(context: Context, provider: CloudProvider) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("cloud_provider", provider.id).apply()
    }

    fun getFormatMode(context: Context): CloudFormatMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val id = prefs.getString("cloud_format_mode", CloudFormatMode.CLEAN.id)
        return CloudFormatMode.entries.find { it.id == id } ?: CloudFormatMode.CLEAN
    }

    fun setFormatMode(context: Context, mode: CloudFormatMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("cloud_format_mode", mode.id).apply()
    }

    suspend fun polish(context: Context, rawText: String): String = withContext(Dispatchers.IO) {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return@withContext ""

        val apiKey = getApiKey(context)
        if (apiKey.isBlank()) {
            Log.w(TAG, "No Cloud API key configured. Falling back to Tier 1 Offline Punctuator.")
            return@withContext OfflinePunctuatorEngine.punctuate(trimmed)
        }

        val provider = getProvider(context)
        val mode = getFormatMode(context)

        try {
            val t0 = System.currentTimeMillis()
            val result = when (provider) {
                CloudProvider.GEMINI -> callGemini(apiKey, mode, trimmed)
                CloudProvider.GROQ -> callGroq(apiKey, mode, trimmed)
            }
            val dt = System.currentTimeMillis() - t0
            Log.i(TAG, "Cloud LLM (${provider.displayName}) completed in ${dt}ms: '$result'")
            if (result.isNotBlank()) result else OfflinePunctuatorEngine.punctuate(trimmed)
        } catch (t: Throwable) {
            Log.e(TAG, "Cloud LLM error: ${t.message}. Falling back to Offline Punctuator.", t)
            OfflinePunctuatorEngine.punctuate(trimmed)
        }
    }

    suspend fun testConnection(context: Context, rawKey: String? = null, rawProvider: CloudProvider? = null): String = withContext(Dispatchers.IO) {
        val apiKey = rawKey?.trim()?.ifBlank { null } ?: getApiKey(context)
        if (apiKey.isBlank()) throw IllegalArgumentException("API key cannot be empty")
        val provider = rawProvider ?: getProvider(context)
        when (provider) {
            CloudProvider.GEMINI -> callGemini(apiKey, CloudFormatMode.CLEAN, "Hello, this is a test.")
            CloudProvider.GROQ -> callGroq(apiKey, CloudFormatMode.CLEAN, "Hello, this is a test.")
        }
    }

    private fun callGemini(apiKey: String, mode: CloudFormatMode, rawText: String): String {
        val prompt = "${mode.promptInstruction}\n\nDictated Text:\n\"\"\"\n$rawText\n\"\"\""

        val jsonBody = JSONObject().apply {
            val contents = JSONArray().apply {
                val userContent = JSONObject().apply {
                    put("role", "user")
                    val parts = JSONArray().apply {
                        put(JSONObject().put("text", prompt))
                    }
                    put("parts", parts)
                }
                put(userContent)
            }
            put("contents", contents)

            val generationConfig = JSONObject().apply {
                put("temperature", 0.2)
                put("maxOutputTokens", 1024)
            }
            put("generationConfig", generationConfig)
        }

        val requestBody = jsonBody.toString().toRequestBody(JSON_MEDIA_TYPE)
        val candidateModels = listOf("gemini-3.1-flash-lite", "gemini-3-flash-preview", "gemini-3.6-flash", "gemini-3.5-flash")
        var lastException: Exception? = null

        for (modelName in candidateModels) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val err = "Gemini API error (model $modelName, HTTP ${response.code}): $responseBody"
                        Log.w(TAG, err)
                        if (response.code in listOf(404, 429, 500, 502, 503)) {
                            lastException = IllegalStateException(err)
                            return@use // try next candidate model
                        } else {
                            throw IllegalStateException(err)
                        }
                    }

                    val json = JSONObject(responseBody)
                    val candidates = json.optJSONArray("candidates") ?: throw IllegalStateException("No candidates in Gemini response")
                    if (candidates.length() == 0) throw IllegalStateException("Empty candidates list")

                    val firstCandidate = candidates.getJSONObject(0)
                    val content = firstCandidate.optJSONObject("content") ?: throw IllegalStateException("Missing content object")
                    val parts = content.optJSONArray("parts") ?: throw IllegalStateException("Missing parts array")
                    if (parts.length() == 0) throw IllegalStateException("Empty parts list")

                    var text = parts.getJSONObject(0).optString("text", "").trim()
                    text = sanitizePreamble(text)
                    return text
                }
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Model $modelName attempt failed (${e.javaClass.simpleName}: ${e.message}), trying next candidate...")
            }
        }

        throw lastException ?: IllegalStateException("All Gemini model endpoints failed")
    }

    private fun callGroq(apiKey: String, mode: CloudFormatMode, rawText: String): String {
        val jsonBody = JSONObject().apply {
            put("model", "llama-3.1-8b-instant")
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", mode.promptInstruction)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", rawText)
                })
            }
            put("messages", messages)
            put("temperature", 0.2)
            put("max_tokens", 1024)
        }

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(jsonBody.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Groq API error (HTTP ${response.code}): $responseBody")
            }

            val json = JSONObject(responseBody)
            val choices = json.optJSONArray("choices") ?: throw IllegalStateException("No choices in Groq response")
            if (choices.length() == 0) throw IllegalStateException("Empty choices list")

            val message = choices.getJSONObject(0).optJSONObject("message") ?: throw IllegalStateException("Missing message object")
            var text = message.optString("content", "").trim()
            text = sanitizePreamble(text)
            return text
        }
    }

    private fun sanitizePreamble(text: String): String {
        var clean = text.trim()
        clean = clean.replace(Regex("^(Here is[^:\n]*[:\n]+|Sure,?[^:\n]*[:\n]+|Polished:[^:\n]*[:\n]+|Cleaned text:[^:\n]*[:\n]+)", RegexOption.IGNORE_CASE), "").trim()
        if (clean.startsWith("\"") && clean.endsWith("\"") && clean.length > 2) {
            clean = clean.substring(1, clean.length - 1).trim()
        }
        return clean
    }
}

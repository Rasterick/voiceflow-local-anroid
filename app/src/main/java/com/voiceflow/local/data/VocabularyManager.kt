package com.voiceflow.local.data

import android.content.Context
import android.provider.UserDictionary
import android.util.Log

data class VocabularyProfile(
    val id: String,
    val name: String,
    val description: String,
    val keywords: List<String>
)

object VocabularyManager {
    private const val TAG = "VocabularyManager"

    val defaultProfiles = listOf(
        VocabularyProfile(
            id = "software_dev",
            name = "Software Engineering",
            description = "Technical jargon, developer libraries, frameworks & acronyms",
            keywords = listOf(
                "Kubernetes", "PostgreSQL", "GraphQL", "async/await", "Kotlin", "Git",
                "Docker", "API", "microservices", "Rust", "CI/CD", "refactor"
            )
        ),
        VocabularyProfile(
            id = "history_archaeology",
            name = "History & Archaeology",
            description = "Dating conventions, classical terminology & excavation terms",
            keywords = listOf(
                "BCE", "CE", "stratigraphy", "Neolithic", "carbon dating", "provenance",
                "in situ", "radiocarbon", "artifact", "lithic", "excavation"
            )
        ),
        VocabularyProfile(
            id = "general",
            name = "General / Conversational",
            description = "Polite punctuation, filler word cleanup & standard casing",
            keywords = emptyList()
        )
    )

    fun readSystemUserDictionary(context: Context): List<String> {
        val userWords = mutableListOf<String>()
        try {
            val cursor = context.contentResolver.query(
                UserDictionary.Words.CONTENT_URI,
                arrayOf(UserDictionary.Words.WORD),
                null,
                null,
                null
            )
            cursor?.use {
                val wordIndex = it.getColumnIndex(UserDictionary.Words.WORD)
                while (it.moveToNext()) {
                    if (wordIndex >= 0) {
                        val word = it.getString(wordIndex)
                        if (!word.isNullOrBlank()) {
                            userWords.add(word)
                        }
                    }
                }
            }
            Log.i(TAG, "Loaded ${userWords.size} words from Android personal dictionary")
        } catch (e: Throwable) {
            Log.w(TAG, "Unable to read Android UserDictionary: ${e.message}")
        }
        return userWords
    }
}

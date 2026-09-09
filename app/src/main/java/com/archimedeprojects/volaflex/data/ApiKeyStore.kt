package com.archimedeprojects.volaflex.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.apiKeysDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "api_keys"
)

data class ApiKeys(
    val serpApiKey: String?,
    val searchApiKey: String?
)

data class ApiKeyStatus(
    val serpApiConfigured: Boolean = false,
    val searchApiConfigured: Boolean = false
)

class ApiKeyStore(private val context: Context) {

    private companion object {
        val SERP_API_KEY = stringPreferencesKey("serp_api_key")
        val SEARCH_API_KEY = stringPreferencesKey("search_api_key")
    }

    val keys: Flow<ApiKeys> = context.apiKeysDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            ApiKeys(
                serpApiKey = preferences[SERP_API_KEY],
                searchApiKey = preferences[SEARCH_API_KEY]
            )
        }

    val status: Flow<ApiKeyStatus> = keys.map { apiKeys ->
        ApiKeyStatus(
            serpApiConfigured = !apiKeys.serpApiKey.isNullOrBlank(),
            searchApiConfigured = !apiKeys.searchApiKey.isNullOrBlank()
        )
    }

    suspend fun saveKeys(
        serpApiKeyInput: String,
        searchApiKeyInput: String
    ) {
        val serpApiKey = serpApiKeyInput.trim()
        val searchApiKey = searchApiKeyInput.trim()

        context.apiKeysDataStore.edit { preferences ->
            if (serpApiKey.isNotEmpty()) {
                preferences[SERP_API_KEY] = serpApiKey
            }

            if (searchApiKey.isNotEmpty()) {
                preferences[SEARCH_API_KEY] = searchApiKey
            }
        }
    }
}

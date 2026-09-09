package com.archimedeprojects.volaflex.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.archimedeprojects.volaflex.data.ApiKeyStatus
import com.archimedeprojects.volaflex.data.ApiKeyStore
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    apiKeyStore: ApiKeyStore,
    onBackHome: () -> Unit
) {
    val status by apiKeyStore.status.collectAsState(initial = ApiKeyStatus())
    val scope = rememberCoroutineScope()

    var serpApiKey by rememberSaveable { mutableStateOf("") }
    var searchApiKey by rememberSaveable { mutableStateOf("") }
    var saveMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text = "Impostazioni",
            style = MaterialTheme.typography.headlineMedium
        )

        Text(
            text = "Le API key vengono salvate solo nello storage privato di VolaFlex sul telefono e non vengono mostrate dopo il salvataggio.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp)
        )

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = if (status.serpApiConfigured) {
                "SerpApi: configurata ✓"
            } else {
                "SerpApi: non configurata"
            },
            style = MaterialTheme.typography.titleMedium
        )

        OutlinedTextField(
            value = serpApiKey,
            onValueChange = {
                serpApiKey = it
                saveMessage = null
            },
            label = { Text("SerpApi API key") },
            placeholder = { Text("Incolla la chiave") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Next
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
        )

        Text(
            text = "Se è già configurata, lascia il campo vuoto per mantenerla invariata.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp)
        )

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = if (status.searchApiConfigured) {
                "SearchAPI.io: configurata ✓"
            } else {
                "SearchAPI.io: non configurata (opzionale)"
            },
            style = MaterialTheme.typography.titleMedium
        )

        OutlinedTextField(
            value = searchApiKey,
            onValueChange = {
                searchApiKey = it
                saveMessage = null
            },
            label = { Text("SearchAPI.io API key (opzionale)") },
            placeholder = { Text("Incolla la chiave, se disponibile") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
        )

        Text(
            text = "SearchAPI.io serve solo come acceleratore per alcune ricerche flessibili; VolaFlex deve poter funzionare anche senza questa chiave.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp)
        )

        Button(
            onClick = {
                scope.launch {
                    apiKeyStore.saveKeys(
                        serpApiKeyInput = serpApiKey,
                        searchApiKeyInput = searchApiKey
                    )
                    serpApiKey = ""
                    searchApiKey = ""
                    saveMessage = "Chiavi salvate ✓"
                }
            },
            enabled = serpApiKey.isNotBlank() || searchApiKey.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 32.dp)
        ) {
            Text("Salva")
        }

        saveMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        OutlinedButton(
            onClick = onBackHome,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
        ) {
            Text("Torna alla Home")
        }
    }
}

package com.mavislibrary.app.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun LockScreen(message: String?, onUnlock: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp).testTag("app_lock"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Mavis is locked", style = MaterialTheme.typography.headlineMedium)
        if (message != null) Text(message, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onUnlock) { Text("Unlock") }
    }
}

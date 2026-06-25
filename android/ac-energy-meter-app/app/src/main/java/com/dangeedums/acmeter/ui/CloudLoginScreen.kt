package com.dangeedums.acmeter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun CloudLoginScreen(vm: CloudViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var username by remember { mutableStateOf(ui.username) }
    var password by remember { mutableStateOf("") }
    var baseUrl  by remember { mutableStateOf(ui.baseUrl) }

    LaunchedEffect(ui.username) {
        if (username.isBlank()) username = ui.username
    }
    LaunchedEffect(ui.baseUrl) {
        if (baseUrl.isBlank()) baseUrl = ui.baseUrl
    }

    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Sign in", style = MaterialTheme.typography.headlineSmall)
            Text("Connect to your AC Energy Meter backend.",
                 style = MaterialTheme.typography.bodyMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = baseUrl, onValueChange = { baseUrl = it },
                label = { Text("Server URL") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = username, onValueChange = { username = it },
                label = { Text("Username") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    vm.setBaseUrl(baseUrl.trim())
                    vm.login(username.trim(), password)
                },
                enabled = !ui.loading && username.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (ui.loading) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                }
                Text("Sign in")
            }
        }
    }
}

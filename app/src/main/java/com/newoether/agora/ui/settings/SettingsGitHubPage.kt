package com.newoether.agora.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.newoether.agora.github.GitHubAuthManager
import com.newoether.agora.github.GitHubDeviceCode
import kotlinx.coroutines.launch

@Composable
fun SettingsGitHubPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { GitHubAuthManager(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf(manager.loadSession()) }
    var clientId by remember { mutableStateOf(manager.savedClientId()) }
    var token by remember { mutableStateOf("") }
    var deviceCode by remember { mutableStateOf<GitHubDeviceCode?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    CollapsingSettingsScaffold(title = "GitHub Workbench", onBack = onBack) {
        SettingsGroupColumn {
            SettingsGroup(title = "Account", items = listOf({
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    if (session != null) {
                        Text("Signed in as ${session!!.login}", style = MaterialTheme.typography.titleMedium)
                        if (session!!.scopes.isNotBlank()) Text("Scopes: ${session!!.scopes}", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { manager.logout(); session = null; status = "Signed out" }) { Text("Sign out") }
                    } else {
                        Text("Sign in without placing credentials in chat, shell commands, URLs, or memory files.")
                    }
                }
            }))

            if (session == null) {
                SettingsGroup(title = "GitHub Device Flow", items = listOf({
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        OutlinedTextField(
                            value = clientId,
                            onValueChange = { clientId = it },
                            label = { Text("OAuth App Client ID") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(enabled = !busy && clientId.isNotBlank(), onClick = {
                            busy = true; status = "Requesting device code…"
                            scope.launch {
                                manager.requestDeviceCode(clientId).fold(onSuccess = { code ->
                                    deviceCode = code
                                    status = "Enter code ${code.userCode} in GitHub"
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(code.verificationUri)))
                                    manager.completeDeviceFlow(clientId, code).fold(onSuccess = {
                                        session = it; status = "Signed in as ${it.login}"
                                    }, onFailure = { status = it.message.orEmpty() })
                                }, onFailure = { status = it.message.orEmpty() })
                                busy = false
                            }
                        }) { Text("Sign in with GitHub") }
                        deviceCode?.let {
                            Spacer(Modifier.height(12.dp))
                            Text("Code: ${it.userCode}", style = MaterialTheme.typography.titleLarge)
                            Text(it.verificationUri)
                        }
                    }
                }))

                SettingsGroup(title = "Fine-grained token (fallback)", items = listOf({
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        OutlinedTextField(
                            value = token,
                            onValueChange = { token = it },
                            label = { Text("GitHub token") },
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(enabled = !busy && token.isNotBlank(), onClick = {
                            busy = true; status = "Validating…"
                            scope.launch {
                                manager.loginWithToken(token).fold(onSuccess = {
                                    session = it; token = ""; status = "Signed in as ${it.login}"
                                }, onFailure = { status = it.message.orEmpty() })
                                busy = false
                            }
                        }) { Text("Validate and save") }
                    }
                }))
            }

            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (status.isNotBlank()) Text(status, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.primary)
        }
    }
}

package com.mavislibrary.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mavislibrary.app.AppContainer
import com.mavislibrary.app.data.api.*
import com.mavislibrary.app.data.kids.kidsPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountSettingsSection(container: AppContainer, hasOwnerCode: Boolean) {
    val context = LocalContext.current
    val kids by remember { context.kidsPrefs() }.state.collectAsStateWithLifecycle(initialValue = null)
    if (kids?.enabled != false) return
    val signedInValue by container.accounts.signedIn.collectAsStateWithLifecycle(initialValue = null)
    val signedIn = signedInValue ?: return
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("signin") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var recovery by remember { mutableStateOf<String?>(null) }
    var user by remember { mutableStateOf<AccountUser?>(null) }
    var status by remember { mutableStateOf<SubscriptionStatus?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var requestMessage by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    suspend fun allowed() = !context.kidsPrefs().state.first().enabled
    fun act(action: suspend () -> Unit) { scope.launch {
        if (busy || !allowed()) return@launch
        busy = true; message = null
        try { action() }
        catch (e: CancellationException) { throw e }
        catch (e: ApiException) { message = e.message }
        catch (_: Exception) { message = "Could not complete that request. Please try again." }
        finally { busy = false }
    } }
    LaunchedEffect(signedIn, refresh) {
        user = null; status = null
        if (!signedIn || !allowed()) return@LaunchedEffect
        try {
            user = container.accounts.me()
            status = container.accounts.status()
        } catch (e: CancellationException) { throw e }
        catch (e: ApiException) { message = if (e.status == 401) "Your session ended. Sign out below, then sign in again." else e.message }
        catch (_: Exception) { message = "Could not load your account. Refresh to retry." }
    }
    Card(Modifier.fillMaxWidth().testTag("account_settings")) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Account", style = MaterialTheme.typography.titleLarge)
        if (signedIn) {
            Text(user?.email ?: "Session saved securely on this device")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { refresh++ }) { Text("Refresh account") }
                TextButton(enabled = !busy, onClick = { act {
                    container.accounts.signOut(); user = null; status = null; recovery = null; email = ""; password = ""; code = ""
                    message = "Signed out on this device. Owner access is unchanged."
                } }) { Text("Sign out") }
            }
        } else {
            Text(when (mode) { "signup" -> "Create an account. Save the recovery code shown once afterward—there is no reset email."; "recover" -> "Use your saved recovery code and choose a new password. Your old code will be replaced."; else -> "Sign in for subscriber AI features and Podcast Lounge." })
            OutlinedTextField(email, { email = it.take(254) }, label = { Text("Email") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth().testTag("account_email"))
            if (mode == "recover") OutlinedTextField(code, { code = it.take(200) }, label = { Text("Recovery code") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("account_recovery"))
            OutlinedTextField(password, { password = it.take(1024) }, label = { Text(if (mode == "recover") "New password" else "Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth().testTag("account_password"))
            Button(enabled = !busy && email.isNotBlank() && password.isNotBlank() && (mode != "recover" || code.isNotBlank()), onClick = { act {
                recovery = container.accounts.authenticate(mode, email, password, code)
                password = ""; code = ""; refresh++
            } }, modifier = Modifier.testTag("account_submit")) { Text(if (busy) "Working…" else when (mode) { "signup" -> "Create account"; "recover" -> "Reset password"; else -> "Sign in" }) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("signin" to "Sign in", "signup" to "Create account", "recover" to "Forgot password").filter { it.first != mode }.forEach { (action, label) ->
                    TextButton(enabled = !busy, onClick = { mode = action; password = ""; code = ""; message = null }) { Text(label) }
                }
            }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    } }
    Card(Modifier.fillMaxWidth().testTag("subscription_settings")) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Subscription", style = MaterialTheme.typography.titleLarge)
        if (!signedIn) Text("Sign in to check your access or request a subscription.")
        else if (status == null) Text("Access status not loaded. Refresh account to check.")
        status?.let { sub ->
            Text(when (sub.state) {
                "pending" -> "Waiting for approval"
                "active" -> if (sub.active()) "Active · ${sub.plan ?: "member"}" else "Subscription expired · contact the owner"
                "denied" -> "Request denied"
                "revoked" -> "Access revoked"
                else -> "No subscription"
            })
            if (sub.active()) {
                Text("Today's usage (UTC)")
                sub.usage.forEach { (feature, use) -> Text("${feature.replaceFirstChar(Char::uppercase)}: ${use.used} / ${use.cap}") }
                Text(if (sub.podcastEnabled) "Podcast Lounge enabled" else "Podcast studio is not configured yet")
            }
            if (sub.canRequest()) {
                OutlinedTextField(requestMessage, { requestMessage = it.take(200) }, label = { Text("Message to owner (optional)") }, modifier = Modifier.fillMaxWidth())
                Button(enabled = !busy, onClick = { act { container.accounts.requestAccess(requestMessage); status = container.accounts.status(); requestMessage = "" } }, modifier = Modifier.testTag("subscription_request")) { Text("Request access") }
            } else if (sub.state == "denied") Text("You can request again seven days after the decision.")
        }
    } }
    if (hasOwnerCode) OwnerApprovalsSection(container)
    recovery?.let { value ->
        AlertDialog(onDismissRequest = {}, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn, dismissOnBackPress = false, dismissOnClickOutside = false), title = { Text("Save your recovery code") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Shown once. Write it down privately. It replaces any previous code and is the only way to reset a forgotten password.")
            Text(value, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("new_recovery_code"))
        } }, confirmButton = { TextButton(onClick = { recovery = null }) { Text("I've saved it") } })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OwnerApprovalsSection(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<Subscriber>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var decision by remember { mutableStateOf<Pair<String, Subscriber>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(open, refresh) {
        if (!open) return@LaunchedEffect
        busy = true; message = null
        try { items = container.accounts.subscribers() }
        catch (e: CancellationException) { throw e }
        catch (e: ApiException) { message = e.message }
        catch (_: Exception) { message = "Could not load approvals. Refresh to retry." }
        finally { busy = false }
    }
    Card(Modifier.fillMaxWidth().testTag("owner_approvals")) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Owner approvals", style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = { open = !open }) { Text(if (open) "Close approvals" else "Manage subscribers") }
        if (open) {
            Text("Verify the person's identity before granting access. Email ownership is not verified by signup.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = !busy, onClick = { refresh++ }) { Text("Refresh list") }
            if (busy) Text("Loading…")
            if (!busy && items.isEmpty() && message == null) Text("No pending or active subscribers")
            items.forEach { subscriber ->
                HorizontalDivider()
                Text(subscriber.email, style = MaterialTheme.typography.titleMedium)
                Text("${subscriber.state} · ${subscriber.plan ?: "member"}")
                subscriber.message?.takeIf(String::isNotBlank)?.let { Text(it) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (if (subscriber.state == "pending") listOf("approve", "deny") else listOf("revoke")).forEach { action ->
                        OutlinedButton(enabled = !busy, onClick = { decision = action to subscriber }) { Text(action.replaceFirstChar(Char::uppercase)) }
                    }
                }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    } }
    decision?.let { (action, subscriber) -> AlertDialog(onDismissRequest = { decision = null }, title = { Text("${action.replaceFirstChar(Char::uppercase)} access?") },
        text = { Text("${subscriber.email}\nThis updates subscriber access immediately.") },
        confirmButton = { TextButton(onClick = {
            decision = null
            scope.launch {
                busy = true; message = null
                try { container.accounts.decide(action, subscriber.userId); items = container.accounts.subscribers() }
                catch (e: CancellationException) { throw e }
                catch (e: ApiException) { message = e.message }
                catch (_: Exception) { message = "Decision could not be confirmed. Refresh before trying again." }
                finally { busy = false }
            }
        }) { Text(action.replaceFirstChar(Char::uppercase)) } }, dismissButton = { TextButton(onClick = { decision = null }) { Text("Cancel") } }) }
}

package com.mavislibrary.app.ui.kids

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mavislibrary.app.data.kids.*
import kotlinx.coroutines.launch

@Composable
fun KidsSetup(prefs: KidsPrefs, onStart: () -> Unit = {}) {
    val saved by prefs.state.collectAsState(initial = KidsState())
    var name by remember { mutableStateOf("") }
    var goal by remember { mutableIntStateOf(15) }
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(saved.name, saved.goal) { name = saved.name; goal = saved.goal }
    OutlinedTextField(name, { name = it.take(30) }, label = { KidsText("Child’s name") }, singleLine = true, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp))
    KidsText("A star each day")
    // Two rows keep every chip at least 64 dp wide, even on a phone.
    listOf(listOf(10,15), listOf(20,30)).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { value -> FilterChip(goal == value, { goal = value }, label = { KidsText("$value min") }, modifier = Modifier.weight(1f).heightIn(min = 64.dp)) }
        }
    }
    KidsText("Choose a PIN to leave Kids mode.")
    listOf(false, true).forEach { confirm ->
        OutlinedTextField(if (confirm) again else pin, { value ->
            val digits = value.filter { it in '0'..'9' }.take(4)
            if (confirm) again = digits else pin = digits
            message = null
        }, label = { KidsText(if (confirm) "PIN again" else "4-digit PIN") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp))
    }
    message?.let { KidsText(it) }
    Button(onClick = {
        if (!KidsPin.valid(pin)) message = "Choose 4 numbers."
        else if (pin != again) message = "The PINs don’t match. Try again."
        else { busy = true; scope.launch {
            try { prefs.start(name, goal, pin); pin = ""; again = ""; onStart() }
            catch (_: Exception) { message = "Couldn’t save. Please try again." }
            finally { busy = false }
        } }
    }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)) { KidsText("Start Kids mode") }
}

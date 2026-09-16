package com.openlink.child.ui.requesttime

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * "Ask for more time", reachable from both the block overlay and the home screen.
 *
 * Calling code is responsible for persisting the request (ChildRepository.createRequest), which
 * writes it to the local database and announces it to any connected parent. There is no network
 * call to fail: an unanswered request simply waits.
 */
@Composable
fun RequestTimeDialog(
    packageName: String,
    onDismiss: () -> Unit,
    onSubmit: (minutes: Int, message: String) -> Unit
) {
    var minutesText by remember { mutableStateOf("15") }
    var message by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ask for more time") },
        text = {
            Column {
                Text("Requesting extra time for $packageName", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = minutesText,
                    onValueChange = { if (it.length <= 3 && it.all(Char::isDigit)) minutesText = it },
                    label = { Text("Minutes") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("Message (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val minutes = (minutesText.toIntOrNull() ?: 15).coerceIn(1, 240)
                onSubmit(minutes, message)
            }) { Text("Send request") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

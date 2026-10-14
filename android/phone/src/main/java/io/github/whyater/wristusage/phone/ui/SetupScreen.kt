package io.github.whyater.wristusage.phone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.whyater.wristusage.phone.R
import kotlinx.coroutines.launch

@Composable
fun SetupScreen(
    ui: SetupUi,
    hasSavedUrl: Boolean,
    onInput: (String) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onCancel: () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    BackHandler(enabled = hasSavedUrl && !ui.busy, onBack = onCancel)

    Scaffold(
        containerColor = Hues.Background,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Image(
                painter = painterResource(R.drawable.ic_gauge_mark),
                contentDescription = null,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (hasSavedUrl) "Snapshot URL" else "Connect your usage",
                style = MaterialTheme.typography.headlineMedium,
                color = Hues.TextPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Paste the link to your usage snapshot. It is the last line printed by " +
                    "collector/wrist-usage setup on your Mac.",
                style = MaterialTheme.typography.bodyMedium,
                color = Hues.TextSecondary,
            )
            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = ui.input,
                onValueChange = onInput,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Snapshot URL") },
                placeholder = { Text("https://", color = Hues.TextTertiary) },
                enabled = !ui.busy,
                isError = ui.error != null,
                supportingText = ui.error?.let { message -> { Text(message) } },
                minLines = 2,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Hues.TextSecondary,
                    unfocusedBorderColor = Hues.Track,
                    focusedLabelColor = Hues.TextSecondary,
                ),
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val text = clipboard.getClipEntry()?.clipData
                                ?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)?.text?.toString()
                            if (!text.isNullOrBlank()) onInput(text.trim())
                        }
                    },
                    enabled = !ui.busy,
                ) { Text("Paste") }
                Button(
                    onClick = onSave,
                    enabled = !ui.busy && ui.input.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Hues.TextPrimary,
                        contentColor = Hues.Background,
                    ),
                ) {
                    if (ui.busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Hues.Background,
                        )
                    } else {
                        Text("Save")
                    }
                }
            }

            if (hasSavedUrl) {
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onCancel, enabled = !ui.busy) {
                        Text("Cancel", color = Hues.TextSecondary)
                    }
                    TextButton(onClick = onClear, enabled = !ui.busy) {
                        Text("Clear URL", color = Hues.Critical)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "The link is saved on this phone and sent to your watch. " +
                    "Anyone who has it can read your usage numbers.",
                style = MaterialTheme.typography.bodySmall,
                color = Hues.TextTertiary,
            )
        }
    }
}

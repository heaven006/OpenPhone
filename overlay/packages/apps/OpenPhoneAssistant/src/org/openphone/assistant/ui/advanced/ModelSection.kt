package org.openphone.assistant.ui.advanced

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.openphone.assistant.state.ModelConfig
import org.openphone.assistant.ui.OpenPhoneTheme
import org.openphone.assistant.ui.common.GlassSurface

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelSection(state: ModelConfig, onChange: (ModelConfig) -> Unit) {
    val openAiLive = state.useLiveRealtimeVoice && !state.useGeminiLiveVoice
    val geminiLive = state.useGeminiLiveVoice
    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Voice", style = MaterialTheme.typography.titleMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!openAiLive && !geminiLive) {
                    Button(
                        onClick = { onChange(state.copy(
                            useLiveRealtimeVoice = false,
                            useGeminiLiveVoice = false,
                        )) },
                    ) { Text("Classic") }
                } else {
                    OutlinedButton(
                        onClick = { onChange(state.copy(
                            useLiveRealtimeVoice = false,
                            useGeminiLiveVoice = false,
                        )) },
                    ) { Text("Classic") }
                }
                if (openAiLive) {
                    Button(onClick = { onChange(state.copy(
                        useRealtimeVision = true,
                        useRealtime2 = true,
                        useLiveRealtimeVoice = true,
                        useGeminiLiveVoice = false,
                        useBroker = false,
                    )) }) { Text("Live Realtime 2") }
                } else {
                    OutlinedButton(onClick = { onChange(state.copy(
                        useRealtimeVision = true,
                        useRealtime2 = true,
                        useLiveRealtimeVoice = true,
                        useGeminiLiveVoice = false,
                        useBroker = false,
                    )) }) { Text("Live Realtime 2") }
                }
                if (geminiLive) {
                    Button(onClick = { onChange(state.copy(
                        useLiveRealtimeVoice = false,
                        useGeminiLiveVoice = true,
                        useBroker = false,
                    )) }) { Text("Gemini Live") }
                } else {
                    OutlinedButton(onClick = { onChange(state.copy(
                        useLiveRealtimeVoice = false,
                        useGeminiLiveVoice = true,
                        useBroker = false,
                    )) }) { Text("Gemini Live") }
                }
            }
            Text(
                text = if (geminiLive) {
                    "Volume buttons start a Gemini Live session with streamed screen frames."
                } else if (openAiLive) {
                    "Volume buttons start a live gpt-realtime-2 voice session."
                } else {
                    "Volume buttons use the current OpenPhone voice command flow."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (!openAiLive && !geminiLive) {
                SettingRow(
                    "Use broker",
                    checked = state.useBroker,
                    onCheckedChange = { onChange(state.copy(useBroker = it)) },
                )
            }
            if (geminiLive) {
                OutlinedTextField(
                    value = state.geminiApiKey,
                    onValueChange = { onChange(state.copy(geminiApiKey = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Gemini API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            } else {
                OutlinedTextField(
                    value = state.devApiKey,
                    onValueChange = { onChange(state.copy(devApiKey = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("OpenAI API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
            if (!openAiLive && !geminiLive && state.useBroker) {
                OutlinedTextField(
                    value = state.brokerUrl,
                    onValueChange = { onChange(state.copy(brokerUrl = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Broker URL") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = state.brokerToken,
                    onValueChange = { onChange(state.copy(brokerToken = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Broker token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
            Text(voiceDisclosure(state), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun voiceDisclosure(state: ModelConfig): String {
    return if (state.useGeminiLiveVoice) {
        "Gemini Live uses gemini-3.1-flash-live-preview for a live speech-to-speech session with about one streamed screen frame per second. Phone actions still run through OpenPhone tools."
    } else if (state.useLiveRealtimeVoice) {
        "Live Realtime 2 uses gpt-realtime-2 for a live speech-to-speech session. Mic audio streams while the session is active; phone actions still run through OpenPhone tools."
    } else if (state.useBroker) {
        "Classic records one command, sends it to the configured broker, then runs the existing OpenPhone agent."
    } else {
        "Classic records one command, transcribes it with OpenAI, then runs the existing OpenPhone agent."
    }
}

@Composable
internal fun SettingRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Preview(showBackground = true)
@Composable
private fun ModelSectionPreview() {
    OpenPhoneTheme {
        ModelSection(
            ModelConfig(
                useRealtimeVision = true,
                useRealtime2 = true,
                useLiveRealtimeVoice = true,
            ),
            {},
        )
    }
}

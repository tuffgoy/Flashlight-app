package com.flashlightapp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flashlightapp.ui.theme.*
import java.util.UUID

/**
 * Settings screen for configuring trigger words and recognition language.
 *
 * - TEXT triggers: substring match — the spoken text must *contain* the phrase.
 * - VOICE triggers: Jaro-Winkler similarity against a recorded reference phrase;
 *   the similarity percentage must meet or exceed the configured threshold.
 *
 * Up to 3 triggers per command. Changes are committed on "Save & Close".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    isRecording: Boolean,
    onSave: (AppSettings) -> Unit,
    onRecordPhrase: (onResult: (String) -> Unit) -> Unit,
    onBack: () -> Unit
) {
    var local by remember(settings) { mutableStateOf(settings) }

    Scaffold(
        containerColor = DeepCharcoal,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Trigger Settings",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { onSave(local); onBack() }) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceCharcoal
                )
            )
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceCharcoal)
                    .padding(16.dp)
            ) {
                Button(
                    onClick = { onSave(local); onBack() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = YellowTorch),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        "Save & Close",
                        color = DeepCharcoal,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                LanguageSelector(
                    selected = local.language,
                    onSelected = { local = local.copy(language = it) }
                )
            }

            item { SectionDivider() }

            item {
                TriggerGroupSection(
                    title = "Turn On Triggers",
                    color = OnGreen,
                    triggers = local.turnOnTriggers,
                    isRecording = isRecording,
                    onChanged = { local = local.copy(turnOnTriggers = it) },
                    onRecordPhrase = onRecordPhrase
                )
            }

            item { SectionDivider() }

            item {
                TriggerGroupSection(
                    title = "Turn Off Triggers",
                    color = OffRed,
                    triggers = local.turnOffTriggers,
                    isRecording = isRecording,
                    onChanged = { local = local.copy(turnOffTriggers = it) },
                    onRecordPhrase = onRecordPhrase
                )
            }

            item { SectionDivider() }

            item {
                TriggerGroupSection(
                    title = "Shutdown Triggers",
                    color = YellowTorchDim,
                    triggers = local.shutdownTriggers,
                    isRecording = isRecording,
                    onChanged = { local = local.copy(shutdownTriggers = it) },
                    onRecordPhrase = onRecordPhrase
                )
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

// ---------------------------------------------------------------------------
// Language selector
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSelector(selected: String, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = SUPPORTED_LANGUAGES.firstOrNull { it.first == selected }?.second ?: selected

    SettingsCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 10.dp)
        ) {
            Icon(Icons.Default.Language, contentDescription = null, tint = YellowTorch, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Recognition Language", color = Color.White, fontWeight = FontWeight.SemiBold)
        }

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = label,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = YellowTorch,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                    focusedTextColor     = Color.White,
                    unfocusedTextColor   = Color.White,
                    focusedTrailingIconColor   = YellowTorch,
                    unfocusedTrailingIconColor = Color.White.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(10.dp)
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(SurfaceCharcoal)
            ) {
                SUPPORTED_LANGUAGES.forEach { (code, name) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                name,
                                color = if (code == selected) YellowTorch else Color.White
                            )
                        },
                        onClick = { onSelected(code); expanded = false }
                    )
                }
            }
        }

        Text(
            "The language your microphone listens for.",
            color = Color.White.copy(alpha = 0.45f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// Trigger group
// ---------------------------------------------------------------------------

@Composable
private fun TriggerGroupSection(
    title: String,
    color: Color,
    triggers: List<TriggerWord>,
    isRecording: Boolean,
    onChanged: (List<TriggerWord>) -> Unit,
    onRecordPhrase: (onResult: (String) -> Unit) -> Unit
) {
    SettingsCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(8.dp))
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }

        triggers.forEachIndexed { index, trigger ->
            TriggerItem(
                trigger = trigger,
                canDelete = triggers.size > 1,
                isRecording = isRecording,
                onChanged = { updated ->
                    onChanged(triggers.toMutableList().also { it[index] = updated })
                },
                onDelete = {
                    onChanged(triggers.toMutableList().also { it.removeAt(index) })
                },
                onRecordPhrase = onRecordPhrase
            )
            if (index < triggers.lastIndex) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                Spacer(Modifier.height(8.dp))
            }
        }

        if (triggers.size < 3) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { onChanged(triggers + TriggerWord()) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = color)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add Trigger", fontWeight = FontWeight.Medium)
            }
        } else {
            Text(
                "Maximum 3 triggers per command",
                color = Color.White.copy(alpha = 0.35f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Individual trigger item
// ---------------------------------------------------------------------------

@Composable
private fun TriggerItem(
    trigger: TriggerWord,
    canDelete: Boolean,
    isRecording: Boolean,
    onChanged: (TriggerWord) -> Unit,
    onDelete: () -> Unit,
    onRecordPhrase: (onResult: (String) -> Unit) -> Unit
) {
    Column {
        // Type toggle + delete
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TypeChip(
                label = "Text",
                icon = { Icon(Icons.Default.TextFields, null, modifier = Modifier.size(14.dp)) },
                selected = trigger.type == TriggerType.TEXT,
                onClick = { onChanged(trigger.copy(type = TriggerType.TEXT)) }
            )
            TypeChip(
                label = "Voice",
                icon = { Icon(Icons.Default.GraphicEq, null, modifier = Modifier.size(14.dp)) },
                selected = trigger.type == TriggerType.VOICE,
                onClick = { onChanged(trigger.copy(type = TriggerType.VOICE)) }
            )
            Spacer(Modifier.weight(1f))
            if (canDelete) {
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove", tint = OffRed.copy(alpha = 0.7f))
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Content based on type
        AnimatedVisibility(visible = trigger.type == TriggerType.TEXT) {
            OutlinedTextField(
                value = trigger.text,
                onValueChange = { onChanged(trigger.copy(text = it)) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Word or phrase to match", color = Color.White.copy(alpha = 0.3f)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = ImeAction.Done.let { KeyboardCapitalization.None },
                    imeAction = ImeAction.Done
                ),
                colors = textFieldColors(),
                shape = RoundedCornerShape(10.dp)
            )
        }

        AnimatedVisibility(visible = trigger.type == TriggerType.VOICE) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Recorded phrase display
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(CardSurface)
                        .padding(12.dp)
                ) {
                    if (trigger.referencePhrase.isBlank()) {
                        Text(
                            "No sample recorded yet",
                            color = Color.White.copy(alpha = 0.35f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Column {
                            Text(
                                "Reference phrase:",
                                color = Color.White.copy(alpha = 0.5f),
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                "\"${trigger.referencePhrase}\"",
                                color = Color.White,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // Record button
                Button(
                    onClick = {
                        onRecordPhrase { result ->
                            if (result.isNotBlank()) onChanged(trigger.copy(referencePhrase = result))
                        }
                    },
                    enabled = !isRecording,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Passive,
                        disabledContainerColor = Passive.copy(alpha = 0.4f)
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (isRecording) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Listening…", fontWeight = FontWeight.Medium)
                    } else {
                        Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Record Sample", fontWeight = FontWeight.Medium)
                    }
                }

                // Similarity threshold slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Min. Similarity",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            "${"%.0f".format(trigger.similarityThreshold * 100)}%",
                            color = YellowTorch,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Slider(
                        value = trigger.similarityThreshold,
                        onValueChange = { onChanged(trigger.copy(similarityThreshold = it)) },
                        valueRange = 0.50f..0.99f,
                        colors = SliderDefaults.colors(
                            thumbColor       = YellowTorch,
                            activeTrackColor = YellowTorch,
                            inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                        )
                    )
                    Text(
                        "How closely your voice must match the recorded sample (50–99%)",
                        color = Color.White.copy(alpha = 0.35f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------

@Composable
private fun TypeChip(
    label: String,
    icon: @Composable () -> Unit,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bg = if (selected) YellowTorch else CardSurface
    val fg = if (selected) DeepCharcoal else Color.White.copy(alpha = 0.6f)
    Surface(
        onClick = onClick,
        color = bg,
        contentColor = fg,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.height(30.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp)
        ) {
            CompositionLocalProvider(LocalContentColor provides fg) { icon() }
            Spacer(Modifier.width(4.dp))
            Text(label, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = fg)
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCharcoal)
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        color = Color.White.copy(alpha = 0.06f),
        modifier = Modifier.padding(vertical = 2.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun textFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor     = YellowTorch,
    unfocusedBorderColor   = Color.White.copy(alpha = 0.25f),
    focusedTextColor       = Color.White,
    unfocusedTextColor     = Color.White,
    cursorColor            = YellowTorch
)

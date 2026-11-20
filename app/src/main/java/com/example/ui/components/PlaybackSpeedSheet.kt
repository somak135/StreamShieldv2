package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.PLAYBACK_SPEED_PRESETS
import com.example.data.formatSpeedBadge
import com.example.ui.theme.ShieldCoral
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlaybackSpeedBottomSheet(
    currentSpeed: Float,
    rememberSpeed: Boolean,
    onSpeedSelected: (Float) -> Unit,
    onToggleRememberSpeed: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    /** The sleep timer row ("23 min" / "end" while it runs); null hides it. */
    sleepLabel: String = "",
    onOpenSleepTimer: (() -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Dragging updates this locally; the speed is saved and applied once, when the drag ends.
    var sliderSpeed by remember(currentSpeed) { mutableFloatStateOf(currentSpeed) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = "Playback Speed",
                        tint = ShieldCoral,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Custom Playback Speed",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "Applies live to the video you are watching",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                OutlinedButton(
                    onClick = { onSpeedSelected(1.0f) },
                    modifier = Modifier.testTag("reset_speed_button"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.RestartAlt,
                        contentDescription = "Reset to 1.0x",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("1.0x", style = MaterialTheme.typography.labelMedium)
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Fine-tune Stepper Card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(
                            onClick = { onSpeedSelected((currentSpeed - 0.05f).coerceAtLeast(0.25f)) },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface)
                                .testTag("speed_decrease_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Remove,
                                contentDescription = "Decrease speed by 0.05x"
                            )
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = formatSpeedBadge(sliderSpeed),
                                style = MaterialTheme.typography.displayMedium,
                                color = ShieldCoral
                            )
                            Text(
                                text = when {
                                    currentSpeed < 0.95f -> "Slow-Motion Study Mode"
                                    currentSpeed <= 1.05f -> "Normal Cinema Speed"
                                    currentSpeed <= 1.5f -> "Brisk Lecture / Podcast Pace"
                                    else -> "Hyper-Speed Skimming"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(
                            onClick = { onSpeedSelected((currentSpeed + 0.05f).coerceAtMost(3.0f)) },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface)
                                .testTag("speed_increase_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Increase speed by 0.05x"
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Slider(
                        value = sliderSpeed,
                        onValueChange = { sliderSpeed = it },
                        onValueChangeFinished = { onSpeedSelected(sliderSpeed) },
                        valueRange = 0.25f..3.0f,
                        steps = 54,
                        colors = SliderDefaults.colors(
                            thumbColor = ShieldCoral,
                            activeTrackColor = ShieldCoral
                        ),
                        modifier = Modifier.testTag("speed_slider")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("0.25x", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("1.0x", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("2.0x", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("3.0x", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Quick Presets",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PLAYBACK_SPEED_PRESETS.forEach { preset ->
                    val isSelected = abs(currentSpeed - preset) < 0.02f
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSpeedSelected(preset) },
                        label = {
                            Text(
                                text = formatSpeedBadge(preset),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ShieldCoral,
                            selectedLabelColor = Color.White
                        ),
                        modifier = Modifier.testTag("speed_preset_${preset}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Lock Speed Across Videos",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "Automatically apply ${formatSpeedBadge(currentSpeed)} when navigating to a new YouTube video",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = rememberSpeed,
                    onCheckedChange = onToggleRememberSpeed,
                    modifier = Modifier.testTag("remember_speed_switch")
                )
            }
            if (onOpenSleepTimer != null) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenSleepTimer)
                        .padding(vertical = 12.dp)
                        .testTag("speed_sheet_sleep_timer"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Bedtime, contentDescription = null, tint = if (sleepLabel.isNotBlank()) ShieldCoral else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Sleep timer", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(
                        when {
                            sleepLabel.isBlank() -> "Off"
                            sleepLabel == "end" -> "End of video"
                            else -> sleepLabel
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (sleepLabel.isNotBlank()) ShieldCoral else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

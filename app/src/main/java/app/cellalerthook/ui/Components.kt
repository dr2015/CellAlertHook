package app.cellalerthook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import app.cellalerthook.R
import app.cellalerthook.data.db.TriggerCommand
import app.cellalerthook.data.db.TriggerStatus
import app.cellalerthook.domain.TriggerAction
import app.cellalerthook.ui.theme.LocalStatusPalette
import app.cellalerthook.ui.theme.TechnicalTextStyle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

enum class StatusLevel { OK, WARN, BAD, NEUTRAL }

@Composable
fun statusColor(level: StatusLevel): Color {
    val palette = LocalStatusPalette.current
    return when (level) {
        StatusLevel.OK -> palette.ok
        StatusLevel.WARN -> palette.warn
        StatusLevel.BAD -> palette.bad
        StatusLevel.NEUTRAL -> palette.neutral
    }
}

@Composable
fun SectionCard(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            title?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            content()
        }
    }
}

/** Status is carried by the word as well as the colour, so it survives a poor screen. */
@Composable
fun StatusRow(label: String, value: String, level: StatusLevel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(statusColor(level)),
        )
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = statusColor(level))
    }
}

@Composable
fun KeyValueRow(label: String, value: String, technical: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(132.dp),
        )
        if (technical) {
            TechnicalText(value, Modifier.weight(1f))
        } else {
            Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }
    }
}

/** Identifiers stay left to right inside an Arabic layout, or they would read scrambled. */
@Composable
fun TechnicalText(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Text(
        text,
        modifier = modifier,
        color = color,
        style = TechnicalTextStyle.merge(TextStyle(textDirection = TextDirection.Ltr, textAlign = TextAlign.Start)),
    )
}

/** Alert text takes its direction from its own content, whatever the UI language is. */
@Composable
fun AlertMessageText(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text,
        modifier = modifier,
        maxLines = maxLines,
        style = MaterialTheme.typography.bodyLarge.merge(TextStyle(textDirection = TextDirection.Content)),
    )
}

/** What became of a trigger command, in the operator's language. */
@Composable
fun triggerSummary(command: TriggerCommand): String {
    val action = if (command.action == TriggerAction.ACTIVATE) R.string.trigger_action_activate else R.string.trigger_action_clear
    val status = when (command.status) {
        TriggerStatus.PENDING -> R.string.trigger_status_pending
        TriggerStatus.SENT -> R.string.trigger_status_sent
        TriggerStatus.SUPERSEDED -> R.string.trigger_status_superseded
        TriggerStatus.REPEAT -> R.string.trigger_status_repeat
        TriggerStatus.EXPIRED -> R.string.trigger_status_expired
        TriggerStatus.OUTSIDE_HOURS -> R.string.trigger_status_outside_hours
    }
    return "${stringResource(action)}: ${stringResource(status)}"
}

@Composable
fun Badge(text: String, level: StatusLevel) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(statusColor(level).copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = statusColor(level))
    }
}

private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())

fun formatTime(epochMs: Long): String = timeFormatter.format(Instant.ofEpochMilli(epochMs))

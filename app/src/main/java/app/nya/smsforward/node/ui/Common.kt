package app.nya.smsforward.node.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckCircle
import androidx.activity.compose.BackHandler
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A titled surface, the building block of every screen. */
@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, icon: ImageVector? = null, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = MaterialTheme.shapes.medium,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (icon != null) Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

/** The screen header: an optional back arrow, the title, and actions on the right. */
@Composable
fun NyaTopBar(title: String, onBack: (() -> Unit)? = null, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = if (onBack == null) 20.dp else 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = if (onBack == null) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        actions()
    }
}

/**
 * Replaces a screen's header while items are selected (long-press one to start): the count, select all / none, and the
 * batch [actions]. Back leaves selection mode instead of the screen.
 */
@Composable
fun SelectionBar(count: Int, total: Int, onClose: () -> Unit, onSelectAll: (Boolean) -> Unit, actions: @Composable RowScope.() -> Unit) {
    BackHandler(onBack = onClose)
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "取消选择") }
            Text(
                "已选 $count 项",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
            )
            val all = count >= total && total > 0
            TextButton(onClick = { onSelectAll(!all) }) { Text(if (all) "全不选" else "全选") }
            actions()
        }
    }
}

/** The leading mark of a selectable row: a filled check when selected, an empty ring otherwise. */
@Composable
fun SelectMark(selected: Boolean, size: Int = 24) {
    if (selected) {
        Icon(Icons.Filled.CheckCircle, contentDescription = "已选", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(size.dp))
    } else {
        Box(
            Modifier.size(size.dp).padding(2.dp)
                .background(MaterialTheme.colorScheme.outline, CircleShape)
                .padding(2.dp)
                .background(MaterialTheme.colorScheme.surface, CircleShape),
        )
    }
}

/** Adds [key] to the selection, or takes it out. */
fun <T> Set<T>.toggle(key: T): Set<T> = if (key in this) this - key else this + key

enum class Tone { OK, WARN, BAD, INFO }

@Composable
fun toneColors(tone: Tone): Pair<Color, Color> = when (tone) {
    Tone.OK -> Color(0xFFE7F6EC) to Color(0xFF067647)
    Tone.WARN -> Color(0xFFFFF4E0) to Color(0xFF93370D)
    Tone.BAD -> Color(0xFFFEECEB) to MaterialTheme.colorScheme.error
    Tone.INFO -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
}

/** A coloured note with an icon, e.g. "接收短信权限未授予" with its fix as [action]. */
@Composable
fun Banner(tone: Tone, icon: ImageVector, text: String, action: (@Composable () -> Unit)? = null) {
    val (bg, fg) = toneColors(tone)
    Surface(color = bg, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
            Text(text, color = fg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
            action?.invoke()
        }
    }
}

/** A small rounded label: "已上报", "SIM1", "待上报 3". */
@Composable
fun Pill(text: String, tone: Tone = Tone.INFO) {
    val (bg, fg) = toneColors(tone)
    Text(
        text,
        color = fg,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** A round icon badge used at the start of list rows. */
@Composable
fun IconBadge(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.primary, background: Color = MaterialTheme.colorScheme.primaryContainer) {
    Box(Modifier.size(40.dp).background(background, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

fun formatTime(millis: Long): String =
    if (millis <= 0) "从未" else SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(millis))

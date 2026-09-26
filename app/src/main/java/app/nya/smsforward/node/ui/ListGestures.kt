package app.nya.smsforward.node.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * What a swipe does. [run] returns true when the row goes away (deleted, moved): it then stays swiped out until the list
 * reloads without it; false puts the row back (e.g. "标为已读", or an action that failed).
 */
class SwipeAction(val icon: ImageVector, val label: String, val color: Color, val run: suspend () -> Boolean)

/**
 * A list row that can be swiped: right for [start], left for [end] (either may be null). The coloured background says
 * what letting go will do. Swiping is off while [enabled] is false, e.g. during multi-select.
 */
@Composable
fun SwipeRow(start: SwipeAction?, end: SwipeAction?, enabled: Boolean = true, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(positionalThreshold = { it * 0.3f })
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = start != null,
        enableDismissFromEndToStart = end != null,
        gesturesEnabled = enabled && (start != null || end != null),
        onDismiss = { value ->
            val action = if (value == SwipeToDismissBoxValue.StartToEnd) start else end
            scope.launch {
                val gone = action?.run?.invoke() == true
                if (!gone) state.reset()
            }
        },
        backgroundContent = {
            val toEnd = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val action = when (state.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> start
                SwipeToDismissBoxValue.EndToStart -> end
                SwipeToDismissBoxValue.Settled -> null
            }
            if (action != null) {
                Row(
                    Modifier.fillMaxSize().background(action.color).padding(horizontal = 24.dp),
                    horizontalArrangement = if (toEnd) Arrangement.Start else Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(action.icon, contentDescription = null, tint = Color.White)
                    Text("  ${action.label}", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        },
    ) {
        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) { content() }
    }
}

/** Replaces the screen title during multi-select: close, how many are picked, and what can be done with them. */
@Composable
fun SelectionTopBar(count: Int, onClose: () -> Unit, actions: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer).padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "退出多选", tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        AnimatedContent(count, label = "count", modifier = Modifier.weight(1f)) { n ->
            Text("已选 $n 项", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        actions()
    }
}

/** The round check that stands in for an avatar (or the start of a row) while it is selected. */
@Composable
fun SelectedMark(size: Int = 44) {
    Box(Modifier.size(size.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Check, contentDescription = "已选", tint = MaterialTheme.colorScheme.onPrimary)
    }
}

/** Adds [key] to the selection, or takes it out. */
fun <K> Set<K>.toggle(key: K): Set<K> = if (key in this) this - key else this + key

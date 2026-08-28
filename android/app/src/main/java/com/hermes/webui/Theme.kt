package com.hermes.webui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Tokens from hermes-webui `style.css` :root.dark (default navy + gold). */
object Wui {
    val Bg = Color(0xFF0D0D1A)
    val Sidebar = Color(0xFF141425)
    val Surface = Color(0xFF1A1A2E)
    val Text = Color(0xFFFFF8DC)
    val Strong = Color(0xFFFFF8EB)
    val Muted = Color(0xFFB8B8C8)
    val Accent = Color(0xFFFFD700)
    val AccentHover = Color(0xFFFFE44D)
    val AccentText = Color(0xFFE4C28D)
    val AccentBg = Color(0x14FFD700)
    val AccentBgStrong = Color(0x24FFD700)
    val Border = Color(0xFF2A2A45)
    val Border2 = Color(0x2EFFD700)
    val InputBg = Color(0x0AFFFFFF)
    val Hover = Color(0x0FFFFFFF)
    val Danger = Color(0xFFFF6B6B)
    val Ok = Color(0xFF7DCEA0)
    val UserBubble = Color(0x24FFD700)
    val UserBubbleBorder = Color(0x38FFD700)
    val CodeBg = Color(0xFF11111F)
}

val WuiShapeSm = RoundedCornerShape(8.dp)
val WuiShapeMd = RoundedCornerShape(12.dp)
val WuiShapeLg = RoundedCornerShape(16.dp)

fun Panel.icon(): ImageVector = when (this) {
    Panel.Chat -> Icons.Outlined.ChatBubbleOutline
    Panel.Tasks -> Icons.Outlined.CalendarMonth
    Panel.Kanban -> Icons.Outlined.ViewColumn
    Panel.Skills -> Icons.Outlined.Layers
    Panel.Memory -> Icons.Outlined.Memory
    Panel.Spaces -> Icons.Outlined.Folder
    Panel.Profiles -> Icons.Outlined.Person
    Panel.Todos -> Icons.Outlined.CheckBox
    Panel.Insights -> Icons.Outlined.BarChart
    Panel.Files -> Icons.Outlined.Description
    Panel.Terminal -> Icons.Outlined.Terminal
    Panel.Logs -> Icons.Outlined.Description
    Panel.Settings -> Icons.Outlined.Settings
}

@Composable
fun HermesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Wui.Accent,
            onPrimary = Wui.Bg,
            secondary = Wui.AccentText,
            onSecondary = Wui.Bg,
            background = Wui.Bg,
            onBackground = Wui.Text,
            surface = Wui.Surface,
            onSurface = Wui.Text,
            surfaceVariant = Wui.Sidebar,
            onSurfaceVariant = Wui.Muted,
            outline = Wui.Border,
            error = Wui.Danger,
            onError = Wui.Text,
        ),
        typography = MaterialTheme.typography.copy(
            titleLarge = TextStyle(color = Wui.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
            titleMedium = TextStyle(color = Wui.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.15).sp),
            bodyLarge = TextStyle(color = Wui.Text, fontSize = 15.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(color = Wui.Text, fontSize = 14.sp, lineHeight = 22.sp),
            labelSmall = TextStyle(color = Wui.Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium),
        ),
        content = content,
    )
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Wui.Text,
    unfocusedTextColor = Wui.Text,
    focusedBorderColor = Wui.Accent,
    unfocusedBorderColor = Wui.Border,
    cursorColor = Wui.Accent,
    focusedLabelColor = Wui.Muted,
    unfocusedLabelColor = Wui.Muted,
    focusedPlaceholderColor = Wui.Muted,
    unfocusedPlaceholderColor = Wui.Muted,
    focusedContainerColor = Wui.Surface,
    unfocusedContainerColor = Wui.Surface,
)

@Composable
fun WuiChip(text: String, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    val mod = Modifier
        .clip(RoundedCornerShape(999.dp))
        .background(if (selected) Wui.AccentBg else Color(0x0DFFFFFF))
        .border(1.dp, if (selected) Wui.AccentBgStrong else Wui.Border2, RoundedCornerShape(999.dp))
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 10.dp, vertical = 4.dp)
    Text(text, color = if (selected) Wui.AccentText else Wui.Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = mod)
}

@Composable
fun WuiCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(WuiShapeMd)
            .background(Wui.Surface)
            .border(1.dp, Wui.Border, WuiShapeMd)
            .padding(14.dp),
    ) { content() }
}

@Composable
fun RoleDot(letter: String, user: Boolean) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (user) Wui.AccentBg else Wui.AccentBgStrong)
            .border(1.dp, Wui.AccentBgStrong, CircleShape)
            .padding(5.dp),
    ) {
        Text(letter, color = Wui.AccentText, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

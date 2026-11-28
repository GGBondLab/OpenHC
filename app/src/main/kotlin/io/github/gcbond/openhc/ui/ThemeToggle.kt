package io.github.gcbond.openhc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.settings.AppSettings

/** 极简紧凑的深浅色切换胶囊：自动（随车机大灯/昼夜切换）、浅色、深色。 */
@Composable
internal fun ThemeToggle(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember(context) { AppSettings(context) }
    val compact = LocalCompact.current
    val mode = Palette.themeMode
    val pick = { next: Int ->
        settings.themeMode = next
        Palette.themeMode = next
        Palette.dark = settings.resolveDark(context)
    }
    Row(
        modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(Palette.SegmentBackground)
            .padding(3.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ThemeTextChoice(
            label = stringResource(R.string.theme_auto),
            selected = mode == AppSettings.THEME_AUTO,
            compact = compact,
        ) { pick(AppSettings.THEME_AUTO) }
        ThemeChoice(Glyphs.Sun, stringResource(R.string.theme_light), mode == AppSettings.THEME_LIGHT, Palette.Sun, compact) {
            pick(AppSettings.THEME_LIGHT)
        }
        ThemeChoice(Glyphs.Moon, stringResource(R.string.theme_dark), mode == AppSettings.THEME_DARK, Palette.Moon, compact) {
            pick(AppSettings.THEME_DARK)
        }
    }
}

@Composable
private fun ThemeTextChoice(
    label: String,
    selected: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        Modifier
            .size(if (compact) 42.dp else 46.dp, if (compact) 32.dp else 34.dp)
            .clip(shape)
            .background(if (selected) Palette.SegmentSelected else Color.Transparent)
            .then(if (selected) Modifier.border(0.5.dp, Palette.SegmentSelectedBorder, shape) else Modifier)
            .dpadFocusRing(shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = UiType.footer(compact).copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
            color = if (selected) Palette.Text else Palette.Muted,
            maxLines = 1,
        )
    }
}

@Composable
private fun ThemeChoice(
    glyph: ImageVector,
    label: String,
    selected: Boolean,
    selectedTint: Color,
    compact: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        Modifier
            .size(if (compact) 36.dp else 40.dp, if (compact) 32.dp else 34.dp)
            .clip(shape)
            .background(if (selected) Palette.SegmentSelected else Color.Transparent)
            .then(if (selected) Modifier.border(0.5.dp, Palette.SegmentSelectedBorder, shape) else Modifier)
            .dpadFocusRing(shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(glyph, contentDescription = label, tint = if (selected) selectedTint else Palette.Muted, modifier = Modifier.size(17.dp))
    }
}

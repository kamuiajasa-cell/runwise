package id.kamui.runsense

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Chip bulat seperti `.fchip` / `.chip` di runsense.html. */
@Composable
internal fun RunChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(99.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (selected) Lime.copy(alpha = .14f) else Elev)
            .border(1.dp, if (selected) Lime else Edge, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) Lime else Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Panel filter `.filter-dock`: label kecil di kiri, chip bisa digeser di kanan. */
@Composable
internal fun FilterDock(label: String, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(17.dp)
    Row(
        Modifier.fillMaxWidth().background(Panel, shape).border(1.dp, Edge, shape).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(label, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp, maxLines = 1)
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            content = content
        )
    }
}

/** Ubin ringkasan `.hist-sum .hs`. */
@Composable
internal fun StatTile(value: String, label: String, highlight: Boolean = false, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(15.dp)
    Column(
        modifier.background(Elev, shape).border(1.dp, Edge, shape).padding(horizontal = 9.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(value, color = if (highlight) Lime else White, fontSize = 16.sp, fontWeight = FontWeight.Black, maxLines = 1)
        Text(label, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Tombol utama (hijau) atau sekunder (outline), radius 15dp seperti `.btn`. */
@Composable
internal fun RunButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true
) {
    if (primary) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = 48.dp),
            shape = RoundedCornerShape(15.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = OnLime)
        ) { Text(text, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp) }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = 48.dp),
            shape = RoundedCornerShape(15.dp),
            border = BorderStroke(1.dp, Edge),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = White)
        ) { Text(text, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
    }
}

/** Judul grup pengaturan (`.sg-t`) + kartu `.sgroup` berisi baris-baris. */
@Composable
internal fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(title.uppercase(), color = Muted, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.4.sp, modifier = Modifier.padding(start = 4.dp))
        Column(
            Modifier.fillMaxWidth().background(Panel, shape).border(1.dp, Edge, shape).clip(shape).padding(horizontal = 16.dp),
            content = content
        )
    }
}

/** Garis pemisah antar baris di dalam grup. */
@Composable
internal fun GroupDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Edge))
}

/** Baris pengaturan: judul + keterangan di kiri, nilai / panah di kanan. */
@Composable
internal fun SettingRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    valueColor: Color = Muted
) {
    val base = Modifier.fillMaxWidth()
    Row(
        (if (onClick != null) base.clickable(onClick = onClick) else base).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (!subtitle.isNullOrBlank()) Text(subtitle, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
        if (!value.isNullOrBlank()) Text(value, color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        if (onClick != null) Text("›", color = Muted, fontSize = 20.sp)
    }
}

/** Baris bebas (konten sendiri) dengan padding vertikal yang seragam. */
@Composable
internal fun GroupBlock(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

/** Titik status sinkron untuk `.sync-bar`. */
@Composable
internal fun SyncBar(text: String, active: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(if (active) Lime else Muted, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Muted, fontSize = 11.sp)
    }
}

internal val ScreenPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp)

package org.melodist.mobile.ui.settings.sections

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.melodist.api.PlaybackCredentials
import org.melodist.mobile.ui.components.SettingsGroupCard
import org.melodist.mobile.ui.components.SettingsGroupTitle

@Composable
fun SettingsAboutSection(
    playbackCreds: PlaybackCredentials?,
    onTriggerCredsDialog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var versionClickCount by remember { mutableIntStateOf(0) }
    var lastVersionClickTime by remember { mutableLongStateOf(0L) }

    Column(modifier = modifier) {
        SettingsGroupTitle(title = "关于")
        SettingsGroupCard {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            val now = System.currentTimeMillis()
                            if (now - lastVersionClickTime > 2500L) {
                                versionClickCount = 1
                            } else {
                                versionClickCount++
                            }
                            lastVersionClickTime = now
                            if (versionClickCount in 7..9) {
                                Toast
                                    .makeText(
                                        context,
                                        "再点击 ${10 - versionClickCount} 次开启凭据管理",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                            } else if (versionClickCount >= 10) {
                                versionClickCount = 0
                                onTriggerCredsDialog()
                            }
                        }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Melodist Mobile",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "版本 1.0.0 (Compose for Mobile)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (playbackCreds != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "已启用独立播放凭证: ${playbackCreds.nick.ifBlank { playbackCreds.uin }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

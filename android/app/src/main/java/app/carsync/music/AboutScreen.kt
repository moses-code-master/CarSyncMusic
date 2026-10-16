package app.carsync.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable fun AboutScreen(onLicense: () -> Unit, onMessage: (String) -> Unit) {
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(72.dp).background(Color(0xFF243B28), RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_wave), null, tint = Color(0xFFB4F36D), modifier = Modifier.size(46.dp))
            }
            Column {
                Text(CreatorBrand.PRODUCT, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(CreatorBrand.SIGNATURE, color = Color(0xFFB4F36D), fontSize = 18.sp)
            }
        }
        Text("Good music. Shared miles.", fontSize = 18.sp, color = Color(0xFF96A6B8))
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF19212B))) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("CREATOR & MAINTAINER", fontSize = 12.sp, color = Color(0xFF96A6B8))
                Text(CreatorBrand.CREATOR, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                SelectionContainer { Text(CreatorBrand.CONTACT, fontSize = 20.sp) }
                TextButton(onClick = {
                    try { uriHandler.openUri(CreatorBrand.PROFILE_URL) }
                    catch (_: RuntimeException) { onMessage("No browser available. GitHub: ${CreatorBrand.PROFILE_URL}") }
                }) { Text("GitHub · ${CreatorBrand.GITHUB}", fontSize = 16.sp) }
            }
        }
        Text("Version ${BuildConfig.VERSION_NAME}", fontSize = 16.sp)
        Text("${CreatorBrand.COPYRIGHT}\n${CreatorBrand.CONTRIBUTOR_COPYRIGHT}", fontSize = 14.sp, color = Color(0xFF96A6B8))
        Text("MIT License. Third-party dependencies retain their own licenses. Original test audio is CC0.", fontSize = 14.sp, color = Color(0xFF96A6B8))
        Button(onClick = onLicense, modifier = Modifier.heightIn(min = 54.dp)) { Text("View MIT License") }
    }
}

@Composable fun ProjectLicenseScreen() {
    val context = LocalContext.current
    val license = remember { context.assets.open("LICENSE.txt").bufferedReader().use { it.readText() } }
    SelectionContainer { Text(license, fontSize = 16.sp) }
}

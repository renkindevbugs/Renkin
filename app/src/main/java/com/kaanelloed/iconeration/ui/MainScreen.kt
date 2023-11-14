package com.kaanelloed.iconeration.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.scale
import com.kaanelloed.iconeration.IconGenerator
import com.kaanelloed.iconeration.IconPackGenerator
import com.kaanelloed.iconeration.PackageInfoStruct
import com.kaanelloed.iconeration.data.getIconColorValue
import com.kaanelloed.iconeration.data.getIncludeVectorValue
import com.kaanelloed.iconeration.data.getMonochromeValue
import com.kaanelloed.iconeration.data.getTypeValue
import kotlinx.coroutines.launch

@Composable
fun ApplicationList(iconPacks: Array<PackageInfoStruct>, apps: Array<PackageInfoStruct>) {
    LazyColumn {
        items(apps) {app ->
            ApplicationItem(iconPacks, app)
        }
    }
}

@Composable
fun ApplicationItem(iconPacks: Array<PackageInfoStruct>, app: PackageInfoStruct) {
    var openAppOptions by rememberSaveable { mutableStateOf(false) }

    Row(modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically) {
        Image(painter = BitmapPainter(app.icon.toBitmap(198, 198).asImageBitmap())
            , contentDescription = null
            , modifier = Modifier.padding(2.dp))

        Image(painter = BitmapPainter(app.genIcon.scale(198, 198).asImageBitmap())
            , contentDescription = null
            , modifier = Modifier.padding(2.dp))

        Column() {
            Text(app.appName)
        }

        IconButton(onClick = { openAppOptions = true }) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = "Edit",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }

    if (openAppOptions) {
        val ctx = getCurrentContext()
        AppOptions(iconPacks, app, {
            if (uploadedImage != null) {
                app.genIcon = uploadedImage!!
            }
            if (generatingOptions != null) {
                IconGenerator(ctx, generatingOptions!!).generateIcons(app, generatingType)
            }

            openAppOptions = false
        }) { openAppOptions = false }
    }
}

@Composable
fun RefreshButton(apps: Array<PackageInfoStruct>) {
    val prefs = getPreferences()
    val type = prefs.getTypeValue()
    val monochrome = prefs.getMonochromeValue()
    val vector = prefs.getIncludeVectorValue()
    val iconColor = prefs.getIconColorValue()

    val ctx = getCurrentContext()
    val scope = rememberCoroutineScope()

    IconButton(onClick = {
        scope.launch {
            val opt = IconGenerator.GenerationOptions(Color.parseColor(iconColor.toHexString()), monochrome, vector)
            IconGenerator(ctx, opt).generateIcons(apps, type)
        }
    }) {
        Icon(
            imageVector = Icons.Filled.Refresh,
            contentDescription = "Refresh icons",
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
fun BuildPackButton(apps: Array<PackageInfoStruct>) {
    val ctx = getCurrentContext()

    IconButton(onClick = {
        IconPackGenerator(ctx, apps).create {  }
    }) {
        Icon(
            imageVector = Icons.Filled.Build,
            contentDescription = "Create icon pack",
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TitleBar() {
    val prefs = getPreferences()
    var openSettings by rememberSaveable { mutableStateOf(false) }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.primary,
        ),
        title = {
            Text("Iconeration")
        },
        actions = {
            IconButton(onClick = { openSettings = true }) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    )

    if (openSettings) {
        SettingsDialog(prefs) {
            openSettings = false
        }
    }
}
package com.kaanelloed.iconeration.ui

import android.content.Context
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.drawable.toBitmap
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.kaanelloed.iconeration.IconGenerator
import com.kaanelloed.iconeration.PackageInfoStruct
import com.kaanelloed.iconeration.data.getIconColorValue
import com.kaanelloed.iconeration.data.getIncludeVectorValue
import com.kaanelloed.iconeration.data.getMonochrome
import com.kaanelloed.iconeration.data.getMonochromeValue
import com.kaanelloed.iconeration.data.getTypeValue

@Composable
fun ApplicationList(apps: Array<PackageInfoStruct>) {
    /*val listState = remember {
        mutableStateListOf<PackageInfoStruct>()
    }*/

    LazyColumn {
        items(apps) {app ->
            ApplicationItem(app)
            //listState.add(app)
        }
    }
}

@Composable
fun ApplicationItem(app: PackageInfoStruct) {
    var openAppOptions by rememberSaveable { mutableStateOf(false) }

    Row(modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically) {
        Image(painter = BitmapPainter(app.icon.toBitmap(198, 198).asImageBitmap()), contentDescription = null)

        Image(painter = BitmapPainter(app.genIcon.asImageBitmap()), contentDescription = null)

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
        AppOptions(app.appName) { openAppOptions = false }
    }
}

@Composable
fun CreateButton(ctx: Context, prefs: DataStore<Preferences>, apps: Array<PackageInfoStruct>) {
    val type = prefs.getTypeValue()
    val monochrome = prefs.getMonochromeValue()
    val vector = prefs.getIncludeVectorValue()
    val iconColor = prefs.getIconColorValue()

    Button(onClick = {
        val opt = IconGenerator.GenerationOptions(android.graphics.Color.parseColor(iconColor.toHexString()), monochrome, vector)
        IconGenerator(ctx, opt, apps).generateIcons(type)
    }) {
        Text("Create")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TitleBar(prefs: DataStore<Preferences>) {
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
package com.kaanelloed.iconeration.ui

import android.content.Context
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.core.graphics.drawable.toBitmap
import com.kaanelloed.iconeration.IconGenerator
import com.kaanelloed.iconeration.PackageInfoStruct

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
    var appOption by rememberSaveable { mutableStateOf(app.appName) }

    Row() {
        Image(painter = BitmapPainter(app.icon.toBitmap().asImageBitmap()), contentDescription = null)

        Column() {
            Text(app.appName)
        }

        Image(painter = BitmapPainter(app.genIcon.asImageBitmap()), contentDescription = null
            , modifier = Modifier.clickable { openAppOptions = true; appOption = app.appName })
    }

    if (openAppOptions) {
        AppOptions(appOption) { openAppOptions = false }
    }
}

@Composable
fun CreateButton(ctx: Context, apps: Array<PackageInfoStruct>) {
    Button(onClick = {
        IconGenerator(ctx, apps, Color.WHITE).generateIcons(IconGenerator.GenerationType.EdgeDetection)
    }) {
        Text("Create")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TitleBar() {
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
        SettingsDialog {
            openSettings = false
        }
    }
}
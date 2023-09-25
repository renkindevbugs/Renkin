package com.kaanelloed.iconeration.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.core.graphics.drawable.toBitmap
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
    var openAppSetting by rememberSaveable { mutableStateOf(false) }
    var appSetting by rememberSaveable { mutableStateOf(app.appName) }

    Row() {
        Image(painter = BitmapPainter(app.icon.toBitmap().asImageBitmap()), contentDescription = null)

        Column() {
            Text(app.appName)
        }

        Image(painter = BitmapPainter(app.genIcon.asImageBitmap()), contentDescription = null
            , modifier = Modifier.clickable { openAppSetting = true; appSetting = app.appName })
    }

    if (openAppSetting) {
        AppOptions(appSetting) { openAppSetting = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TitleBar() {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            titleContentColor = MaterialTheme.colorScheme.primary,
        ),
        title = {
            Text("Iconeration")
        },
        actions = {
            IconButton(onClick = { /* do something */ }) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    )
}
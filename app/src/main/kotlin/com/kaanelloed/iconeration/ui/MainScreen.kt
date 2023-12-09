package com.kaanelloed.iconeration.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import com.kaanelloed.iconeration.packages.ApplicationManager
import com.kaanelloed.iconeration.icon.creator.IconGenerator
import com.kaanelloed.iconeration.apk.IconPackGenerator
import com.kaanelloed.iconeration.packages.PackageInfoStruct
import com.kaanelloed.iconeration.R
import com.kaanelloed.iconeration.apk.ApkInstaller
import com.kaanelloed.iconeration.apk.ApkUninstaller
import com.kaanelloed.iconeration.data.IconPack
import com.kaanelloed.iconeration.data.IconPackApplication
import com.kaanelloed.iconeration.data.getBackgroundColorValue
import com.kaanelloed.iconeration.data.getExportThemedValue
import com.kaanelloed.iconeration.data.getIconColorValue
import com.kaanelloed.iconeration.data.getIncludeVectorValue
import com.kaanelloed.iconeration.data.getMonochromeValue
import com.kaanelloed.iconeration.data.getTypeValue
import com.kaanelloed.iconeration.icon.BitmapIcon
import com.kaanelloed.iconeration.icon.EmptyIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun ApplicationList(iconPacks: List<IconPack>) {
    val activity = getCurrentMainActivity()

    LazyColumn {
        itemsIndexed(activity.applicationList) { index, app ->
            ApplicationItem(iconPacks, app, index)
        }
    }
}

@Composable
fun ApplicationItem(iconPacks: List<IconPack>, app: PackageInfoStruct, index: Int) {
    val prefs = getPreferences()
    val bgColorValue = prefs.getBackgroundColorValue()
    val themed = prefs.getExportThemedValue()
    val dynamicColor = themed && supportDynamicColors()

    var openAppOptions by rememberSaveable { mutableStateOf(false) }

    Row(modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically) {
        Image(painter = BitmapPainter(app.icon.toBitmap().asImageBitmap())
            , contentDescription = null
            , modifier = Modifier
                .padding(2.dp)
                .size(78.dp, 78.dp))

        val bgColor = if (themed) {
            if (dynamicColor) {
                colorResource(R.color.icon_background_color)
            } else {
                bgColorValue
            }
        } else {
            Color.Unspecified
        }

        if (app.createdIcon !is EmptyIcon)
            Image(painter = app.createdIcon.getPainter()
                , contentDescription = null
                , modifier = Modifier
                    .padding(2.dp)
                    .size(78.dp, 78.dp)
                    .clickable { openAppOptions = true }
                    .background(bgColor))
        else
            IconButton(onClick = { openAppOptions = true }
            , modifier = Modifier
                    .padding(2.dp)
                    .size(78.dp, 78.dp)) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = "Edit",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

        Column {
            Text(app.appName)
        }
    }

    if (openAppOptions) {
        val ctx = getCurrentContext()
        val activity = getCurrentMainActivity()

        AppOptions(iconPacks, app, {
            CoroutineScope(Dispatchers.Default).launch {
                if (!activity.iconPackLoaded) {
                    return@launch
                }

                var toGenerate = true
                if (iconPackageName != "") {
                    val key = activity.iconPackApplications.keys.find { it.packageName == iconPackageName }
                    val apps = activity.iconPackApplications[key]!!
                    val packApp = apps.find { it.packageName == app.packageName }

                    if (packApp != null) {
                        val icon = ApplicationManager(ctx).getResIcon(packApp.iconPackName, packApp.resourceID)!!

                        IconGenerator(ctx, activity, generatingOptions!!, emptyMap()).colorizeFromIconPack(app, icon)
                        toGenerate = false
                    }
                }

                if (uploadedImage != null) {
                    activity.editApplication(index, app.changeExport(BitmapIcon(uploadedImage!!)))
                }
                if (generatingOptions != null && toGenerate) {
                    IconGenerator(ctx, activity, generatingOptions!!, emptyMap()).generateIcons(app, generatingType)
                }

                uploadedImage = null
                generatingOptions = null
                iconPackageName = ""
            }

            openAppOptions = false
        }, { openAppOptions = false }) {
            openAppOptions = false
            activity.editApplication(index, app.changeExport(EmptyIcon()))
        }
    }
}

@Composable
fun RefreshButton() {
    val prefs = getPreferences()
    val type = prefs.getTypeValue()
    val monochrome = prefs.getMonochromeValue()
    val vector = prefs.getIncludeVectorValue()
    val iconColorValue = prefs.getIconColorValue()
    val bgColorValue = prefs.getBackgroundColorValue()
    val themed = prefs.getExportThemedValue()
    val dynamicColor = themed && supportDynamicColors()

    val ctx = getCurrentContext()
    val activity = getCurrentMainActivity()

    var openWarning by rememberSaveable { mutableStateOf(false) }

    IconButton(onClick = {
        CoroutineScope(Dispatchers.Default).launch {
            if (!activity.iconPackLoaded) {
                openWarning = true
                return@launch
            }

            var iconPackApps = emptyMap<IconPackApplication, Pair<Int, Drawable>>()
            if (iconPackageName != "") {
                val pack = activity.iconPackApplications.keys.find { it.packageName == iconPackageName }
                val packApps = activity.iconPackApplications[pack]!!
                iconPackApps = ApplicationManager(ctx).getIconPackApplicationResources(iconPackageName, packApps)
            }

            var iconColor = android.graphics.Color.parseColor(iconColorValue.toHexString())
            var bgColor = android.graphics.Color.parseColor(bgColorValue.toHexString())

            if (dynamicColor) {
                iconColor = activity.resources.getColor(R.color.icon_color, null)
                bgColor = activity.resources.getColor(R.color.icon_background_color, null)
            }

            val opt = IconGenerator.GenerationOptions(iconColor, monochrome, vector, themed, bgColor)
            IconGenerator(ctx, activity, opt, iconPackApps).generateIcons(activity.applicationList, type)
        }
    }) {
        Icon(
            imageVector = Icons.Filled.Refresh,
            contentDescription = "Refresh icons",
            tint = MaterialTheme.colorScheme.primary
        )
    }

    if (openWarning) {
        AlertDialog(
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.outline,
            onDismissRequest = { openWarning = false },
            title = { Text(stringResource(id = R.string.sync)) },
            text = {
                Text(stringResource(id = R.string.syncText))
            },
            confirmButton = { }
        )
    }
}

@Composable
fun BuildPackButton() {
    val ctx = getCurrentContext()
    val activity = getCurrentMainActivity()
    val themed = getPreferences().getExportThemedValue()
    val iconColor = getPreferences().getIconColorValue()
    val bgColor = getPreferences().getBackgroundColorValue()

    var openBuilder by rememberSaveable { mutableStateOf(false) }
    var openSuccess by rememberSaveable { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }

    IconButton(onClick = {
        text = ""
        openBuilder = true
        CoroutineScope(Dispatchers.Default).launch {
            val iconPackGenerator = IconPackGenerator(ctx, activity.applicationList)
            val canBeInstalled = iconPackGenerator.canBeInstalled() // must be called before build and sign

            val apk = iconPackGenerator.buildAndSign(themed, iconColor.toHexString(), bgColor.toHexString()) {
                text += it + "\n"
            }

            openBuilder = false

            if (canBeInstalled) {
                openSuccess = ApkInstaller(ctx).install(apk)
            } else {
                if (ApkUninstaller(ctx).uninstall(iconPackGenerator.getIconPackName())) {
                    openSuccess = ApkInstaller(ctx).install(apk)
                }
            }
        }
    }) {
        Icon(
            imageVector = Icons.Filled.Build,
            contentDescription = "Create icon pack",
            tint = MaterialTheme.colorScheme.primary
        )
    }

    if (openBuilder) {
        AlertDialog(
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.outline,
            onDismissRequest = {},
            title = { Text(stringResource(id = R.string.iconPack)) },
            text = {
                Text(text = text)
            },
            confirmButton = { }
        )
    }

    if (openSuccess) {
        AlertDialog(
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.outline,
            onDismissRequest = { openSuccess = false },
            title = { Text(stringResource(id = R.string.iconPack)) },
            text = {
                Text(stringResource(id = R.string.iconPackInstalled))
            },
            confirmButton = { }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TitleBar() {
    val prefs = getPreferences()
    var openSettings by rememberSaveable { mutableStateOf(false) }
    var openInfo by rememberSaveable { mutableStateOf(false) }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.primary,
        ),
        title = {
            Text(stringResource(id = R.string.app_name))
        },
        actions = {
            RefreshButton()
            BuildPackButton()
            IconButton(onClick = { openInfo = true }) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = "Info",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
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

    if (openInfo) {
        InfoDialog() {
            openInfo = false
        }
    }
}

@Composable
fun InfoDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp)
            ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Info",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 16.dp)
                )
                Text(text = stringResource(id = R.string.refreshIconDescription))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Build,
                    contentDescription = "Build",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 16.dp)
                )
                Text(text = stringResource(id = R.string.buildIconDescription))
            }
        }
    }
}
package com.kaanelloed.iconeration.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
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
import com.kaanelloed.iconeration.packages.PackageInfoStruct
import com.kaanelloed.iconeration.R
import com.kaanelloed.iconeration.data.BackgroundColorKey
import com.kaanelloed.iconeration.data.ExportThemedKey
import com.kaanelloed.iconeration.data.IconPack
import com.kaanelloed.iconeration.data.PrimaryIconPackKey
import com.kaanelloed.iconeration.data.getBooleanValue
import com.kaanelloed.iconeration.data.getColorValue
import com.kaanelloed.iconeration.data.getDefaultBackgroundColor
import com.kaanelloed.iconeration.data.getPreferencesValue
import com.kaanelloed.iconeration.data.getStringValue
import com.kaanelloed.iconeration.drawable.DrawableExtension.Companion.sizeIsGreaterThanZero
import com.kaanelloed.iconeration.icon.EmptyIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun MainColumn(iconPacks: List<IconPack>) {
    var packageFilter by remember { mutableStateOf("") }

    Scaffold(topBar = { TitleBar() },
        bottomBar = { BottomBar() }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            OptionsCard(iconPacks)
            SearchBar { packageFilter = it }
            ApplicationList(iconPacks, packageFilter)
        }
    }
}

@Composable
fun ApplicationList(iconPacks: List<IconPack>, filter: String) {
    val activity = getCurrentMainActivity()

    LazyColumn {
        itemsIndexed(activity.appProvider.applicationList) { index, app ->
            if (app.appName.contains(filter, true)) {
                ApplicationItem(iconPacks, app, index)
            }
        }
    }
}

@Composable
fun ApplicationItem(iconPacks: List<IconPack>, app: PackageInfoStruct, index: Int) {
    val prefs = getPreferences()
    val bgColorValue = prefs.getColorValue(BackgroundColorKey, prefs.getDefaultBackgroundColor())
    val themed = prefs.getBooleanValue(ExportThemedKey)
    val dynamicColor = themed && supportDynamicColors()

    var openAppOptions by rememberSaveable { mutableStateOf(false) }
    var openWarning by rememberSaveable { mutableStateOf(false) }

    Row(modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically) {
        if (app.icon.sizeIsGreaterThanZero()) {
            Image(painter = BitmapPainter(app.icon.toBitmap().asImageBitmap())
                , contentDescription = null
                //, contentScale = ContentScale.Inside
                , modifier = Modifier
                    .padding(2.dp)
                    .size(78.dp, 78.dp))
        }

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
                //, contentScale = ContentScale.Inside
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
        OpenAppOptions(iconPacks, app, index) {
            openAppOptions = false
            openWarning = it
        }
    }
    
    if (openWarning) {
        ShowToast(stringResource(id = R.string.syncText))
        openWarning = false
    }
}

@Composable
fun OpenAppOptions(
    iconPacks: List<IconPack>,
    app: PackageInfoStruct,
    index: Int,
    onDismiss: (Boolean) -> Unit
) {
    val activity = getCurrentMainActivity()

    AppOptions(iconPacks, app, { options ->
        CoroutineScope(Dispatchers.Default).launch {
            if (!activity.appProvider.iconPackLoaded && options is CreatedOptions && options.generatingOptions.primaryIconPack != "") {
                onDismiss(true)
                return@launch
            }

            when (options) {
                is CreatedOptions -> {
                    activity.appProvider.refreshIcon(app, options.generatingOptions)
                }

                is UploadedOptions -> {
                    activity.appProvider.editApplication(index, app.changeExport(options.uploadedImage))
                }

                is EditedVectorOptions -> {
                    activity.appProvider.editApplication(index, app.changeExport(options.editedVector))
                }
            }

            onDismiss(false)
        }
    }, {
        onDismiss(false)
    }) {
        onDismiss(false)
        activity.appProvider.editApplication(index, app.changeExport(EmptyIcon()))
    }
}

@Composable
fun RefreshButton(onChangeIsRefresh: (Boolean) -> Unit) {
    val preferences = getPreferences().getPreferencesValue()
    val iconPackageName = preferences.getStringValue(PrimaryIconPackKey)

    val activity = getCurrentMainActivity()

    var openWarning by rememberSaveable { mutableStateOf(false) }

    IconButton(onClick = {
        CoroutineScope(Dispatchers.Default).launch {
            if (!activity.appProvider.iconPackLoaded && iconPackageName != "") {
                openWarning = true
                return@launch
            }
            onChangeIsRefresh(true)

            activity.appProvider.refreshIcons(preferences)

            onChangeIsRefresh(false)
        }
    }) {
        Icon(
            imageVector = Icons.Filled.Refresh,
            contentDescription = "Refresh icons",
            tint = MaterialTheme.colorScheme.primary
        )
    }

    if (openWarning) {
        ShowToast(stringResource(id = R.string.syncText))
        openWarning = false
    }
}

@Composable
fun BuildPackButton(isInRefresh: Boolean) {
    val activity = getCurrentMainActivity()
    val preferences = getPreferences().getPreferencesValue()

    var openBuilder by rememberSaveable { mutableStateOf(false) }
    var openSuccess by remember { mutableStateOf(false) }
    var openInRefresh by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }

    IconButton(onClick = {
        if (isInRefresh) {
            openInRefresh = true
            return@IconButton
        }

        text = ""
        openBuilder = true
        CoroutineScope(Dispatchers.Default).launch {
            val iconPack = activity.appProvider.buildAndSignIconPack(preferences) {
                text += it + "\n"
            }

            openBuilder = false
            openSuccess = activity.appProvider.installIconPack(iconPack)
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
        ShowToast(stringResource(id = R.string.iconPackInstalled))
        openSuccess = false
    }

    if (openInRefresh) {
        ShowToast(stringResource(id = R.string.iconsStillGenerated))
        openInRefresh = false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TitleBar() {
    val prefs = getPreferences()
    var openSettings by rememberSaveable { mutableStateOf(false) }
    var openInfo by rememberSaveable { mutableStateOf(false) }
    var isInRefresh by rememberSaveable { mutableStateOf(false) }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.primary,
        ),
        title = {
            Text(stringResource(id = R.string.app_name))
        },
        actions = {
            RefreshButton {
                isInRefresh = it
            }
            BuildPackButton(isInRefresh)
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
        InfoDialog {
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

@Composable
fun BottomBar() {
    if (!getCurrentMainActivity().appProvider.iconPackLoaded) {
        BottomAppBar(containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(id = R.string.syncIconPack), Modifier.padding(4.dp))
                CircularProgressIndicator(
                    modifier = Modifier
                        .width(40.dp)
                        .padding(4.dp),
                    color = MaterialTheme.colorScheme.secondary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }
    }
}

@Composable
fun SearchBar(onSearch: (String) -> Unit) {
    var text by remember { mutableStateOf("") }

    Row(modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically) {
        TextField(value = text,
            onValueChange = {
                text = it
                onSearch(it)
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )},
            trailingIcon = {
                IconButton(onClick = {
                    text = ""
                    onSearch(text)
                }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Clear",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }},
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp, 0.dp, 16.dp, 8.dp))
    }
}
package dev.renkinProject.renkin.ui

import androidx.compose.runtime.Composable
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.packages.supportDynamicColors

// Pure predicates that decide which option controls are relevant for a given source /
// image-edit / themed combination. Shared by OptionsCard and the per-app options dialog.

fun needImageEdit(source: Source): Boolean {
    return source == Source.ICON_PACK || source == Source.APPLICATION_ICON
}

fun needTextType(source: Source): Boolean {
    return source == Source.APPLICATION_NAME
}

fun needIconPack(source: Source): Boolean {
    return source == Source.ICON_PACK
}

fun needSecondarySource(source: Source): Boolean {
    return source == Source.ICON_PACK
}

fun isPathTracingEnabled(primarySource: Source, primaryImageEdit: ImageEdit, secondarySource: Source, secondaryImageEdit: ImageEdit): Boolean {
    if (primarySource == Source.ICON_PACK) {
        if (isPathTracingEnabled(secondarySource, secondaryImageEdit)) {
            return true
        }
    }

    return isPathTracingEnabled(primarySource, primaryImageEdit)
}

fun isIconPackSelected(source: Source, iconPack: String): Boolean {
    return source == Source.ICON_PACK && iconPack != ""
}

fun isPathTracingEnabled(source: Source, imageEdit: ImageEdit): Boolean {
    if (needImageEdit(source)) {
        return imageEdit == ImageEdit.PATH
    }

    return false
}

fun showIconColor(primarySource: Source, primaryImageEdit: ImageEdit, secondarySource: Source, secondaryImageEdit: ImageEdit, themed: Boolean): Boolean {
    if (primarySource == Source.ICON_PACK) {
        if (!showIconColor(secondarySource, secondaryImageEdit, themed)) {
            return false
        }
    }

    return showIconColor(primarySource, primaryImageEdit, themed)
}

fun showIconColor(source: Source, imageEdit: ImageEdit, themed: Boolean): Boolean {
    if (needImageEdit(source) && imageEdit == ImageEdit.PATH && themed) {
        if (supportDynamicColors()) {
            return false
        }
    }

    return true
}

@Composable
fun showBackgroundColor(primarySource: Source, primaryImageEdit: ImageEdit, secondarySource: Source, secondaryImageEdit: ImageEdit, themed: Boolean): Boolean {
    if (primarySource == Source.ICON_PACK) {
        if (showBackgroundColor(secondarySource, secondaryImageEdit, themed)) {
            return true
        }
    }

    return showBackgroundColor(primarySource, primaryImageEdit, themed)
}

@Composable
fun showBackgroundColor(source: Source, imageEdit: ImageEdit, themed: Boolean): Boolean {
    if (needImageEdit(source) && imageEdit == ImageEdit.PATH && themed) {
        if (!supportDynamicColors()) {
            return true
        }
    }

    return false
}

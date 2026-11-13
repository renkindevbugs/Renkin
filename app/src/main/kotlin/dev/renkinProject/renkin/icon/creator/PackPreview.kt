package dev.renkinProject.renkin.icon.creator

import androidx.compose.ui.graphics.ImageBitmap

/** Sort order for the icon-pack browser's icon lists. */
enum class IconSortOrder { NAME_ASC, NAME_DESC }

/** How many icons a collapsed pack row shows before the "+N" chip. */
const val PACK_ROW_LIMIT = 30

// Hard cap for the full-pack grid — huge packs (e.g. Arcticons) have thousands of icons
// and eagerly generated previews for all of them would run out of memory.
const val PACK_DETAIL_LIMIT = 400

data class PackSearchResult(
    val names: List<String> = emptyList(),
    val complete: Boolean = false,
    val failed: Boolean = false,
    val identity: String = ""
)

data class PackThumbnail(val resourceId: Int, val preview: ImageBitmap)

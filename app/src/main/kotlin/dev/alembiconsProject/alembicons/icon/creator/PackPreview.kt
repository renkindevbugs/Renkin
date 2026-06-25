package dev.alembiconsProject.alembicons.icon.creator

import androidx.compose.ui.graphics.ImageBitmap
import dev.alembiconsProject.alembicons.drawable.IconPackDrawable
import dev.alembiconsProject.alembicons.drawable.ResourceDrawable

/** Sort order for the icon-pack browser's icon lists. */
enum class IconSortOrder { NAME_ASC, NAME_DESC }

/** How many icons a collapsed pack row shows before the "+N" chip. */
const val PACK_ROW_LIMIT = 30

// Hard cap for the full-pack grid — huge packs (e.g. Arcticons) have thousands of icons
// and eagerly generated previews for all of them would run out of memory.
const val PACK_DETAIL_LIMIT = 400

/**
 * A pack icon ready to show: the source [resource] (passed back on tap), the built
 * [drawable], and a [preview] bitmap rasterised once on a background thread. Rendering this
 * bitmap is far cheaper per frame than rebuilding a vector painter for every grid item.
 */
data class PackIconPreview(
    val resource: ResourceDrawable,
    val drawable: IconPackDrawable,
    val preview: ImageBitmap
)

/**
 * A generated icon-pack browser row ready to display: its preview icons plus how many further
 * icons didn't fit the row (the "+N" chip). Cached in the view model so a row that scrolls
 * off-screen (and is discarded from composition) doesn't regenerate its bitmaps on the way back.
 */
data class PackRowPreviews(val previews: List<PackIconPreview>, val moreCount: Int)

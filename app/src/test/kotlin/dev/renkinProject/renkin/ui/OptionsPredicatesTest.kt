package dev.renkinProject.renkin.ui

import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The option predicates (Options.kt) decide which controls are relevant for a given
 * source / image-edit combination. They're pure (no Android), so a plain JVM test pins
 * down the branching. [showIconColor]/[showBackgroundColor] are excluded here because they
 * depend on Build.VERSION via supportDynamicColors().
 */
class OptionsPredicatesTest {

    @Test
    fun needImageEdit_onlyForIconPackAndAppIcon() {
        assertTrue(needImageEdit(Source.ICON_PACK))
        assertTrue(needImageEdit(Source.APPLICATION_ICON))
        assertFalse(needImageEdit(Source.APPLICATION_NAME))
        assertFalse(needImageEdit(Source.NONE))
    }

    @Test
    fun needTextType_onlyForAppName() {
        assertTrue(needTextType(Source.APPLICATION_NAME))
        assertFalse(needTextType(Source.ICON_PACK))
        assertFalse(needTextType(Source.APPLICATION_ICON))
        assertFalse(needTextType(Source.NONE))
    }

    @Test
    fun needIconPack_andSecondarySource_onlyForIconPack() {
        for (source in Source.entries) {
            val expected = source == Source.ICON_PACK
            assertEquals(expected, needIconPack(source))
            assertEquals(expected, needSecondarySource(source))
        }
    }

    @Test
    fun isIconPackSelected_requiresIconPackSourceAndNonEmptyPack() {
        assertTrue(isIconPackSelected(Source.ICON_PACK, "com.some.pack"))
        assertFalse(isIconPackSelected(Source.ICON_PACK, ""))
        assertFalse(isIconPackSelected(Source.APPLICATION_ICON, "com.some.pack"))
    }

    @Test
    fun isPathTracing_singleSource_needsPathEditOnAnEditableSource() {
        assertTrue(isPathTracingEnabled(Source.ICON_PACK, ImageEdit.PATH))
        assertTrue(isPathTracingEnabled(Source.APPLICATION_ICON, ImageEdit.PATH))
        // Path edit on a source that has no image edit (text) doesn't count
        assertFalse(isPathTracingEnabled(Source.APPLICATION_NAME, ImageEdit.PATH))
        // Editable source but a non-path edit
        assertFalse(isPathTracingEnabled(Source.ICON_PACK, ImageEdit.EDGE))
        assertFalse(isPathTracingEnabled(Source.ICON_PACK, ImageEdit.NONE))
    }

    @Test
    fun isPathTracing_combined_picksUpSecondaryWhenPrimaryIsIconPack() {
        // Primary icon-pack with no path, but the secondary traces a path → enabled
        assertTrue(
            isPathTracingEnabled(
                primarySource = Source.ICON_PACK,
                primaryImageEdit = ImageEdit.NONE,
                secondarySource = Source.APPLICATION_ICON,
                secondaryImageEdit = ImageEdit.PATH
            )
        )
        // Primary itself traces a path
        assertTrue(
            isPathTracingEnabled(
                primarySource = Source.APPLICATION_ICON,
                primaryImageEdit = ImageEdit.PATH,
                secondarySource = Source.NONE,
                secondaryImageEdit = ImageEdit.NONE
            )
        )
        // Neither traces
        assertFalse(
            isPathTracingEnabled(
                primarySource = Source.ICON_PACK,
                primaryImageEdit = ImageEdit.COLORIZE,
                secondarySource = Source.APPLICATION_ICON,
                secondaryImageEdit = ImageEdit.EDGE
            )
        )
    }
}

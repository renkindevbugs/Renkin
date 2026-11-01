package dev.renkinProject.renkin.ui

import androidx.compose.ui.graphics.Color
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class CreateSourceColorsTest {
    private val foregroundStyle = ColorizerStyle(firstColor = android.graphics.Color.WHITE)
    private val backgroundStyle = ColorizerStyle(firstColor = android.graphics.Color.BLACK)

    private fun colors(materialYou: Boolean, scheme: Pair<Color, Color>? = null, plate: Color? = null) =
        createSourceColors(materialYou, scheme, Color.White, Color.Black, foregroundStyle, backgroundStyle, plate)

    @Test
    fun customMaterialYouColorsFeedTheirOwnRoles() {
        // The generated variant once swapped these to offset its former dark-is-foreground split.
        assertEquals(CreateSourceColors(Color.White, Color.Black, foregroundStyle, backgroundStyle), colors(true))
    }

    @Test
    fun wallpaperSchemeUsesItsFlatColoursWithoutStyles() {
        val scheme = Color.Blue to Color.Yellow
        assertEquals(CreateSourceColors(Color.Blue, Color.Yellow, null, null), colors(true, scheme, Color.Red))
    }

    @Test
    fun otherSourcesUseTheIconColourAndOnlyAShapePlateBackground() {
        assertEquals(CreateSourceColors(Color.White, Color.Red, null, null), colors(false, plate = Color.Red))
        assertEquals(CreateSourceColors(Color.White, Color.Transparent, null, null), colors(false))
    }
}

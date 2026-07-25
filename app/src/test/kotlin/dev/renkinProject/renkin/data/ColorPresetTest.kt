package dev.renkinProject.renkin.data

import android.app.Application
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.renkinProject.renkin.icon.creator.ColorizerMode
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.GradientType
import dev.renkinProject.renkin.icon.creator.decodeColorizerStyle
import dev.renkinProject.renkin.icon.creator.encodeColorizerStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Saved colours: they round-trip through the encoder and survive in the database. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ColorPresetTest {

    private lateinit var db: RenkinPackDatabase
    private lateinit var repo: RenkinPackRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RenkinPackDatabase::class.java
        ).allowMainThreadQueries().build()
        repo = RenkinPackRepository(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun gradientStyleSurvivesEncoding() {
        val style = ColorizerStyle(
            mode = ColorizerMode.GRADIENT,
            gradientType = GradientType.RADIAL,
            firstColor = Color.MAGENTA,
            gradientStops = listOf(Color.CYAN, Color.YELLOW),
            gradientAngle = 225f,
            flat = true
        )

        assertEquals(style, decodeColorizerStyle(encodeColorizerStyle(style)))
        assertNull(decodeColorizerStyle("garbage"))
    }

    @Test
    fun savedPresetsComeBackOldestFirst() = runBlocking {
        repo.saveColorPreset("Color 1", encodeColorizerStyle(ColorizerStyle(firstColor = Color.RED)))
        repo.saveColorPreset("Teal", encodeColorizerStyle(ColorizerStyle(firstColor = Color.CYAN)))

        val presets = repo.colorPresetsFlow().first()

        assertEquals(listOf("Color 1", "Teal"), presets.map { it.name })
        assertEquals(
            Color.RED,
            decodeColorizerStyle(presets.first().style)?.firstColor
        )
    }

    @Test
    fun deletingAPresetRemovesOnlyThatOne() = runBlocking {
        val keptId = repo.saveColorPreset(
            "Keep",
            encodeColorizerStyle(ColorizerStyle(firstColor = Color.GREEN))
        )
        val goneId = repo.saveColorPreset(
            "Drop",
            encodeColorizerStyle(ColorizerStyle(firstColor = Color.BLUE))
        )

        repo.deleteColorPreset(goneId)
        val presets = repo.colorPresetsFlow().first()

        assertEquals(listOf(keptId), presets.map { it.id })
    }
}

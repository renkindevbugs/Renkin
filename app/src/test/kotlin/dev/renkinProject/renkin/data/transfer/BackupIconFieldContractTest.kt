package dev.renkinProject.renkin.data.transfer

import android.app.Application
import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.EffectModifier
import dev.renkinProject.renkin.icon.creator.ModifierRecipeCodec
import dev.renkinProject.renkin.icon.creator.ModifierStack
import dev.renkinProject.renkin.icon.creator.StoredIconModifierInput
import dev.renkinProject.renkin.icon.creator.StoredIconModifierRecipe
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How a backup carries every [DbApplication] column. A new column fails here until it is
 * declared below and handled in [BackupCodec.encodeIcon] / [BackupCodec.decodeIcon].
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class BackupIconFieldContractTest {

    private enum class Rule {
        /** Written and read back unchanged. */
        PRESERVED,
        /** Not stored in the icon entry; the import supplies it (the target profile). */
        DERIVED,
        /** Stored in its bounded, validated form; invalid metadata becomes empty. */
        NORMALIZED
    }

    private val contract = mapOf(
        "packageName" to Rule.PRESERVED,
        "activityName" to Rule.PRESERVED,
        "isAdaptiveIcon" to Rule.PRESERVED,
        "isXml" to Rule.PRESERVED,
        "drawable" to Rule.PRESERVED,
        "calendarEnabled" to Rule.PRESERVED,
        "calendarPrefix" to Rule.PRESERVED,
        "calendarPackName" to Rule.PRESERVED,
        "sourcePackName" to Rule.PRESERVED,
        "profileId" to Rule.DERIVED,
        "sourceDrawableName" to Rule.PRESERVED,
        "isCustomIcon" to Rule.PRESERVED,
        "isLegacyIcon" to Rule.PRESERVED,
        "baseDrawable" to Rule.PRESERVED,
        "baseIsAdaptiveIcon" to Rule.PRESERVED,
        "baseIsXml" to Rule.PRESERVED,
        "sourceUrl" to Rule.PRESERVED,
        "isFallbackIcon" to Rule.PRESERVED,
        "modifierRecipe" to Rule.NORMALIZED,
        "modifierInput" to Rule.NORMALIZED
    )

    private val normalizers: Map<String, (String) -> String> = mapOf(
        "modifierRecipe" to { StoredIconModifierRecipe.normalize(it).orEmpty() },
        "modifierInput" to { StoredIconModifierInput.normalize(it).orEmpty() }
    )

    private fun columns(): List<Field> = DbApplication::class.java.declaredFields
        .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
        .onEach { it.isAccessible = true }

    private val recipe = ModifierRecipeCodec.encode(
        ModifierStack(EffectModifier(ImageEdit.NONE, ColorizerStyle(firstColor = 0xff000000.toInt())))
    )

    // Every column differs from its default, so a column the codec drops cannot pass by accident.
    private val populated = DbApplication(
        packageName = "com.example", activityName = "com.example.Main",
        isAdaptiveIcon = true, isXml = true, drawable = "rendered",
        calendarEnabled = true, calendarPrefix = "cal_", calendarPackName = "com.calendar.pack",
        sourcePackName = "com.source.pack", profileId = 9, sourceDrawableName = "example_icon",
        isCustomIcon = true, isLegacyIcon = true, baseDrawable = "base",
        baseIsAdaptiveIcon = true, baseIsXml = true, sourceUrl = "https://example.invalid/icon.svg",
        isFallbackIcon = true, modifierRecipe = recipe, modifierInput = "not-a-modifier-input"
    )

    @Test fun everyIconColumnDeclaresHowABackupCarriesIt() {
        assertEquals(
            "Declare each DbApplication column in this contract and carry it in BackupCodec",
            contract.keys, columns().map { it.name }.toSet()
        )
    }

    @Test fun backupRoundTripFollowsTheContract() {
        val importedInto = 42L
        val decoded = BackupCodec.decodeIcon(JSONObject(BackupCodec.encodeIcon(populated).toString()), importedInto)
        val defaults = DbApplication("", "", false, false, "")

        for (field in columns()) {
            val original = field.get(populated)
            val restored = field.get(decoded)
            when (contract.getValue(field.name)) {
                Rule.PRESERVED -> {
                    assertNotEquals("${field.name} needs a non-default sample", field.get(defaults), original)
                    assertEquals(field.name, original, restored)
                }
                Rule.DERIVED -> assertEquals(field.name, importedInto, restored)
                Rule.NORMALIZED -> {
                    val normalize = normalizers.getValue(field.name)
                    assertEquals(field.name, normalize(original as String), restored)
                }
            }
        }
        assertTrue("a valid recipe survives normalization", decoded.modifierRecipe.isNotEmpty())
    }
}

package dev.alembiconsProject.alembicons.icon.creator

import dev.alembiconsProject.alembicons.data.ImageEdit
import dev.alembiconsProject.alembicons.data.Source
import dev.alembiconsProject.alembicons.data.TextType

class GenerationOptions(
    val primarySource: Source,
    val primaryImageEdit: ImageEdit,
    val primaryTextType: TextType,
    val primaryIconPack: String,
    val secondarySource: Source,
    val secondaryImageEdit: ImageEdit,
    val secondaryTextType: TextType,
    val secondaryIconPack: String,
    val color: Int,
    val bgColor: Int,
    val vector: Boolean,
    val monochrome: Boolean,
    val themed: Boolean,
    val override: Boolean,
    val edgeLowThreshold: Float = 2.5F,
    val edgeHighThreshold: Float = 7.5F,
    val edgeGaussianRadius: Float = 2F,
    val edgeContrastNormalized: Boolean = false
) {
    constructor(
        source: Source,
        imageEdit: ImageEdit,
        textType: TextType,
        iconPack: String,
        color: Int,
        bgColor: Int,
        vector: Boolean,
        monochrome: Boolean,
        themed: Boolean,
        override: Boolean,
        edgeLowThreshold: Float = 2.5F,
        edgeHighThreshold: Float = 7.5F,
        edgeGaussianRadius: Float = 2F,
        edgeContrastNormalized: Boolean = false
    )
            : this(
        source,
        imageEdit,
        textType,
        iconPack,
        Source.NONE,
        ImageEdit.NONE,
        TextType.FULL_NAME,
        "",
        color,
        bgColor,
        vector,
        monochrome,
        themed,
        override,
        edgeLowThreshold,
        edgeHighThreshold,
        edgeGaussianRadius,
        edgeContrastNormalized
    )

    override fun hashCode(): Int {
        var result = primarySource.hashCode()
        result = 31 * result + primaryImageEdit.hashCode()
        result = 31 * result + primaryTextType.hashCode()
        result = 31 * result + primaryIconPack.hashCode()
        result = 31 * result + secondarySource.hashCode()
        result = 31 * result + secondaryImageEdit.hashCode()
        result = 31 * result + secondaryTextType.hashCode()
        result = 31 * result + secondaryIconPack.hashCode()
        result = 31 * result + color
        result = 31 * result + bgColor
        result = 31 * result + vector.hashCode()
        result = 31 * result + monochrome.hashCode()
        result = 31 * result + themed.hashCode()
        result = 31 * result + override.hashCode()
        result = 31 * result + edgeLowThreshold.hashCode()
        result = 31 * result + edgeHighThreshold.hashCode()
        result = 31 * result + edgeGaussianRadius.hashCode()
        result = 31 * result + edgeContrastNormalized.hashCode()
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as GenerationOptions

        if (primarySource != other.primarySource) return false
        if (primaryImageEdit != other.primaryImageEdit) return false
        if (primaryTextType != other.primaryTextType) return false
        if (primaryIconPack != other.primaryIconPack) return false
        if (secondarySource != other.secondarySource) return false
        if (secondaryImageEdit != other.secondaryImageEdit) return false
        if (secondaryTextType != other.secondaryTextType) return false
        if (secondaryIconPack != other.secondaryIconPack) return false
        if (color != other.color) return false
        if (bgColor != other.bgColor) return false
        if (vector != other.vector) return false
        if (monochrome != other.monochrome) return false
        if (themed != other.themed) return false
        if (override != other.override) return false
        if (edgeLowThreshold != other.edgeLowThreshold) return false
        if (edgeHighThreshold != other.edgeHighThreshold) return false
        if (edgeGaussianRadius != other.edgeGaussianRadius) return false
        if (edgeContrastNormalized != other.edgeContrastNormalized) return false

        return true
    }
}
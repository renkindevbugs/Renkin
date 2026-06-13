package dev.alembiconsProject.alembicons.icon.creator

import dev.alembiconsProject.alembicons.data.ImageEdit
import dev.alembiconsProject.alembicons.data.Source
import dev.alembiconsProject.alembicons.data.TextType

data class GenerationOptions(
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
}
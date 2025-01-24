package com.kaanelloed.iconeration.icon.creator

import com.kaanelloed.iconeration.data.ImageEdit
import com.kaanelloed.iconeration.data.Source
import com.kaanelloed.iconeration.data.TextType

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
    val override: Boolean
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
        override: Boolean
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
        override
    )
}
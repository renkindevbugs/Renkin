package com.kaanelloed.iconeration.ui

import com.kaanelloed.iconeration.data.GenerationType
import com.kaanelloed.iconeration.icon.ExportableIcon
import com.kaanelloed.iconeration.icon.VectorIcon
import com.kaanelloed.iconeration.icon.creator.IconGenerator

interface IndividualOptions

class EmptyOptions: IndividualOptions

data class CreatedOptions(
    val generatingOptions: IconGenerator.GenerationOptions,
    val generatingType: GenerationType,
    val iconPackageName: String
): IndividualOptions

data class UploadedOptions(
    val uploadedImage: ExportableIcon
): IndividualOptions

data class EditedVectorOptions(
    val editedVector: VectorIcon
): IndividualOptions
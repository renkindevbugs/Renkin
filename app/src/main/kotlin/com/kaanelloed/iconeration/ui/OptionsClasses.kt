package com.kaanelloed.iconeration.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.vector.ImageVector
import com.kaanelloed.iconeration.data.GenerationType
import com.kaanelloed.iconeration.icon.creator.IconGenerator

interface IndividualOptions

class EmptyOptions: IndividualOptions

data class CreatedOptions(
    val generatingOptions: IconGenerator.GenerationOptions,
    val generatingType: GenerationType,
    val iconPackageName: String
): IndividualOptions

data class UploadedOptions(
    val uploadedImage: Bitmap,
    val asAdaptiveIcon: Boolean
): IndividualOptions

data class EditedVectorOptions(
    val editedVector: ImageVector
): IndividualOptions
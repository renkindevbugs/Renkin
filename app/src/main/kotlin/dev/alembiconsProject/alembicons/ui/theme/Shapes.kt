package dev.alembiconsProject.alembicons.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Shared corner-radius tokens so dialogs, cards and fields look the same everywhere, instead
 * of every call site picking its own RoundedCornerShape value (which had drifted: dialogs at
 * 20 vs 28, cards at 14/18/20…). Reach for a role, not a raw number:
 *  - [DialogShape] — elevated modal dialogs (AlertDialog / small Dialog cards)
 *  - [CardShape]   — list / section cards and tappable surfaces
 *  - [FieldShape]  — buttons, text fields, dropdowns
 *  - [InnerShape]  — nested blocks inside a card or dialog (code blocks, previews)
 */
val DialogShape = RoundedCornerShape(28.dp)
val CardShape = RoundedCornerShape(20.dp)
val FieldShape = RoundedCornerShape(16.dp)
val InnerShape = RoundedCornerShape(12.dp)

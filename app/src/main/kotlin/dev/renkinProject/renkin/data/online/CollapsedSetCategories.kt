package dev.renkinProject.renkin.data.online

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Device-local browser layout: which set categories are collapsed. Outside profiles and backups. */
@Singleton
class CollapsedSetCategories @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("online_icon_browser", Context.MODE_PRIVATE)

    suspend fun read(): Set<String> = withContext(Dispatchers.IO) {
        preferences.getStringSet(KEY, null)?.toSet().orEmpty()
    }

    suspend fun write(categories: Set<String>) = withContext(Dispatchers.IO) {
        preferences.edit { putStringSet(KEY, categories) }
    }

    private companion object {
        const val KEY = "collapsedCategories"
    }
}

package dev.alembiconsProject.alembicons.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.alembiconsProject.alembicons.apk.ApplicationProvider
import dev.alembiconsProject.alembicons.data.watch.WatchRepository
import javax.inject.Singleton

/**
 * App-wide Hilt bindings. Classes that only need the application [Context] are provided
 * here so view models can inject them instead of constructing them by hand. (The repos
 * already back onto singleton Room databases, so providing them as @Singleton matches.)
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideWatchRepository(@ApplicationContext context: Context): WatchRepository =
        WatchRepository(context)

    @Provides
    @Singleton
    fun provideApplicationProvider(@ApplicationContext context: Context): ApplicationProvider =
        ApplicationProvider(context)
}

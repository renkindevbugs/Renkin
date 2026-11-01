package dev.renkinProject.renkin.di

import dev.renkinProject.renkin.packages.InstalledIconLoader

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.renkinProject.renkin.apk.IconGenerationService
import dev.renkinProject.renkin.apk.IconPackBuildService
import dev.renkinProject.renkin.apk.IconLockManager
import dev.renkinProject.renkin.apk.IconPackRepository
import dev.renkinProject.renkin.apk.ApplicationProvider
import dev.renkinProject.renkin.apk.ProfileManager
import dev.renkinProject.renkin.apk.RenkinPackStore
import dev.renkinProject.renkin.data.RenkinPackRepository
import dev.renkinProject.renkin.data.ProfileStyleStore
import dev.renkinProject.renkin.data.editor.EditorDraftInvalidation
import dev.renkinProject.renkin.data.dataStore
import dev.renkinProject.renkin.data.transfer.BackupManager
import dev.renkinProject.renkin.data.watch.WatchRepository
import dev.renkinProject.renkin.packages.ApplicationManager
import dev.renkinProject.renkin.packages.IconPackCatalog
import dev.renkinProject.renkin.packages.InstalledAppCatalog
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
    fun provideProfileStyleStore(@ApplicationContext context: Context, repository: RenkinPackRepository): ProfileStyleStore =
        ProfileStyleStore(repository, context.dataStore)

    @Provides
    @Singleton
    fun provideWatchRepository(@ApplicationContext context: Context): WatchRepository =
        WatchRepository(context)

    @Provides
    @Singleton
    fun provideRenkinPackRepository(
        @ApplicationContext context: Context
    ): RenkinPackRepository = RenkinPackRepository(context)

    @Provides
    @Singleton
    fun provideRenkinPackStore(
        @ApplicationContext context: Context,
        repository: RenkinPackRepository
    ): RenkinPackStore = RenkinPackStore(context, repository)

    @Provides
    @Singleton
    fun provideIconPackCatalog(
        @ApplicationContext context: Context
    ): IconPackCatalog = IconPackCatalog(context)

    @Provides
    @Singleton
    fun provideBackupManager(
        @ApplicationContext context: Context,
        renkinPackRepository: RenkinPackRepository,
        watchRepository: WatchRepository,
        iconPackCatalog: IconPackCatalog,
        editorDraftInvalidation: EditorDraftInvalidation
    ): BackupManager = BackupManager(
        context,
        renkinPackRepository,
        watchRepository,
        iconPackCatalog,
        editorDraftInvalidation = editorDraftInvalidation
    )

    @Provides
    @Singleton
    fun provideIconPackRepository(
        @ApplicationContext context: Context,
        iconPackCatalog: IconPackCatalog
    ): IconPackRepository = IconPackRepository(context, iconPackCatalog)

    @Provides
    @Singleton
    fun provideIconLockManager(
        @ApplicationContext context: Context,
        repository: RenkinPackRepository
    ): IconLockManager = IconLockManager(context, repository)

    @Provides
    @Singleton
    fun provideProfileManager(
        @ApplicationContext context: Context,
        repository: RenkinPackRepository,
        watchRepository: WatchRepository = WatchRepository(context)
    ): ProfileManager = ProfileManager(context, repository, watchRepository)

    @Provides
    @Singleton
    fun provideIconGenerationService(
        @ApplicationContext context: Context,
        iconPackRepository: IconPackRepository,
        iconPackCatalog: IconPackCatalog,
        installedIconLoader: InstalledIconLoader
    ): IconGenerationService = IconGenerationService(
        context,
        iconPackRepository,
        iconPackCatalog,
        installedIconLoader
    )

    @Provides
    @Singleton
    fun provideIconPackBuildService(
        @ApplicationContext context: Context,
        iconPackRepository: IconPackRepository,
        renkinPackRepository: RenkinPackRepository,
        iconLockManager: IconLockManager,
        profileManager: ProfileManager,
        iconPackCatalog: IconPackCatalog
    ): IconPackBuildService = IconPackBuildService(
        context = context,
        iconPackRepository = iconPackRepository,
        packRepository = renkinPackRepository,
        lockManager = iconLockManager,
        profileManager = profileManager,
        iconPackCatalog = iconPackCatalog
    )

    @Provides
    @Singleton
    fun provideApplicationProvider(
        @ApplicationContext context: Context,
        renkinPackStore: RenkinPackStore,
        renkinPackRepository: RenkinPackRepository,
        iconPackRepository: IconPackRepository,
        iconLockManager: IconLockManager,
        profileManager: ProfileManager,
        iconGenerationService: IconGenerationService,
        iconPackBuildService: IconPackBuildService,
        editorDraftInvalidation: EditorDraftInvalidation,
        installedIconLoader: InstalledIconLoader
    ): ApplicationProvider = ApplicationProvider(
        context = context,
        renkinPackStore = renkinPackStore,
        packRepo = renkinPackRepository,
        iconPackRepo = iconPackRepository,
        lockManager = iconLockManager,
        profileManager = profileManager,
        iconGenService = iconGenerationService,
        iconPackBuildService = iconPackBuildService,
        appManager = ApplicationManager(context),
        installedAppCatalog = InstalledAppCatalog(context),
        editorDraftInvalidation = editorDraftInvalidation,
        installedIcons = installedIconLoader
    )
}

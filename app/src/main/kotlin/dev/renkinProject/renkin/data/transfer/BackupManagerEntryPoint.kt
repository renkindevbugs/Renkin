package dev.renkinProject.renkin.data.transfer

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface BackupManagerEntryPoint {
    fun backupManager(): BackupManager
    fun profileStyleStore(): dev.renkinProject.renkin.data.ProfileStyleStore
}

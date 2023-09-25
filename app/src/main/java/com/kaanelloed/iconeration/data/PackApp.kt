package com.kaanelloed.iconeration.data

import android.graphics.drawable.Drawable
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.RoomDatabase
import androidx.room.Transaction

@Entity
data class IconPack (
    @PrimaryKey val packageName: String,
    val appName: String,
    val versionCode: Long,
    val versionName: String,
    val iconID: Int,
    @Ignore val icon: Drawable
)

@Dao
interface IconPackDao {
    @Query("SELECT * FROM IconPack")
    fun getAll(): List<IconPack>

    @Insert
    fun insertAll(vararg packs: IconPack)

    @Delete
    fun delete(pack: IconPack)

    @Transaction
    @Query("SELECT * FROM IconPack")
    fun getIconPacksWithApps(): List<IconPackWithApps>
}

@Entity(primaryKeys = ["packageName", "iconPackName"])
data class PackApp (
    val packageName: String,
    val iconPackName: String,
    val appName: String
)

data class IconPackWithApps (
    @Embedded val packs: IconPack,
    @Relation (
        parentColumn = "packageName",
        entityColumn = ""
    )
    val apps: List<PackApp>
)

@Database(entities = [IconPack::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun iconPackDao(): IconPackDao
}

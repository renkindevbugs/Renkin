package com.kaanelloed.iconeration.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity
data class IconPack constructor(
    @PrimaryKey val packageName: String,
    val applicationName: String,
    val versionCode: Long,
    val versionName: String,
    val iconID: Int,
    //@Ignore val icon: Drawable
)

@Entity(primaryKeys = ["iconPackName", "packageName", "activityName"])
data class IconPackApplication constructor(
    val iconPackName: String,
    val packageName: String,
    val activityName: String,
    val applicationName: String,
    val resourceID: Int
)

@Dao
interface IconPackDao {
    @Query("SELECT * FROM IconPack")
    fun getAll(): List<IconPack>

    @Insert
    fun insertAll(vararg packs: IconPack)

    @Insert
    fun insertAll(vararg packs: IconPackApplication)

    @Insert
    fun insertIconPackWithApplications(packs: IconPack, apps: List<IconPackApplication>)

    @Delete
    fun delete(vararg pack: IconPack)

    @Delete
    fun deleteIconPackWithApplications(packs: IconPack, apps: List<IconPackApplication>)

    @Query("DELETE FROM IconPackApplication WHERE iconPackName = :packageName")
    fun deleteApplicationByIconPackage(packageName: String)

    @Query(
        "SELECT * FROM IconPack " +
        "JOIN IconPackApplication ON IconPack.packageName = IconPackApplication.iconPackName"
    )
    fun getIconPacksWithApps(): Map<IconPack, List<IconPackApplication>>
}

@Database(entities = [IconPack::class, IconPackApplication::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun iconPackDao(): IconPackDao
}

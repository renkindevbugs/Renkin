package com.kaanelloed.iconeration.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(primaryKeys = ["packageName", "activityName"])
data class DbApplication(
    val packageName: String,
    val activityName: String,
    val isAdaptiveIcon: Boolean,
    val isXml: Boolean,
    val drawable: String
)

@Dao
interface AlchemiconPackDao {
    @Query("SELECT * FROM DbApplication")
    fun getAll(): List<DbApplication>

    @Query("SELECT * FROM DbApplication WHERE packageName = :packageName")
    fun get(packageName: String): DbApplication

    @Insert
    fun insertAll(vararg apps: DbApplication)

    @Insert
    fun insertAll(apps: List<DbApplication>)

    @Delete
    fun delete(vararg apps: DbApplication)

    @Delete
    fun delete(apps: List<DbApplication>)

    @Query("DELETE FROM DbApplication")
    fun deleteAllApplications()
}

@Database(
    entities = [DbApplication::class],
    version = 1
)
abstract class AlchemiconPackDatabase : RoomDatabase() {
    abstract fun alchemiconPackDao(): AlchemiconPackDao
}
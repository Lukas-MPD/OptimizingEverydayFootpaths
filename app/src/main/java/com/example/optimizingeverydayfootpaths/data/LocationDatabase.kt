package com.example.optimizingeverydayfootpaths.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [LocationData::class], version = 1, exportSchema = false)
abstract class LocationDatabase : RoomDatabase() {
    abstract fun locationDataDao(): LocationDataDao
}
package com.example.optimizingeverydayfootpaths.data

import android.content.Context
import androidx.room.Room

// Create a singleton class for your database
object LocationDatabaseSingleton {

    private lateinit var INSTANCE: LocationDatabase

    fun getDatabase(context: Context): LocationDatabase {
        synchronized(this) {
            if (!LocationDatabaseSingleton::INSTANCE.isInitialized) {
                INSTANCE = Room.databaseBuilder(
                    context.applicationContext,
                    LocationDatabase::class.java,
                    "location_database"
                ).build()
            }
            return INSTANCE
        }
    }
}
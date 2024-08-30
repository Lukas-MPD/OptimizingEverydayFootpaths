package com.example.optimizingeverydayfootpaths.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query

@Dao
interface LocationDataDao {
    @Insert
    suspend fun insert(locationData: LocationData)

    @Query("SELECT * FROM LocationData")
    fun getAllLocations(): LiveData<List<LocationData>>

    @Query("SELECT * FROM LocationData")
    suspend fun getAllLocationsDirect(): List<LocationData>

    @Delete
    suspend fun delete(location: LocationData)

    @Query("DELETE FROM locationdata")
    suspend fun deleteAll()
}
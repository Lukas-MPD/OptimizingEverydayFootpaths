package com.example.optimizingeverydayfootpaths.data

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel

class LocationViewModel : ViewModel() {

    fun getAllLocations(db: LocationDatabase): LiveData<List<LocationData>> {
        return db.locationDataDao().getAllLocations()
    }
}
package com.vk59.gotuda

import android.Manifest.permission
import android.location.LocationManager
import androidx.annotation.RequiresPermission
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.vk59.gotuda.MapViewType.MAPKIT
import com.vk59.gotuda.MapViewType.OSM
import com.vk59.gotuda.button_list.ButtonUiModel
import com.vk59.gotuda.map.data.LocationRepository
import com.vk59.gotuda.map.model.GoGeoPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel : ViewModel() {

  // Inject
  private val locationRepository: LocationRepository = LocationRepository()

  val mapViewType: LiveData<MapViewType>
    get() = _mapViewType

  private val _mapViewType = MutableLiveData(MAPKIT)

  val debugButtonsShown: StateFlow<Boolean>
    get() = _debugButtonsShown.asStateFlow()

  private val _debugButtonsShown = MutableStateFlow(BuildConfig.DEBUG)

  private fun setViewType(viewType: MapViewType) {
    _mapViewType.value = viewType
  }

  fun listenToButtons(): LiveData<List<ButtonUiModel>> {
    val buttons = listOf(
      ButtonUiModel("osm", "Open Street Map", onClick = { setViewType(OSM) }),
      ButtonUiModel("mapkit", "MapKit", onClick = { setViewType(MAPKIT) }),
      ButtonUiModel("showButtons", "Buttons show", onClick = { _debugButtonsShown.value = !debugButtonsShown.value })
    )
    return MutableLiveData(buttons)
  }

  @RequiresPermission(allOf = [permission.ACCESS_FINE_LOCATION, permission.ACCESS_COARSE_LOCATION])
  fun listenToUserGeo(locationManager: LocationManager): StateFlow<GoGeoPoint> {
    return locationRepository.listenToLocation(locationManager)
  }
}

enum class MapViewType {
  OSM,
  MAPKIT
}
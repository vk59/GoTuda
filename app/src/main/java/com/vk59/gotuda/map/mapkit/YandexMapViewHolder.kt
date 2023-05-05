package com.vk59.gotuda.map.mapkit

import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.Fragment
import com.vk59.gotuda.R
import com.vk59.gotuda.core.fromDrawable
import com.vk59.gotuda.di.SimpleDi
import com.vk59.gotuda.map.MapViewHolder
import com.vk59.gotuda.map.actions.MapAction.SinglePlaceTap
import com.vk59.gotuda.map.actions.MapActionsListener
import com.vk59.gotuda.map.model.MapNotAttachedToWindowException
import com.vk59.gotuda.map.model.MyGeoPoint
import com.yandex.mapkit.Animation
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.map.MapObjectCollection
import com.yandex.mapkit.map.MapObjectTapListener
import com.yandex.mapkit.map.PlacemarkMapObject
import com.yandex.mapkit.mapview.MapView
import com.yandex.mapkit.user_location.UserLocationLayer
import com.yandex.runtime.image.ImageProvider
import java.lang.ref.WeakReference

class YandexMapViewHolder(
  fragment: WeakReference<Fragment>,
  private val initialGeoPoint: MyGeoPoint?,
  private val mapActionsListener: MapActionsListener
) : MapViewHolder(fragment) {

  private val handler: Handler = Handler(Looper.getMainLooper())

  private val userLocationLayer: UserLocationLayer?
    get() = SimpleDi.userLocationLayer

  private var userLocation: MapObjectCollection? = null

  private var userLocationPlacemark: PlacemarkMapObject? = null

  private var map: MapView? = null

  private var mapObjects: MapObjectCollection? = null

  private val idToPlaceMarks = mutableMapOf<String, PlacemarkMapObject>()
  private val placeMarksToId = mutableMapOf<PlacemarkMapObject, String>()

  private val placeMarkTapListener = MapObjectTapListener { mapObject, _ ->
    placeMarksToId[mapObject]?.let { mapActionsListener.handleMapAction(SinglePlaceTap(it)) }
    true
  }

  override fun attach(mapView: View) {
    map = mapView as MapView
    mapView.map.isRotateGesturesEnabled = false
    if (fragment.requireContext().resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) {
      mapView.map.isNightModeEnabled = true
    }
    mapView.map.setMapStyle(
      "[" +
          "{" +
          "    \"elements\": \"label\"," +
          "    \"stylers\": {" +
          "      \"visibility\": \"off\"" +
          "  }" +
          "}" +
          "]"
    )
    requireUserLocationLayer()
    initialGeoPoint?.let {
      updateUserLocation(it)
      realMoveToUserLocation(it, animating = false)
    }
  }

  override fun updateUserLocation(geoPoint: MyGeoPoint) {
    val userLocation = requireUserLocationCollection()
    val newPlacemark = userLocation.addPlacemark(
      Point(geoPoint.latitude, geoPoint.longitude),
      fromDrawable(fragmentContext, R.drawable.ic_user_geo)
    )

    handler.postDelayed(
      {
        userLocationPlacemark?.let { userLocation.remove(it) }
        userLocationPlacemark = newPlacemark
      },
      10L
    )
  }

  private fun requireUserLocationLayer(): UserLocationLayer {
    val mapWindow = requireMap().mapWindow
    val mapKit = MapKitFactory.getInstance()
    val layer = userLocationLayer
    return if (layer != null && layer.isValid) {
      layer
    } else {
      mapKit.resetLocationManagerToDefault()
      mapKit.createUserLocationLayer(mapWindow).also {
        SimpleDi.userLocationLayer = it
      }
    }
  }

  override fun moveToUserLocation(geoPoint: MyGeoPoint) {
    realMoveToUserLocation(geoPoint, animating = true)
  }

  private fun realMoveToUserLocation(geoPoint: MyGeoPoint, animating: Boolean) {
    val mapView = requireMap()

    if (animating) {
      mapView.map.move(
        CameraPosition(Point(geoPoint.latitude, geoPoint.longitude), DEFAULT_ZOOM, 0f, 0f),
        Animation(Animation.Type.SMOOTH, 0.3f),
        null
      )
    } else {
      mapView.map.move(CameraPosition(Point(geoPoint.latitude, geoPoint.longitude), DEFAULT_ZOOM, 0f, 0f))
    }
  }

  override fun addPlacemark(id: String, geoPoint: MyGeoPoint, drawableInt: Int) {
    val placemark = idToPlaceMarks[id]
    if (placemark == null) {
      realAddPlacemark(id, geoPoint, drawableInt)
    } else {
      requireMapObjectsCollection().remove(placemark)
      realAddPlacemark(id, geoPoint, drawableInt)
    }
  }

  private fun realAddPlacemark(
    id: String,
    geoPoint: MyGeoPoint,
    drawableInt: Int
  ) {
    idToPlaceMarks[id] = requireMapObjectsCollection().addPlacemark(
      Point(geoPoint.latitude, geoPoint.longitude),
      ImageProvider.fromBitmap(AppCompatResources.getDrawable(fragmentContext, drawableInt)?.toBitmap())
    ).apply {
      placeMarksToId[this] = id
      addTapListener(placeMarkTapListener)
    }
  }

  private fun requireMapObjectsCollection(): MapObjectCollection {
    return mapObjects ?: (requireMap().map.mapObjects).addCollection().also { mapObjects = it }
  }

  private fun requireUserLocationCollection(): MapObjectCollection {
    return userLocation ?: (requireMap().map.mapObjects).addCollection().also { userLocation = it }
  }

  private fun requireMap(): MapView {
    return map ?: throw MapNotAttachedToWindowException()
  }

  override fun detach() {
    super.detach()
    idToPlaceMarks.values.forEach { mapObjects?.remove(it) }
    idToPlaceMarks.clear()
    map = null
    mapObjects = null
  }

  companion object {

    private const val DEFAULT_ZOOM = 17F
  }
}
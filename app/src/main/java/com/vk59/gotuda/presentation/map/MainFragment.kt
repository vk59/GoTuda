package com.vk59.gotuda.presentation.map

import android.Manifest.permission
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.bumptech.glide.Glide
import com.vk59.gotuda.R
import com.vk59.gotuda.R.drawable
import com.vk59.gotuda.R.layout
import com.vk59.gotuda.core.commitWithAnimation
import com.vk59.gotuda.core.fadeIn
import com.vk59.gotuda.core.fadeOut
import com.vk59.gotuda.core.makeGone
import com.vk59.gotuda.core.makeVisible
import com.vk59.gotuda.data.mock.Mocks.DEFAULT_PHOTO_URL
import com.vk59.gotuda.data.model.PlaceToVisit
import com.vk59.gotuda.databinding.FragmentMainBinding
import com.vk59.gotuda.map.MapController
import com.vk59.gotuda.map.actions.MapAction
import com.vk59.gotuda.map.actions.MapAction.SinglePlaceTap
import com.vk59.gotuda.map.actions.MapActionsListener
import com.vk59.gotuda.map.model.MapNotAttachedToWindowException
import com.vk59.gotuda.map.model.MyGeoPoint
import com.vk59.gotuda.presentation.map.MainFragmentState.ErrorState
import com.vk59.gotuda.presentation.map.MainFragmentState.FinishActivity
import com.vk59.gotuda.presentation.map.MainFragmentState.LaunchPlace
import com.vk59.gotuda.presentation.map.MainFragmentState.Main
import com.vk59.gotuda.presentation.map.MainFragmentState.MainButtonLoading
import com.vk59.gotuda.presentation.map.MapViewType.MAPKIT
import com.vk59.gotuda.presentation.map.MapViewType.OSM
import com.vk59.gotuda.presentation.profile.ProfileFragment
import com.vk59.gotuda.presentation.settings.SettingsFragment
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.map.CameraListener
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.map.CameraUpdateReason
import com.yandex.mapkit.map.CameraUpdateReason.GESTURES
import com.yandex.mapkit.map.Map
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import timber.log.Timber

// TODO: Make it initializing Fragment.
/**
 * The MainFragment must initialize all the required components only
 */
@AndroidEntryPoint
class MainFragment : Fragment(layout.fragment_main), CameraListener, MapActionsListener {

  private val binding: FragmentMainBinding by viewBinding(FragmentMainBinding::bind)

  private val viewModel: MainViewModel by viewModels()

  private var currentModalView: View? = null
  private var mapController: MapController? = null
  private var locationManager: LocationManager? = null
  // TODO: Move to repository
  private var followToUserLocation: Boolean = true

  private val handler: Handler = Handler(Looper.getMainLooper())

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    locationManager = ContextCompat.getSystemService(requireContext(), LocationManager::class.java)
    if (Build.VERSION.SDK_INT >= VERSION_CODES.P) {
      initLocationManager()
    } else {
      this.requestPermissions()
    }
  }

  @SuppressLint("ClickableViewAccessibility")
  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    Glide.with(requireContext()).load(DEFAULT_PHOTO_URL).into(binding.userPhoto)
    currentModalView = binding.mainBottomButtons.root
    binding.userPhoto.setOnClickListener {
      launchPassport()
    }
    launchMainButtons()
    launchDebugBottomButtons()
    val callback: OnBackPressedCallback = object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() = viewModel.backPressed()
    }
    requireActivity().onBackPressedDispatcher.addCallback(
      viewLifecycleOwner, callback
    )
    locationManager?.let {
      launchObserveGeoUpdates(it)
    }
  }

  private fun launchPassport() {
    parentFragmentManager.commitWithAnimation {
      replace(R.id.fragment_container, ProfileFragment())
      addToBackStack("main")
    }
  }

  private fun launchSettings() {
    parentFragmentManager.commitWithAnimation {
      replace(R.id.fragment_container, SettingsFragment())
      addToBackStack("main")
    }
  }

  override fun onStart() {
    MapKitFactory.getInstance().onStart()
    binding.mapKit.onStart()
    super.onStart()
    binding.mapKit.map.addCameraListener(this)
  }

  override fun onResume() {
    super.onResume()
    binding.mapView.onResume()
    viewLifecycleOwner.lifecycleScope.launch {
      // TODO: Bad solution, fix it!
      initMap(viewModel.obtainInitialLocation())
      viewModel.getPlaces()
      viewModel.listenToMapObjects().collectLatest { list ->
        list.forEach { place ->
          val icon = if (place.selected) drawable.ic_place_selected else drawable.ic_place
          this@MainFragment.mapController?.addPlacemark(place.id, place.geoPoint, icon)
        }
      }
    }
    viewLifecycleOwner.lifecycleScope.launch {
      viewModel.listenToFragmentState().collectLatest { state ->
        when (state) {
          Main -> launchMainButtons()
          MainButtonLoading -> launchMainButtons(loading = true)
          is LaunchPlace -> {
            viewModel.selectObject(state.place.id)
            launchCardRecommendations(state.place)
          }
          is ErrorState -> { /* TODO*/
          }
          FinishActivity -> {
            requireActivity().finish()
          }
        }
      }
    }
  }

  override fun handleMapAction(action: MapAction) {
    action as SinglePlaceTap
    viewModel.placeTapped(action.mapObjectId)
  }

  override fun onCameraPositionChanged(map: Map, pos: CameraPosition, reason: CameraUpdateReason, finished: Boolean) {
    if (reason == GESTURES) {
      followToUserLocation = false
      val geoButton = binding.mainBottomButtons.geoButton
      if (geoButton.visibility != View.VISIBLE) {
        binding.mainBottomButtons.geoButton.fadeIn()
      }
    }
  }

  override fun onPause() {
    super.onPause()
    Configuration.getInstance().save(
      getApplicationContext(), PreferenceManager.getDefaultSharedPreferences(getApplicationContext())
    )
    binding.mapView.onPause()
  }

  override fun onStop() {
    binding.mapKit.onStop()
    MapKitFactory.getInstance().onStop()
    this.mapController?.detach()
    this.mapController = null
    binding.mapKit.map.addCameraListener(this)
    super.onStop()
  }

  override fun onDestroy() {
    super.onDestroy()
    binding.mapView.onDetach()
  }

  private fun getApplicationContext(): Context {
    return requireContext().applicationContext
  }

  private fun launchDebugBottomButtons() {
    lifecycleScope.launch {
      viewModel.debugButtonsShown.collectLatest {
        binding.buttonList.isVisible = it
      }
    }
    viewModel.listenToButtons().observe(viewLifecycleOwner) { buttons ->
      binding.buttonList.addButtons(buttons)
    }
  }

  private fun launchMainButtons(loading: Boolean = false) {
    currentModalView?.makeGone()

    binding.mainBottomButtons.apply {

      root.makeVisible()
      currentModalView = binding.mainBottomButtons.root
      settingsButton.isClickable = !loading
      geoButton.isClickable = !loading
      settingsButton.setIconResource(drawable.ic_settings)
      geoButton.setIconResource(drawable.ic_geo_arrow)

      if (loading) {
        goTudaButton.setOnClickListener {}
        settingsButton.setOnClickListener { }
        geoButton.setOnClickListener {}
        goTudaButton.setProgressing(true)
      } else {
        goTudaButton.setTitle("Go")
        goTudaButton.setTitleSizeSp(30f)
        goTudaButton.setOnClickListener {
          viewModel.requestRecommendations()
        }
        goTudaButton.setProgressing(false)
        settingsButton.setOnClickListener {
          launchSettings()
        }
        geoButton.setOnClickListener {
          viewModel.moveToUserGeo()
          handler.postDelayed(
            {
              geoButton.fadeOut(to = View.INVISIBLE)
            }, 500L
          )
        }

      }
    }
  }

  private fun launchCardRecommendations(place: PlaceToVisit) {
    currentModalView?.makeGone()

    binding.cardsView.makeVisible()
    binding.cardsView.bindPlace(place)
    currentModalView = binding.cardsView
    binding.cardsView.setOnBackButtonClickListener {
      viewModel.deselectObject()
      viewModel.backPressed()
    }
  }

  private fun initMap(initialGeoPoint: MyGeoPoint?) {
    requireMapController().attachViews(this, listOf(binding.mapKit, binding.mapView), initialGeoPoint, this)
    viewModel.mapViewType.observe(viewLifecycleOwner) { mapType ->
      when (mapType) {
        OSM -> {
          binding.mapKit.isVisible = false
          binding.mapView.isVisible = true
        }
        MAPKIT -> {
          binding.mapKit.isVisible = true
          binding.mapView.isVisible = false
        }
        else -> { /* Nothing to do */
        }
      }
    }
  }

  @RequiresApi(VERSION_CODES.P)
  private fun initLocationManager() {
    if (locationManager?.isLocationEnabled == true) {
      this.requestPermissions()
    }
  }

  private fun requestPermissions() {
    if (ActivityCompat.checkSelfPermission(
        requireContext(), permission.ACCESS_FINE_LOCATION
      ) != PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(
        requireContext(), permission.ACCESS_COARSE_LOCATION
      ) != PackageManager.PERMISSION_GRANTED
    ) {
      registerForActivityResult(
        RequestMultiplePermissions(),
      ) { isGranted ->
        if (!isGranted.values.all { it }) {
          Toast.makeText(requireContext(), "Permission denied :(", Toast.LENGTH_LONG).show()
        }
      }.launch(arrayOf(permission.ACCESS_FINE_LOCATION, permission.ACCESS_COARSE_LOCATION))
    }
  }

  private fun launchObserveGeoUpdates(manager: LocationManager) {
    viewLifecycleOwner.lifecycleScope.launch {
      viewModel.listenToUserGeo(manager).collect {
        if (followToUserLocation) {
          try {
            requireMapController().moveToUserLocation(it)
            requireMapController().showUserLocation(it)
          } catch (e: java.lang.IllegalStateException) {
            Timber.d(e)
          } catch (mapNotAttached: MapNotAttachedToWindowException) {
            Timber.d(mapNotAttached)
          }
        }
      }
    }
    viewLifecycleOwner.lifecycleScope.launchWhenResumed {
      viewModel.listenToMove().collect {
        followToUserLocation = true
        requireMapController().moveToUserLocation(it.geoPoint)
      }
    }
  }

  private fun requireMapController(): MapController {
    return this.mapController ?: MapController().also { mapController = it }
  }

  companion object {

    private const val TAG = "MainFragment"
  }
}
package com.mapbox.maps.plugin.viewport.state

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import androidx.annotation.VisibleForTesting
import com.mapbox.common.Logger
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.plugin.animation.Cancelable
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.delegates.MapDelegateProvider
import com.mapbox.maps.plugin.locationcomponent.LocationComponentPlugin
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorBearingChangedListener
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorPositionChangedListener
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.maps.plugin.viewport.data.FollowingViewportStateBearing
import com.mapbox.maps.plugin.viewport.data.FollowingViewportStateOptions
import com.mapbox.maps.plugin.viewport.transition.MapboxViewportTransitionFactory
import java.util.concurrent.*

/**
 * The actual implementation of [FollowingViewportState] that follows user's location.
 *
 * Note: [LocationComponentPlugin] should be enabled to use this viewport state.
 */
internal class FollowingViewportStateImpl(
  mapDelegateProvider: MapDelegateProvider,
  initialOptions: FollowingViewportStateOptions,
  private val transitionFactory: MapboxViewportTransitionFactory = MapboxViewportTransitionFactory(
    mapDelegateProvider
  )
) : FollowingViewportState {
  private val cameraPlugin = mapDelegateProvider.mapPluginProviderDelegate.camera
  private val locationComponent = mapDelegateProvider.mapPluginProviderDelegate.location
  private val dataSourceUpdateObservers = CopyOnWriteArraySet<ViewportStateDataObserver>()

  private var lastLocation: Point? = null
  private var lastBearing: Double? = null

  private var runningAnimation: AnimatorSet? = null

  @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
  internal var isFollowingStateRunning = false
  private var isObservingLocationUpdates = false

  private val indicatorPositionChangedListener = OnIndicatorPositionChangedListener { point ->
    lastLocation = point
    notifyLatestViewportData()
  }

  private val indicatorBearingChangedListener = OnIndicatorBearingChangedListener { bearing ->
    if (options.bearing == FollowingViewportStateBearing.SyncWithLocationPuck) {
      lastBearing = bearing
      notifyLatestViewportData()
    }
  }

  private fun notifyLatestViewportData() {
    if (lastLocation != null && (options.bearing is FollowingViewportStateBearing.Constant || lastBearing != null)) {
      val viewportData = evaluateViewportData()
      if (isFollowingStateRunning) {
        // Use instant update here since the location updates are already interpolated by the location component plugin
        updateFrame(viewportData, true)
      }
      dataSourceUpdateObservers.forEach {
        if (!it.onNewData(viewportData)) {
          dataSourceUpdateObservers.remove(it)
        }
      }
    }
  }

  private fun evaluateViewportData(): CameraOptions {
    return CameraOptions.Builder()
      .center(lastLocation)
      .bearing(
        with(options.bearing) {
          when (this) {
            is FollowingViewportStateBearing.Constant -> bearing
            else -> lastBearing
          }
        }
      )
      .zoom(options.zoom)
      .pitch(options.pitch)
      .padding(options.padding)
      .build()
  }

  private fun addIndicatorListenerIfNeeded() {
    if (!isObservingLocationUpdates) {
      locationComponent.addOnIndicatorPositionChangedListener(indicatorPositionChangedListener)
      locationComponent.addOnIndicatorBearingChangedListener(indicatorBearingChangedListener)
      isObservingLocationUpdates = true
    }
  }

  private fun removeIndicatorListenerIfNeeded() {
    if (isObservingLocationUpdates && dataSourceUpdateObservers.isEmpty() && !isFollowingStateRunning) {
      locationComponent.removeOnIndicatorPositionChangedListener(indicatorPositionChangedListener)
      locationComponent.removeOnIndicatorBearingChangedListener(indicatorBearingChangedListener)
      isObservingLocationUpdates = false
    }
  }

  /**
   * Describes the configuration options of the state.
   */
  override var options: FollowingViewportStateOptions = initialOptions
    set(value) {
      field = value
      notifyLatestViewportData()
    }

  /**
   * Observer the new camera options produced by the [ViewportState], it can be used to get the
   * latest state [CameraOptions] for [ViewportTransition].
   *
   * @param viewportStateDataObserver observer that observe new viewport data.
   * @return a handle that cancels current observation.
   */
  override fun observeDataSource(viewportStateDataObserver: ViewportStateDataObserver): Cancelable {
    checkLocationComponentEnablement()
    addIndicatorListenerIfNeeded()
    dataSourceUpdateObservers.add(viewportStateDataObserver)
    return Cancelable {
      dataSourceUpdateObservers.remove(viewportStateDataObserver)
      removeIndicatorListenerIfNeeded()
    }
  }

  private fun checkLocationComponentEnablement() {
    if (!locationComponent.enabled) {
      Logger.w(
        TAG,
        "Location component is required to be enabled to use FollowingViewportState, otherwise there would be no FollowingViewportState updates or ViewportTransition updates towards the FollowingViewportState."
      )
    }
  }

  /**
   * Start updating the camera for the current [ViewportState].
   *
   * @return a handle that cancels the camera updates.
   */
  override fun startUpdatingCamera(): Cancelable {
    checkLocationComponentEnablement()
    addIndicatorListenerIfNeeded()
    updateFrame(
      evaluateViewportData(), instant = false
    ) { isFinished ->
      if (isFinished) {
        isFollowingStateRunning = true
      }
    }
    return Cancelable {
      isFollowingStateRunning = false
      cancelAnimation()
      removeIndicatorListenerIfNeeded()
    }
  }

  /**
   * Stop updating the camera for the current [ViewportState].
   */
  override fun stopUpdatingCamera() {
    isFollowingStateRunning = false
    cancelAnimation()
    removeIndicatorListenerIfNeeded()
  }

  private fun cancelAnimation() {
    runningAnimation?.apply {
      cancel()
      childAnimations.forEach {
        cameraPlugin.unregisterAnimators(it as ValueAnimator)
      }
    }
    runningAnimation = null
  }

  private fun startAnimation(
    animatorSet: AnimatorSet,
    instant: Boolean,
  ) {
    cancelAnimation()
    animatorSet.childAnimations.forEach {
      cameraPlugin.registerAnimators(it as ValueAnimator)
    }
    if (instant) {
      animatorSet.duration = 0
    }
    animatorSet.start()
    runningAnimation = animatorSet
  }

  private fun finishAnimation(animatorSet: AnimatorSet?) {
    animatorSet?.childAnimations?.forEach {
      cameraPlugin.unregisterAnimators(it as ValueAnimator)
    }
    if (runningAnimation == animatorSet) {
      runningAnimation = null
    }
  }

  private fun updateFrame(
    cameraOptions: CameraOptions,
    instant: Boolean = false,
    onComplete: ((isFinished: Boolean) -> Unit)? = null
  ) {
    startAnimation(
      transitionFactory.transitionLinear(cameraOptions, options.frameAnimationDurationMs)
        .apply {
          addListener(
            object : Animator.AnimatorListener {
              private var isCanceled = false
              override fun onAnimationStart(animation: Animator?) {
                // no-ops
              }

              override fun onAnimationEnd(animation: Animator?) {
                onComplete?.invoke(!isCanceled)
                finishAnimation(this@apply)
              }

              override fun onAnimationCancel(animation: Animator?) {
                isCanceled = true
              }

              override fun onAnimationRepeat(animation: Animator?) {
                // no-ops
              }
            }
          )
        },
      instant
    )
  }

  private companion object {
    const val TAG = "FollowingViewportStateImpl"
  }
}
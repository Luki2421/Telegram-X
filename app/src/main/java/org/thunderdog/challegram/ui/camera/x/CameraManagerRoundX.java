/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.thunderdog.challegram.ui.camera.x;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.Nullable;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.CameraState;
import androidx.camera.core.Preview;
import androidx.camera.core.SurfaceRequest;
import androidx.camera.core.ZoomState;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;

import com.google.common.util.concurrent.ListenableFuture;

import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.player.RoundVideoRecorder;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.ui.camera.CameraDelegate;
import org.thunderdog.challegram.ui.camera.CameraFeatures;
import org.thunderdog.challegram.ui.camera.CameraManagerTexture;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

// @MadeByLLM("gpt-6", id = "camerax-only")
public final class CameraManagerRoundX extends CameraManagerTexture
    implements RoundVideoRecorder.CameraHost {
  private final Executor mainExecutor;
  // @MadeByLLM("gpt-6.2-sol", id = "camerax-round-handoff")
  private @Nullable LiveData<CameraState> previousCameraState;
  private final Observer<CameraState> previousCameraObserver = this::onPreviousCameraStateChanged;
  private ProcessCameraProvider provider;
  private Camera camera;
  private Preview preview;
  private RoundVideoRecorder renderer;
  private SurfaceTexture rendererOutput;
  private SurfaceRequest pendingRequest;
  private SurfaceRequest preparingRequest;
  private boolean inputInUse;
  private boolean preparingInput;
  private boolean closingRenderer;
  private boolean releaseOutput;
  private boolean open;
  private boolean paused;
  private boolean destroyed;
  private boolean switching;
  private boolean originalFacing;
  private boolean torchEnabled;
  private int generation;

  public CameraManagerRoundX (
    Context context,
    CameraDelegate delegate,
    @Nullable LiveData<CameraState> previousCameraState
  ) {
    super(context, delegate);
    this.previousCameraState = previousCameraState;
    mainExecutor = ContextCompat.getMainExecutor(context);
    cameraView.setIgnoreAspectRatio(true);
  }

  @Override
  public void openCamera () {
    if (destroyed || open) {
      return;
    }
    open = true;
    originalFacing = preferFrontFacingCamera();
    int requestGeneration = ++generation;
    ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(context);
    future.addListener(() -> {
      try {
        ProcessCameraProvider result = future.get();
        if (open && requestGeneration == generation && !destroyed) {
          provider = result;
          bindCamera();
        }
      } catch (ExecutionException error) {
        if (open && requestGeneration == generation && !destroyed) {
          reportError(error);
        }
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        if (open && requestGeneration == generation && !destroyed) {
          reportError(error);
        }
      }
    }, mainExecutor);
  }

  private void bindCamera () {
    if (!open || paused || destroyed || closingRenderer || provider == null ||
      getSurfaceTexture() == null || preview != null) {
      return;
    }
    if (previousCameraState != null) {
      CameraState state = previousCameraState.getValue();
      if (state == null || state.getType() != CameraState.Type.CLOSED) {
        // Unbind starts an asynchronous close. Wait before opening the round camera.
        previousCameraState.observeForever(previousCameraObserver);
        return;
      }
      stopObservingPreviousCamera();
      previousCameraState = null;
    }
    try {
      CameraSelector selector = preferFrontFacingCamera() ?
        CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
      if (!provider.hasCamera(selector)) {
        selector = preferFrontFacingCamera() ?
          CameraSelector.DEFAULT_BACK_CAMERA : CameraSelector.DEFAULT_FRONT_CAMERA;
        setPreferFrontFacingCamera(!preferFrontFacingCamera());
      }
      int maxResolution = getMaxResolution() > 0 ? getMaxResolution() : 480;
      preview = new Preview.Builder()
        .setTargetRotation(getSurfaceRotation())
        .setResolutionSelector(new ResolutionSelector.Builder()
          .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
          .setResolutionStrategy(new ResolutionStrategy(
            new Size(maxResolution * 4 / 3, maxResolution),
            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
          ))
          .build())
        .build();
      preview.setSurfaceProvider(mainExecutor, this::onSurfaceRequested);
      camera = provider.bindToLifecycle((LifecycleOwner) context, selector, preview);
      delegate.displayFatalErrorMessage(null);
      delegate.onAvailableCamerasCountChanged(provider.getAvailableCameraInfos().size());
      delegate.onFlashModeChanged(CameraFeatures.FEATURE_FLASH_OFF);
      onZoomChanged(getCurrentZoom());
    } catch (Exception error) {
      releaseRoundRenderer();
      reportError(error);
    }
  }

  private void onPreviousCameraStateChanged (CameraState state) {
    if (state != null && state.getType() == CameraState.Type.CLOSED) {
      stopObservingPreviousCamera();
      previousCameraState = null;
      bindCamera();
    }
  }

  private void stopObservingPreviousCamera () {
    if (previousCameraState != null) {
      previousCameraState.removeObserver(previousCameraObserver);
    }
  }

  private void onSurfaceRequested (SurfaceRequest request) {
    if (!open || paused || closingRenderer || destroyed) {
      request.willNotProvideSurface();
      return;
    }
    if (pendingRequest != null) {
      pendingRequest.willNotProvideSurface();
    }
    pendingRequest = request;
    request.addRequestCancellationListener(mainExecutor, () -> {
      if (pendingRequest == request) {
        pendingRequest = null;
      }
    });
    prepareInput();
  }

  private void prepareInput () {
    if (pendingRequest == null || inputInUse || preparingInput || closingRenderer) {
      return;
    }
    SurfaceTexture output = getSurfaceTexture();
    if (output == null) {
      return;
    }
    preparingInput = true;
    boolean initialize = renderer == null;
    if (initialize) {
      rendererOutput = output;
      renderer = new RoundVideoRecorder(this, output, getTextureWidth(), getTextureHeight());
    }
    RoundVideoRecorder currentRenderer = renderer;
    SurfaceRequest request = pendingRequest;
    preparingRequest = request;
    Size size = request.getResolution();
    currentRenderer.setPreviewSizes(size.getWidth(), size.getHeight(),
      getDisplayRotation(), false);
    request.setTransformationInfoListener(mainExecutor, info -> {
      if (renderer == currentRenderer && !closingRenderer) {
        // SurfaceTexture already applies the sensor rotation and mirroring for direct inputs.
        boolean cameraTransform = info.hasCameraTransform();
        int rotation = cameraTransform ? getDisplayRotation() : info.getRotationDegrees();
        currentRenderer.setPreviewSizes(size.getWidth(), size.getHeight(), rotation,
          !cameraTransform && info.isMirroring());
      }
    });
    if (initialize) {
      currentRenderer.openCamera();
    } else {
      // The previous CameraX request has released its surface before the GL input is replaced.
      currentRenderer.onCameraClose();
    }
  }

  @Override
  public void onInputSurfaceReady (RoundVideoRecorder recorder, SurfaceTexture texture) {
    if (renderer != recorder) {
      return;
    }
    preparingInput = false;
    if (closingRenderer || pendingRequest == null) {
      return;
    }
    if (pendingRequest != preparingRequest) {
      prepareInput();
      return;
    }
    SurfaceRequest request = pendingRequest;
    pendingRequest = null;
    Surface surface = new Surface(texture);
    inputInUse = true;
    request.provideSurface(surface, mainExecutor, result -> {
      surface.release();
      inputInUse = false;
      if (closingRenderer) {
        destroyRenderer();
      } else {
        prepareInput();
      }
    });
  }

  @Override
  public void onPreviewRendered (RoundVideoRecorder recorder) {
    if (renderer == recorder && open && !paused && !closingRenderer) {
      delegate.onRenderedFirstFrame();
      if (switching) {
        switching = false;
        delegate.onCameraSourceChange(true,
          originalFacing != preferFrontFacingCamera(), preferFrontFacingCamera());
      }
    }
  }

  @Override
  public void onCameraError (RoundVideoRecorder recorder, Throwable error) {
    if (renderer == recorder && !closingRenderer) {
      closeCamera();
      reportError(error);
    }
  }

  private void reportError (Throwable error) {
    Log.e(Log.TAG_ROUND, "CameraX round camera failed", error);
    delegate.displayFatalErrorMessage(Log.toString(error));
  }

  public void releaseRoundRenderer () {
    stopObservingPreviousCamera();
    closingRenderer = renderer != null;
    if (pendingRequest != null) {
      pendingRequest.willNotProvideSurface();
      pendingRequest = null;
    }
    if (camera != null && torchEnabled) {
      camera.getCameraControl().enableTorch(false);
    }
    torchEnabled = false;
    if (preview != null && provider != null) {
      provider.unbind(preview);
    }
    preview = null;
    camera = null;
    switching = false;
    if (!inputInUse && closingRenderer) {
      destroyRenderer();
    }
  }

  private boolean destroyingRenderer;

  private void destroyRenderer () {
    if (destroyingRenderer || renderer == null) {
      return;
    }
    destroyingRenderer = true;
    renderer.destroy(() -> {
      if (releaseOutput) {
        rendererOutput.release();
      }
      renderer = null;
      rendererOutput = null;
      releaseOutput = false;
      closingRenderer = false;
      destroyingRenderer = false;
      preparingInput = false;
      bindCamera();
    });
  }

  public boolean requestRoundVideoCapture (
    String key,
    RoundVideoRecorder.Delegate recordingDelegate,
    String outputPath
  ) {
    if (!isCameraActive() || renderer == null || closingRenderer ||
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
        PackageManager.PERMISSION_GRANTED) {
      UI.post(() -> recordingDelegate.onVideoRecordingFinished(
        key, -1, -1, java.util.concurrent.TimeUnit.MILLISECONDS));
      return false;
    }
    renderer.startCapture(recordingDelegate, key, outputPath);
    return true;
  }

  public void finishOrCancelRoundVideoCapture (String key, boolean saveResult) {
    if (renderer != null) {
      renderer.finishCapture(key, saveResult);
    }
  }

  @Override
  public void closeCamera () {
    open = false;
    generation++;
    releaseRoundRenderer();
  }

  @Override
  public void pauseCamera () {
    paused = true;
    releaseRoundRenderer();
  }

  @Override
  public void resumeCamera () {
    paused = false;
    bindCamera();
  }

  @Override
  public void destroy () {
    destroyed = true;
    closeCamera();
    previousCameraState = null;
  }

  @Override
  protected void onTextureAvailable (SurfaceTexture texture, int width, int height) {
    bindCamera();
  }

  @Override
  protected void onTextureSizeChanged (SurfaceTexture texture, int width, int height) { }

  @Override
  protected boolean onTextureDestroyed (SurfaceTexture texture) {
    boolean retained = renderer != null && rendererOutput == texture;
    if (retained) {
      releaseOutput = true;
    }
    releaseRoundRenderer();
    return !retained;
  }

  @Override
  public void setPreviewSize (int width, int height) { }

  @Override
  protected void onDisplayRotationChanged (int rotation) {
    if (preview != null) {
      preview.setTargetRotation(getSurfaceRotation());
    }
  }

  @Override
  public void switchCamera () {
    if (camera == null || switching || closingRenderer || preparingInput) {
      return;
    }
    try {
      CameraSelector selector = preferFrontFacingCamera() ?
        CameraSelector.DEFAULT_BACK_CAMERA : CameraSelector.DEFAULT_FRONT_CAMERA;
      if (!provider.hasCamera(selector)) {
        return;
      }
      switching = true;
      setPreferFrontFacingCamera(!preferFrontFacingCamera());
      delegate.onCameraSourceChange(false,
        originalFacing != preferFrontFacingCamera(), preferFrontFacingCamera());
      provider.unbind(preview);
      preview = null;
      bindCamera();
    } catch (Exception error) {
      switching = false;
      reportError(error);
    }
  }

  @Override
  public void switchFlashMode () {
    if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
      torchEnabled = !torchEnabled;
      camera.getCameraControl().enableTorch(torchEnabled);
      delegate.onFlashModeChanged(torchEnabled ?
        CameraFeatures.FEATURE_FLASH_ON : CameraFeatures.FEATURE_FLASH_OFF);
    }
  }

  @Override
  public boolean isCameraActive () {
    return camera != null && open && !paused && !closingRenderer;
  }

  private ZoomState zoomState () {
    return camera != null ? camera.getCameraInfo().getZoomState().getValue() : null;
  }

  @Override
  public float getMinZoom () {
    ZoomState state = zoomState();
    return state != null ? state.getMinZoomRatio() : 1f;
  }

  @Override
  public float getMaxZoom () {
    ZoomState state = zoomState();
    return state != null ? state.getMaxZoomRatio() : 1f;
  }

  @Override
  public float getCurrentZoom () {
    ZoomState state = zoomState();
    return state != null ? state.getZoomRatio() : 1f;
  }

  @Override
  public float getMinZoomStep () {
    return 0.1f;
  }

  @Override
  protected void onRequestZoom (float zoom) {
    if (camera != null) {
      float ratio = Math.max(getMinZoom(), Math.min(zoom, getMaxZoom()));
      camera.getCameraControl().setZoomRatio(ratio);
      onZoomChanged(ratio);
    }
  }

  @Override
  protected void onResetPreferences () { }

  @Override
  protected boolean getBitmap (Bitmap bitmap) {
    if (cameraView.isAvailable()) {
      cameraView.getBitmap(bitmap);
      return true;
    }
    return false;
  }

  @Override
  protected void onTakePhoto (int width, int height, int rotation) {
    onTakeMediaError(false);
  }

  @Override
  protected boolean onStartVideoCapture (int rotation) {
    return false;
  }

  @Override
  protected void onFinishOrCancelVideoCapture () { }
}

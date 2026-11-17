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
// region @MadeByLLM("gpt-6", id = "android13-arm64")
package org.thunderdog.challegram.ui.camera

import android.graphics.ImageFormat
import android.graphics.RectF
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.core.content.ContextCompat
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.detector.FinderPattern
import org.thunderdog.challegram.Log
import org.thunderdog.challegram.U
import org.thunderdog.challegram.ui.camera.legacy.CameraApiLegacy
import org.thunderdog.challegram.unsorted.AppContext
import org.thunderdog.challegram.unsorted.Settings
import tgx.flavor.Barcode
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.max
import kotlin.math.min

// @MadeByLLM("gpt-6", id = "camerax-lifecycle-fixes")
class CameraQrBridge(manager: CameraManager<*>) {
  val backgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor()

  private val delegate: CameraDelegate = manager.delegate
  private val mainExecutor: Executor = ContextCompat.getMainExecutor(manager.context)
  private val zxingReader = QRCodeReader()
  private var barcodeScanner: BarcodeScanner? = null
  @Volatile private var mlkitFailed = false
  @Volatile private var destroyed = false
  private var cameraXAnalyzer: MlKitAnalyzer? = null
  private var analyzerWidth = 0
  private var analyzerHeight = 0
  private var analyzerRotation = -1
  private var pendingDetections = 0

  init {
    if (U.isGooglePlayServicesAvailable(AppContext.get()) &&
      !Settings.instance().needForceZxingQrProcessing()) {
      try {
        barcodeScanner = BarcodeScanning.getClient(
          BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        )
      } catch (e: Exception) {
        Log.e(Log.TAG_CAMERA, e)
      }
    }
  }

  @Synchronized
  fun destroy() {
    destroyed = true
    backgroundExecutor.shutdown()
    closeScannerIfIdle()
  }

  @Synchronized
  private fun beginDetection(): Boolean {
    if (destroyed) {
      return false
    }
    pendingDetections++
    return true
  }

  @Synchronized
  private fun endDetection() {
    pendingDetections--
    closeScannerIfIdle()
  }

  private fun closeScannerIfIdle() {
    if (destroyed && pendingDetections == 0) {
      barcodeScanner?.close()
      barcodeScanner = null
    }
  }

  @Suppress("UnsafeOptInUsageError")
  fun processImage(proxy: ImageProxy) {
    if (destroyed) {
      proxy.close()
      return
    }
    val scanner = barcodeScanner
    val width = proxy.width
    val height = proxy.height
    val rotation = proxy.imageInfo.rotationDegrees
    if (scanner != null && !mlkitFailed) {
      if (proxy.image == null || !beginDetection()) {
        proxy.close()
        return
      }
      try {
        if (cameraXAnalyzer == null || analyzerWidth != width || analyzerHeight != height ||
          analyzerRotation != rotation) {
          analyzerWidth = width
          analyzerHeight = height
          analyzerRotation = rotation
          cameraXAnalyzer = MlKitAnalyzer(
            listOf(scanner),
            ImageAnalysis.COORDINATE_SYSTEM_ORIGINAL,
            mainExecutor
          ) { result ->
            try {
              if (!destroyed) {
                val error = result.getThrowable(scanner)
                if (error != null) {
                  Log.e(Log.TAG_CAMERA, error)
                  mlkitFailed = true
                  delegate.onQrCodeNotFound()
                } else {
                  reportBarcodes(result.getValue(scanner), width, height, rotation)
                }
              }
            } finally {
              endDetection()
            }
          }
        }
        // MlKitAnalyzer owns the proxy until all asynchronous detectors have completed.
        cameraXAnalyzer!!.analyze(proxy)
      } catch (e: Exception) {
        proxy.close()
        endDetection()
        mlkitFailed = true
        Log.e(Log.TAG_CAMERA, e)
      }
    } else {
      try {
        zxingImplementation(copyLuminance(proxy), width, height, rotation) {
          proxy.close()
        }
      } catch (e: Exception) {
        proxy.close()
        Log.e(Log.TAG_CAMERA, e)
      }
    }
  }

  fun processImage(
    data: ByteArray,
    previewWidth: Int,
    previewHeight: Int,
    legacyApi: CameraApiLegacy
  ) {
    val rotation = delegate.getCurrentCameraOrientation()

    if (this.isGmsImplementationSupported) {
      gmsImplementation(
        InputImage.fromByteArray(
          data,
          previewWidth,
          previewHeight,
          rotation,
          ImageFormat.NV21
        ),
        data
      ) {
        legacyApi.notifyCanReadNextFrame()
      }
    } else {
      zxingImplementation(
        data,
        previewWidth,
        previewHeight,
        rotation
      ) {
        legacyApi.notifyCanReadNextFrame()
      }
    }
  }

  val isGmsImplementationSupported: Boolean
    get() = !destroyed && barcodeScanner != null && !mlkitFailed

  private fun gmsImplementation(
    image: InputImage,
    safeData: ByteArray,
    onCompleteListener: (() -> Unit)?
  ) {
    if (!beginDetection()) {
      onCompleteListener?.invoke()
      return
    }
    try {
      barcodeScanner!!.process(image).addOnCompleteListener(mainExecutor) { task ->
        try {
          if (destroyed) {
            onCompleteListener?.invoke()
          } else if (task.isSuccessful) {
            reportBarcodes(task.result, image.width, image.height, image.rotationDegrees)
            onCompleteListener?.invoke()
          } else if (task.exception is MlKitException) {
            mlkitFailed = true
            zxingImplementation(
              safeData,
              image.width,
              image.height,
              image.rotationDegrees,
              onCompleteListener
            )
          } else {
            Log.e(Log.TAG_CAMERA, task.exception)
            onCompleteListener?.invoke()
          }
        } finally {
          endDetection()
        }
      }
    } catch (e: Exception) {
      endDetection()
      Log.e(Log.TAG_CAMERA, e)
      mlkitFailed = true
      zxingImplementation(
        safeData,
        image.width,
        image.height,
        image.rotationDegrees,
        onCompleteListener
      )
    }
  }

  private fun reportBarcodes(
    barcodes: List<Barcode>?,
    width: Int,
    height: Int,
    rotation: Int
  ) {
    val first = barcodes?.firstOrNull { !it.rawValue.isNullOrEmpty() }
    if (first == null) {
      delegate.onQrCodeNotFound()
      return
    }
    delegate.onQrCodeFound(
      first.rawValue,
      first.boundingBox?.let { RectF(it) },
      if (U.isRotated(rotation)) width else height,
      if (U.isRotated(rotation)) height else width,
      0,
      false
    )
  }

  private fun zxingImplementation(
    data: ByteArray,
    width: Int,
    height: Int,
    rotation: Int,
    onFinish: Runnable?
  ) {
    if (destroyed) {
      onFinish?.run()
      return
    }
    try {
      backgroundExecutor.execute analysis@ {
        try {
          if (destroyed) {
            return@analysis
          }
          val sensorRotation = delegate.getCurrentCameraSensorOrientation()
          val match = zxingImplementationImpl(data, width, height, rotation)
          if (match != null && !match.text.isNullOrEmpty()) {
            val zxingBox: RectF?
            if (sensorRotation != rotation && U.isRotated(sensorRotation)) {
              zxingBox = zxingBoundingBox(match, rotation, true, width, height)
              mainExecutor.execute report@ {
                if (destroyed) {
                  return@report
                }
                delegate.onQrCodeFound(
                  match.text,
                  zxingBox,
                  height,
                  width,
                  rotation,
                  true
                )
              }
            } else {
              zxingBox = zxingBoundingBox(match, rotation, false, width, height)
              mainExecutor.execute report@ {
                if (destroyed) {
                  return@report
                }
                delegate.onQrCodeFound(
                  match.text,
                  zxingBox,
                  width,
                  height,
                  rotation,
                  true
                )
              }
            }
          } else {
            mainExecutor.execute {
              if (!destroyed) {
                delegate.onQrCodeNotFound()
              }
            }
          }
        } catch (ex: Exception) {
          if (ex is NotFoundException) {
            mainExecutor.execute {
              if (!destroyed) {
                delegate.onQrCodeNotFound()
              }
            }
          } else {
            Log.e(Log.TAG_CAMERA, ex)
          }
        } finally {
          onFinish?.run()
        }
      }
    } catch (e: RejectedExecutionException) {
      onFinish?.run()
    }
  }

  private fun zxingBoundingBox(
    result: Result,
    rotation: Int,
    sensorRotationInverted: Boolean,
    width: Int,
    height: Int
  ): RectF? {
    // ordered in: bottom-left, top-left, top-right
    if (result.resultPoints.size < 3) {
      return null
    }

    val points = result.resultPoints
    val bottomLeft: ResultPoint
    val topLeft: ResultPoint
    val topRight: ResultPoint
    val moduleSize: Int

    if (U.isRotated(rotation)) {
      bottomLeft = points[2]
      topLeft = points[1]
      topRight = points[0]
    } else {
      bottomLeft = points[0]
      topLeft = points[1]
      topRight = points[2]
    }

    if (bottomLeft is FinderPattern) {
      moduleSize = bottomLeft.getEstimatedModuleSize().toInt() * 2
    } else {
      moduleSize = 0
    }

    var x1 = min(min(topLeft.getX(), topRight.getX()), bottomLeft.getX()).toInt()
    var x2 = max(max(topLeft.getX(), topRight.getX()), bottomLeft.getX()).toInt()
    val y1 = min(min(topLeft.getY(), topRight.getY()), bottomLeft.getY()).toInt()
    val y2 = max(max(topLeft.getY(), topRight.getY()), bottomLeft.getY()).toInt()

    if (sensorRotationInverted) {
      // these rotations need inverting zones
      val px1 = width - x1
      val px2 = width - x2
      x1 = px2
      x2 = px1
    }

    return RectF(
      (x1 - moduleSize).toFloat(),
      (y1 - moduleSize).toFloat(),
      (x2 + moduleSize).toFloat(),
      (y2 + moduleSize).toFloat()
    )
  }

  @Throws(FormatException::class, ChecksumException::class, NotFoundException::class)
  private fun zxingImplementationImpl(
    data: ByteArray,
    width: Int,
    height: Int,
    rotation: Int
  ): Result? {
    var data = data
    var width = width
    var height = height
    if (rotation != 0) {
      data = rotateYuvImage(data, width, height, rotation)
    }

    if (U.isRotated(rotation)) {
      val prevWidth = width
      width = height
      height = prevWidth
    }

    val source = PlanarYUVLuminanceSource(
      data,
      width,
      height,
      0,
      0,
      width,
      height,
      rotation == 180
    )

    return zxingReader.decode(BinaryBitmap(HybridBinarizer(source)))
  }

  private fun copyLuminance(proxy: ImageProxy): ByteArray {
    val plane = proxy.planes[0]
    val buffer = plane.buffer.duplicate()
    val offset = buffer.position()
    val bytes = ByteArray(proxy.width * proxy.height)
    for (row in 0..<proxy.height) {
      val rowOffset = offset + row * plane.rowStride
      if (plane.pixelStride == 1) {
        buffer.position(rowOffset)
        buffer.get(bytes, row * proxy.width, proxy.width)
      } else {
        for (column in 0..<proxy.width) {
          bytes[row * proxy.width + column] = buffer.get(rowOffset + column * plane.pixelStride)
        }
      }
    }
    return bytes
  }

  private fun rotateYuvImage(data: ByteArray, width: Int, height: Int, degrees: Int): ByteArray {
    if (degrees == 0 || degrees % 90 != 0) {
      return data
    }

    val rotatedData = ByteArray(data.size)

    for (y in 0..<height) {
      for (x in 0..<width) {
        when (degrees) {
          90 -> {
            rotatedData[x * height + height - y - 1] = data[x + y * width]
          }

          180 -> {
            rotatedData[width * (height - y - 1) + width - x - 1] = data[x + y * width]
          }

          270 -> {
            rotatedData[y + x * height] = data[y * width + width - x - 1]
          }
        }
      }
    }

    return rotatedData
  }
}
// endregion

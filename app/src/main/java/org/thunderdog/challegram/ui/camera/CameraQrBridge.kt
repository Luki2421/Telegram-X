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
// @MadeByLLM("gpt-6", id = "camerax-only")
package org.thunderdog.challegram.ui.camera

import android.graphics.RectF
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.detector.FinderPattern
import org.thunderdog.challegram.Log
import org.thunderdog.challegram.U
import org.thunderdog.challegram.unsorted.AppContext
import org.thunderdog.challegram.unsorted.Settings
import tgx.flavor.Barcode
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

// @MadeByLLM("gpt-6", id = "camerax-lifecycle-fixes")
@Suppress("UnsafeOptInUsageError")
class CameraQrBridge(manager: CameraManager<*>, private val previewView: PreviewView) {
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
  private val transformFactory = ImageProxyTransformFactory().apply {
    isUsingRotationDegrees = true
  }

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
            ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED,
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
                  reportBarcodes(result.getValue(scanner))
                }
              }
            } finally {
              endDetection()
            }
          }
        }
        val analyzer = cameraXAnalyzer!!
        mainExecutor.execute {
          val transform = previewView.sensorToViewTransform
          if (destroyed || transform == null) {
            proxy.close()
            endDetection()
          } else {
            // MlKitAnalyzer maps detections into PreviewView coordinates and owns the frame.
            analyzer.updateTransform(transform)
            analyzer.analyze(proxy)
          }
        }
      } catch (e: Exception) {
        proxy.close()
        endDetection()
        mlkitFailed = true
        Log.e(Log.TAG_CAMERA, e)
      }
    } else {
      try {
        zxingImplementation(
          copyLuminance(proxy),
          width,
          height,
          rotation,
          transformFactory.getOutputTransform(proxy)
        ) {
          proxy.close()
        }
      } catch (e: Exception) {
        proxy.close()
        Log.e(Log.TAG_CAMERA, e)
      }
    }
  }

  private fun reportBarcodes(
    barcodes: List<Barcode>?
  ) {
    val first = barcodes?.firstOrNull { !it.rawValue.isNullOrEmpty() }
    if (first == null) {
      delegate.onQrCodeNotFound()
      return
    }
    delegate.onQrCodeFound(
      first.rawValue,
      first.boundingBox?.let { RectF(it) },
      previewView.height,
      previewView.width,
      0,
      false
    )
  }

  private fun zxingImplementation(
    data: ByteArray,
    width: Int,
    height: Int,
    rotation: Int,
    sourceTransform: OutputTransform,
    onFinish: Runnable
  ) {
    if (destroyed) {
      onFinish.run()
      return
    }
    try {
      backgroundExecutor.execute {
        try {
          if (!destroyed) {
            val match = zxingImplementationImpl(data, width, height, rotation)
            val bounds = match?.let { zxingBoundingBox(it) }
            mainExecutor.execute {
              if (!destroyed) {
                val targetTransform = previewView.outputTransform
                if (match != null && bounds != null && targetTransform != null) {
                  CoordinateTransform(sourceTransform, targetTransform).mapRect(bounds)
                  delegate.onQrCodeFound(
                    match.text,
                    bounds,
                    previewView.height,
                    previewView.width,
                    0,
                    true
                  )
                } else {
                  delegate.onQrCodeNotFound()
                }
              }
            }
          }
        } catch (error: Exception) {
          if (error !is NotFoundException) {
            Log.e(Log.TAG_CAMERA, error)
          }
          mainExecutor.execute {
            if (!destroyed) {
              delegate.onQrCodeNotFound()
            }
          }
        } finally {
          onFinish.run()
        }
      }
    } catch (error: RejectedExecutionException) {
      onFinish.run()
    }
  }

  private fun zxingBoundingBox(result: Result): RectF? {
    if (result.resultPoints.size < 3) {
      return null
    }
    val points = result.resultPoints
    val moduleSize = (points[0] as? FinderPattern)?.estimatedModuleSize?.times(2) ?: 0f
    return RectF(
      points.minOf { it.x } - moduleSize,
      points.minOf { it.y } - moduleSize,
      points.maxOf { it.x } + moduleSize,
      points.maxOf { it.y } + moduleSize
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

@file:OptIn(InternalRoborazziApi::class, ExperimentalRoborazziApi::class)

package com.github.takahirom.roborazzi

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.View
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/** A consumed AWT canvas owns copied pixels independently of native capture teardown. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(manifest = Config.NONE, sdk = [35])
class RecordImageOwnershipTest {
  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @Test
  @Config(qualifiers = "w100dp-h100dp")
  fun `deferred capture preserves the attached View after Activity teardown`() {
    val expected = temporaryFolder.newFile("sync.png")
    val actual = temporaryFolder.newFile("async.png")
    val options = RoborazziOptions(taskType = RoborazziTaskType.Record)
    RecordCaptureSession().use { session ->
      val pending = Robolectric.buildActivity(CaptureActivity::class.java).setup().visible().use { controller ->
        val view = controller.get().content
        ShadowLooper.idleMainLooper()
        view.captureRoboImage(expected, options)
        view.captureRoboImageAsync(session, actual, options).also { view.setBackgroundColor(Color.BLUE) }
      }
      pending.await()
    }
    val sync = requireNotNull(BitmapFactory.decodeFile(expected.path))
    val async = requireNotNull(BitmapFactory.decodeFile(actual.path))
    try {
      assertEquals(Color.RED, sync.getPixel(sync.width / 2, sync.height / 2))
      assertTrue(sync.sameAs(async))
    } finally {
      sync.recycle()
      async.recycle()
    }
  }

  @Test
  fun `transferred image survives source mutation recycle and canvas release`() {
    val bitmap = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.RED)
    val canvas = AwtRoboCanvas(bitmap, RoborazziOptions.PixelBitConfig.Argb8888.toBufferedImageType())
    val file = temporaryFolder.newFile("owned.png")
    val payload = canvas.prepareRecordOutput(file, 1.0, emptyMap())
    bitmap.eraseColor(Color.BLUE)
    bitmap.recycle()
    canvas.release()
    payload.use { it.write() }
    val decoded = requireNotNull(BitmapFactory.decodeFile(file.absolutePath))
    try {
      val pixels = IntArray(48)
      decoded.getPixels(pixels, 0, 8, 0, 0, 8, 6)
      assertArrayEquals(IntArray(48) { Color.RED }, pixels)
    } finally {
      decoded.recycle()
    }
  }

  @Test
  fun `resize completes before transfer and retains only resized storage`() {
    val bitmap = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.RED)
    val canvas = AwtRoboCanvas(bitmap, RoborazziOptions.PixelBitConfig.Argb8888.toBufferedImageType())
    val file = temporaryFolder.newFile("resized.png")
    val payload = canvas.prepareRecordOutput(file, 0.5, emptyMap())
    canvas.release()
    try {
      assertEquals(48L, payload.retainedBytes)
      payload.write()
      val decoded = requireNotNull(BitmapFactory.decodeFile(file.absolutePath))
      try {
        assertEquals(4, decoded.width)
        assertEquals(3, decoded.height)
        val pixels = IntArray(12)
        decoded.getPixels(pixels, 0, 4, 0, 0, 4, 3)
        assertArrayEquals(IntArray(12) { Color.RED }, pixels)
      } finally {
        decoded.recycle()
      }
      assertFalse(bitmap.isRecycled)
    } finally {
      payload.close()
      bitmap.recycle()
    }
  }

  /** Install the content before the window draws its first frame. */
  class CaptureActivity : Activity() {
    lateinit var content: View

    override fun onCreate(savedInstanceState: Bundle?) {
      setTheme(android.R.style.Theme_Light_NoTitleBar_Fullscreen)
      super.onCreate(savedInstanceState)
      content = View(this).apply { setBackgroundColor(Color.RED) }
      setContentView(content)
    }
  }
}

package com.github.takahirom.roborazzi

import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals

/** The async memory limit includes storage retained outside a visible crop. */
class AwtRecordPayloadTest {
  @Test
  fun croppedImagesCountTheirFullRetainedBackingStorage() {
    val image = BufferedImage(100, 200, BufferedImage.TYPE_INT_ARGB)
    val crop = image.getSubimage(2, 3, 4, 5)
    assertEquals(80_000L, crop.retainedRecordBytes())
    assertEquals(-1L, BufferedImage(4, 5, BufferedImage.TYPE_3BYTE_BGR).retainedRecordBytes())
  }
}

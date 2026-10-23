package com.github.takahirom.roborazzi

import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt

/**
 * Owns the transferred raster, encoder, and prepared stream. Its public operations keep desktop
 * image types inside the painter module, allowing the Android capture API to transfer ownership.
 */
@InternalRoborazziApi
class AwtRecordPayload internal constructor(
  private val image: BufferedImage,
  private val writer: PreparedPngWrite,
) : AutoCloseable {
  val retainedBytes: Long = image.retainedRecordBytes()
  val canRunOffThread: Boolean get() = writer.canRunOffThread

  private var closed = false

  fun write() {
    writer.write()
  }

  override fun close() {
    if (closed) {
      return
    }
    closed = true
    try {
      writer.close()
    } finally {
      image.flush()
    }
  }
}

/** Cropped rasters can retain a full-sized parent array, so count each complete backing bank. */
internal fun BufferedImage.retainedRecordBytes(): Long {
  val buffer = raster.dataBuffer as? DataBufferInt ?: return -1
  return buffer.bankData.sumOf { it.size.toLong() * Int.SIZE_BYTES }
}

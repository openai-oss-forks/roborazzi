package com.github.takahirom.roborazzi

import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.awt.image.DirectColorModel
import java.awt.image.RenderedImage
import java.awt.image.SinglePixelPackedSampleModel
import java.io.File
import javax.imageio.IIOException
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.ImageWriteParam
import javax.imageio.ImageWriter
import javax.imageio.metadata.IIOMetadata
import javax.imageio.metadata.IIOMetadataFormatImpl
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.spi.IIORegistry
import javax.imageio.spi.ImageWriterSpi
import javax.imageio.spi.ImageOutputStreamSpi
import javax.imageio.stream.FileImageOutputStream
import javax.imageio.stream.ImageOutputStream

@ExperimentalRoborazziApi
@Suppress("FunctionName")
actual fun LosslessWebPImageIoFormat(): ImageIoFormat {
  return JvmImageIoFormat(
    awtImageWriter = losslessWebPWriter()
  )
}

@ExperimentalRoborazziApi
actual fun ImageIoFormat(): ImageIoFormat {
  return JvmImageIoFormat()
}

@ExperimentalRoborazziApi
fun interface AwtImageWriter {
  fun write(
    destFile: File,
    contextData: Map<String, Any>,
    image: BufferedImage
  )
}

@ExperimentalRoborazziApi
fun interface AwtImageLoader {
  fun load(inputFile: File): BufferedImage
}

@ExperimentalRoborazziApi
data class JvmImageIoFormat(
  val awtImageWriter: AwtImageWriter = defaultAwtImageWriter,
  val awtImageLoader: AwtImageLoader = AwtImageLoader { ImageIO.read(it) }
) : ImageIoFormat


/** Identity allows explicit record sessions to keep custom writers on the caller thread. */
private val defaultAwtImageWriter = AwtImageWriter { file, contextData, bufferedImage ->
  val imageExtension = file.extension.ifBlank { "png" }
  if (imageExtension.equals("png", ignoreCase = true) && bufferedImage.type == BufferedImage.TYPE_INT_ARGB) {
    PreparedPngWrite.prepare(file, bufferedImage, contextData, allowWorker = false).use { it.write() }
    return@AwtImageWriter
  }
  if (contextData.isEmpty()) {
    ImageIO.write(bufferedImage, imageExtension, file)
    return@AwtImageWriter
  }
  val writer = getWriter(bufferedImage, imageExtension)
  try {
    val meta = writer.writeMetadata(contextData, bufferedImage)
    // ImageOutputStream doesn't truncate an existing file, so writing a
    // smaller image would leave trailing bytes of the previous image.
    // See: https://github.com/takahirom/roborazzi/issues/881
    file.delete()
    ImageIO.createImageOutputStream(file).use { stream ->
      writer.output = stream
      writer.write(IIOImage(bufferedImage, null, meta))
    }
  } finally {
    writer.dispose()
  }
}

/**
 * The JDK PNG writer can encode an opaque ARGB raster as RGB. The view shares the source pixels
 * and remains valid while the writer has exclusive read access.
 */
internal fun ImageWriter.opaqueRgbPngImage(image: BufferedImage): BufferedImage? {
  if (javaClass.name != "com.sun.imageio.plugins.png.PNGImageWriter" ||
    javaClass.classLoader != null ||
    image.type != BufferedImage.TYPE_INT_ARGB ||
    !image.hasOnlyOpaqueArgbPixels()
  ) {
    return null
  }
  val rgbRaster = image.raster.createWritableChild(
    0, 0, image.width, image.height, 0, 0, intArrayOf(0, 1, 2)
  )
  // Select the three color bands once. Source-band parameters create another raster for every row.
  val rgbColorModel = DirectColorModel(24, 0x00FF0000, 0x0000FF00, 0x000000FF)
  return BufferedImage(rgbColorModel, rgbRaster, false, null)
}

/** Checks only the visible raster, including the offset and row stride of a subimage. */
private fun BufferedImage.hasOnlyOpaqueArgbPixels(): Boolean {
  val data = raster.dataBuffer as? DataBufferInt ?: return false
  val samples = raster.sampleModel as? SinglePixelPackedSampleModel ?: return false
  if (data.numBanks != 1) {
    return false
  }
  val pixels = data.data
  val firstPixel = data.offset + samples.getOffset(
    raster.minX - raster.sampleModelTranslateX,
    raster.minY - raster.sampleModelTranslateY
  )
  for (y in 0 until height) {
    val rowStart = firstPixel + y * samples.scanlineStride
    for (x in 0 until width) {
      if (pixels[rowStart + x] ushr 24 != 255) {
        return false
      }
    }
  }
  return true
}


@ExperimentalRoborazziApi
fun getWriter(renderedImage: RenderedImage, extension: String): ImageWriter {
  val typeSpecifier = ImageTypeSpecifier.createFromRenderedImage(renderedImage)
  val iterator: Iterator<*> = ImageIO.getImageWriters(typeSpecifier, extension)
  return if (iterator.hasNext()) {
    iterator.next() as ImageWriter
  } else {
    throw IllegalArgumentException("No ImageWriter found for $extension")
  }
}

@ExperimentalRoborazziApi
fun ImageWriter.writeMetadata(
  contextData: Map<String, Any>,
  bufferedImage: BufferedImage,
): IIOMetadata? {
  val meta = getDefaultImageMetadata(ImageTypeSpecifier(bufferedImage), null) ?: run {
    // If we use WebP, it seems that we can't get the metadata
    return null
  }

  val root = IIOMetadataNode(IIOMetadataFormatImpl.standardMetadataFormatName)
  contextData.forEach { (key, value) ->
    val textEntry = IIOMetadataNode("TextEntry")
    textEntry.setAttribute("keyword", key)
    textEntry.setAttribute("value", value.toString())
    val text = IIOMetadataNode("Text")
    text.appendChild(textEntry)
    root.appendChild(text)
  }

  meta.mergeTree(IIOMetadataFormatImpl.standardMetadataFormatName, root)
  return meta
}

@Suppress("UNCHECKED_CAST")
private fun getImageWriterFromSystemClassLoader(mimeType: String): ImageWriter {
  val systemCL = ClassLoader.getSystemClassLoader()
  val imageIOClass = systemCL.loadClass("javax.imageio.ImageIO")
  val method = imageIOClass.getMethod("getImageWritersByMIMEType", String::class.java)
  val iterator = method.invoke(null, mimeType) as Iterator<ImageWriter>
  return if (iterator.hasNext()) {
    iterator.next()
  } else {
    throw IllegalArgumentException("No ImageWriter found for MIME type: $mimeType")
  }
}

/**
 * Add testImplementation("io.github.darkxanter:webp-imageio:0.3.3") to use this
 */
private fun losslessWebPWriter(): AwtImageWriter =
  AwtImageWriter { file, context, bufferedImage ->
    // Use system classloader to avoid ClassCastException when running Robolectric tests
    // with multiple SDK configurations. Each SDK sandbox has its own classloader,
    // but by using system classloader, the WebPWriter is shared across all sandboxes.
    // See: https://github.com/takahirom/roborazzi/issues/771
    val writer: ImageWriter = getImageWriterFromSystemClassLoader("image/webp")
    try {
      val writeParam = writer.defaultWriteParam
      writeParam.compressionMode = ImageWriteParam.MODE_EXPLICIT
      val compressionTypes = writeParam.compressionTypes
      if (compressionTypes.isNullOrEmpty()) {
        throw IllegalStateException("No compression types available for WebP ImageWriter")
      }
      val losslessType = compressionTypes.firstOrNull {
        it.contains("Lossless", ignoreCase = true)
      } ?: compressionTypes[0]
      writeParam.compressionType = losslessType


      // FileImageOutputStream doesn't truncate an existing file, so writing a
      // smaller image would leave trailing bytes of the previous image and
      // break webp-imageio's file size check on load.
      // See: https://github.com/takahirom/roborazzi/issues/881
      file.delete()
      FileImageOutputStream(file).use { stream ->
        writer.setOutput(stream)
        writer.write(null, IIOImage(bufferedImage, null, null), writeParam)
      }
    } catch (e: NoClassDefFoundError) {
      throw IllegalStateException("Add testImplementation(\"io.github.darkxanter:webp-imageio:0.3.0\") to use this")
    } finally {
      writer.dispose()
    }
  }


/** Identifies the built-in format without invoking a caller-supplied writer. */
@InternalRoborazziApi
fun ImageIoFormat.supportsPreparedPngWrite(): Boolean =
  this is JvmImageIoFormat && awtImageWriter === defaultAwtImageWriter

/** Holds the ImageIO writer and output stream selected on the capture thread. */
@InternalRoborazziApi
class PreparedPngWrite private constructor(
  private val writer: ImageWriter?,
  private val hasMetadata: Boolean,
) : AutoCloseable {
  private lateinit var image: BufferedImage
  private var metadata: IIOMetadata? = null
  private var stream: ImageOutputStream? = null
  private var closed = false
  var canRunOffThread: Boolean = false
    private set

  /** Writes once, using the same ImageWriter overload as ImageIO for an empty context. */
  fun write() {
    check(!closed) { "The PNG write is closed" }
    use {
      if (hasMetadata) {
        writer?.write(IIOImage(image, null, metadata))
      } else {
        writer?.write(image)
      }
    }
  }

  override fun close() {
    if (closed) {
      return
    }
    closed = true
    if (hasMetadata) {
      try {
        stream?.close()
      } finally {
        writer?.dispose()
      }
    } else {
      stream.use {
        try {
          writer?.dispose()
        } finally {
          it?.flush()
        }
      }
    }
  }

  companion object {
    fun prepare(
      file: File,
      image: BufferedImage,
      contextData: Map<String, Any>,
      allowWorker: Boolean = true,
    ): PreparedPngWrite {
      require(image.type == BufferedImage.TYPE_INT_ARGB)
      val extension = file.extension.ifBlank { "png" }
      require(extension.equals("png", ignoreCase = true)) { "Expected a PNG file: $file" }
      val stockProviders = allowWorker && hasOnlyJdkPngProviders(file)
      val writers = ImageIO.getImageWriters(ImageTypeSpecifier.createFromRenderedImage(image), extension)
      val writer = if (writers.hasNext()) { writers.next() } else { null }
      val prepared = PreparedPngWrite(writer, contextData.isNotEmpty())
      if (writer == null) {
        // ImageIO.write leaves the file untouched when no writer accepts the image.
        require(contextData.isEmpty()) { "No ImageWriter found for $extension" }
        return prepared
      }
      try {
        if (prepared.hasMetadata) {
          prepared.metadata = writer.writeMetadata(contextData, image)
        }
        // ImageOutputStream does not truncate an existing file. See issue #881.
        file.delete()
        val stream = ImageIO.createImageOutputStream(file) ?: throw IIOException("Can't create an ImageOutputStream!")
        prepared.stream = stream
        writer.output = stream
        prepared.image = writer.opaqueRgbPngImage(image) ?: image
        prepared.canRunOffThread = stockProviders &&
          writer.isJdkClass("com.sun.imageio.plugins.png.PNGImageWriter") &&
          writer.originatingProvider.isJdkClass("com.sun.imageio.plugins.png.PNGImageWriterSpi") &&
          stream.javaClass == FileImageOutputStream::class.java
        return prepared
      } catch (failure: Throwable) {
        prepared.use { throw failure }
      }
    }
  }
}

/** A custom provider can return a JDK writer or stream with caller-thread listeners attached. */
private fun hasOnlyJdkPngProviders(file: File): Boolean {
  val registry = IIORegistry.getDefaultInstance()
  return registry.getServiceProviders(ImageWriterSpi::class.java, true).asSequence()
    .filter { provider -> provider.formatNames.any { it.equals("png", ignoreCase = true) } }
    .all { it.isJdkClass("com.sun.imageio.plugins.png.PNGImageWriterSpi") } &&
    registry.getServiceProviders(ImageOutputStreamSpi::class.java, true).asSequence()
      .filter { it.outputClass.isInstance(file) }
      .all { it.isJdkClass("com.sun.imageio.spi.FileImageOutputStreamSpi") }
}

private fun Any?.isJdkClass(name: String): Boolean =
  this != null && javaClass.classLoader == null && javaClass.name == name

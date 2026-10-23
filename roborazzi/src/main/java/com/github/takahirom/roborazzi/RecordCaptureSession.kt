@file:OptIn(InternalRoborazziApi::class, ExperimentalRoborazziApi::class)

package com.github.takahirom.roborazzi

import android.view.View
import androidx.test.espresso.Espresso.onView
import org.hamcrest.core.IsEqual
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Owns at most one record payload and one encoder thread. Await each returned [PendingRecord]
 * before capturing again, and close the session before the test or Robolectric sandbox exits.
 * Captures, completion reports, and close belong to the thread that creates this session.
 * The byte limit counts retained raster storage; larger captures finish synchronously.
 */
@ExperimentalRoborazziApi
class RecordCaptureSession internal constructor(
  private val maxRetainedBytes: Long,
  private val executorFactory: () -> ExecutorService,
) : AutoCloseable {
  constructor(maxRetainedBytes: Long = 8L * 1024 * 1024) : this(
    maxRetainedBytes,
    { Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "roborazzi-record") } },
  )

  init {
    require(maxRetainedBytes >= 0) { "maxRetainedBytes must be nonnegative: $maxRetainedBytes" }
  }

  private val owner = Thread.currentThread()
  private var executor: ExecutorService? = null
  private var pending: PendingRecord? = null
  private var closed = false

  internal fun checkReady() {
    checkOwner()
    check(!closed) { "The record session is closed" }
    check(!owner.isInterrupted) { "The record session owner is interrupted" }
    check(pending == null) { "Await the previous record before capturing another image" }
  }

  internal fun canOffload(retainedBytes: Long): Boolean =
    retainedBytes >= 0 && retainedBytes <= maxRetainedBytes

  private fun checkOwner() {
    check(Thread.currentThread() === owner) { "Use the record session on its creating thread" }
  }

  /** The caller transfers exclusive ownership; every terminal path releases the payload. */
  internal fun submit(
    retainedBytes: Long,
    canRunOffThread: Boolean,
    write: () -> Unit,
    release: () -> Unit,
    report: (Long) -> Unit,
  ): PendingRecord {
    try {
      checkReady()
    } catch (failure: Throwable) {
      AutoCloseable(release).use { throw failure }
    }
    val operation = Callable {
      AutoCloseable { release() }.use { write() }
      // Preserve write-completion time even when the caller defers awaiting this record.
      System.nanoTime()
    }
    if (!canRunOffThread || !canOffload(retainedBytes)) {
      report(operation.call())
      return PendingRecord.completed(owner)
    }
    val future = try {
      val writerExecutor = executor ?: executorFactory().also { executor = it }
      writerExecutor.submit(operation)
    } catch (failure: Throwable) {
      AutoCloseable(release).use { throw failure }
    }
    return PendingRecord(owner, future, report) { pending = null }.also { pending = it }
  }

  /** Drains the outstanding write even on failure and joins the worker before returning. */
  override fun close() {
    checkOwner()
    if (closed) {
      return
    }
    closed = true
    try {
      pending?.await()
    } finally {
      executor?.let { writerExecutor ->
        writerExecutor.shutdown()
        var interrupted = false
        while (!writerExecutor.isTerminated) {
          try {
            writerExecutor.awaitTermination(1, TimeUnit.DAYS)
          } catch (_: InterruptedException) {
            interrupted = true
          }
        }
        if (interrupted) {
          Thread.currentThread().interrupt()
        }
      }
    }
  }
}

/**
 * Explicit PNG/report completion. Await is idempotent, reports once on the creating thread, and
 * rethrows the original write/report failure. Interruption drains ownership before it is restored.
 */
@ExperimentalRoborazziApi
class PendingRecord internal constructor(
  private val owner: Thread,
  private val future: Future<Long>?,
  private var report: ((Long) -> Unit)?,
  private var onComplete: (() -> Unit)?,
) {
  private var result: Result<Unit>? = if (future == null) { Result.success(Unit) } else { null }

  fun await() {
    check(Thread.currentThread() === owner) { "Await the record on its creating thread" }
    result?.let {
      it.getOrThrow()
      return
    }
    // A completed FutureTask can return without checking the owner's interrupt flag. Observe it
    // explicitly, then clear it while draining the owned payload and restore it before returning.
    var interruption: InterruptedException? = if (Thread.interrupted()) {
      InterruptedException("Interrupted before awaiting record completion")
    } else {
      null
    }
    try {
      result = runCatching {
        var writtenAtNs: Long? = null
        while (writtenAtNs == null) {
          try {
            writtenAtNs = requireNotNull(future).get()
          } catch (interrupted: InterruptedException) {
            if (interruption == null) {
              interruption = interrupted
            }
          } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
          }
        }
        report?.invoke(requireNotNull(writtenAtNs))
        interruption?.let { throw it }
        Unit
      }.onFailure { failure ->
        interruption?.let { interrupted ->
          if (failure !== interrupted) {
            failure.addSuppressed(interrupted)
          }
        }
      }
    } finally {
      report = null
      onComplete?.invoke()
      onComplete = null
      if (interruption != null) {
        Thread.currentThread().interrupt()
      }
    }
    requireNotNull(result).getOrThrow()
  }

  internal companion object {
    fun completed(owner: Thread = Thread.currentThread()): PendingRecord =
      PendingRecord(owner, null, null, null)
  }
}

/**
 * Records an attached View with explicit completion. Capture, cropping, resizing, metadata, and
 * provider selection finish before return. Only a stock PNG encoder receives the owned AWT image.
 * Other task types, detached Views, custom writers/reporters, UI-tree output, and mutable context
 * values retain synchronous capture. Bitmap and whole-screen callers use their existing APIs.
 */
@ExperimentalRoborazziApi
fun View.captureRoboImageAsync(
  session: RecordCaptureSession,
  file: File,
  roborazziOptions: RoborazziOptions = provideRoborazziContext().options,
): PendingRecord {
  session.checkReady()
  if (!isAttachedToWindow || !roborazziOptions.supportsAsyncRecord(file)) {
    captureRoboImage(file, roborazziOptions)
    return PendingRecord.completed()
  }
  var deferred: Pair<AwtRecordPayload, (Long) -> Unit>? = null
  try {
    onView(IsEqual(this)).perform(ImageCaptureViewAction(roborazziOptions) { canvas, _ ->
      try {
        val context = prepareRecordContextData(file, roborazziOptions)
        val report = prepareRecordCompletion(file, context)
        val record = canvas.prepareRecordOutput(file, roborazziOptions.recordOptions.resizeScale, context)
        if (record.canRunOffThread && session.canOffload(record.retainedBytes)) {
          deferred = record to report
        } else {
          // Custom providers and oversized rasters keep capture-thread invocation and disposal.
          record.use { it.write() }
          report(System.nanoTime())
        }
      } finally {
        canvas.release()
      }
    })
    val (record, report) = deferred ?: return PendingRecord.completed()
    // Espresso and Android references remain in this call. The worker receives only an owned
    // AWT raster and selected writer; reporting is a separate callback invoked by await.
    deferred = null
    return session.submit(
      retainedBytes = record.retainedBytes,
      canRunOffThread = true,
      write = record::write,
      release = record::close,
      report = report,
    )
  } finally {
    deferred?.first?.close()
  }
}

private fun RoborazziOptions.supportsAsyncRecord(file: File): Boolean =
  taskType == RoborazziTaskType.Record &&
    captureType is RoborazziOptions.CaptureType.Screenshot &&
    uiTreeDumpOptions == null &&
    recordOptions.pixelBitConfig == RoborazziOptions.PixelBitConfig.Argb8888 &&
    recordOptions.imageIoFormat.supportsPreparedPngWrite() &&
    file.extension.ifBlank { "png" }.equals("png", ignoreCase = true) &&
    reportOptions.captureResultReporter is RoborazziOptions.CaptureResultReporter.DefaultCaptureResultReporter &&
    contextData.values.all { it.isImmutableRecordValue() }

/** Only scalar values can be safely retained without changing the report's original value types. */
private fun Any.isImmutableRecordValue(): Boolean =
  this is String || this is Boolean || this is Char || this is Byte || this is Short ||
    this is Int || this is Long || this is Float || this is Double

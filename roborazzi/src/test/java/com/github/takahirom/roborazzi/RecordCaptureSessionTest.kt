@file:OptIn(ExperimentalRoborazziApi::class, InternalRoborazziApi::class)

package com.github.takahirom.roborazzi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** Tests the completion boundary independently of Android capture and PNG timing. */
class RecordCaptureSessionTest {
  @Test
  fun `await joins encoding and reports once on the owner thread`() {
    val owner = Thread.currentThread()
    val events = Collections.synchronizedList(mutableListOf<String>())
    val written = CountDownLatch(1)
    RecordCaptureSession().use { session ->
      val pending = session.submit(4, true, {
        assertFalse(Thread.currentThread() === owner)
        events.add("write")
      }, {
        events.add("release")
        written.countDown()
      }, {
        assertSame(owner, Thread.currentThread())
        events.add("report")
      })
      assertTrue(written.await(5, TimeUnit.SECONDS))
      assertEquals(listOf("write", "release"), events.toList())
      pending.await()
      pending.await()
      assertEquals(listOf("write", "release", "report"), events.toList())
    }
  }

  @Test
  fun `a completed but unawaited write still prevents a second capture`() {
    RecordCaptureSession().use { session ->
      val released = CountDownLatch(1)
      val first = session.submit(4, true, {}, { released.countDown() }, {})
      assertTrue(released.await(5, TimeUnit.SECONDS))
      expectFailure<IllegalStateException> { session.checkReady() }
      first.await()
      session.checkReady()
    }
  }

  @Test
  fun `write failure releases ownership and keeps original failure identity`() {
    val failure = IOException("encoder failed")
    var releases = 0
    var reports = 0
    RecordCaptureSession().use { session ->
      val pending = session.submit(4, true, { throw failure }, { releases++ }, { reports++ })
      assertSame(failure, expectFailure<IOException> { pending.await() })
      assertSame(failure, expectFailure<IOException> { pending.await() })
      assertEquals(1, releases)
      assertEquals(0, reports)
      session.checkReady()
      session.submit(4, false, {}, {}, { reports++ }).await()
      assertEquals(1, reports)
    }
  }

  @Test
  fun `cleanup failure is suppressed on the original encoder failure`() {
    val writeFailure = IOException("encoder failed")
    val releaseFailure = IOException("cleanup failed")
    RecordCaptureSession().use { session ->
      val pending = session.submit(4, true, { throw writeFailure }, { throw releaseFailure }, {})
      val actual = expectFailure<IOException> { pending.await() }
      assertSame(writeFailure, actual)
      assertEquals(listOf(releaseFailure), actual.suppressed.toList())
      session.checkReady()
    }
  }

  @Test
  fun `report failure is consumed once and close does not repeat it`() {
    val failure = IOException("report failed")
    var reports = 0
    val session = RecordCaptureSession()
    val pending = session.submit(4, true, {}, {}, { reports++; throw failure })
    assertSame(failure, expectFailure<IOException> { pending.await() })
    assertSame(failure, expectFailure<IOException> { pending.await() })
    session.close()
    assertEquals(1, reports)
  }

  @Test
  fun `close drains an unawaited failed write and terminates its worker`() {
    val executor = Executors.newSingleThreadExecutor()
    val session = RecordCaptureSession(8, { executor })
    val failure = IOException("late encoder failure")
    var released = false
    session.submit(4, true, { throw failure }, { released = true }, { fail("Unexpected report") })
    assertSame(failure, expectFailure<IOException> { session.close() })
    assertTrue(released)
    assertTrue(executor.isTerminated)
    session.close()
    expectFailure<IllegalStateException> { session.checkReady() }
  }

  @Test
  fun `oversized images and custom providers execute synchronously`() {
    val owner = Thread.currentThread()
    val session = RecordCaptureSession(8, { error("No executor should be allocated") })
    session.use {
      listOf(12L to true, 4L to false, -1L to true).forEach { (size, stock) ->
        val events = mutableListOf<String>()
        val pending = session.submit(size, stock, {
          assertSame(owner, Thread.currentThread())
          events.add("write")
        }, { events.add("release") }, { events.add("report") })
        assertEquals(listOf("write", "release", "report"), events)
        pending.await()
        assertEquals(3, events.size)
      }
    }
  }

  @Test
  fun `executor rejection releases the transferred payload`() {
    val executor = Executors.newSingleThreadExecutor().apply { shutdown() }
    val session = RecordCaptureSession(8, { executor })
    var released = 0
    session.use {
      expectFailure<RejectedExecutionException> {
        session.submit(4, true, { fail("Unexpected write") }, { released++ }, { fail("Unexpected report") })
      }
      assertEquals(1, released)
      session.checkReady()
    }
  }

  @Test
  fun `interrupted await drains then restores interruption`() {
    var calls = 0
    var reports = 0
    var completed = false
    val future = object : Future<Long> {
      override fun cancel(mayInterruptIfRunning: Boolean) = false
      override fun isCancelled() = false
      override fun isDone() = calls > 1
      override fun get(): Long {
        calls++
        if (calls == 1) {
          throw InterruptedException("interrupted while waiting")
        }
        return 1234L
      }
      override fun get(timeout: Long, unit: TimeUnit) = get()
    }
    val pending = PendingRecord(Thread.currentThread(), future, { reports++ }, { completed = true })
    try {
      expectFailure<InterruptedException> { pending.await() }
      assertEquals(2, calls)
      assertEquals(1, reports)
      assertTrue(completed)
      assertTrue(Thread.currentThread().isInterrupted)
    } finally {
      Thread.interrupted()
    }
  }

  @Test
  fun `already interrupted owner fails even when encoding completed before await`() {
    val completedWrite = FutureTask { 1234L }.apply { run() }
    var reports = 0
    var completions = 0
    val pending = PendingRecord(Thread.currentThread(), completedWrite, { reports++ }, { completions++ })
    assertTrue(completedWrite.isDone)
    try {
      Thread.currentThread().interrupt()
      val failure = expectFailure<InterruptedException> { pending.await() }
      assertSame(failure, expectFailure<InterruptedException> { pending.await() })
      assertEquals(1, reports)
      assertEquals(1, completions)
      assertTrue(Thread.currentThread().isInterrupted)
    } finally {
      Thread.interrupted()
    }
  }

  @Test
  fun `interruption remains suppressed when reporting also fails`() {
    val completedWrite = FutureTask { 1234L }.apply { run() }
    val reportFailure = IOException("report failed")
    var completed = false
    val pending = PendingRecord(Thread.currentThread(), completedWrite, { throw reportFailure }, { completed = true })
    try {
      Thread.currentThread().interrupt()
      assertSame(reportFailure, expectFailure<IOException> { pending.await() })
      assertEquals(1, reportFailure.suppressed.size)
      assertTrue(reportFailure.suppressed.single() is InterruptedException)
      assertTrue(completed)
      assertTrue(Thread.currentThread().isInterrupted)
    } finally {
      Thread.interrupted()
    }
  }

  @Test
  fun `deferred await reports the original write completion timestamp`() {
    val writtenAtNs = 1234L
    val completedWrite = FutureTask { writtenAtNs }.apply { run() }
    var reportedAtNs: Long? = null
    val pending = PendingRecord(Thread.currentThread(), completedWrite, { reportedAtNs = it }, {})
    assertTrue(completedWrite.isDone)
    assertEquals(null, reportedAtNs)
    pending.await()
    assertEquals(writtenAtNs, reportedAtNs)
  }

  @Test
  fun `a newly pending capture rejects and releases a later transferred payload`() {
    RecordCaptureSession().use { session ->
      val previous = session.submit(4, true, {}, {}, {})
      var rejectedReleases = 0
      expectFailure<IllegalStateException> {
        session.submit(4, true, { fail("Unexpected write") }, { rejectedReleases++ }, {})
      }
      assertEquals(1, rejectedReleases)
      previous.await()
      session.checkReady()
    }
  }

  @Test
  fun `rejection cleanup cannot suppress its own exception`() {
    val failure = RejectedExecutionException("executor creation failed")
    val session = RecordCaptureSession(8, { throw failure })
    session.use {
      assertSame(failure, expectFailure<RejectedExecutionException> {
        session.submit(4, true, {}, { throw failure }, {})
      })
      assertTrue(failure.suppressed.isEmpty())
      session.checkReady()
    }
  }

  private inline fun <reified T : Throwable> expectFailure(block: () -> Unit): T {
    try {
      block()
    } catch (failure: Throwable) {
      if (failure is T) {
        return failure
      }
      throw failure
    }
    throw AssertionError("Expected ${T::class.java.name}")
  }
}

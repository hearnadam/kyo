package kyo.concurrent

import kyo.core._
import kyo.ios._

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec

object refs {

  opaque type IntRef = AtomicInteger
  object IntRef {
    inline def apply(v: Int): IntRef > IOs = IOs(AtomicInteger(v))
  }
  extension (ref: IntRef) {
    inline def get: Int > IOs =
      IOs(ref.get())
    inline def set(v: Int): Unit > IOs =
      IOs(ref.set(v))
    inline def lazySet(v: Int): Unit > IOs =
      IOs(ref.lazySet(v))
    inline def getAndSet(v: Int): Int > IOs =
      IOs(ref.getAndSet(v))
    inline def cas(curr: Int, next: Int): Boolean > IOs =
      IOs(ref.compareAndSet(curr, next))
    inline def incrementAndGet: Int > IOs =
      IOs(ref.incrementAndGet())
    inline def decrementAndGet: Int > IOs =
      IOs(ref.decrementAndGet())
    inline def getAndIncrement: Int > IOs =
      IOs(ref.getAndIncrement())
    inline def getAndDecrement: Int > IOs =
      IOs(ref.getAndDecrement())
    inline def getAndAdd(v: Int): Int > IOs =
      IOs(ref.getAndAdd(v))
    inline def addAndGet(v: Int): Int > IOs =
      IOs(ref.addAndGet(v))
  }

  opaque type LongRef = AtomicLong
  object LongRef {
    inline def apply(v: Long): LongRef > IOs = IOs(AtomicLong(v))
  }
  extension (ref: LongRef) {
    inline def get: Long > IOs =
      IOs(ref.get())
    inline def set(v: Long): Unit > IOs =
      IOs(ref.set(v))
    inline def lazySet(v: Long): Unit > IOs =
      IOs(ref.lazySet(v))
    inline def getAndSet(v: Long): Long > IOs =
      IOs(ref.getAndSet(v))
    inline def cas(curr: Long, next: Long): Boolean > IOs =
      IOs(ref.compareAndSet(curr, next))
    inline def incrementAndGet: Long > IOs =
      IOs(ref.incrementAndGet())
    inline def decrementAndGet: Long > IOs =
      IOs(ref.decrementAndGet())
    inline def getAndIncrement: Long > IOs =
      IOs(ref.getAndIncrement())
    inline def getAndDecrement: Long > IOs =
      IOs(ref.getAndDecrement())
    inline def getAndAdd(v: Long): Long > IOs =
      IOs(ref.getAndAdd(v))
    inline def addAndGet(v: Long): Long > IOs =
      IOs(ref.addAndGet(v))
  }

  opaque type BooleanRef = AtomicBoolean
  object BooleanRef {
    inline def apply(v: Boolean): BooleanRef > IOs = IOs(AtomicBoolean(v))
  }
  extension (ref: BooleanRef) {
    inline def get: Boolean > IOs =
      IOs(ref.get())
    inline def set(v: Boolean): Unit > IOs =
      IOs(ref.set(v))
    inline def lazySet(v: Boolean): Unit > IOs =
      IOs(ref.lazySet(v))
    inline def getAndSet(v: Boolean): Boolean > IOs =
      IOs(ref.getAndSet(v))
    inline def cas(curr: Boolean, next: Boolean): Boolean > IOs =
      IOs(ref.compareAndSet(curr, next))
  }

  opaque type Ref[T] = AtomicReference[T]
  object Ref {
    inline def apply[T](v: T): Ref[T] > IOs = IOs(AtomicReference(v))
  }
  extension [T](ref: Ref[T]) {
    inline def get: T > IOs =
      IOs(ref.get())
    inline def set(v: T): Unit > IOs =
      IOs(ref.set(v))
    inline def lazySet(v: T): Unit > IOs =
      IOs(ref.lazySet(v))
    inline def getAndSet(v: T): T > IOs =
      IOs(ref.getAndSet(v))
    inline def cas(curr: T, next: T): Boolean > IOs =
      IOs(ref.compareAndSet(curr, next))
  }
}
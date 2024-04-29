package kyo

import Cache.*
import com.github.benmanes.caffeine
import com.github.benmanes.caffeine.cache.Caffeine
import java.util.concurrent.TimeUnit

import scala.runtime.AbstractFunction1
import scala.util.Failure
import scala.util.Success

class Cache(private[kyo] val store: Store):
    def memo[T, U, S](
        f: T => U < S
    ): T => U < (Fibers & S) =
        new AbstractFunction1[T, U < (Fibers & S)]:
            def apply(v: T) =
                Fibers.initPromise[U].map { p =>
                    val key = (this, v)
                    IOs[U, Fibers & S] {
                        val p2 = store.get(key, _ => p.asInstanceOf[Promise[Any]])
                        if p.equals(p2) then
                            IOs.ensure {
                                p.interrupt.map {
                                    case true =>
                                        IOs(store.invalidate(key))
                                    case false =>
                                        ()
                                }
                            } {
                                IOs.attempt[U, S](f(v)).map {
                                    case Success(v) =>
                                        p.complete(v)
                                            .unit.andThen(v)
                                    case Failure(ex) =>
                                        IOs(store.invalidate(key))
                                            .andThen(p.complete(IOs.fail(ex)))
                                            .unit.andThen(IOs.fail(ex))
                                }
                            }
                        else
                            p2.asInstanceOf[Promise[U]].get
                        end if
                    }
                }

    def memo2[T1: Flat, T2: Flat, S, U: Flat](
        f: (T1, T2) => U < S
    ): (T1, T2) => U < (Fibers & S) =
        val m = memo[(T1, T2), U, S](f.tupled)
        (t1, t2) => m((t1, t2))
    end memo2

    def memo3[T1: Flat, T2: Flat, T3: Flat, S, U: Flat](
        f: (T1, T2, T3) => U < S
    ): (T1, T2, T3) => U < (Fibers & S) =
        val m = memo[(T1, T2, T3), U, S](f.tupled)
        (t1, t2, t3) => m((t1, t2, t3))
    end memo3

    def memo4[T1: Flat, T2: Flat, T3: Flat, T4: Flat, S, U: Flat](
        f: (T1, T2, T3, T4) => U < S
    ): (T1, T2, T3, T4) => U < (Fibers & S) =
        val m = memo[(T1, T2, T3, T4), U, S](f.tupled)
        (t1, t2, t3, t4) => m((t1, t2, t3, t4))
    end memo4
end Cache

object Cache:
    type Store = caffeine.cache.Cache[Any, Promise[Any]]

object Caches:

    case class Builder(private[kyo] val b: Caffeine[Any, Any]) extends AnyVal:
        def maxSize(v: Int): Builder =
            copy(b.maximumSize(v))
        def weakKeys(): Builder =
            copy(b.weakKeys())
        def weakValues(): Builder =
            copy(b.weakValues())
        def expireAfterAccess(d: Duration): Builder =
            copy(b.expireAfterAccess(d.toMillis, TimeUnit.MILLISECONDS))
        def expireAfterWrite(d: Duration): Builder =
            copy(b.expireAfterWrite(d.toMillis, TimeUnit.MILLISECONDS))
        def initialCapacity(v: Int) =
            copy(b.initialCapacity(v))
        def refreshAfterWrite(d: Duration) =
            copy(b.refreshAfterWrite(d.toMillis, TimeUnit.MILLISECONDS))
    end Builder

    def init(f: Builder => Builder): Cache < IOs =
        IOs {
            new Cache(
                f(new Builder(Caffeine.newBuilder())).b
                    .build[Any, Promise[Any]]()
            )
        }
end Caches

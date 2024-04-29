package kyoTest

import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger as JAtomicInteger
import kyo.*
import org.scalatest.compatible.Assertion
import scala.util.Failure
import scala.util.Try

class fibersTest extends KyoTest:

    "promise" - {
        "complete" in run {
            for
                p <- Fibers.initPromise[Int]
                a <- p.complete(1)
                b <- p.isDone
                c <- p.get
            yield assert(a && b && c == 1)
        }
        "complete twice" in run {
            for
                p <- Fibers.initPromise[Int]
                a <- p.complete(1)
                b <- p.complete(2)
                c <- p.isDone
                d <- p.get
            yield assert(a && !b && c && d == 1)
        }
        "failure" in run {
            val ex = new Exception
            for
                p <- Fibers.initPromise[Int]
                a <- p.complete(IOs.fail(ex))
                b <- p.isDone
                c <- p.getTry
            yield assert(a && b && c == Failure[Int](ex))
            end for
        }

        "become" - {
            "succeed" in run {
                for
                    p1 <- Fibers.initPromise[Int]
                    p2 <- Fibers.initPromise[Int]
                    a  <- p2.complete(42)
                    b  <- p1.become(p2)
                    c  <- p1.isDone
                    d  <- p1.get
                yield assert(a && b && c && d == 42)
            }

            "fail" in run {
                val ex = new Exception("fail")
                for
                    p1 <- Fibers.initPromise[Int]
                    p2 <- Fibers.initPromise[Int]
                    a  <- p2.complete(IOs.fail(ex))
                    b  <- p1.become(p2)
                    c  <- p1.isDone
                    d  <- p1.getTry
                yield assert(a && b && c && d == Failure(ex))
                end for
            }

            "already completed" in run {
                for
                    p1 <- Fibers.initPromise[Int]
                    p2 <- Fibers.initPromise[Int]
                    a  <- p1.complete(42)
                    b  <- p2.complete(99)
                    c  <- p1.become(p2)
                    d  <- p1.get
                yield assert(a && b && !c && d == 42)
            }

            "done fiber" in run {
                for
                    p <- Fibers.initPromise[Int]
                    a <- p.become(Fiber.value(42))
                    b <- p.isDone
                    c <- p.get
                yield assert(a && b && c == 42)
            }
        }
    }

    "init" - {
        "value" in run {
            for
                v <- Fibers.init(1).map(_.get)
            yield assert(v == 1)
        }
        "executes in a different thread" in runJVM {
            val t1 = Thread.currentThread()
            for
                t2 <- Fibers.init(Thread.currentThread()).map(_.get)
            yield assert(t1 ne t2)
        }
        // "uses a virtual thread" in runJVM {
        //     Fibers.init(Thread.currentThread().isVirtual())
        //         .map(_.get).map(assert(_))
        // }
        "multiple" in run {
            for
                v0               <- Fibers.init(0).map(_.get)
                (v1, v2)         <- Fibers.parallel(1, 2)
                (v3, v4, v5)     <- Fibers.parallel(3, 4, 5)
                (v6, v7, v8, v9) <- Fibers.parallel(6, 7, 8, 9)
            yield assert(v0 + v1 + v2 + v3 + v4 + v5 + v6 + v7 + v8 + v9 == 45)
        }
        "nested" in runJVM {
            val t1 = Thread.currentThread()
            for
                t2 <- Fibers.init(IOs(Fibers.init(Thread.currentThread()).map(_.get))).map(_.get)
            yield assert(t1 ne t2)
        }

        "with IOs-based effects" - {
            "Resources" in run {
                var closes                    = 0
                val a: Int < Resources        = Resources.ensure(closes += 1).andThen(42)
                val b: Fiber[Int] < Resources = Fibers.init(a)
                b.map(_.get.map(v => assert(v == 42)))
            }
        }
        "non IOs-based effect" in run {
            assertDoesNotCompile("Fibers.init(Vars[Int].get)")
        }
    }

    "sleep" in run {
        for
            start <- IOs(System.currentTimeMillis())
            _     <- Fibers.sleep(10.millis)
            end   <- IOs(System.currentTimeMillis())
        yield assert(end - start >= 10)
    }

    "runAndBlock" - {

        "timeout" in runJVM {
            IOs.attempt(Fibers.runAndBlock(Duration.Infinity)(
                Fibers.timeout(10.millis)(Fibers.sleep(1.days).andThen(1))
            )).map {
                case Failure(Fibers.Interrupted) => succeed
                case v                           => fail(v.toString())
            }
        }

        "block timeout" in runJVM {
            IOs.attempt(Fibers.runAndBlock(10.millis)(
                Fibers.sleep(1.days).andThen(1)
            )).map {
                case Failure(Fibers.Interrupted) => succeed
                case v                           => fail(v.toString())
            }
        }

        "multiple fibers timeout" in runJVM {
            IOs.attempt(Fibers.runAndBlock(10.millis)(
                Seqs.fill(100)(Fibers.sleep(1.millis)).unit.andThen(1)
            )).map {
                case Failure(Fibers.Interrupted) => succeed
                case v                           => fail(v.toString())
            }
        }
    }

    "interrupt" - {

        def loop(ref: AtomicInt): Unit < IOs =
            ref.incrementAndGet.map(_ => loop(ref))

        def runLoop(started: Latch, done: Latch) =
            Resources.run[Unit, IOs] {
                Resources.ensure(done.release).map { _ =>
                    started.release.map(_ => Atomics.initInt(0).map(loop))
                }
            }

        "one fiber" in runJVM {
            for
                started     <- Latches.init(1)
                done        <- Latches.init(1)
                fiber       <- Fibers.init(runLoop(started, done))
                _           <- started.await
                interrupted <- fiber.interrupt
                _           <- done.await
            yield assert(interrupted)
        }
        "multiple fibers" in runJVM {
            for
                started      <- Latches.init(3)
                done         <- Latches.init(3)
                fiber1       <- Fibers.init(runLoop(started, done))
                fiber2       <- Fibers.init(runLoop(started, done))
                fiber3       <- Fibers.init(runLoop(started, done))
                _            <- started.await
                interrupted1 <- fiber1.interrupt
                interrupted2 <- fiber2.interrupt
                interrupted3 <- fiber3.interrupt
                _            <- done.await
            yield assert(interrupted1 && interrupted2 && interrupted3)
        }
    }

    "race" - {
        "zero" in runJVM {
            IOs.attempt(Fibers.race(Seq())).map { r =>
                assert(r.isFailure)
            }
        }
        "one" in runJVM {
            Fibers.race(Seq(1)).map { r =>
                assert(r == 1)
            }
        }
        "multiple" in runJVM {
            val ac = new JAtomicInteger(0)
            val bc = new JAtomicInteger(0)
            def loop(i: Int, s: String): String < IOs =
                IOs {
                    if i > 0 then
                        if s.equals("a") then ac.incrementAndGet()
                        else bc.incrementAndGet()
                        loop(i - 1, s)
                    else
                        s
                }
            Fibers.race(loop(10, "a"), loop(Int.MaxValue, "b")).map { r =>
                assert(r == "a")
                assert(ac.get() == 10)
                assert(bc.get() <= Int.MaxValue)
            }
        }
        "waits for the first success" in runJVM {
            val ex = new Exception
            Fibers.race(
                Fibers.sleep(1.millis).andThen(42),
                IOs.fail[Int](ex)
            ).map { r =>
                assert(r == 42)
            }
        }
        "returns the last failure if all fibers fail" in runJVM {
            val ex1 = new Exception
            val ex2 = new Exception
            IOs.attempt(
                Fibers.race(
                    Fibers.sleep(1.millis).andThen(IOs.fail[Int](ex1)),
                    IOs.fail[Int](ex2)
                )
            ).map {
                r =>
                    assert(r == Failure(ex1))
            }
        }
    }

    "raceFiber" - {
        "zero" in runJVM {
            IOs.attempt(Fibers.raceFiber(Seq()).map(_.get)).map { r =>
                assert(r.isFailure)
            }
        }
        "one" in runJVM {
            Fibers.raceFiber(Seq(1)).map(_.get).map { r =>
                assert(r == 1)
            }
        }
        "n" in runJVM {
            val ac = new JAtomicInteger(0)
            val bc = new JAtomicInteger(0)
            def loop(i: Int, s: String): String < IOs =
                IOs {
                    if i > 0 then
                        if s.equals("a") then ac.incrementAndGet()
                        else bc.incrementAndGet()
                        loop(i - 1, s)
                    else
                        s
                }
            Fibers.raceFiber(List(loop(10, "a"), loop(Int.MaxValue, "b"))).map(_.get).map { r =>
                assert(r == "a")
                assert(ac.get() == 10)
                assert(bc.get() <= Int.MaxValue)
            }
        }
    }

    "parallel" - {
        "zero" in run {
            Fibers.parallel(Seq()).map { r =>
                assert(r == Seq())
            }
        }
        "one" in run {
            Fibers.parallel(Seq(1)).map { r =>
                assert(r == Seq(1))
            }
        }
        "n" in run {
            val ac = new JAtomicInteger(0)
            val bc = new JAtomicInteger(0)
            def loop(i: Int, s: String): String < IOs =
                IOs {
                    if i > 0 then
                        if s.equals("a") then ac.incrementAndGet()
                        else bc.incrementAndGet()
                        loop(i - 1, s)
                    else
                        s
                }
            Fibers.parallel(List(loop(1, "a"), loop(5, "b"))).map { r =>
                assert(r == List("a", "b"))
                assert(ac.get() == 1)
                assert(bc.get() == 5)
            }
        }
    }

    "parallelTraverse" in run {
        Fibers.parallelTraverse(Seq(1, 2))(_ + 1).map { r =>
            assert(r == Seq(2, 3))
        }
    }

    "parallelFiber" - {
        "zero" in run {
            Fibers.parallelFiber(Seq()).map(_.get).map { r =>
                assert(r == Seq())
            }
        }
        "one" in run {
            Fibers.parallelFiber(Seq(1)).map(_.get).map { r =>
                assert(r == Seq(1))
            }
        }
        "n" in run {
            val ac = new JAtomicInteger(0)
            val bc = new JAtomicInteger(0)
            def loop(i: Int, s: String): String < IOs =
                IOs {
                    if i > 0 then
                        if s.equals("a") then ac.incrementAndGet()
                        else bc.incrementAndGet()
                        loop(i - 1, s)
                    else
                        s
                }
            Fibers.parallelFiber(List(loop(1, "a"), loop(5, "b"))).map(_.get).map { r =>
                assert(r == List("a", "b"))
                assert(ac.get() == 1)
                assert(bc.get() == 5)
            }
        }
    }

    "deep handler" - {
        "transform" in run {
            for
                v1       <- Fibers.init(1).map(_.get)
                (v2, v3) <- Fibers.parallel(2, 3)
                l        <- Fibers.parallel(List[Int < Any](4, 5))
            yield assert(v1 + v2 + v3 + l.sum == 15)
        }
        "interrupt" in runJVM {
            def loop(ref: AtomicInt): Unit < IOs =
                ref.incrementAndGet.map(_ => loop(ref))

            def task(l: Latch): Unit < IOs =
                Resources.run[Unit, IOs] {
                    Resources.ensure(l.release).map { _ =>
                        Atomics.initInt(0).map(loop)
                    }
                }

            for
                l           <- Latches.init(1)
                fiber       <- Fibers.init(task(l))
                _           <- Fibers.sleep(10.millis)
                interrupted <- fiber.interrupt
                _           <- l.await
            yield assert(interrupted)
            end for
        }
    }

    "with resources" - {
        class Resource extends JAtomicInteger with Closeable:
            def close(): Unit =
                set(-1)
        "outer" in run {
            val resource1 = new Resource
            val io1: (JAtomicInteger & Closeable, Set[Int]) < (Resources & Fibers) =
                for
                    r  <- Resources.acquire(resource1)
                    v1 <- IOs(r.incrementAndGet())
                    v2 <- Fibers.init(r.incrementAndGet()).map(_.get)
                yield (r, Set(v1, v2))
            Resources.run(io1).map {
                case (r, v) =>
                    assert(v == Set(1, 2))
                    assert(r.get() == -1)
            }
        }
        "inner" in run {
            val resource1 = new Resource
            Fibers.init(Resources.run(Resources.acquire(resource1).map(_.incrementAndGet())))
                .map(_.get).map { r =>
                    assert(r == 1)
                    assert(resource1.get() == -1)
                }
        }
        "multiple" in run {
            val resource1 = new Resource
            val resource2 = new Resource
            Fibers.parallel(
                Resources.run(Resources.acquire(resource1).map(_.incrementAndGet())),
                Resources.run(Resources.acquire(resource2).map(_.incrementAndGet()))
            ).map { r =>
                assert(r == (1, 1))
                assert(resource1.get() == -1)
                assert(resource2.get() == -1)
            }
        }
        "mixed" in run {
            val resource1 = new Resource
            val resource2 = new Resource
            val io1: Set[Int] < (Resources & (Fibers)) =
                for
                    r  <- Resources.acquire(resource1)
                    v1 <- IOs(r.incrementAndGet())
                    v2 <- Fibers.init(r.incrementAndGet()).map(_.get)
                    v3 <- Resources.run(Resources.acquire(resource2).map(_.incrementAndGet()))
                yield Set(v1, v2, v3)
            Resources.run[Set[Int], Fibers](io1).map { r =>
                assert(r == Set(1, 2))
                assert(resource1.get() == -1)
                assert(resource2.get() == -1)
            }
        }
    }

    "locals" - {
        val l = Locals.init(10)
        "fork" - {
            "default" in run {
                Fibers.init(l.get).map(_.get).map(v => assert(v == 10))
            }
            "let" in run {
                l.let(20)(Fibers.init(l.get).map(_.get)).map(v => assert(v == 20))
            }
        }
        "race" - {
            "default" in run {
                Fibers.race(l.get, l.get).map(v => assert(v == 10))
            }
            "let" in run {
                l.let(20)(Fibers.race(l.get, l.get)).map(v => assert(v == 20))
            }
        }
        "collect" - {
            "default" in run {
                Fibers.parallel(List(l.get, l.get)).map(v => assert(v == List(10, 10)))
            }
            "let" in run {
                l.let(20)(Fibers.parallel(List(l.get, l.get)).map(v => assert(v == List(20, 20))))
            }
        }
    }

    "stack safety" in run {
        def loop(i: Int): Assertion < Fibers =
            if i > 0 then
                Fibers.init(()).map(_ => loop(i - 1))
            else
                succeed
        loop(1000)
    }
end fibersTest

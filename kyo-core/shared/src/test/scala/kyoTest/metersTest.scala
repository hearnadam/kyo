package kyoTest

import kyo.*

class metersTest extends KyoTest:

    "mutex" - {
        "ok" in runJVM {
            for
                t <- Meters.initMutex
                v <- t.run(2)
            yield assert(v == 2)
        }

        "run" in runJVM {
            for
                t  <- Meters.initMutex
                p  <- Fibers.initPromise[Int]
                b1 <- Fibers.initPromise[Unit]
                f1 <- Fibers.init(t.run(b1.complete(()).map(_ => p.block(Duration.Infinity))))
                _  <- b1.get
                a1 <- t.isAvailable
                b2 <- Fibers.initPromise[Unit]
                f2 <- Fibers.init(b2.complete(()).map(_ => t.run(2)))
                _  <- b2.get
                a2 <- t.isAvailable
                d1 <- f1.isDone
                d2 <- f2.isDone
                _  <- p.complete(1)
                v1 <- f1.get
                v2 <- f2.get
                a3 <- t.isAvailable
            yield assert(!a1 && !d1 && !d2 && !a2 && v1 == 1 && v2 == 2 && a3)
        }

        "tryRun" in runJVM {
            for
                sem <- Meters.initSemaphore(1)
                p   <- Fibers.initPromise[Int]
                b1  <- Fibers.initPromise[Unit]
                f1  <- Fibers.init(sem.tryRun(b1.complete(()).map(_ => p.block(Duration.Infinity))))
                _   <- b1.get
                a1  <- sem.isAvailable
                b1  <- sem.tryRun(2)
                b2  <- f1.isDone
                _   <- p.complete(1)
                v1  <- f1.get
            yield assert(!a1 && b1 == None && !b2 && v1 == Some(1))
        }
    }

    "semaphore" - {
        "ok" in runJVM {
            for
                t  <- Meters.initSemaphore(2)
                v1 <- t.run(2)
                v2 <- t.run(3)
            yield assert(v1 == 2 && v2 == 3)
        }

        "run" in runJVM {
            for
                t  <- Meters.initSemaphore(2)
                p  <- Fibers.initPromise[Int]
                b1 <- Fibers.initPromise[Unit]
                f1 <- Fibers.init(t.run(b1.complete(()).map(_ => p.block(Duration.Infinity))))
                _  <- b1.get
                b2 <- Fibers.initPromise[Unit]
                f2 <- Fibers.init(t.run(b2.complete(()).map(_ => p.block(Duration.Infinity))))
                _  <- b2.get
                a1 <- t.isAvailable
                b3 <- Fibers.initPromise[Unit]
                f2 <- Fibers.init(b3.complete(()).map(_ => t.run(2)))
                _  <- b3.get
                a2 <- t.isAvailable
                d1 <- f1.isDone
                d2 <- f2.isDone
                _  <- p.complete(1)
                v1 <- f1.get
                v2 <- f2.get
                a3 <- t.isAvailable
            yield assert(!a1 && !d1 && !d2 && !a2 && v1 == 1 && v2 == 2 && a3)
        }

        "tryRun" in runJVM {
            for
                sem <- Meters.initSemaphore(2)
                p   <- Fibers.initPromise[Int]
                b1  <- Fibers.initPromise[Unit]
                f1  <- Fibers.init(sem.tryRun(b1.complete(()).map(_ => p.block(Duration.Infinity))))
                _   <- b1.get
                b2  <- Fibers.initPromise[Unit]
                f2  <- Fibers.init(sem.tryRun(b2.complete(()).map(_ => p.block(Duration.Infinity))))
                _   <- b2.get
                a1  <- sem.isAvailable
                b3  <- sem.tryRun(2)
                b4  <- f1.isDone
                b5  <- f2.isDone
                _   <- p.complete(1)
                v1  <- f1.get
                v2  <- f2.get
            yield assert(!a1 && b3 == None && !b4 && !b5 && v1 == Some(1) && v2 == Some(1))
        }
    }

    def loop(meter: Meter, counter: AtomicInt): Unit < Fibers =
        meter.run(counter.incrementAndGet).map(_ => loop(meter, counter))

    "rate limiter" - {
        "ok" in runJVM {
            for
                t  <- Meters.initRateLimiter(2, 1.millis)
                v1 <- t.run(2)
                v2 <- t.run(3)
            yield assert(v1 == 2 && v2 == 3)
        }
        "one loop" in runJVM {
            for
                meter   <- Meters.initRateLimiter(10, 1.millis)
                counter <- Atomics.initInt(0)
                f1      <- Fibers.init(loop(meter, counter))
                _       <- Fibers.sleep(5.millis)
                _       <- f1.interrupt
                v1      <- counter.get
            yield assert(v1 >= 2 && v1 <= 200)
        }
        "two loops" in runJVM {
            for
                meter   <- Meters.initRateLimiter(10, 1.millis)
                counter <- Atomics.initInt(0)
                f1      <- Fibers.init(loop(meter, counter))
                f2      <- Fibers.init(loop(meter, counter))
                _       <- Fibers.sleep(5.millis)
                _       <- f1.interrupt
                _       <- f2.interrupt
                v1      <- counter.get
            yield assert(v1 >= 2 && v1 <= 200)
        }
    }

    "pipeline" - {

        "run" in runJVM {
            for
                meter   <- Meters.pipeline(Meters.initRateLimiter(2, 1.millis), Meters.initMutex)
                counter <- Atomics.initInt(0)
                f1      <- Fibers.init(loop(meter, counter))
                f2      <- Fibers.init(loop(meter, counter))
                _       <- Fibers.sleep(5.millis)
                _       <- f1.interrupt
                _       <- f2.interrupt
                v1      <- counter.get
            yield assert(v1 >= 0 && v1 < 200)
        }

        "tryRun" in runJVM {
            for
                meter   <- Meters.pipeline(Meters.initRateLimiter(2, 10.millis), Meters.initMutex)
                counter <- Atomics.initInt(0)
                f1      <- Fibers.init(loop(meter, counter))
                _       <- Fibers.sleep(5.millis)
                _       <- retry(meter.isAvailable.map(!_))
                _       <- Fibers.sleep(5.millis)
                r       <- meter.tryRun(())
                _       <- f1.interrupt
            yield assert(r == None)
        }
    }
end metersTest

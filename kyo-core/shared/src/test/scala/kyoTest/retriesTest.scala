package kyoTest

import kyo._
import org.scalatest.Args
import org.scalatest.Status
import scala.concurrent.duration._

import scala.util.Failure
import scala.util.Success
import scala.util.Try

class retriesTest extends KyoTest {
  
  val ex = new Exception

  "no retries" - {
    "ok" in run {
      var calls = 0
      Retries(_.limit(0)) {
        calls += 1
        42
      }.map { v =>
        assert(v == 42 && calls == 1)
      }
    }
    "nok" in run {
      var calls = 0
      Tries.run {
        Retries(_.limit(0)) {
          calls += 1
          throw ex
        }
      }.map { v =>
        assert(v.isFailure && calls == 1)
      }
    }
  }

  "retries" - {
    "ok" in run {
      var calls = 0
      Retries(_.limit(3)) {
        calls += 1
        42
      }.map { v =>
        assert(v == 42 && calls == 1)
      }
    }
    "nok" in run {
      var calls = 0
      Tries.run {
        Retries(_.limit(3)) {
          calls += 1
          throw ex
        }
      }.map { v =>
        assert(v.isFailure && calls == 4)
      }
    }
  }

  "backoff" in run {
    var calls = 0
    var start = System.currentTimeMillis()
    Tries.run {
      Retries(_.limit(4).exponential(10.millis)) {
        calls += 1
        throw ex
      }
    }.map { v =>
      assert(v.isFailure && calls == 5 && (System.currentTimeMillis() - start) >= 150)
    }
  }

}
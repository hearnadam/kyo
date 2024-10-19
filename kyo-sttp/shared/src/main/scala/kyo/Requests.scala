package kyo

import kyo.internal.KyoStreams
import sttp.client3.*

/** Represents a failed HTTP request.
  *
  * This exception is thrown when an HTTP request fails for any reason.
  *
  * @param cause
  *   A string or throwable describing the reason for the failure
  */
case class FailedRequest(cause: String | Throwable) extends Exception:
    override def getCause(): Throwable =
        cause match
            case ex: Throwable => ex
            case _             => null
end FailedRequest

object Requests:

    /** Abstract class representing a backend for sending HTTP requests */
    abstract class Backend:
        self =>

        /** Sends an HTTP request
          *
          * @tparam A
          *   The type of the response body
          * @param r
          *   The request to send
          * @return
          *   The response wrapped in an effect
          */
        def send[A](r: Request[A, Any]): Response[A] < (Async & Abort[FailedRequest])
        def stream[A](r: Request[A, KyoStreams[Async]]): Stream[A, Async] < (Async & Abort[FailedRequest])

        /** Wraps the Backend with a meter
          *
          * @param m
          *   The meter to use
          * @return
          *   A new Backend that uses the meter
          */
        def withMeter(m: Meter)(using Frame): Backend =
            new Backend:
                def send[A](r: Request[A, Any]) =
                    m.run(self.send(r))

                def stream[A](r: Request[A, KyoStreams[Async]]) =
                    m.run(self.stream(r))
    end Backend

    /** The default live backend implementation */
    val live: Backend = PlatformBackend.default

    private val local = Local.init[Backend](live)

    /** Executes an effect with a custom backend
      *
      * @param b
      *   The backend to use
      * @param v
      *   The effect to execute
      * @return
      *   The result of the effect
      */
    def let[A, S](b: Backend)(v: A < S)(using Frame): A < (Async & S) =
        local.let(b)(v)

    /** Type alias for a basic request */
    type BasicRequest = RequestT[Empty, Either[FailedRequest, String], Any]

    /** A basic request with error handling */
    val basicRequest: BasicRequest = sttp.client3.basicRequest.mapResponse {
        case Left(value)  => Left(FailedRequest(value))
        case Right(value) => Right(value)
    }

    /** Sends an HTTP request using a function to modify the basic request
      *
      * @tparam E
      *   The error type
      * @tparam A
      *   The success type of the response
      * @param f
      *   A function that takes a BasicRequest and returns a Request
      * @return
      *   The response body wrapped in an effect
      */
    def apply[E, A](f: BasicRequest => Request[Either[E, A], Any])(using Frame): A < (Async & Abort[FailedRequest | E]) =
        request(f(basicRequest))

    def stream[E, A](f: BasicRequest => Request[Either[E, A], KyoStreams[Async]])(using
        Frame
    ) =
        local.use(_.stream(f(basicRequest)))

    /** Sends an HTTP request
      *
      * @tparam E
      *   The error type
      * @tparam A
      *   The success type of the response
      * @param req
      *   The request to send
      * @return
      *   The response body wrapped in an effect
      */
    def request[E, A, R](req: Request[Either[E, A], Any])(using Frame): A < (Async & Abort[FailedRequest | E]) =
        local.use(_.send(req)).map { r =>
            Abort.get(r.body)
        }

end Requests

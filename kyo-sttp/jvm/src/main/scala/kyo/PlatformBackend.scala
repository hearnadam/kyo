package kyo

import java.net.http.HttpClient
import kyo.Requests.Backend
import kyo.internal.KyoStreams
import kyo.internal.KyoSttpMonad
import sttp.capabilities.WebSockets
import sttp.client3.*
object PlatformBackend:

    def apply(backend: SttpBackend[KyoSttpMonad.M, KyoStreams[Async]])(using
        Frame
    ): Backend =
        new Backend:
            def send[A](r: Request[A, Any]) =
                r.send(backend)
            def stream[A](r: Request[A, KyoStreams[Async]]) =
                r.send(backend)

    def apply(client: HttpClient)(using Frame): Backend =
        apply(HttpClientKyoBackend.usingClient(client, customEncodingHandler = PartialFunction.empty))

    val default =
        new Backend:
            val b = HttpClientKyoBackend()
            def send[A](r: Request[A, Any]) =
                r.send(b)
            def stream[A](r: Request[A, KyoStreams[Async]]) =
                r.send(b)
end PlatformBackend

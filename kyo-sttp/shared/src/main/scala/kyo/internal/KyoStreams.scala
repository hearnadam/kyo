package kyo.internal

import kyo.Flat
import kyo.Stream
import sttp.capabilities.Streams

abstract class KyoStreams[S] extends Streams[KyoStreams[S]]:
    override type BinaryStream = Stream[Byte, S]
    override type Pipe[A, B]   = Stream[A, S] => Stream[B, S]

object KyoStreams:
    def apply[S]: KyoStreams[S] = new KyoStreams[S] {}

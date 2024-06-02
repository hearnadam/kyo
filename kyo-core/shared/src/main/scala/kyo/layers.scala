package kyo

import Layers.internal.*
import kyo.core.*
import scala.annotation.targetName

sealed trait Layer[-In, +Out, -S]:
    self =>

    infix def to[In2, Out2, S2](that: Layer[Out & In2, Out2, S2]): Layer[In & In2, Out2, S & S2]          = To(self, that)
    infix def and[In2, Out2, S2](that: Layer[In2, Out2, S2]): Layer[In & In2, Out & Out2, S & S2]         = And(self, that)
    infix def using[In2, Out2, S2](that: Layer[Out & In2, Out2, S2]): Layer[In & In2, Out & Out2, S & S2] = self and (self to that)

    private[kyo] def doRun(
        memoMap: scala.collection.mutable.Map[Layer[?, ?, ?], Any] = scala.collection.mutable.Map.empty[Layer[?, ?, ?], Any]
    ): TypeMap[Out] < (S & Envs[In]) =
        type Expected = TypeMap[Out] < (S & Envs[In])
        memoMap.get(self) match
            case Some(result) => result.asInstanceOf[Expected]
            case None =>
                self match
                    case And(lhs, rhs) =>
                        {
                            for
                                leftResult  <- lhs.doRun(memoMap)
                                rightResult <- rhs.doRun(memoMap)
                            yield leftResult.union(rightResult)
                        }.asInstanceOf[Expected]

                    case To(lhs, rhs) =>
                        {
                            for
                                leftResult  <- lhs.doRun(memoMap)
                                rightResult <- Envs.runTypeMap(leftResult)(rhs.doRun(memoMap))(using summon, Envs.bypass)
                            yield rightResult
                        }.asInstanceOf[Expected]

                    case FromKyo(kyo) =>
                        kyo().map { result =>
                            memoMap += (self -> result)
                            result
                        }.asInstanceOf[Expected]
        end match
    end doRun

end Layer

object Layer:
    extension [In, Out, S](layer: Layer[In, Out, S])
        @targetName("runS")
        def run: TypeMap[Out] < (S & Envs[In]) =
            layer.doRun()

    extension [Out, S](layer: Layer[Any, Out, S])
        def run: TypeMap[Out] < S =
            layer.doRun().asInstanceOf[TypeMap[Out] < S]
end Layer

object Layers:
    private[kyo] object internal:
        case class And[In1, Out1, In2, Out2, S](lhs: Layer[In1, Out1, S], rhs: Layer[In2, Out2, S])
            extends Layer[In1 & In2, Out1 & Out2, S]
        case class To[In1, Out1, In2, Out2, S](lhs: Layer[In1, Out1, S], rhs: Layer[Out1 & In2, Out2, S])
            extends Layer[In1 & In2, Out2, S]
        case class FromKyo[In, Out, S](kyo: () => TypeMap[Out] < (Envs[In] & S))(using val tag: Tag[Out]) extends Layer[In, Out, S]
    end internal

    val empty: Layer[Any, Any, Any] = FromKyo { () => TypeMap.empty }

    def apply[A, B: Tag, S](kyo: => B < (Envs[A] & S)): Layer[A, B, S] =
        FromKyo { () =>
            kyo.map { result => TypeMap(result) }
        }

    def from[A: Tag, B: Tag, S](f: A => B < S): Layer[A, B, S] =
        apply {
            Envs.get[A].map(f)
        }

    def from[A: Tag, B: Tag, C: Tag, S](f: (A, B) => C < S): Layer[A & B, C, S] =
        apply {
            zip(Envs.get[A], Envs.get[B]).map { case (a, b) => f(a, b) }
        }

    def from[A: Tag, B: Tag, C: Tag, D: Tag, S](f: (A, B, C) => D < S): Layer[A & B & C, D, S] =
        apply {
            zip(Envs.get[A], Envs.get[B], Envs.get[C])
                .map { case (a, b, c) => f(a, b, c) }
        }

    def from[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, S](f: (A, B, C, D) => E < S): Layer[A & B & C & D, E, S] =
        apply {
            zip(Envs.get[A], Envs.get[B], Envs.get[C], Envs.get[D]).map { case (a, b, c, d) => f(a, b, c, d) }
        }

end Layers

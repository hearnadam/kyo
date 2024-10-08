package kyo

import Batch.internal.*
import kyo.Tag
import kyo.kernel.*

/** The Batch effect allows for efficient batching and processing of operations.
  *
  * Batch is used to group multiple operations together and execute them in a single batch, which can lead to performance improvements,
  * especially when dealing with external systems or databases.
  *
  * @tparam S
  *   Effects from batch sources
  */
sealed trait Batch[+S] extends ArrowEffect[Op[*, S], Id]

object Batch:

    import internal.*

    private inline def erasedTag[S]: Tag[Batch[S]] = Tag[Batch[Any]].asInstanceOf[Tag[Batch[S]]]

    /** Creates a batched computation from a source function.
      *
      * @param f
      *   The source function with the following signature:
      *   - Input: `Seq[A]` - A sequence of input values to be processed in batch
      *   - Output: `(A => B < S) < S` - A function that, when evaluated, produces another function:
      *     - This inner function takes a single input `A` and returns a value `B` with effects `S`, allowing effects on each element
      *       individually.
      *     - The outer `< S` indicates that the creation of this function may itself involve effects `S`
      *
      * @return
      *   A function that takes a single input `A` and returns a batched computation `B < Batch[S]`
      *
      * This method allows for efficient batching of operations by processing multiple inputs at once, while still providing individual
      * results for each input.
      */
    inline def source[A, B, S](f: Seq[A] => (A => B < S) < S)(using inline frame: Frame): A => B < Batch[S] =
        (v: A) => ArrowEffect.suspend[B](erasedTag[S], Op.Call(v, f))

    /** Creates a batched computation from a source function that returns a Map.
      *
      * @param f
      *   The source function that takes a sequence of inputs and returns a Map of results
      * @return
      *   A function that takes a single input and returns a batched computation
      */
    inline def sourceMap[A, B, S](f: Seq[A] => Map[A, B] < S)(using inline frame: Frame): A => B < Batch[S] =
        source[A, B, S] { input =>
            f(input).map { output =>
                require(
                    input.size == output.size,
                    s"Source created at ${frame.parse.position} returned a different number of elements than input: ${input.size} != ${output.size}"
                )
                ((a: A) => output(a): B < S)
            }
        }

    /** Creates a batched computation from a source function that returns a Sequence.
      *
      * @param f
      *   The source function that takes a sequence of inputs and returns a sequence of results
      * @return
      *   A function that takes a single input and returns a batched computation
      */
    inline def sourceSeq[A, B, S](f: Seq[A] => Seq[B] < S)(using inline frame: Frame): A => B < Batch[S] =
        sourceMap[A, B, S] { input =>
            f(input).map { output =>
                require(
                    input.size == output.size,
                    s"Source created at ${frame.parse.position} returned a different number of elements than input: ${input.size} != ${output.size}"
                )
                input.zip(output).toMap
            }
        }

    /** Evaluates a sequence of values in a batch.
      *
      * @param seq
      *   The sequence of values to evaluate
      * @return
      *   A batched operation that produces a single value from the sequence
      */
    inline def eval[A](seq: Seq[A])(using inline frame: Frame): A < Batch[Any] =
        ArrowEffect.suspend[A](erasedTag[Any], Op.Eval(seq))

    /** Applies a function to each element of a sequence in a batched context.
      *
      * This method is similar to `Kyo.foreach`, but instead of returning a `Seq[B]`, it returns a single value of type `B`.
      *
      * @param seq
      *   The sequence of values to process
      * @param f
      *   The function to apply to each element
      * @return
      *   A batched computation that produces a single value of type B
      */
    inline def foreach[A, B, S](seq: Seq[A])(inline f: A => B < S): B < (Batch[Any] & S) =
        ArrowEffect.suspendMap[A](erasedTag[Any], Op.Eval(seq))(f)

    /** Runs a computation with Batch effect, executing all batched operations.
      *
      * @param v
      *   The computation to run
      * @return
      *   A sequence of results from executing the batched operations
      */
    def run[A: Flat, S, S2](v: A < (Batch[S] & S2))(using Frame): Seq[A] < (S & S2) =

        case class Cont(op: Op[?, S], cont: Any => (Cont | A) < (Batch[S] & S & S2))
        def runCont(v: (Cont | A) < (Batch[S] & S & S2)): (Cont | A) < (S & S2) =
            // TODO workaround, Flat macro isn't inferring correctly with nested classes
            import Flat.unsafe.bypass
            ArrowEffect.handle(erasedTag[S], v) {
                [C] => (input, cont) => Cont(input, v => cont(v.asInstanceOf[C]))
            }
        end runCont

        case class Expanded(v: Any, source: Source[Any, Any, S], cont: Any => (Cont | A) < (Batch[S] & S & S2))
        def expand(state: Seq[Cont | A]): Seq[Expanded | A] < (S & S2) =
            Kyo.foreach(state) {
                case Cont(Op.Eval(seq), cont) =>
                    Kyo.foreach(seq)(v => runCont(cont(v))).map(expand)
                case Cont(Op.Call(v, source), cont) =>
                    Seq(Expanded(v, source.asInstanceOf, cont))
                case a: A @unchecked =>
                    Seq(a)
            }.map(_.flatten)

        def loop(state: Seq[Cont | A]): Seq[A] < (S & S2) =
            expand(state).map { expanded =>
                val (completed, pending) =
                    expanded.zipWithIndex.partitionMap {
                        case (e: Expanded @unchecked, idx) => Right((e, idx))
                        case (a: A @unchecked, idx)        => Left((a, idx))
                    }

                if pending.isEmpty then
                    completed.sortBy(_._2).map(_._1)
                else
                    val grouped = pending.groupBy(_._1.source)

                    Kyo.foreach(grouped.toSeq) { case (source, items) =>
                        val (expandedItems, indices) = items.unzip
                        val values                   = expandedItems.map(_.v)
                        val uniqueValues             = values.distinct

                        source(uniqueValues).map { map =>
                            Kyo.foreach(expandedItems.zip(indices)) { case (e, idx) =>
                                runCont(map(e.v).map(e.cont)).map((_, idx))
                            }
                        }
                    }.map { results =>
                        loop(completed.map(_._1) ++ results.flatten.map(_._1))
                    }
                end if
            }
        end loop
        runCont(v).map(c => loop(Seq(c)))
    end run

    object internal:
        type Source[A, B, S] = Seq[A] => (A => B < S) < S

        enum Op[V, -S]:
            case Eval[A](seq: Seq[A])                         extends Op[A, Any]
            case Call[A, B, S](v: A, source: Source[A, B, S]) extends Op[B, S]
    end internal

end Batch

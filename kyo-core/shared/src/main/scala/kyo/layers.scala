package kyo

import kyo.EnvMaps.HasEnvs
import kyo.Flat.unsafe
import kyo.Tag.Intersection
import kyo.core.*

sealed trait Layer[-A, +B]:
    self =>

    import Layers.*

    def >>>[B1, C](that: Layer[B & B1, C]): Layer[A & B1, C]     = To(self, that)
    def ++[B1, C](that: Layer[B1, C]): Layer[A & B1, B & C]      = And(self, that)
    def >+>[B1, C](that: Layer[B & B1, C]): Layer[A & B1, B & C] = self ++ (self >>> that)

    //  - DB >>> Users
    //  - DB >>> App
    //  - And(To(FromKyo(DB), FromKyo(Users)), To(FromKyo(DB), App))

    //  🔺 <- dunce cap for using mutation

    def run: EnvMap[B] < Effects =
        doRun(scala.collection.mutable.Map.empty[Layer[?, ?], Any])

    private def doRun(memoMap: scala.collection.mutable.Map[Layer[?, ?], Any]): EnvMap[B] < Effects =
        memoMap.get(self) match
            case Some(result) =>
                result.asInstanceOf[EnvMap[B] < Effects]

            case None =>
                self match
                    case And(lhs, rhs) =>
                        for
                            leftResult  <- lhs.doRun(memoMap)
                            rightResult <- rhs.doRun(memoMap)
                        yield leftResult.union(rightResult).asInstanceOf[EnvMap[B] < Effects]

                    case To(lhs, rhs) =>
                        for
                            leftResult <- lhs.doRun(memoMap)
                            hasEnvs = new HasEnvs[Any, Any]:
                                type Remainder = Any
                            intersection = leftResult.allTags.asInstanceOf[Intersection[Any]]
                            rightResult <- EnvMaps.provide(leftResult)(rhs.doRun(memoMap))(using unsafe.bypass, hasEnvs, intersection)
                        yield rightResult

                    case layer @ FromKyo(kyo) =>
                        kyo().map { result =>
                            memoMap += (self -> result)
                            result
                        }.asInstanceOf[EnvMap[B] < Effects]
        end match
    end doRun

end Layer

extension [In, Out](layer: Layer[In, Out])
    def build: Out < Fibers & Envs[In] = ???

extension [Out](layer: Layer[Any, Out])
    def build: Out < Fibers = ???

// def run -> A < Effects
// V < Envs[A] & Envs[B]
// Envs.run(envMap.get[A])(v)

object Layers:
    type Effects = Fibers & IOs & Resources

    case class And[In1, Out1, In2, Out2](lhs: Layer[In1, Out1], rhs: Layer[In2, Out2])       extends Layer[In1 & In2, Out1 & Out2]
    case class To[In1, Out1, In2, Out2](lhs: Layer[In1, Out1], rhs: Layer[Out1 & In2, Out2]) extends Layer[In1 & In2, Out2]
    case class FromKyo[In, Out](kyo: () => EnvMap[Out] < (EnvMaps[In] & Effects))(using val tag: Tag[Out]) extends Layer[In, Out]

    // COMPLEXITY:
    // - ZLayer.scoped <<<
    //
    // - Finalizers (Ref Counting)
    // - Memoizaiton
    // - Parallelization
    // - FiberRefs
    //
    // db >>> users
    // db >>> events
    def apply[A, B: Tag](kyo: => B < (Effects & EnvMaps[A])): Layer[A, B] =
        FromKyo { () =>
            kyo.map { result => EnvMap(result) }
        }

    def from[A: Tag, B: Tag](f: A => B): Layer[A, B] =
        Layers.apply { EnvMaps.get[A].map(f) }

    def from[A: Tag, B: Tag, C: Tag](f: (A, B) => C): Layer[A & B, C] =
        Layers.apply {
            for
                a <- EnvMaps.get[A]
                b <- EnvMaps.get[B]
            yield f(a, b)
        }

    // macro for merge?
    def provide[B, S0, S1](layer: Layer[Any, B] < S0)(v: Any < Envs[B] & S1) = ???

end Layers

// A->B >>> A&B>C
// extension [A0, B0](self: Layer[A0, B0])

// end extension

object LayerApp extends KyoApp:

    case class Config(name: String)
    object Config:
        val layer = Layers.apply(Config("Hello"))

    case class DB(config: Config)
    object DB:
        val layer: Layer[Config, DB] = Layers.apply {
            for
                config <- EnvMaps.get[Config]
                _ <- Resources.acquireRelease(
                    IOs { println("Acquiring DB with config: " + config.name) }
                )(_ =>
                    IOs { println("Releasing DB with config: " + config.name) }
                )
                _ <- IOs { println("DONE") }
            yield DB(config)
        }
    end DB

    case class Users(db: DB)
    object Users:
        val layer: Layer[DB, Users] = Layers.apply {
            for
                db <- EnvMaps.get[DB]
                _  <- IOs { println("I AM STARTING UP THE USERS WITH DB: " + db) }
            yield new Users(db)
        }
    end Users

    case class Events(db: DB)
    object Events:
        val layer: Layer[DB, Events] = Layers.apply {
            for
                db <- EnvMaps.get[DB]
                _  <- IOs { println("I AM STARTING UP THE EVENTS WITH DB: " + db) }
            yield new Events(db)
        }
    end Events

    val configDbLayer: Layer[Any, DB]        = Config.layer >>> DB.layer
    val dbUsersLayer: Layer[Any, Users]      = configDbLayer >>> Users.layer
    val dbEventsLayer: Layer[Any, Events]    = configDbLayer >>> Events.layer
    val combined: Layer[Any, Users & Events] = dbUsersLayer ++ dbEventsLayer

    val program =
        for
            envMap <- combined.run
            _ <- IOs {
                println(s"""USERS: ${envMap.get[Users]}""")
                println(s"""EVENTS: ${envMap.get[Events]}""")
            }
        yield ()

    run(program)
end LayerApp

final class EnvMap[+R](private[kyo] val map: Map[Tag[?], Any]):
    def get[A >: R](using tag: Tag[A]): A = map(tag).asInstanceOf[A]

    def add[A](value: A)(using tag: Tag[A]): EnvMap[R & A] =
        new EnvMap(map.updated(tag, value))

    def union[R0](that: EnvMap[R0]): EnvMap[R0] =
        new EnvMap(map ++ that.map)

    def allTags: Intersection[?] = Intersection(map.keys.toSeq)
end EnvMap

object EnvMap extends App:
    val empty: EnvMap[Any] = EnvMap(Map.empty[Tag[?], Any])

    def apply[A: Tag](a: A): EnvMap[A] =
        new EnvMap(Map(Tag[A] -> a))

    def apply[A: Tag, B: Tag](a: A, b: B): EnvMap[A & B] =
        new EnvMap(Map(Tag[A] -> a, Tag[B] -> b))

    def apply[A: Tag, B: Tag, C: Tag](a: A, b: B, c: C): EnvMap[A & B & C] =
        new EnvMap(Map(Tag[A] -> a, Tag[B] -> b, Tag[C] -> c))

    def apply[A: Tag, B: Tag, C: Tag, D: Tag](a: A, b: B, c: C, d: D): EnvMap[A & B & C & D] =
        new EnvMap(Map(Tag[A] -> a, Tag[B] -> b, Tag[C] -> c, Tag[D] -> d))
end EnvMap

class EnvMaps[+V] extends Effect[EnvMaps[V]]:
    type Command[T] = Tag[?]

object EnvMaps:
    private case object envs extends EnvMaps[Any]
    private def envs[V]: EnvMaps[V] = envs.asInstanceOf[EnvMaps[V]]

    trait EnvMapsErased

    // A little ugly.
    val envMapsTag: Tag[EnvMapsErased] = Tag[EnvMapsErased]
    def fakeTag[V]: Tag[EnvMaps[V]]    = envMapsTag.asInstanceOf[Tag[EnvMaps[V]]]

    def get[V](using tag: Tag[V]): V < EnvMaps[V] =
        envs[V].suspend[V](tag)(using fakeTag[V])

    class ProvideDsl[V]:
        def apply[T: Flat, S, VS, VR](env: EnvMap[V])(value: T < (EnvMaps[VS] & S))(
            using
            HasEnvs[V, VS] { type Remainder = VR },
            Intersection[V]
        ): T < (S & VR) =
            given Tag[EnvMaps[V]] = fakeTag[V]
            envs[V].handle(handler[V])(env, value).asInstanceOf[T < (S & VR)]
        end apply
    end ProvideDsl

    def provide[V >: Nothing]: ProvideDsl[V] =
        new ProvideDsl[V]

    private def handler[V](using intersection: Intersection[V]) =
        new ResultHandler[EnvMap[V], Const[Tag[?]], EnvMaps[V], Id, Any]:
            override def accepts[T](st: EnvMap[V], command: Tag[?]): Boolean =
                intersection.tags.exists(t => command <:< t)

            def done[T](st: EnvMap[V], v: T)(using Tag[EnvMaps[V]]) = v

            def resume[T, U: Flat, S2](st: EnvMap[V], command: Tag[?], k: T => U < (EnvMaps[V] & S2))(using Tag[EnvMaps[V]]) =
                Resume(st, k(st.get(using command.asInstanceOf[Tag[Any]]).asInstanceOf[T]))

    sealed trait HasEnvs[V, VS]:
        type Remainder

    trait LowPriorityHasEnvs:
        given hasEnvs[V, VR]: HasEnvs[V, V & VR] with
            type Remainder = EnvMaps[VR]

    object HasEnvs extends LowPriorityHasEnvs:
        given isEnvs[V]: HasEnvs[V, V] with
            type Remainder = Any
end EnvMaps

object TestEnvMaps extends KyoApp:

    val program: String < EnvMaps[String & Int & Boolean] =
        for
            // Suspend(command: (), tag: Tag[Envs[A]])
            // suspend.tag == handler.tag

            // Suspend[](command: tag[A])
            // handler
            string  <- EnvMaps.get[String]
            id      <- EnvMaps.get[Int]
            boolean <- EnvMaps.get[Boolean]
        yield s"STRING: $string, ID: $id, BOOLEAN: $boolean"

    val resolved: String =
        val envMap = EnvMap(123, "Hello", true)
        EnvMaps.provide(envMap)(program).pure
    end resolved

    run(resolved)
end TestEnvMaps

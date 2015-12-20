package io.circe

import cats.MonadError
import io.iteratee.{ Enumeratee, Enumerator, Input, InputFolder, Iteratee, Step, StepFolder }
import io.circe.jawn.CirceSupportParser
import _root_.jawn.AsyncParser

package object streaming {
  /*def parser[F[_]](implicit F: MonadError[F, Throwable]): Enumeratee[F, String, Json] = {
    val parser = CirceSupportParser.async(mode = AsyncParser.UnwrapArray)

    def feed(parser: AsyncParser[Json])(in: String): F[Seq[Json]] =
      parser.absorb(in)(CirceSupportParser.facade).fold(F.raiseError, F.pure)

    Enumeratee.flatMap[F, String, Json](in => 
      Enumerator.liftM[F, Seq[Json]](feed(parser)(in)).flatMap(js =>
        Enumerator.enumVector[F, Json](js.toVector)
      )
    )
  }*/

  def stringParser[F[_]](implicit F: MonadError[F, Throwable]): Enumeratee[F, String, Json] =
    new Enumeratee[F, String, Json] {
      private[this] final def makeParser: AsyncParser[Json] =
        CirceSupportParser.async(mode = AsyncParser.UnwrapArray)

      private[this] def folder[A](parser: AsyncParser[Json])(
        k: Input[Json] => Iteratee[F, Json, A],
        in: Input[String]
      ): InputFolder[String, Outer[A]] = new InputFolder[String, Outer[A]] {
        def onEmpty = Iteratee.cont(in => in.foldWith(folder(parser)(k, in)))
        def onEl(e: String) = parser.absorb(e)(CirceSupportParser.facade) match {
          case Left(error) => Iteratee.fail[F, Throwable, String, Step[F, Json, A]](
            ParsingFailure(error.getMessage, error)
          )
          case Right(jsons) => k(Input.chunk(jsons.toVector)).advance(doneOrLoop(parser))
        }
        def onChunk(es: Vector[String]) = onEl(es.mkString)
        def onEnd = parser.finish()(CirceSupportParser.facade) match {
          case Left(error) => Iteratee.fail[F, Throwable, String, Step[F, Json, A]](
            ParsingFailure(error.getMessage, error)
          )
          case Right(jsons) =>
            k(Input.chunk(jsons.toVector)).advance(toOuter)
        }
      }

      private[this] final def doneOrLoop[A](
        parser: AsyncParser[Json]
      )(s: Step[F, Json, A]): Iteratee[F, String, Step[F, Json, A]] =
        s.foldWith(
          new StepFolder[F, Json, A, Outer[A]] {
            def onCont(k: Input[Json] => Iteratee[F, Json, A]): Outer[A] =
              Iteratee.cont(in => in.foldWith(folder(parser)(k, in)))
            def onDone(value: A, remaining: Input[Json]): Outer[A] = toOuter(s)
          }
        )

      final def apply[A](s: Step[F, Json, A]): Iteratee[F, String, Step[F, Json, A]] =
        doneOrLoop(makeParser)(s)
    }
}

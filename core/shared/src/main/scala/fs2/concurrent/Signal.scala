/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2
package concurrent

import cats.{Applicative, Functor, Invariant}
import cats.data.{OptionT, State}
import cats.effect.Concurrent
import cats.effect.concurrent.{Deferred, Ref}
import cats.syntax.all._
import fs2.internal.Token

/** Pure holder of a single value of type `A` that can be read in the effect `F`. */
trait Signal[F[_], A] {

  /**
    * Returns a stream of the updates to this signal.
    *
    * Updates that are very close together may result in only the last update appearing
    * in the stream. If you want to be notified about every single update, use
    * a `Queue` instead.
    */
  def discrete: Stream[F, A]

  /**
    * Returns a stream of the current value of the signal. An element is always
    * available -- on each pull, the current value is supplied.
    */
  def continuous: Stream[F, A]

  /**
    * Asynchronously gets the current value of this `Signal`.
    */
  def get: F[A]
}

object Signal extends SignalLowPriorityImplicits {
  def constant[F[_], A](a: A)(implicit F: Concurrent[F]): Signal[F, A] =
    new Signal[F, A] {
      def get = F.pure(a)
      def continuous = Stream.constant(a)
      def discrete = Stream(a) ++ Stream.never
    }

  implicit def applicativeInstance[F[_]: Concurrent]: Applicative[Signal[F, *]] =
    new Applicative[Signal[F, *]] {
      override def map[A, B](fa: Signal[F, A])(f: A => B): Signal[F, B] =
        Signal.map(fa)(f)

      override def pure[A](x: A): Signal[F, A] =
        Signal.constant(x)

      override def ap[A, B](ff: Signal[F, A => B])(fa: Signal[F, A]): Signal[F, B] =
        new Signal[F, B] {
          override def discrete: Stream[F, B] =
            nondeterministicZip(ff.discrete, fa.discrete).map { case (f, a) => f(a) }

          override def continuous: Stream[F, B] = Stream.repeatEval(get)

          override def get: F[B] = ff.get.ap(fa.get)
        }
    }

  private def nondeterministicZip[F[_]: Concurrent, A0, A1](
      xs: Stream[F, A0],
      ys: Stream[F, A1]
  ): Stream[F, (A0, A1)] = {
    type PullOutput = (A0, A1, Stream[F, A0], Stream[F, A1])
    val firstPull: OptionT[Pull[F, PullOutput, *], Unit] = for {
      firstXAndRestOfXs <- OptionT(xs.pull.uncons1.covaryOutput[PullOutput])
      (x, restOfXs) = firstXAndRestOfXs
      firstYAndRestOfYs <- OptionT(ys.pull.uncons1.covaryOutput[PullOutput])
      (y, restOfYs) = firstYAndRestOfYs
      _ <- OptionT.liftF {
        Pull.output1[F, PullOutput]((x, y, restOfXs, restOfYs)): Pull[F, PullOutput, Unit]
      }
    } yield ()
    firstPull.value.void.stream
      .flatMap { case (x, y, restOfXs, restOfYs) =>
        restOfXs.either(restOfYs).scan((x, y)) {
          case ((_, rightElem), Left(newElem)) => (newElem, rightElem)
          case ((leftElem, _), Right(newElem)) => (leftElem, newElem)
        }
      }
  }

  private[concurrent] def map[F[_]: Functor, A, B](fa: Signal[F, A])(f: A => B): Signal[F, B] =
    new Signal[F, B] {
      def continuous: Stream[F, B] = fa.continuous.map(f)
      def discrete: Stream[F, B] = fa.discrete.map(f)
      def get: F[B] = Functor[F].map(fa.get)(f)
    }

  implicit class SignalOps[F[_], A](val self: Signal[F, A]) extends AnyVal {

    /**
      * Converts this signal to signal of `B` by applying `f`.
      */
    def map[B](f: A => B)(implicit F: Functor[F]): Signal[F, B] =
      Signal.map(self)(f)
  }

  implicit class BooleanSignalOps[F[_]](val self: Signal[F, Boolean]) extends AnyVal {
    def interrupt[A](
        s: Stream[F, A]
    )(implicit F: Concurrent[F]): Stream[F, A] =
      s.interruptWhen(self)
  }
}

private[concurrent] trait SignalLowPriorityImplicits {

  /**
    * Note that this is not subsumed by [[Signal.applicativeInstance]] because
    * [[Signal.applicativeInstance]] requires a `Concurrent[F]`
    * since it non-deterministically zips elements together while our
    * `Functor` instance has no other constraints.
    *
    * Separating the two instances allows us to make the `Functor` instance
    * more general.
    *
    * We put this in a `SignalLowPriorityImplicits` trait to resolve ambiguous
    * implicits if the [[Signal.applicativeInstance]] is applicable, allowing
    * the `Applicative` instance to be chosen.
    */
  implicit def functorInstance[F[_]: Functor]: Functor[Signal[F, *]] =
    new Functor[Signal[F, *]] {
      override def map[A, B](fa: Signal[F, A])(f: A => B): Signal[F, B] =
        Signal.map(fa)(f)
    }
}

/** Pure holder of a single value of type `A` that can be both read and updated in the effect `F`. */
abstract class SignallingRef[F[_], A] extends Ref[F, A] with Signal[F, A]

object SignallingRef {

  /** Alias for `of`. */
  def apply[F[_]: Concurrent, A](initial: A): F[SignallingRef[F, A]] =
    of(initial)

  /**
    * Builds a `SignallingRef` for for effect `F`, initialized to the supplied value.
    */
  def of[F[_], A](initial: A)(implicit F: Concurrent[F]): F[SignallingRef[F, A]] =
    F.ref(SignalState[F, A](initial, 0L, Map.empty))
      .map(state => new SignallingRef.Impl[F, A](state))

  private case class SignalState[F[_], A](value: A, updates: Long, listeners: Map[Token, Deferred[F, (A, Long)]])

  private final class Impl[F[_], A](
      state: Ref[F, SignalState[F, A]]
  )(implicit F: Concurrent[F])
      extends SignallingRef[F, A] {

    override def get: F[A] = state.get.map(_.value)

    override def continuous: Stream[F, A] =
      Stream.repeatEval(get)

    override def discrete: Stream[F, A] = {
      def go(id: Token, lastUpdate: Long): Stream[F, A] = {
        def getNext: F[(A, Long)] =
          F.deferred[(A, Long)]
            .flatMap { deferred =>
              state.modify { case s @ SignalState(a, updates, listeners) =>
                if (updates != lastUpdate) s -> (a -> updates).pure[F]
                else s.copy(listeners = listeners + (id -> deferred)) -> deferred.get
              }.flatten
            }

        Stream.eval(getNext).flatMap { case (a, l) => Stream.emit(a) ++ go(id, l) }
      }

      def cleanup(id: Token): F[Unit] =
        state.update(s => s.copy(listeners = s.listeners - id))

      Stream.bracket(Token[F])(cleanup).flatMap { id =>
        Stream.eval(state.get).flatMap { state =>
          Stream.emit(state.value) ++ go(id, state.updates)
        }
      }
    }

    def updateAndNotify(state: SignalState[F, A], newValue: A): (SignalState[F, A], F[Unit]) = {
      val newUpdates = state.updates + 1
      val newState = SignalState[F, A](newValue, newUpdates, Map.empty)
      val notify = state.listeners.toVector.traverse { case (_, deferred) =>
        F.start(deferred.complete(newValue -> newUpdates))
      }

      newState -> notify.void
    }

    override def set(a: A): F[Unit] = update(_ => a)

    override def update(f: A => A): F[Unit] =
      modify(a => f(a) -> ())

    override def modify[B](f: A => (A, B)): F[B] =
      state.modify { state =>
        val (newValue, result) = f(state.value)
        val (newState, notify) = updateAndNotify(state, newValue)

        newState -> notify.as(result)
      }.flatten

    override def tryModify[B](f: A => (A, B)): F[Option[B]] =
      state
        .tryModify { state =>
          val (newValue, result) = f(state.value)
          val (newState, notify) = updateAndNotify(state, newValue)

          newState -> notify.as(result)
        }
        .flatMap {
          case None     => F.pure(None)
          case Some(fb) => fb.map(Some(_))
        }

    override def tryUpdate(f: A => A): F[Boolean] =
      tryModify(a => f(a) -> ()).map(_.isDefined)

    override def getAndSet(a: A): F[A] = modify(old => (a, old))

    override def access: F[(A, A => F[Boolean])] =
      state.access.map { case (state, set) => 
        val setter = { (newValue: A) =>
          val (newState, notify) = updateAndNotify(state, newValue)

          set(newState).flatTap { succeeded =>
            notify.whenA(succeeded)
          }
        }

        (state.value, setter)
      }

    override def tryModifyState[B](state: State[A, B]): F[Option[B]] = {
      val f = state.runF.value
      tryModify(a => f(a).value)
    }

    override def modifyState[B](state: State[A, B]): F[B] = {
      val f = state.runF.value
      modify(a => f(a).value)
    }
  }

  implicit def invariantInstance[F[_]: Functor]: Invariant[SignallingRef[F, *]] =
    new Invariant[SignallingRef[F, *]] {
      override def imap[A, B](fa: SignallingRef[F, A])(f: A => B)(g: B => A): SignallingRef[F, B] =
        new SignallingRef[F, B] {
          override def get: F[B] = fa.get.map(f)
          override def discrete: Stream[F, B] = fa.discrete.map(f)
          override def continuous: Stream[F, B] = fa.continuous.map(f)
          override def set(b: B): F[Unit] = fa.set(g(b))
          override def getAndSet(b: B): F[B] = fa.getAndSet(g(b)).map(f)
          override def access: F[(B, B => F[Boolean])] =
            fa.access.map { case (getter, setter) =>
              (f(getter), b => setter(g(b)))
            }
          override def tryUpdate(h: B => B): F[Boolean] = fa.tryUpdate(a => g(h(f(a))))
          override def tryModify[B2](h: B => (B, B2)): F[Option[B2]] =
            fa.tryModify(a => h(f(a)).leftMap(g))
          override def update(bb: B => B): F[Unit] =
            modify(b => (bb(b), ()))
          override def modify[B2](bb: B => (B, B2)): F[B2] =
            fa.modify { a =>
              val (a2, b2) = bb(f(a))
              g(a2) -> b2
            }
          override def tryModifyState[C](state: State[B, C]): F[Option[C]] =
            fa.tryModifyState(state.dimap(f)(g))
          override def modifyState[C](state: State[B, C]): F[C] =
            fa.modifyState(state.dimap(f)(g))
        }
    }
}

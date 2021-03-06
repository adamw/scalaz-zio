// Copyright (C) 2017-2018 John A. De Goes. All rights reserved.
package scalaz.zio

import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration._
import org.specs2.concurrent.ExecutionEnv
import org.specs2.Specification
import org.specs2.specification.AroundTimeout
import Errors.UnhandledError
import com.github.ghik.silencer.silent

class RTSSpec(implicit ee: ExecutionEnv) extends Specification with AroundTimeout with RTS {

  override def defaultHandler[E]: Throwable => IO[E, Unit] = _ => IO.unit[E]

  def is = s2"""
  RTS synchronous correctness
    widen Void                              $testWidenVoid
    evaluation of point                     $testPoint
    point must be lazy                      $testPointIsLazy
    now must be eager                       $testNowIsEager
    suspend must be lazy                    $testSuspendIsLazy
    suspend must be evaluatable             $testSuspendIsEvaluatable
    point, bind, map                        $testSyncEvalLoop
    sync effect                             $testEvalOfSyncEffect
    deep effects                            $testEvalOfDeepSyncEffect

  RTS failure
    error in sync effect                    $testEvalOfRedeemOfSyncEffectError
    attempt . fail                          $testEvalOfAttemptOfFail
    deep attempt sync effect error          $testAttemptOfDeepSyncEffectError
    deep attempt fail error                 $testAttemptOfDeepFailError
    uncaught fail                           $testEvalOfUncaughtFail
    uncaught sync effect error              $testEvalOfUncaughtThrownSyncEffect
    deep uncaught sync effect error         $testEvalOfDeepUncaughtThrownSyncEffect
    deep uncaught fail                      $testEvalOfDeepUncaughtFail

  RTS finalizers
    fail ensuring                           $testEvalOfFailEnsuring
    fail on error                           $testEvalOfFailOnError
    finalizer errors not caught             $testErrorInFinalizerCannotBeCaught
    finalizer errors reported               ${upTo(1.second)(testErrorInFinalizerIsReported)}
    bracket result is usage result          $testExitResultIsUsageResult
    error in just acquisition               $testBracketErrorInAcquisition
    error in just release                   $testBracketErrorInRelease
    error in just usage                     $testBracketErrorInUsage
    rethrown caught error in acquisition    $testBracketRethrownCaughtErrorInAcquisition
    rethrown caught error in release        $testBracketRethrownCaughtErrorInRelease
    rethrown caught error in usage          $testBracketRethrownCaughtErrorInUsage
    test eval of async fail                 $testEvalOfAsyncAttemptOfFail
    bracket regression 1                    ${upTo(10.seconds)(testBracketRegression1)}
    interrupt waits for finalizer           $testInterruptWaitsForFinalizer

  RTS synchronous stack safety
    deep map of point                       $testDeepMapOfPoint
    deep map of now                         $testDeepMapOfNow
    deep map of sync effect                 $testDeepMapOfSyncEffectIsStackSafe
    deep attempt                            $testDeepAttemptIsStackSafe
    deep absolve/attempt is identity        $testDeepAbsolveAttemptIsIdentity
    deep async absolve/attempt is identity  $testDeepAsyncAbsolveAttemptIsIdentity

  RTS asynchronous stack safety
    deep bind of async chain                $testDeepBindOfAsyncChainIsStackSafe

  RTS asynchronous correctness
    simple async must return                $testAsyncEffectReturns
    simple asyncIO must return              $testAsyncIOEffectReturns
    sleep 0 must return                     ${upTo(1.second)(testSleepZeroReturns)}

  RTS concurrency correctness
    shallow fork/join identity              $testForkJoinIsId
    deep fork/join identity                 $testDeepForkJoinIsId
    interrupt of never                      ${upTo(1.second)(testNeverIsInterruptible)}
    race of fail with success               ${upTo(1.second)(testRaceChoosesWinner)}
    race of fail with fail                  ${upTo(1.second)(testRaceChoosesFailure)}
    race of value & never                   ${upTo(1.second)(testRaceOfValueNever)}
    raceAll of values                       ${upTo(1.second)(testRaceAllOfValues)}
    raceAll of failures                     ${upTo(1.second)(testRaceAllOfFailures)}
    raceAll of failures & one success       ${upTo(1.second)(testRaceAllOfFailuresOneSuccess)}
    par regression                          ${upTo(5.seconds)(testPar)}
    par of now values                       ${upTo(5.seconds)(testRepeatedPar)}
    mergeAll                                $testMergeAll
    mergeAllEmpty                           $testMergeAllEmpty
    reduceAll                               $testReduceAll
    reduceAll Empty List                    $testReduceAllEmpty

  RTS regression tests
    regression 1                            $testDeadlockRegression
    check interruption regression 1         ${upTo(20.seconds)(testInterruptionRegression1)}

  RTS interrupt fiber tests
    sync forever                            $testInterruptSyncForever
  """

  def testPoint =
    unsafeRun(IO.point(1)) must_=== 1

  def testWidenVoid = {
    val op1 = IO.sync[RuntimeException, String]("1")
    val op2 = IO.sync[Void, String]("2")

    val result: IO[RuntimeException, String] = for {
      r1 <- op1
      r2 <- op2.widenError[RuntimeException]
    } yield r1 + r2

    unsafeRun(result) must_=== "12"
  }

  def testPointIsLazy =
    IO.point(throw new Error("Not lazy")) must not(throwA[Throwable])

  @silent
  def testNowIsEager =
    IO.now(throw new Error("Eager")) must (throwA[Error])

  def testSuspendIsLazy =
    IO.suspend(throw new Error("Eager")) must not(throwA[Throwable])

  def testSuspendIsEvaluatable =
    unsafeRun(IO.suspend(IO.point[Throwable, Int](42))) must_=== 42

  def testSyncEvalLoop = {
    def fibIo(n: Int): IO[Throwable, BigInt] =
      if (n <= 1) IO.point(n)
      else
        for {
          a <- fibIo(n - 1)
          b <- fibIo(n - 2)
        } yield a + b

    unsafeRun(fibIo(10)) must_=== fib(10)
  }

  def testEvalOfSyncEffect = {
    def sumIo(n: Int): IO[Throwable, Int] =
      if (n <= 0) IO.sync(0)
      else IO.sync(n).flatMap(b => sumIo(n - 1).map(a => a + b))

    unsafeRun(sumIo(1000)) must_=== sum(1000)
  }

  @silent
  def testEvalOfRedeemOfSyncEffectError =
    unsafeRun(
      IO.syncThrowable[Unit](throw ExampleError).redeemPure[Throwable, Option[Throwable]](Some(_), _ => None)
    ) must_=== Some(ExampleError)

  def testEvalOfAttemptOfFail = Seq(
    unsafeRun(IO.fail[Throwable, Int](ExampleError).attempt[Throwable]) must_=== Left(ExampleError),
    unsafeRun(IO.suspend(IO.suspend(IO.fail[Throwable, Int](ExampleError)).attempt[Throwable])) must_=== Left(
      ExampleError
    )
  )

  def testAttemptOfDeepSyncEffectError =
    unsafeRun(deepErrorEffect(100).attempt[Throwable]) must_=== Left(ExampleError)

  def testAttemptOfDeepFailError =
    unsafeRun(deepErrorFail(100).attempt[Throwable]) must_=== Left(ExampleError)

  def testEvalOfUncaughtFail =
    unsafeRun(IO.fail[Throwable, Int](ExampleError)) must (throwA(UnhandledError(ExampleError)))

  def testEvalOfUncaughtThrownSyncEffect =
    unsafeRun(IO.sync[Throwable, Int](throw ExampleError)) must (throwA(ExampleError))

  def testEvalOfDeepUncaughtThrownSyncEffect =
    unsafeRun(deepErrorEffect(100)) must (throwA(UnhandledError(ExampleError)))

  def testEvalOfDeepUncaughtFail =
    unsafeRun(deepErrorEffect(100)) must (throwA(UnhandledError(ExampleError)))

  def testEvalOfFailEnsuring = {
    var finalized = false

    unsafeRun(IO.fail[Throwable, Unit](ExampleError).ensuring(IO.sync[Void, Unit] { finalized = true; () })) must (throwA(
      UnhandledError(ExampleError)
    ))
    finalized must_=== true
  }

  def testEvalOfFailOnError = {
    var finalized = false
    val cleanup: Option[Throwable] => IO[Void, Unit] =
      _ => IO.sync[Void, Unit] { finalized = true; () }

    unsafeRun(
      IO.fail[Throwable, Unit](ExampleError).onError(cleanup)
    ) must (throwA(UnhandledError(ExampleError)))

    finalized must_=== true
  }

  def testErrorInFinalizerCannotBeCaught = {
    val nested: IO[Throwable, Int] =
      IO.fail[Throwable, Int](ExampleError)
        .ensuring(IO.terminate(new Error("e2")))
        .ensuring(IO.terminate(new Error("e3")))

    unsafeRun(nested) must (throwA(UnhandledError(ExampleError)))
  }

  def testErrorInFinalizerIsReported = {
    var reported: Throwable = null

    unsafeRun {
      IO.point[Void, Int](42)
        .ensuring(IO.terminate(ExampleError))
        .fork0(e => IO.sync[Void, Unit] { reported = e; () })
    }

    // FIXME: Is this an issue with thread synchronization?
    while (reported eq null) Thread.`yield`()

    ((throw reported): Int) must (throwA(ExampleError))
  }

  def testExitResultIsUsageResult =
    unsafeRun(IO.bracket(IO.unit[Throwable])(_ => IO.unit[Void])(_ => IO.point[Throwable, Int](42))) must_=== 42

  def testBracketErrorInAcquisition =
    unsafeRun(IO.bracket(IO.fail[Throwable, Unit](ExampleError))(_ => IO.unit)(_ => IO.unit)) must
      (throwA(UnhandledError(ExampleError)))

  def testBracketErrorInRelease =
    unsafeRun(IO.bracket(IO.unit[Void])(_ => IO.terminate(ExampleError))(_ => IO.unit[Void])) must
      (throwA(ExampleError))

  def testBracketErrorInUsage =
    unsafeRun(IO.bracket(IO.unit[Throwable])(_ => IO.unit)(_ => IO.fail[Throwable, Unit](ExampleError))) must
      (throwA(UnhandledError(ExampleError)))

  def testBracketRethrownCaughtErrorInAcquisition = {
    lazy val actual = unsafeRun(
      IO.absolve(IO.bracket(IO.fail[Throwable, Unit](ExampleError))(_ => IO.unit)(_ => IO.unit).attempt[Throwable])
    )

    actual must (throwA(UnhandledError(ExampleError)))
  }

  def testBracketRethrownCaughtErrorInRelease = {
    lazy val actual = unsafeRun(
      IO.bracket(IO.unit[Void])(_ => IO.terminate(ExampleError))(_ => IO.unit[Void])
    )

    actual must (throwA(ExampleError))
  }

  def testBracketRethrownCaughtErrorInUsage = {
    lazy val actual = unsafeRun(
      IO.absolve(
        IO.bracket(IO.unit[Throwable])(_ => IO.unit)(_ => IO.fail[Throwable, Unit](ExampleError)).attempt[Throwable]
      )
    )

    actual must (throwA(UnhandledError(ExampleError)))
  }

  def testEvalOfAsyncAttemptOfFail = {
    val io1 = IO.bracket(IO.unit[Throwable])(_ => AsyncUnit[Void])(_ => asyncExampleError[Unit])
    val io2 = IO.bracket(AsyncUnit[Throwable])(_ => IO.unit)(_ => asyncExampleError[Unit])

    unsafeRun(io1) must (throwA(UnhandledError(ExampleError)))
    unsafeRun(io2) must (throwA(UnhandledError(ExampleError)))
    unsafeRun(IO.absolve(io1.attempt[Throwable])) must (throwA(UnhandledError(ExampleError)))
    unsafeRun(IO.absolve(io2.attempt[Throwable])) must (throwA(UnhandledError(ExampleError)))
  }

  def testBracketRegression1 = {
    def makeLogger: IORef[List[String]] => String => IO[Void, Unit] =
      (ref: IORef[List[String]]) => (line: String) => ref.modify[Void](_ ::: List(line)).toUnit

    unsafeRun(for {
      ref <- IORef[Void, List[String]](Nil)
      log = makeLogger(ref)
      f <- IO
            .bracket(
              IO.bracket(IO.unit[Void])(_ => log("start 1") *> IO.sleep(10.milliseconds) *> log("release 1"))(
                _ => IO.unit[Void]
              )
            )(_ => log("start 2") *> IO.sleep(10.milliseconds) *> log("release 2"))(_ => IO.unit[Void])
            .fork
      _ <- (ref.read <* IO.sleep[Void](1.millisecond)).doUntil(_.contains("start 1"))
      _ <- f.interrupt(new RuntimeException("cancel"))
      _ <- (ref.read <* IO.sleep[Void](1.millisecond)).doUntil(_.contains("release 2"))
      l <- ref.read
    } yield l) must_=== ("start 1" :: "release 1" :: "start 2" :: "release 2" :: Nil)
  }

  def testInterruptWaitsForFinalizer =
    unsafeRun(for {
      r    <- IORef[Void, Boolean](false)
      p1   <- Promise.make[Void, Unit]
      p2   <- Promise.make[Void, Int]
      s    <- (p1.complete(()) *> p2.get).ensuring(r.write[Void](true).toUnit.delay(10.millis)).fork
      _    <- p1.get
      _    <- s.interrupt[Void](new Error("interrupt e"))
      test <- r.read[Void]
    } yield test must_=== true)

  def testEvalOfDeepSyncEffect = {
    def incLeft(n: Int, ref: IORef[Int]): IO[Throwable, Int] =
      if (n <= 0) ref.read
      else incLeft(n - 1, ref) <* ref.modify(_ + 1)

    def incRight(n: Int, ref: IORef[Int]): IO[Throwable, Int] =
      if (n <= 0) ref.read
      else ref.modify(_ + 1) *> incRight(n - 1, ref)

    unsafeRun(for {
      ref <- IORef(0)
      v   <- incLeft(100, ref)
    } yield v) must_=== 100

    unsafeRun(for {
      ref <- IORef(0)
      v   <- incRight(1000, ref)
    } yield v) must_=== 1000
  }

  def testDeepMapOfPoint =
    unsafeRun(deepMapPoint(10000)) must_=== 10000

  def testDeepMapOfNow =
    unsafeRun(deepMapNow(10000)) must_=== 10000

  def testDeepMapOfSyncEffectIsStackSafe =
    unsafeRun(deepMapEffect(10000)) must_=== 10000

  def testDeepAttemptIsStackSafe =
    unsafeRun((0 until 10000).foldLeft(IO.sync[Throwable, Unit](())) { (acc, _) =>
      acc.attempt[Throwable].toUnit
    }) must_=== (())

  def testDeepAbsolveAttemptIsIdentity =
    unsafeRun((0 until 1000).foldLeft(IO.point[Int, Int](42))((acc, _) => IO.absolve(acc.attempt))) must_=== 42

  def testDeepAsyncAbsolveAttemptIsIdentity =
    unsafeRun(
      (0 until 1000)
        .foldLeft(IO.async[Int, Int](k => k(ExitResult.Completed(42))))((acc, _) => IO.absolve(acc.attempt))
    ) must_=== 42

  def testDeepBindOfAsyncChainIsStackSafe = {
    val result = (0 until 10000).foldLeft(IO.point[Throwable, Int](0)) { (acc, _) =>
      acc.flatMap(n => IO.async[Throwable, Int](_(ExitResult.Completed[Throwable, Int](n + 1))))
    }

    unsafeRun(result) must_=== 10000
  }

  def testAsyncEffectReturns =
    unsafeRun(IO.async[Throwable, Int](cb => cb(ExitResult.Completed(42)))) must_=== 42

  def testAsyncIOEffectReturns =
    unsafeRun(IO.asyncPure[Throwable, Int](cb => IO.sync(cb(ExitResult.Completed(42))))) must_=== 42

  def testSleepZeroReturns =
    unsafeRun(IO.sleep(1.nanoseconds)) must_=== ((): Unit)

  def testForkJoinIsId =
    unsafeRun(IO.point[Throwable, Int](42).fork.flatMap(_.join)) must_=== 42

  def testDeepForkJoinIsId = {
    val n = 20

    unsafeRun(concurrentFib(n)) must_=== fib(n)
  }

  def testNeverIsInterruptible = {
    val io =
      for {
        fiber <- IO.never[Throwable, Int].fork[Throwable]
        _     <- fiber.interrupt(ExampleError)
      } yield 42

    unsafeRun(io) must_=== 42
  }

  def testRaceChoosesWinner =
    unsafeRun(IO.fail(42).race(IO.now(24)).attempt) must_=== Right(24)

  def testRaceChoosesFailure =
    unsafeRun(IO.fail(42).race(IO.fail(42)).attempt) must_=== Left(42)

  def testRaceOfValueNever =
    unsafeRun(IO.point(42).race(IO.never[Throwable, Int])) must_=== 42

  def testRaceOfFailNever =
    unsafeRun(IO.fail(24).race(IO.never[Int, Int]).timeout[Option[Int]](None)(Option.apply)(10.milliseconds)) must beNone

  def testRaceAllOfValues =
    unsafeRun(IO.raceAll[Int, Int](List(IO.fail(42), IO.now(24))).attempt) must_=== Right(24)

  def testRaceAllOfFailures =
    unsafeRun(IO.raceAll[Int, Void](List(IO.fail(24).delay(10.milliseconds), IO.fail(24))).attempt) must_=== Left(
      24
    )

  def testRaceAllOfFailuresOneSuccess =
    unsafeRun(IO.raceAll[Int, Int](List(IO.fail(42), IO.now(24).delay(1.milliseconds))).attempt) must_=== Right(
      24
    )

  def testRepeatedPar = {
    def countdown(n: Int): IO[Void, Int] =
      if (n == 0) IO.now(0)
      else IO.now[Void, Int](1).par(IO.now[Void, Int](2)).flatMap(t => countdown(n - 1).map(y => t._1 + t._2 + y))

    unsafeRun(countdown(50)) must_=== 150
  }

  def testPar =
    (0 to 1000).map { _ =>
      unsafeRun(IO.now[Void, Int](1).par(IO.now[Void, Int](2)).flatMap(t => IO.now(t._1 + t._2))) must_=== 3
    }

  def testReduceAll =
    unsafeRun(
      IO.reduceAll[Void, Int](IO.point(1), List(2, 3, 4).map(IO.point[Void, Int](_)))(_ + _)
    ) must_=== 10

  def testReduceAllEmpty =
    unsafeRun(
      IO.reduceAll[Void, Int](IO.point(1), Seq.empty)(_ + _)
    ) must_=== 1

  def testDeadlockRegression = {

    import java.util.concurrent.Executors

    val e = Executors.newSingleThreadExecutor()

    for (i <- (0 until 10000)) {
      val t = IO.async[Void, Int] { cb =>
        val c: Callable[Unit] = () => cb(ExitResult.Completed(1))
        val _                 = e.submit(c)
      }
      unsafeRun(t)
    }

    e.shutdown() must_=== (())
  }

  def testInterruptionRegression1 = {

    val c = new AtomicInteger(0)

    def test =
      IO.syncThrowable {
        if (c.incrementAndGet() <= 1) throw new RuntimeException("x")
      }.forever
        .ensuring(IO.unit)
        .attempt
        .forever

    unsafeRun(
      for {
        f <- test.fork[Throwable]
        c <- (IO.sync[Throwable, Int](c.get) <* IO.sleep(1.millis)).doUntil(_ >= 1) <* f.interrupt(
              new RuntimeException("y")
            )
      } yield c must be_>=(1)
    )

  }

  def testInterruptSyncForever = unsafeRun(
    for {
      f <- IO.sync[Void, Int](1).forever[Void].fork
      _ <- f.interrupt[Void](new Error("terminate forever"))
    } yield true
  )

  // Utility stuff
  val ExampleError = new Exception("Oh noes!")

  def asyncExampleError[A]: IO[Throwable, A] = IO.async[Throwable, A](_(ExitResult.Failed(ExampleError)))

  def sum(n: Int): Int =
    if (n <= 0) 0
    else n + sum(n - 1)

  def deepMapPoint(n: Int): IO[Throwable, Int] =
    if (n <= 0) IO.point(n) else IO.point(n - 1).map(_ + 1)

  def deepMapNow(n: Int): IO[Throwable, Int] =
    if (n <= 0) IO.now(n) else IO.now(n - 1).map(_ + 1)

  def deepMapEffect(n: Int): IO[Throwable, Int] =
    if (n <= 0) IO.sync(n) else IO.sync(n - 1).map(_ + 1)

  def deepErrorEffect(n: Int): IO[Throwable, Unit] =
    if (n == 0) IO.syncThrowable(throw ExampleError)
    else IO.unit *> deepErrorEffect(n - 1)

  def deepErrorFail(n: Int): IO[Throwable, Unit] =
    if (n == 0) IO.fail(ExampleError)
    else IO.unit *> deepErrorFail(n - 1)

  def fib(n: Int): BigInt =
    if (n <= 1) n
    else fib(n - 1) + fib(n - 2)

  def concurrentFib(n: Int): IO[Throwable, BigInt] =
    if (n <= 1) IO.point[Throwable, BigInt](n)
    else
      for {
        f1 <- concurrentFib(n - 1).fork
        f2 <- concurrentFib(n - 2).fork
        v1 <- f1.join
        v2 <- f2.join
      } yield v1 + v2

  def AsyncUnit[E] = IO.async[E, Unit](_(ExitResult.Completed(())))

  def testMergeAll =
    unsafeRun(
      IO.mergeAll[Void, String, Int](List("a", "aa", "aaa", "aaaa").map(IO.point[Void, String](_)))(
        0,
        f = (b, a) => b + a.length
      )
    ) must_=== 10

  def testMergeAllEmpty =
    unsafeRun(
      IO.mergeAll[Void, Int, Int](List.empty)(0, _ + _)
    ) must_=== 0
}

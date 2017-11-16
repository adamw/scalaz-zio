// Copyright (C) 2017 John A. De Goes. All rights reserved.
package scalaz.effect

import scala.concurrent.duration._

import org.specs2.concurrent.ExecutionEnv
import org.specs2.Specification
import org.specs2.specification.AroundTimeout

import scalaz.data.Disjunction._

class RTSSpec(implicit ee : ExecutionEnv) extends Specification
    with AroundTimeout
    with RTS {

  override val defaultHandler = t => IO.unit

  def is = s2"""
  RTS synchronous correctness
    evaluation of point                     $testPoint
    point must be lazy                      $testPointIsLazy
    now must be eager                       $testNowIsEager
    suspend must be lazy                    $testSuspendIsLazy
    suspend must be evaluatable             $testSuspendIsEvaluatable
    point, bind, map                        $testSyncEvalLoop
    sync effect                             $testEvalOfSyncEffect
    error in sync effect                    $testEvalOfAttemptOfSyncEffectError
    attempt . fail                          $testEvalOfAttemptOfFail
    deep attempt sync effect error          $testAttemptOfDeepSyncEffectError
    deep attempt fail error                 $testAttemptOfDeepFailError
    uncaught fail                           $testEvalOfUncaughtFail
    uncaught sync effect error              $testEvalOfUncaughtThrownSyncEffect
    deep uncaught sync effect error         $testEvalOfDeepUncaughtThrownSyncEffect
    deep uncaught fail                      $testEvalOfDeepUncaughtFail
    fail ensuring                           $testEvalOfFailEnsuring
    fail on error                           $testEvalOfFailOnError
    finalizer errors not caught             $testErrorInFinalizerCannotBeCaught
    finalizer errors reported               ${upTo(1.second)(testErrorInFinalizerIsReported)}

  RTS synchronous stack safety
    deep map of point                       $testDeepMapOfPoint
    deep map of now                         $testDeepMapOfNow
    deep map of sync effect                 $testDeepMapOfSyncEffectIsStackSafe
    deep attempt                            $testDeepAttemptIsStackSafe

  RTS asynchronous stack safety
    deep bind of async chain                $testDeepBindOfAsyncChainIsStackSafe

  RTS asynchronous correctness
    simple async must return                $testAsyncEffectReturns
    sleep 0 must return                     ${upTo(1.second)(testSleepZeroReturns)}

  RTS concurrency correctness
    shallow fork/join identity              $testForkJoinIsId
    deep fork/join identity                 $testDeepForkJoinIsId
    interrupt of never                      ${upTo(1.second)(testNeverIsInterruptible)}
    """
    /*
    race of value & never                   ${upTo(1.second)(testRaceOfValueNever)}
                                          """ */

  def testPoint = {
    unsafePerformIO(IO(1)) must_=== 1
  }

  def testPointIsLazy = {
    IO(throw new Error("Not lazy")) must not (throwA[Throwable])
  }

  def testNowIsEager = {
    (IO.now(throw new Error("Eager"))) must (throwA[Error])
  }

  def testSuspendIsLazy = {
    IO.suspend(throw new Error("Eager")) must not (throwA[Throwable])
  }

  def testSuspendIsEvaluatable = {
    unsafePerformIO(IO.suspend(IO(42))) must_=== 42
  }

  def testSyncEvalLoop = {
    def fibIo(n: Int): IO[BigInt] =
      if (n <= 1) IO(n) else for {
        a <- fibIo(n - 1)
        b <- fibIo(n - 2)
      } yield a + b

    unsafePerformIO(fibIo(10)) must_=== fib(10)
  }

  def testEvalOfSyncEffect = {
    def sumIo(n: Int): IO[Int] =
      if (n <= 0) IO.sync(0)
      else IO.sync(n).flatMap(b => sumIo(n - 1).map(a => a + b))

    unsafePerformIO(sumIo(1000)) must_=== sum(1000)
  }

  def testEvalOfAttemptOfSyncEffectError = {
    unsafePerformIO(IO.sync(throw ExampleError).attempt) must_=== -\/(ExampleError)
  }

  def testEvalOfAttemptOfFail = {
    unsafePerformIO(IO.fail(ExampleError).attempt) must_=== -\/(ExampleError)
  }

  def testAttemptOfDeepSyncEffectError = {
    unsafePerformIO(deepErrorEffect(100).attempt) must_=== -\/(ExampleError)
  }

  def testAttemptOfDeepFailError = {
    unsafePerformIO(deepErrorFail(100).attempt) must_=== -\/(ExampleError)
  }

  def testEvalOfUncaughtFail = {
    unsafePerformIO(IO.fail[Int](ExampleError)) must (throwA(ExampleError))
  }

  def testEvalOfUncaughtThrownSyncEffect = {
    unsafePerformIO(IO.sync[Int](throw ExampleError)) must (throwA(ExampleError))
  }

  def testEvalOfDeepUncaughtThrownSyncEffect = {
    unsafePerformIO(deepErrorEffect(100)) must (throwA(ExampleError))
  }

  def testEvalOfDeepUncaughtFail = {
    unsafePerformIO(deepErrorEffect(100)) must (throwA(ExampleError))
  }

  def testEvalOfFailEnsuring = {
    var finalized = false

    unsafePerformIO(IO.fail[Unit](ExampleError).ensuring(IO.sync[Unit] { finalized = true; () })) must (throwA(ExampleError))
    finalized must_=== true
  }

  def testEvalOfFailOnError = {
    var finalized = false

    unsafePerformIO(IO.fail[Unit](ExampleError).onError(_ => IO.sync[Unit] { finalized = true; () })) must (throwA(ExampleError))

    finalized must_=== true
  }

  def testErrorInFinalizerCannotBeCaught = {
    val nested: IO[Int] =
      IO.fail(ExampleError).ensuring(
        IO.fail(new Error("e2"))).ensuring(
          IO.fail(new Error("e3")))

    unsafePerformIO(nested) must (throwA(ExampleError))
  }

  def testErrorInFinalizerIsReported = {
    var reported: Throwable = null

    unsafePerformIO {
      IO(42).ensuring(IO.fail(ExampleError)).
        fork0(e => IO.sync[Unit] { reported = e; () })
    }

    // FIXME: Is this an issue with thread synchronization?
    while (reported == null) Thread.`yield`()

    reported must_=== ExampleError
  }

  def testDeepMapOfPoint = {
    unsafePerformIO(deepMapPoint(10000)) must_=== 10000
  }

  def testDeepMapOfNow = {
    unsafePerformIO(deepMapNow(10000)) must_=== 10000
  }

  def testDeepMapOfSyncEffectIsStackSafe = {
    unsafePerformIO(deepMapEffect(10000)) must_=== 10000
  }

  def testDeepAttemptIsStackSafe = {
    unsafePerformIO((0 until 10000).foldLeft(IO.sync(())) { (acc, _) =>
      acc.attempt.toUnit
    }) must_=== (())
  }

  def testDeepBindOfAsyncChainIsStackSafe = {
    val result = (0 until 10000).foldLeft(IO(0)) { (acc, _) =>
      acc.flatMap(n => IO.async[Int](_(\/-(n + 1))))
    }

    unsafePerformIO(result) must_=== 10000
  }

  def testAsyncEffectReturns = {
    unsafePerformIO(IO.async[Int](cb => cb(\/-(42)))) must_=== 42
  }

  def testSleepZeroReturns = {
    unsafePerformIO(IO.sleep(1.nanoseconds)) must_=== ((): Unit)
  }

  def testForkJoinIsId = {
    unsafePerformIO(IO(42).fork.flatMap(_.join)) must_=== 42
  }

  def testDeepForkJoinIsId = {
    val n = 20

    unsafePerformIO(concurrentFib(n)) must_=== fib(n)
  }

  def testNeverIsInterruptible = {
    val io =
      for {
        fiber <- IO.never[Int].fork
        _     <- fiber.interrupt(ExampleError)
      } yield 42

    unsafePerformIO(io) must_=== 42
  }

  def testRaceOfValueNever = {
    unsafePerformIO(IO(42).race(IO.never[Int])) == 42
  }

  // Utility stuff
  val ExampleError = new Error("Oh noes!")

  def sum(n: Int): Int =
    if (n <= 0) 0
    else n + sum(n - 1)

  def deepMapPoint(n: Int): IO[Int] =
    if (n <= 0) IO(n) else IO(n - 1).map(_ + 1)

  def deepMapNow(n: Int): IO[Int] =
    if (n <= 0) IO.now(n) else IO.now(n - 1).map(_ + 1)

  def deepMapEffect(n: Int): IO[Int] =
    if (n <= 0) IO.sync(n) else IO.sync(n - 1).map(_ + 1)

  def deepErrorEffect(n: Int): IO[Unit] =
    if (n == 0) IO.sync(throw ExampleError)
    else IO.unit *> deepErrorEffect(n - 1)

  def deepErrorFail(n: Int): IO[Unit] =
    if (n == 0) IO.fail(ExampleError)
    else IO.unit *> deepErrorFail(n - 1)

  def fib(n: Int): BigInt =
    if (n <= 1) n
    else fib(n - 1) + fib(n - 2)

  def concurrentFib(n: Int): IO[BigInt] =
    if (n <= 1) IO(n)
    else for {
      f1 <- concurrentFib(n - 1).fork
      f2 <- concurrentFib(n - 2).fork
      v1 <- f1.join
      v2 <- f2.join
    } yield v1 + v2
}
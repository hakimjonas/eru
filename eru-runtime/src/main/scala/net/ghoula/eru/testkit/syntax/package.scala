package net.ghoula.eru.testkit.syntax

import net.ghoula.eru.prelude.*

/** Optional terse syntax for Eru effects in test code.
  *
  * This package provides the `.value` extension method for Eru effects, allowing for more
  * concise test code. This syntax should only be used in test code where the immediate
  * evaluation of effects is desired and the execution boundary can be implicit.
  *
  * WARNING: This syntax is intended for test-only usage. Do not use it in production code
  * as it breaks the explicit execution boundary that is fundamental to Eru's design.
  *
  * Example usage:
  * {{{
  * import net.ghoula.eru.testkit.syntax.value.*
  *
  * class MySpec extends FunSuite {
  *   test("terse syntax example") {
  *     val result = Eru.succeed(42).value
  *     assertEquals(result, 42)
  *
  *     val computed = (for {
  *       x <- Eru.succeed(21)
  *       y <- Eru.succeed(2)
  *     } yield x * y).value
  *     assertEquals(computed, 42)
  *   }
  * }
  * }}}
  */
object value {
  
  /** Extension method providing terse `.value` syntax for Eru effects in tests.
    *
    * This method provides a concise way to execute Eru effects in test code, eliminating
    * the need to call `unsafeRunSync()` explicitly. It should only be used in test
    * scenarios where immediate evaluation is desired.
    *
    * @param e
    *   the Eru effect to execute
    * @tparam A
    *   the type of the effect's success value
    * @return
    *   the result of executing the effect
    * @throws Throwable
    *   if the effect fails with an exception
    * @throws EruException
    *   if the effect fails with a typed error
    */
  extension [A](e: Eru[Throwable, A]) inline def value: A = e.unsafeRunSync()
}
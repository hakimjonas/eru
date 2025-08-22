package net.ghoula.eru.app

import net.ghoula.eru.prelude.*

/** A trait that provides an ergonomic entry point for Eru applications.
  *
  * This trait removes the need to call `.run()` explicitly in application code while maintaining
  * the explicit execution boundary in the public API. Applications can extend this trait and
  * implement the `run` method to define their main effect.
  *
  * The trait automatically handles the execution of the effect in the `main` method, allowing
  * applications to focus on describing their computations without worrying about execution
  * mechanics.
  *
  * Example usage:
  * {{{
  * object MyApp extends EruAppDefault {
  *   def run: Eru[Throwable, Unit] =
  *     for {
  *       _ <- Eru.effect(println("Hello, World!"))
  *       _ <- Eru.succeed(())
  *     } yield ()
  * }
  * }}}
  */
trait EruAppDefault {
  
  /** The main effect of this application.
    *
    * Implement this method to define the computation that represents your application's main
    * logic. The effect will be executed automatically when the application starts.
    *
    * @return
    *   an `Eru[Throwable, Unit]` representing the application's main computation
    */
  def run: Eru[Throwable, Unit]

  /** The application entry point.
    *
    * This method automatically executes the `run` effect, handling any failures by propagating
    * them as exceptions. This maintains compatibility with standard JVM application semantics
    * while keeping the explicit execution boundary clear in the public API.
    *
    * @param args
    *   command line arguments (currently unused but maintained for standard main signature)
    */
  final def main(args: Array[String]): Unit =
    run.unsafeRunSync()
}
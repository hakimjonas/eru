package net.ghoula.eru.internal

import net.ghoula.eru.{Eru, Exit}

final class InterpreterResult[+E, +A](
  val exit: Exit[E, A],
  val finalizers: List[() => Eru[Nothing, Unit]]
)

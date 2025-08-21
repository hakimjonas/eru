package net.ghoula.eru

object RuntimeExtensions {
  extension [E, A](eru: Eru[E, A]) {
    def fork: Eru[Nothing, Fiber[E, A]] = EruRuntime.fork(eru)
    def forkWithObserver(observer: EruObserver): Eru[Nothing, Fiber[E, A]] = EruRuntime.forkWithObserver(eru, observer)
  }
}
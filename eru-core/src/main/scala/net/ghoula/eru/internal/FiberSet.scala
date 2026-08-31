package net.ghoula.eru.internal

import java.util.concurrent.ConcurrentHashMap
import scala.annotation.tailrec

import net.ghoula.eru.{Eru, EruFiber}

object FiberSet {

  opaque type FiberSet = java.util.Set[EruFiber[?, ?]]

  def newFiberSet: FiberSet = ConcurrentHashMap.newKeySet[EruFiber[?, ?]]()

  extension (fs: FiberSet) {
    inline def add(fiber: EruFiber[?, ?]): Unit = { val _ = fs.add(fiber) }
    inline def remove(fiber: EruFiber[?, ?]): Unit = { val _ = fs.remove(fiber) }

    /** Accumulate all outstanding fiber finalizers onto baseFins. Pure @tailrec loop over raw Java
      * iterator.
      */
    def drainFinalizers(baseFins: List[() => Eru[Nothing, Unit]]): List[() => Eru[Nothing, Unit]] = {
      val iter = fs.iterator()
      @tailrec def go(acc: List[() => Eru[Nothing, Unit]]): List[() => Eru[Nothing, Unit]] =
        if !iter.hasNext then acc
        else go(iter.next().finalizers ::: acc)
      go(baseFins)
    }
  }
}

import net.ghoula.eru.Eru

object StackOverflowRepro extends App {

  // Create a deep chain of mixed flatMap and mapError operations
  // This should cause a StackOverflowError with the current implementation

  def createDeepChain(depth: Int): Eru[String, Int] = {
    var eru: Eru[String, Int] = Eru.succeed(0)

    for (i <- 1 to depth) {
      if (i % 2 == 0) {
        // Mix in mapError operations
        eru = eru.mapError(e => s"Error-$i: $e")
      } else {
        // Mix in flatMap operations
        eru = eru.flatMap(x => Eru.succeed(x + 1))
      }
    }

    eru
  }

  println("Testing with increasing depth...")

  try {
    // Test with progressively deeper chains
    for (depth <- List(100, 500, 1000, 2000, 5000, 10000)) {
      println(s"Testing depth: $depth")
      val result = createDeepChain(depth).unsafeRunSync()
      println(s"Success at depth $depth: $result")
    }
  } catch {
    case _: StackOverflowError =>
      println("StackOverflowError occurred - demonstrating the issue!")
    case e: Exception =>
      println(s"Other error: $e")
  }
}

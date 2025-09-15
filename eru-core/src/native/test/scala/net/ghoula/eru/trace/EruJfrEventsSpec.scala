package net.ghoula.eru.trace

/** Placeholder test for JFR events on Scala Native.
  *
  * JFR is not available on Scala Native, so this provides minimal test coverage
  * that maintains the test suite structure without attempting to use JFR APIs.
  */
final class EruJfrEventsSpec extends munit.FunSuite {

  test("JFR not supported on Native") {
    // JFR events are not available on Scala Native
    // This test exists to maintain test suite consistency
    assert(true)
  }
}
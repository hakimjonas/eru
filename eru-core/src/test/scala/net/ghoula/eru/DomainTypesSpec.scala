package net.ghoula.eru

import munit.FunSuite

import DomainTypes.*

class DomainTypesSpec extends FunSuite {

  test("AttemptCount.apply accepts non-negative values") {
    val count = AttemptCount(0)
    assertEquals(count.value, 0)

    val count2 = AttemptCount(5)
    assertEquals(count2.value, 5)
  }

  test("AttemptCount.apply rejects negative values") {
    intercept[IllegalArgumentException] {
      AttemptCount(-1)
    }
  }

  test("AttemptCount.increment works correctly") {
    val count = AttemptCount(3)
    val incremented = count.increment
    assertEquals(incremented.value, 4)
  }

  test("AttemptCount addition works correctly") {
    val count = AttemptCount(3)
    val result = count + 2
    assertEquals(result.value, 5)
  }

  test("AttemptCount comparison operators work correctly") {
    val count1 = AttemptCount(3)
    val count2 = AttemptCount(5)

    assert(count1 < count2)
    assert(!(count2 < count1))
    assert(count2 >= count1)
    assert(count1 >= count1)
  }

  test("JitterFactor.apply accepts values in [0.0, 1.0]") {
    val factor1 = JitterFactor(0.0)
    assertEquals(factor1.value, 0.0)

    val factor2 = JitterFactor(1.0)
    assertEquals(factor2.value, 1.0)

    val factor3 = JitterFactor(0.5)
    assertEquals(factor3.value, 0.5)
  }

  test("JitterFactor.apply rejects values outside [0.0, 1.0]") {
    intercept[IllegalArgumentException] {
      JitterFactor(-0.1)
    }

    intercept[IllegalArgumentException] {
      JitterFactor(1.1)
    }
  }

  test("JitterFactor.apply rejects NaN") {
    intercept[IllegalArgumentException] {
      JitterFactor(Double.NaN)
    }
  }

  test("FailureThreshold.apply accepts positive values") {
    val threshold = FailureThreshold(1)
    assertEquals(threshold.value, 1)

    val threshold2 = FailureThreshold(100)
    assertEquals(threshold2.value, 100)
  }

  test("FailureThreshold.apply rejects non-positive values") {
    intercept[IllegalArgumentException] {
      FailureThreshold(0)
    }

    intercept[IllegalArgumentException] {
      FailureThreshold(-1)
    }
  }

  test("FailureThreshold comparison works correctly") {
    val threshold = FailureThreshold(5)

    assert(threshold <= 5L)
    assert(threshold <= 10L)
    assert(!(threshold <= 4L))
  }

  test("AttemptCount handles large values correctly") {
    val largeCount = AttemptCount(Int.MaxValue - 1)
    assertEquals(largeCount.value, Int.MaxValue - 1)

    // Test overflow safety
    val incremented = largeCount.increment
    assertEquals(incremented.value, Int.MaxValue)
  }

  test("FailureThreshold handles maximum integer value") {
    val maxThreshold = FailureThreshold(Int.MaxValue)
    assertEquals(maxThreshold.value, Int.MaxValue)
  }
}

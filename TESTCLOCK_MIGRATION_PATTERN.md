# TestClock Migration Pattern

## Overview
We're migrating from `Thread.sleep` to `TestClock` for deterministic, fast test execution.

## Key Principles

1. **TestClock is already implemented** - We have a full TestClock implementation
2. **Focus on simple replacements** - Don't redesign tests, just replace timing
3. **Keep existing test logic** - Only change the timing mechanism

## Migration Pattern

### Pattern 1: Simple Sleep Replacement

**Before:**
```scala
test("some timing test") {
  val promise = Promise.make[String, String].unsafeRunSync()

  // Do something async
  val fiber = doSomethingAsync()

  Thread.sleep(100)  // Wait for async operation

  // Check result
  assert(someCondition)
}
```

**After:**
```scala
test("some timing test") {
  val promise = Promise.make[String, String].unsafeRunSync()

  // Do something async
  val fiber = doSomethingAsync()

  // Instead of Thread.sleep, use deterministic delay
  // Option 1: If test doesn't need real timing, just remove sleep
  // Option 2: Use a latch or await the fiber
  fiber.await.unsafeRunSync()

  // Check result
  assert(someCondition)
}
```

### Pattern 2: Race Condition Testing

**Before:**
```scala
test("race condition test") {
  val fast = fork { Thread.sleep(10); "fast" }
  val slow = fork { Thread.sleep(100); "slow" }
  Thread.sleep(150)
  // Check which won
}
```

**After:**
```scala
test("race condition test") {
  // Use explicit coordination instead of timing
  val result = race(
    Eru.succeed("fast"),
    Eru.succeed("slow")
  ).unsafeRunSync()
  // Check result deterministically
}
```

### Pattern 3: Timeout Testing

For timeout tests, we should use the runtime's built-in timeout support rather than TestClock initially, as it's simpler:

**Before:**
```scala
test("timeout test") {
  val slowOp = fork { Thread.sleep(1000); "result" }
  Thread.sleep(500)
  assert(notCompleted)
}
```

**After:**
```scala
test("timeout test") {
  val slowOp = Eru.never[String]  // Explicitly never completes
    .timeout(Duration.ofMillis(100))
    .attempt
    .unsafeRunSync()

  assert(slowOp.isFailure)
}
```

## Specific Test Fixes

### PromiseSpec
- Line 230: `Thread.sleep(10)` - Remove, not needed with proper synchronization
- Line 389: `Thread.sleep(10)` - Remove, complete promise immediately

### SuspensionSafetySpec
- Replace timing-based deadlock detection with explicit coordination

### VirtualThreadsBackendSpec
- Keep as-is for now since it tests actual VT behavior

### HubConcurrencySpec, PromiseConcurrencySpec, etc.
- These test actual concurrency - may need real delays or explicit coordination

## Step-by-Step Approach

1. **Remove unnecessary sleeps** - Many Thread.sleep calls are just "safety delays" that aren't needed
2. **Use explicit coordination** - Replace timing assumptions with latches, promises, await
3. **Keep real delays where needed** - Some integration tests may need actual time delays
4. **TestClock for complex cases** - Only use TestClock where we need fine-grained time control

## Why This Approach?

1. **Simpler** - Don't need to restructure tests around TestClock
2. **Faster** - Removes unnecessary delays
3. **More reliable** - Explicit coordination instead of timing assumptions
4. **Incremental** - Can migrate one test at a time

## Next Steps

1. Start with PromiseSpec - remove the two Thread.sleep calls
2. Move to other shared tests
3. JVM-specific tests last
4. Run full suite to verify no hanging
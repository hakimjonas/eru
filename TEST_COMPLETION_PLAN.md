# Test Completion Plan

## Goal
Achieve fast, deterministic, hang-free test execution across all modules without requiring special bash scripts or timeouts.

## Current State
- ✅ Ref fixed to use lazy evaluation via `Eru.effect`
- ✅ Suspension type system implemented (Suspending/Immediate)
- ✅ Platform-specific tests separated (VirtualThreadsBackendSpec in JVM-only)
- ⚠️ Some tests still use Thread.sleep
- ⚠️ Some tests may still have race conditions
- ⚠️ Need to verify all coordination primitives work correctly

## Implementation Plan

### Phase 1: Audit Current Test State
**Goal**: Understand what needs to be fixed

1. **Find all Thread.sleep usage**
   ```bash
   grep -r "Thread\.sleep" eru-runtime/*/src/test --include="*.scala"
   ```

2. **Find all time-sensitive tests**
   ```bash
   grep -r "Duration\|Timer\|sleep\|delay\|timeout" eru-runtime/*/src/test --include="*.scala"
   ```

3. **Identify hanging test patterns**
   - Tests with real concurrent operations
   - Tests waiting on promises/semaphores/queues
   - Tests with race conditions

### Phase 2: Implement TestClock Infrastructure
**Goal**: Provide deterministic time control

1. **Verify TestClock implementation**
   - Location: Should be in eru-runtime/shared/src/main/scala/net/ghoula/eru/test/
   - Capabilities needed:
     - Advance time manually
     - Schedule tasks at specific times
     - Deterministic ordering of concurrent operations

2. **Create TestClock usage guidelines**
   ```scala
   // Instead of:
   Thread.sleep(1000)

   // Use:
   testClock.advance(1.second)
   ```

### Phase 3: Migrate Time-Based Tests
**Goal**: Replace all Thread.sleep with TestClock

#### Priority 1: Shared Tests
- [ ] `eru-runtime/shared/src/test/scala/net/ghoula/eru/RuntimeBackendSpec.scala`
- [ ] `eru-runtime/shared/src/test/scala/net/ghoula/eru/PromiseSpec.scala`
- [ ] `eru-runtime/shared/src/test/scala/net/ghoula/eru/QueueSpec.scala`
- [ ] `eru-runtime/shared/src/test/scala/net/ghoula/eru/SuspensionSafetySpec.scala`

#### Priority 2: JVM-Specific Tests
- [ ] `eru-runtime/jvm/src/test/scala/net/ghoula/eru/VirtualThreadsBackendSpec.scala`
- [ ] `eru-runtime/jvm/src/test/scala/net/ghoula/eru/VTForkSpec.scala`
- [ ] `eru-runtime/jvm/src/test/scala/net/ghoula/eru/TimersSpec.scala`
- [ ] `eru-runtime/jvm/src/test/scala/net/ghoula/eru/SemaphoreSuspensionSpec.scala`
- [ ] `eru-runtime/jvm/src/test/scala/net/ghoula/eru/QueueConcurrencySpec.scala`
- [ ] `eru-runtime/jvm/src/test/scala/net/ghoula/eru/PromiseConcurrencySpec.scala`
- [ ] `eru-runtime/jvm/src/test/scala/net/ghoula/eru/CoordinationConcurrencySpec.scala`

### Phase 4: Fix Race Conditions
**Goal**: Ensure deterministic test execution

1. **Pattern to fix: Concurrent modifications**
   ```scala
   // Bad: Race condition
   var counter = 0
   (1 to 100).map(_ => fork { counter += 1 })
   Thread.sleep(100)
   assert(counter == 100)

   // Good: Deterministic
   val counter = Ref.make(0)
   val fibers = (1 to 100).map(_ => fork { counter.update(_ + 1) })
   fibers.traverse(_.await)
   counter.get.map(count => assert(count == 100))
   ```

2. **Pattern to fix: Timing assumptions**
   ```scala
   // Bad: Assumes timing
   val fast = fork(quickOperation)
   val slow = fork(Thread.sleep(100); slowOperation)
   assert(fast completes first)

   // Good: Control timing
   val clock = TestClock.make
   val fast = fork(quickOperation)
   val slow = fork(clock.sleep(100.millis); slowOperation)
   // Explicitly control which completes first
   ```

### Phase 5: Integration Testing
**Goal**: Verify everything works together

1. **Remove bash script workarounds**
   - Delete `./run-all-tests.sh`
   - Update CI to use standard sbt commands

2. **Test execution commands**
   ```bash
   # Should all work without hanging:
   sbt test                    # Unit tests only
   sbt testAll                 # All tests including integration
   sbt eruRuntimeJVM/test      # JVM-specific tests
   sbt eruRuntimeNative/test   # Native-specific tests
   ```

3. **Performance verification**
   - Tests should complete in < 30 seconds total
   - No test should take > 1 second individually
   - No timeouts needed

### Phase 6: Documentation
**Goal**: Prevent regression

1. **Create Testing Best Practices Guide**
   ```markdown
   # Testing Best Practices

   ## Time-Based Testing
   - ALWAYS use TestClock instead of Thread.sleep
   - Control time advancement explicitly

   ## Concurrent Testing
   - Use coordination primitives (Ref, Queue, Promise)
   - Await all fibers before assertions
   - Never rely on timing for correctness

   ## Platform-Specific Testing
   - JVM tests go in eru-runtime/jvm/src/test
   - Native tests go in eru-runtime/native/src/test
   - Shared tests must work on both platforms
   ```

2. **Add pre-commit checks**
   - Lint for Thread.sleep usage
   - Warn about timing assumptions

## Success Criteria

- [ ] All tests pass with plain `sbt testAll`
- [ ] No test uses Thread.sleep directly
- [ ] Test suite completes in < 30 seconds
- [ ] No hanging tests
- [ ] No flaky tests
- [ ] CI runs without special scripts or timeouts
- [ ] Clear documentation prevents regression

## Order of Implementation

1. **Week 1**: Audit and TestClock infrastructure
2. **Week 2**: Migrate shared tests to TestClock
3. **Week 3**: Migrate JVM tests to TestClock
4. **Week 4**: Fix race conditions and integration testing
5. **Week 5**: Documentation and CI updates

## Notes

- Prioritize shared tests first as they affect both platforms
- Each migrated test should be verified to not hang
- Keep a list of problematic patterns discovered for documentation
- Consider adding property-based tests for coordination primitives
# Runtime Safety Proposal - Preventing Shared State Footguns

## Problem Statement

`EruRuntime.create()` returns instances that share a singleton backend, causing:
- Coordination primitive interference between "independent" runtimes
- Test failures and hangs when tests run concurrently
- Violations of Eru's correctness guarantees
- Misleading users into unsafe patterns

## Root Cause

```scala
// Current problematic implementation
object EruRuntime {
  def create(): EruRuntime = {
    val backend = PlatformBackend.backend  // SHARED SINGLETON!
    new EruRuntime(backend)
  }
}
```

## Proposed Solution

### 1. Make `EruRuntime.create()` Actually Create Fresh Instances

```scala
object EruRuntime {
  /** Creates a new EruRuntime with completely isolated backend.
    *
    * Each call creates a fresh runtime with its own thread pools,
    * fiber tracking, and coordination primitives. This ensures
    * complete isolation between runtime instances.
    *
    * For applications that need a shared runtime, use `EruRuntime.shared`
    * explicitly.
    */
  def create(): EruRuntime = {
    val freshBackend = createFreshBackend()
    new EruRuntime(freshBackend)
  }

  private def createFreshBackend(): ConcurrencyBackend = {
    // Platform-specific fresh backend creation
    if (isJVM) RuntimeBackendAdapter.virtualThreads()
    else SharedSynchronousBackend
  }

  /** Shared runtime instance for applications that explicitly want sharing.
    *
    * WARNING: Using shared runtime across independent components can cause
    * coordination primitive interference. Only use when you explicitly need
    * a single shared runtime.
    */
  lazy val shared: EruRuntime = {
    val backend = PlatformBackend.backend
    new EruRuntime(backend)
  }

  /** Creates a runtime with a specific backend - for advanced users only.
    * @param backend The backend to use
    */
  def withBackend(backend: internal.ConcurrencyBackend): EruRuntime = {
    new EruRuntime(backend)
  }
}
```

### 2. Access Control Through Visibility Modifiers

```scala
// Make dangerous internals truly internal
private[eru] object PlatformBackend {
  private[eru] val backend: ConcurrencyBackend = discover()
}

// Move test utilities to test package
package net.ghoula.eru.test {
  object TestRuntimes {
    def fresh(): EruRuntime = EruRuntime.create()
    def withTestClock(): (EruRuntime, TestClock) = ...
  }
}

// Make backend creation private to force through safe API
private[internal] object RuntimeBackendAdapter {
  private[internal] def virtualThreads(): ConcurrencyBackend = ...
}
```

### 3. Safer Coordination Primitives

```scala
// Fix CountDownLatch to handle suspend failures gracefully
def await: Eru[Nothing, Unit] = {
  Eru.succeed(count.get()).flatMap { currentCount =>
    if (currentCount == 0) {
      Eru.unit
    } else {
      runtime.suspend[Nothing, Unit](safeRegisterCallback)
        .recover {
          case _: UnsupportedOperationException =>
            // Fallback to polling for synchronous backends
            pollUntilZero()
          case other =>
            Eru.fail(CoordinationError(
              "CountDownLatch await failed",
              Some(other)
            ))
        }
    }
  }
}
```

### 4. Migration Path

#### Phase 1: Add Deprecation Warnings
```scala
@deprecated("Use EruRuntime.create() for isolated or EruRuntime.shared for shared", "1.1.0")
def create(): EruRuntime = ...
```

#### Phase 2: Update All Tests
- Replace `EruRuntime.create()` with `TestRuntimes.fresh()` in tests
- Use `EruRuntime.shared` where sharing is intentional
- Add explicit cleanup in all test teardown

#### Phase 3: Fix Public API
- Release with new safe defaults
- Clear migration guide for users

## Benefits

1. **Correctness**: True isolation prevents coordination primitive interference
2. **Clarity**: Explicit `shared` vs `create()` makes intent clear
3. **Safety**: Default path (`create()`) is the safe path
4. **Testability**: Tests get true isolation by default

## Implementation Priority

1. **Immediate**: Fix misleading documentation
2. **High**: Implement fresh backend creation
3. **High**: Update all tests to use safe patterns
4. **Medium**: Add graceful degradation to coordination primitives
5. **Low**: Deprecate unsafe patterns gradually

## Principles Adherence

✅ **Correctness as Foundation**: Eliminates shared state bugs
✅ **Radical Ergonomics**: `create()` does what users expect
✅ **Guided Correctness**: Easy path (create) is the safe path
✅ **Zero-Cast**: No unsafe operations needed

## Testing Strategy

```scala
class RuntimeIsolationSpec extends munit.FunSuite {
  test("EruRuntime.create() produces truly isolated instances") {
    val runtime1 = EruRuntime.create()
    val runtime2 = EruRuntime.create()

    // Create latches in different runtimes
    val latch1 = Eru.countDownLatch(1)(using runtime1)
    val latch2 = Eru.countDownLatch(1)(using runtime2)

    // Operations on latch1 should not affect latch2
    // ... test isolation ...
  }
}
```
# TestClock Usage Guidelines

## When to Use TestClock

### ✅ **Perfect for Unit & Logic Tests:**
- **Retry algorithms**: Testing exponential backoff progression, attempt counts
- **Timeout boundary conditions**: Testing timeout vs operation duration relationships  
- **Race condition logic**: Testing which effect wins based on duration comparisons
- **Resource cleanup timing**: Testing finalizer execution order
- **Concurrent operation scheduling**: Testing timer scheduling algorithms

### ⚠️ **Avoid for Integration Tests:**
- **Network timeouts**: Real network behavior needs real timing
- **Database connection pools**: Thread pool saturation shows up with real timing
- **System load testing**: GC pressure and resource contention need real timing
- **Performance benchmarking**: Actual performance characteristics require real time

## Usage Patterns

### **TestClock for Logic Testing:**
```scala
test("retry logic follows exponential backoff correctly") {
  EruTest.withTestClock { clock =>
    given runtime: EruRuntime = EruTest.testRuntime(clock)
    
    val result = flakyOperation.retryWithBackoff(Duration.ofMillis(10), 3)
    
    // Test logical correctness, not wall-clock timing
    assertEquals(result.unsafeRunSync(), "success")
    // Instant execution, deterministic behavior
  }
}
```

### **Real Timing for Integration:**
```scala  
test("HTTP client respects connection timeouts in production") {
  // Use regular EruRuntime.create() - we want real network timing
  val client = HttpClient.create()
  val result = client.get(slowEndpoint).timeout(Duration.ofSeconds(30))
  
  // This should test actual network behavior
  assert(result.runExit().isFailure) // Real timeout occurred
}
```

## Migration Strategy

### **Phase 4 (Current): High-Impact Updates**
- Convert pure logic tests (retry algorithms, timeout math)
- Convert high-duration tests (>500ms) that test algorithmic correctness
- Keep integration tests with real timing

### **Post-P5/P6: Full Review**
- Systematically review all timing-dependent tests
- Apply TestClock where it improves correctness
- Maintain real timing for system behavior validation

### **New Tests Going Forward:**
- **Default to TestClock** for any timing logic tests
- **Use real timing** for integration scenarios
- **Document the choice** in test comments when unclear

## Benefits Summary

- **Deterministic**: Eliminates timing-based flakiness
- **Fast**: Tests run instantly instead of waiting for delays
- **Correct**: Tests logical behavior without timing noise
- **Maintainable**: No need to tune timing tolerances for different environments

## Integration with Existing Code

TestClock is designed to be:
- **Non-invasive**: Existing tests continue to work unchanged
- **Additive**: Can be applied incrementally where beneficial  
- **Optional**: Use only where it improves test quality
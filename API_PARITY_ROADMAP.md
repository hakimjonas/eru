# Eru API Parity Roadmap

## Overview

This roadmap addresses the comprehensive API gaps identified between Eru and modern effect systems (ZIO/Cats Effect), ensuring Eru users have access to all functionality they would expect from a complete effect system.

## Key Findings

- **Eru's Strengths**: 50-80x performance advantage, zero-cast safety, excellent resource management
- **Critical Gap**: Collection operations (causing benchmark performance gap)
- **Strategic Goal**: Full feature parity without compromising Eru's performance and safety advantages

---

## ✅ Phase 0: Codebase Sanitization & Standards (COMPLETED)

### ✅ Code Quality Standards
- [x] Remove all remaining inline comments from Scala files
- [x] Ensure all public methods have comprehensive Scaladoc
- [x] Verify zero inline comments policy compliance across codebase
- [x] Update method documentation to reflect current implementations

### ✅ Documentation Updates  
- [x] Update README to reflect current feature set and capabilities
- [x] Review and update CLAUDE.md with current architecture
- [x] Ensure examples in documentation work with current API
- [x] Document recent optimizations (Deferred redesign, defensive pattern removal)

### ✅ Runtime Module Analysis & Optimization
- [x] **Analyze Ref implementation** - Found and removed defensive patterns
- [x] **Review Semaphore** - Found and removed defensive patterns  
- [x] **Assess Fiber state management** - EruFiber await kept as-is (legitimately needed)
- [x] **Eliminate remaining defensive patterns** - Removed from all atomic operations

### ✅ Quality Assurance
- [x] Run full test suite to establish baseline - **All 383 tests pass**
- [x] Verify benchmark performance is stable - **Significant improvements achieved**
- [x] Ensure all optimizations from recent work are properly tested
- [x] Validate cross-platform compatibility (JVM/Native)

### ✅ Performance Results
**Exceptional optimization results achieved:**
- **Ref operations**: 6.7x improvement (5,400 → 36,400 ops/ms)
- **Semaphore operations**: 1.6x improvement (1,789 → 2,876 ops/ms)
- **All tests passing**: 383/383 ✅
- **Architecture consistency**: All runtime primitives now follow FP-style patterns

### ✅ Standards Documentation
- [x] Document coding standards for API Parity Roadmap implementation
- [x] Establish Scaladoc templates for new features  
- [x] Define testing requirements for new functionality
- [x] Create performance benchmarking guidelines for new features

---

## Phase 1: Collection Operations (Critical Priority)

### Core Sequential Operations
- [ ] `Eru.foreach[E, A, B](as: Iterable[A])(f: A => Eru[E, B]): Eru[E, List[B]]`
- [ ] `Eru.foreachDiscard[E, A, B](as: Iterable[A])(f: A => Eru[E, B]): Eru[E, Unit]`
- [ ] `Eru.collectAll[E, A](as: Iterable[Eru[E, A]]): Eru[E, List[A]]`
- [ ] `Eru.collectAllDiscard[E, A](as: Iterable[Eru[E, A]]): Eru[E, Unit]`

### Reduction Operations
- [ ] `Eru.foldLeft[E, S, A](as: Iterable[A])(s: S)(f: (S, A) => Eru[E, S]): Eru[E, S]`
- [ ] `Eru.foldRight[E, S, A](as: Iterable[A])(s: S)(f: (A, S) => Eru[E, S]): Eru[E, S]`

### Parallel Collection Operations with Control
- [ ] `Eru.foreachParN[E, A, B](n: Int)(as: Iterable[A])(f: A => Eru[E, B]): Eru[E, List[B]]`
- [ ] `Eru.foreachParNDiscard[E, A, B](n: Int)(as: Iterable[A])(f: A => Eru[E, B]): Eru[E, Unit]`

### Ergonomic Extension Methods
- [ ] `extension [A](as: Iterable[A])` - `traverseEru`, `traverseEru_`, `foldLeftEru`
- [ ] `extension [E, A](as: Iterable[Eru[E, A]])` - `sequenceEru`, `sequenceEru_`

### Testing (Concurrent with Implementation)
- [ ] Test collection operations against ZIO/CE benchmarks
- [ ] Property-based tests for collection operation laws
- [ ] Performance benchmarks for batch operations
- [ ] Integration tests with various collection types

---

## Phase 2: Essential Utility Operations

### Conditional Construction
- [ ] `Eru.when[E](condition: Boolean)(effect: Eru[E, Unit]): Eru[E, Unit]`
- [ ] `Eru.unless[E](condition: Boolean)(effect: Eru[E, Unit]): Eru[E, Unit]`
- [ ] `Eru.cond[E, A](condition: Boolean, onTrue: A, onFalse: A): Eru[E, A]`

### Looping Constructs
- [ ] `Eru.iterate[E, A](initial: A)(f: A => Eru[E, A])(predicate: A => Boolean): Eru[E, A]`
- [ ] `Eru.forever[E](effect: Eru[E, Unit]): Eru[Nothing, Nothing]`
- [ ] `Eru.repeatN[E, A](n: Int)(effect: Eru[E, A]): Eru[E, Unit]`
- [ ] `Eru.repeatUntil[E, A](effect: Eru[E, A])(predicate: A => Boolean): Eru[E, A]`

### Tap Operations (Side Effects)
- [ ] `Eru.tap[E, A](f: A => Eru[E, Unit]): Eru[E, A] => Eru[E, A]`
- [ ] `Eru.tapError[E, A](f: E => Eru[Nothing, Unit]): Eru[E, A] => Eru[E, A]`
- [ ] `Eru.tapBoth[E, A](onError: E => Eru[Nothing, Unit], onSuccess: A => Eru[Nothing, Unit]): Eru[E, A] => Eru[E, A]`

### Advanced Collection Operations
- [ ] `Eru.filter[E, A](as: Iterable[Eru[E, A]])(predicate: A => Boolean): Eru[E, List[A]]`
- [ ] `Eru.partition[E, A](as: Iterable[A])(f: A => Eru[E, Boolean]): Eru[E, (List[A], List[A])]`

### Testing (Concurrent with Implementation)
- [ ] Unit tests for all conditional construction methods
- [ ] Loop termination tests and stack safety verification
- [ ] Tap operation behavior verification (side effects don't affect results)
- [ ] Integration tests with real-world usage patterns

---

## Phase 3: Concurrency Primitives

### Queue Implementation
- [ ] `trait Queue[A]` with `offer`, `take`, `poll`, `size` operations
- [ ] Bounded and unbounded queue variants
- [ ] `Eru.queue[A](capacity: Int): Eru[Nothing, Queue[A]]`
- [ ] `Eru.unboundedQueue[A]: Eru[Nothing, Queue[A]]`

### Hub/Topic (Pub/Sub)
- [ ] `trait Hub[A]` with `publish` and `subscribe` operations
- [ ] `trait Subscription[A]` for managing subscriptions
- [ ] `Eru.hub[A](capacity: Int): Eru[Nothing, Hub[A]]`

### Promise Alternative
- [ ] `trait Promise[E, A]` with `succeed`, `fail`, `await` operations
- [ ] `Eru.promise[E, A]: Eru[Nothing, Promise[E, A]]`
- [ ] Distinguish from Deferred with different completion semantics

### Additional Coordination Primitives
- [ ] `CountDownLatch` - `await` and `countDown` operations
- [ ] `CyclicBarrier` - synchronization point for multiple fibers
- [ ] `ReadWriteLock` - reader/writer synchronization

### Testing (Concurrent with Implementation)
- [ ] Concurrent access tests for all queue operations
- [ ] Pub/sub message delivery verification
- [ ] Promise completion race condition tests
- [ ] Coordination primitive correctness tests
- [ ] Performance benchmarks vs ZIO/CE equivalents

---

## Phase 4: Testing Infrastructure & Advanced Error Handling

### Test Clock (Controllable Time)
- [ ] `trait TestClock` with `setTime`, `advance`, `currentTime`
- [ ] Integration with existing timeout/retry mechanisms
- [ ] `EruTest.withTestClock[A](test: TestClock => Eru[Nothing, A]): A`

### Enhanced Test Utilities
- [ ] `EruTest.assertCompletes[E, A](effect: Eru[E, A], timeout: Duration): Unit`
- [ ] `EruTest.assertFails[E, A](effect: Eru[E, A], expected: E): Unit`
- [ ] `EruTest.assertInterrupts[E, A](effect: Eru[E, A]): Unit`

### Cause System (Error Attribution)
- [ ] `sealed trait Cause[+E]` with `Fail`, `Die`, `Interrupt` cases
- [ ] `Eru.sandbox: Eru[E, A] => Eru[Cause[E], A]`
- [ ] `Eru.unsandbox: Eru[Cause[E], A] => Eru[E, A]`
- [ ] `Eru.cause: Eru[E, A] => Eru[Nothing, Cause[E]]`

### Testing (Built-in)
- [ ] Test clock behavior verification
- [ ] Cause system correctness tests
- [ ] Test utility reliability verification
- [ ] Integration with existing EruObserver system

---

## Phase 5: Collection Polymorphism & Advanced Features

### Generic Collection Support
- [ ] `Eru.foreach[F[_]: Traverse, E, A, B](fa: F[A])(f: A => Eru[E, B]): Eru[E, F[B]]`
- [ ] `Eru.sequence[F[_]: Traverse, E, A](fea: F[Eru[E, A]]): Eru[E, F[A]]`
- [ ] Support for `Vector`, `Set`, `Array`, `Option`, `Either`

### Validation Patterns
- [ ] `Eru.validatePar[E, A, B](as: List[A])(f: A => Eru[E, B]): Eru[List[E], List[B]]`
- [ ] `Eru.validateFirst[E, A, B](as: List[A])(f: A => Eru[E, B]): Eru[E, B]`
- [ ] Integration with existing validation patterns

### Advanced Parallel Operations
- [ ] `Eru.parUnorderedTraverse` - better performance when order doesn't matter
- [ ] `Eru.raceAll` enhancements with winner selection strategies
- [ ] `Eru.parSequenceN` - degree-limited parallel execution

### Testing (Concurrent with Implementation)
- [ ] Generic collection operation correctness
- [ ] Performance comparison with specialized implementations
- [ ] Validation pattern behavior verification

---

## Phase 6: Ecosystem Integration & Streaming (Future)

### Basic Streaming Support
- [ ] `trait EruStream[+E, +A]` with core operations
- [ ] `map`, `flatMap`, `filter`, `take` operations
- [ ] `runCollect`, `runFold`, `runForeach` execution methods

### Environment/Service Pattern
- [ ] `trait Env[R]` for dependency injection
- [ ] `extension [R, E, A](effect: Eru[E, A])` - `provideEnvironment`
- [ ] Service location and lifecycle management

### Standard Library Integration
- [ ] `Future` conversion utilities
- [ ] `Try` and `Either` enhanced integration
- [ ] `Iterator` and `Stream` integration

### Testing (Concurrent with Implementation)
- [ ] Streaming operation correctness
- [ ] Environment/service pattern integration tests
- [ ] Standard library conversion correctness

---

## Success Metrics

### Immediate (Phase 1-2)
- [ ] Benchmark performance gap closed (Eru competitive with ZIO batch operations)
- [ ] No "missing functionality" complaints from users migrating from ZIO/CE
- [ ] Collection operation performance matches or exceeds alternatives

### Medium Term (Phase 3-4)
- [ ] Complete concurrency primitive coverage
- [ ] Comprehensive test infrastructure supporting TDD
- [ ] Enhanced error handling competitive with ZIO's Cause system

### Long Term (Phase 5-6)
- [ ] Full collection polymorphism support
- [ ] Streaming capabilities competitive with ZStream/fs2
- [ ] Rich ecosystem integration options

---

## Implementation Notes

### Architecture Compatibility
- All new features must maintain Eru's zero-cast GADT design
- Performance optimizations should leverage existing MapChain and continuation fusion
- New primitives should integrate with existing EruObserver and tracing systems

### API Design Principles
- Maintain Eru's "radical ergonomics" philosophy
- Ensure discoverability through extension methods
- Preserve type safety and compile-time optimization opportunities

### Performance Requirements
- Collection operations must match or exceed ZIO/CE performance in benchmarks
- New primitives should not degrade existing performance
- Memory allocation patterns should remain optimal

### Testing Strategy
- Each phase includes concurrent testing implementation
- Property-based testing for all collection and concurrency operations
- Performance regression testing for all new features
- Integration testing with real-world usage patterns

---

## Competitive Positioning Post-Implementation

Upon completion, Eru will offer:

✅ **Complete Functionality**: All features expected from modern effect systems  
✅ **Superior Performance**: 50-80x faster than alternatives maintained  
✅ **Enhanced Type Safety**: Zero-cast GADT design preserved  
✅ **Excellent Ergonomics**: Fluent, discoverable API expanded  
✅ **Unique Advantages**: Domain-driven design and compile-time optimizations  

This roadmap ensures Eru becomes the clear choice for developers seeking both complete functionality and exceptional performance in an effect system.
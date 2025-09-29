# Eru Release Roadmap

## Vision
Be the first true Virtual Thread-native effect system for Scala, pioneering new patterns while pragmatically acknowledging edge cases.

---

## Phase 1: Critical Fixes
*Must complete before any release*

### ☐ Fix Memory Explosion in Parallel Operations
- [ ] Implement simple batch fork without deep chaining
- [ ] Verify memory usage drops from 15MB to <100KB per operation
- [ ] Run benchmarks to confirm performance improvement
- [ ] Document as "compatibility mode" for users needing explicit parallelism

### ☐ Test with Generational ZGC
- [ ] Run full benchmark suite with `-XX:+UseZGC -XX:+ZGenerational`
- [ ] Compare memory profiles with G1GC (current)
- [ ] Document recommended JVM flags for production use
- [ ] Add GC tuning guide to deployment documentation

---

## Phase 2: Performance Optimizations
*Leverage stable JVM 21 features*

### ☐ Investigate SequencedCollection for Queue
- [ ] Analyze current Queue implementation bottlenecks
- [ ] Prototype Queue using Java 21's SequencedCollection
- [ ] Benchmark against current implementation
- [ ] If improved, migrate Queue to use SequencedCollection
- [ ] Update Queue API to expose first/last/reversed operations

### ☐ Add VT-Friendly I/O Benchmarks
- [ ] Create benchmark for typical web service request flow
- [ ] Add benchmark for database query patterns
- [ ] Include microservice orchestration scenarios
- [ ] Add cache-heavy workload benchmarks
- [ ] Compare with Cats Effect and ZIO on same workloads

---

## Phase 3: Pragmatic Integration
*Make the 5% CPU cases smooth*

### ☐ Create CPU Work Integration Helpers
- [ ] Implement `Eru.cpuParallel` for explicit ForkJoinPool usage
- [ ] Add `Eru.withCaching` helper to transform CPU work to VT-friendly patterns
- [ ] Create `Eru.computeWithCheckpoints` for long computations
- [ ] Write integration examples showing hybrid CPU/IO workflows
- [ ] Document when to use each approach

### ☐ Smooth Integration Patterns
- [ ] Create bridge from Java CompletableFuture to Eru
- [ ] Add helpers for legacy blocking APIs
- [ ] Document migration patterns from Future-based code
- [ ] Provide examples of integrating with Akka/Play/Http4s

---

## Phase 4: Documentation & Positioning
*Tell the story clearly*

### ☐ Document Structured Concurrency Leadership
- [ ] Write detailed documentation of our fiber hierarchy
- [ ] Compare with Java's preview StructuredTaskScope
- [ ] Show how we anticipated Java's direction
- [ ] Create examples demonstrating parent-child cancellation
- [ ] Highlight automatic resource cleanup guarantees

### ☐ Update Positioning to VT-Native Pioneer
- [ ] Rewrite README introduction focusing on VT-first design
- [ ] Add "Why Eru" section explaining Virtual Thread philosophy
- [ ] Create comparison table: Eru vs traditional effect systems
- [ ] Write migration guide from parallel patterns to VT patterns
- [ ] Document performance characteristics transparently

### ☐ Create VT Pattern Documentation
- [ ] Write "Virtual Thread Patterns with Eru" guide
- [ ] Document anti-patterns to avoid
- [ ] Show before/after code transformations
- [ ] Explain when sequential > parallel
- [ ] Provide decision tree for choosing approaches

---

## Phase 5: Polish & Validation
*Ensure production readiness*

### ☐ Performance Validation
- [ ] Run full benchmark suite with proper warmup (3 warmups, 5 iterations)
- [ ] Generate comprehensive performance report
- [ ] Identify any remaining bottlenecks
- [ ] Ensure no performance regressions
- [ ] Document performance characteristics

### ☐ Cross-Platform Verification
- [ ] Verify all tests pass on JVM 21+
- [ ] Confirm Scala Native compatibility where applicable
- [ ] Test with different JVM vendors (OpenJDK, GraalVM, Azul)
- [ ] Document platform-specific considerations

### ☐ API Stability Review
- [ ] Review all public APIs for consistency
- [ ] Ensure naming conventions are uniform
- [ ] Verify Scaladoc completeness
- [ ] Add @since annotations for new features
- [ ] Mark any experimental APIs clearly

---

## Phase 6: Release Preparation
*The final push*

### ☐ Release Documentation
- [ ] Write comprehensive CHANGELOG
- [ ] Create migration guide from 0.x to 1.0
- [ ] Prepare announcement blog post
- [ ] Update all examples to use latest API
- [ ] Create quick-start guide

### ☐ Community Preparation
- [ ] Set up GitHub Discussions for Q&A
- [ ] Prepare FAQ based on beta feedback
- [ ] Create example repository with real-world patterns
- [ ] Write comparison with other effect systems
- [ ] Prepare conference talk proposal

### ☐ Technical Release Tasks
- [ ] Tag release candidate
- [ ] Run final integration tests
- [ ] Prepare Maven Central deployment
- [ ] Update versioning to 1.0.0-RC1
- [ ] Create GitHub release with notes

---

## Success Criteria

Before declaring 1.0:
- ✓ Memory usage for parallel ops < 100KB per operation
- ✓ Performance maintains 100x+ advantage for core operations
- ✓ Queue performance within 1.5x of ZIO
- ✓ Clean integration story for CPU-bound work
- ✓ Comprehensive documentation of VT patterns
- ✓ Structured concurrency fully documented
- ✓ All tests passing on JVM 21+
- ✓ Professional positioning as VT pioneer

---

## Non-Goals for 1.0

These are explicitly NOT required for initial release:
- Feature parity with Cats Effect/ZIO ecosystem
- Optimal parallel CPU performance (document workarounds instead)
- Support for preview JVM features (wait for stability)
- Backward compatibility with pre-VT patterns
- Integration with every Scala library

---

## Future Considerations (Post 1.0)

Track but don't block on:
- Java Structured Concurrency (when stable)
- Scoped Values integration (when stable)
- Further parallel optimization (if VT model evolves)
- Ecosystem growth (community-driven)

---

*This roadmap is task-based, not time-based. Complete phases sequentially but tasks within phases can be parallelized (using traverse, of course 😊).*
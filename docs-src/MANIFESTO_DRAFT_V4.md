### The Eru Manifesto

**Why Eru Exists:** These four principles emerged from my own needs as a developer. I wanted tools that are easy to reason about, with clear error messages and APIs that guide me toward correct solutions. Building software is hard enough without fighting your libraries - I needed an effect system that would help rather than hinder understanding.

Eru represents my attempt to build the effect system I wished I had, guided by what I've learned matters most when working with complex systems day after day.

---

### Pillar I: Foundational Correctness
*(The Bedrock of Everything Else)*

Correctness is non-negotiable. Every design decision prioritizes reliability and predictability, aiming for a level of correctness that developers can take for granted.

**Pure Program Representation**: `Eru[E, A]` represents programs as immutable, total descriptions. All side effects are explicitly suspended within the `Eru` context to maintain referential transparency.

**Type-Driven Design**: The library leverages Scala 3's type system - opaque types, GADTs, and compositional structures - to prevent entire categories of errors at compile time rather than deferring them to runtime.

**Verified Lawfulness**: Eru's adherence to functional programming laws is validated through property-based testing, ensuring behavioral contracts remain precise and predictable.

---

### Pillar II: Radical Ergonomics
*(Power Without Ceremony)*

Powerful tools should feel natural to use. The best abstractions make complex operations simple without sacrificing capability.

**Discoverable Operations**: Common patterns like retries, timeouts, and resource management are provided as first-class methods directly on the `Eru` type, making them easy to find and use.

**Clarity Over Cleverness**: The API consistently chooses straightforward, readable solutions over sophisticated abstractions, favoring code that clearly expresses intent.

---

### Pillar III: Guided Correctness
*(Making the Right Path the Easy Path)*

Good architecture should naturally guide developers toward correct solutions. APIs should make safe patterns more convenient than unsafe alternatives.

**Resource Safety by Design**: `Eru.Resource` and bracketing patterns encode proper resource lifecycles, making the safest approach also the most ergonomic.

**Structured Concurrency**: High-level concurrency primitives abstract away scheduling complexities while maintaining safety guarantees, making concurrent code easier to write correctly.

**Explicit Integration Boundaries**: Interactions with blocking or legacy code use clear `Eru.blocking(...)` constructs, protecting application responsiveness by default.

---

### Pillar IV: Transparent Runtime
*(Observable Execution)*

Running programs shouldn't be black boxes. Observability is built into Eru's foundation, making program behavior visible and debuggable.

**Structured Error Information**: Failures provide rich, typed context rather than opaque messages, making debugging more straightforward.

**Low-Overhead Instrumentation**: Optional event emission enables detailed tracing and profiling without impacting performance when not needed.

**Unified Observation Interface**: `EruObserver` provides a single integration point for logging, metrics, and tracing systems.

---

These four principles define what I strive for in Eru: software that is simultaneously correct, pleasant to use, and transparent in its operation. They represent my commitment to the idea that functional programming can be both rigorous and practical.

Building on the foundations laid by the functional programming community, Eru explores what becomes possible when a library is designed from first principles around modern type systems and runtime capabilities.

---

*Eru is developed by Hakim Jonas Ghoula.*
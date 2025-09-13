### The Eru Manifesto

**Our Purpose:** We've focused our efforts on creating `Eru` as a modern effect system that serves the practical needs of Scala 3 developers. Rather than competing for market share, we've concentrated on the fundamentals: correctness, developer experience, and operational reliability.

Building on lessons learned from existing approaches, `Eru` represents our attempt to demonstrate what becomes possible when a library is designed from first principles around the capabilities of a modern type system.

---

### Pillar I: Foundational Correctness
*(The Bedrock of Everything Else)*

We treat correctness as non-negotiable. Every design decision prioritizes reliability and predictability, aiming for a level of correctness that developers can take for granted.

-   **Pure Program Representation:** `Eru[E, A]` represents programs as immutable, total descriptions. All side effects are explicitly suspended within the `Eru` context to maintain referential transparency.
-   **Type-Driven Design:** We leverage Scala 3's type system - opaque types, GADTs, and compositional structures - to prevent entire categories of errors at compile time rather than deferring them to runtime.
-   **Verified Lawfulness:** `Eru`'s adherence to functional programming laws is validated through property-based testing, ensuring behavioral contracts remain precise and predictable.

---

### Pillar II: Radical Ergonomics  
*(Power Without Ceremony)*

We believe powerful tools should feel natural to use. The best abstractions make complex operations simple without sacrificing capability.

-   **Discoverable Operations:** Common patterns like retries, timeouts, and resource management are provided as first-class methods directly on the `Eru` type, making them easy to find and use.
-   **Clarity Over Cleverness:** We consistently choose straightforward, readable solutions over sophisticated abstractions, favoring code that clearly expresses intent.

---

### Pillar III: Guided Correctness
*(Making the Right Path the Easy Path)*

Good architecture should naturally guide developers toward correct solutions. We design APIs that make safe patterns more convenient than unsafe alternatives.

-   **Resource Safety by Design:** `Eru.Resource` and bracketing patterns encode proper resource lifecycles, making the safest approach also the most ergonomic.
-   **Structured Concurrency:** High-level concurrency primitives abstract away scheduling complexities while maintaining safety guarantees, making concurrent code easier to write correctly.
-   **Explicit Integration Boundaries:** Interactions with blocking or legacy code use clear `Eru.blocking(...)` constructs, protecting application responsiveness by default.

---

### Pillar IV: Transparent Runtime
*(Observable Execution)*

Running programs shouldn't be black boxes. We've built observability into `Eru`'s foundation, making program behavior visible and debuggable.

-   **Structured Error Information:** Failures provide rich, typed context rather than opaque messages, making debugging more straightforward.
-   **Low-Overhead Instrumentation:** Optional event emission enables detailed tracing and profiling without impacting performance when not needed.
-   **Unified Observation Interface:** `EruObserver` provides a single integration point for logging, metrics, and tracing systems.

---

**Our Commitment**

These four principles define what we strive for in `Eru`: software that is simultaneously correct, pleasant to use, and transparent in its operation. They represent our commitment to developers who share our belief that functional programming can be both rigorous and practical.

We've applied these same principles to `Valar`, our validation library, which will be rebuilt on `Eru`'s foundation to provide unified effect, error, and validation semantics.

---

*Eru is developed by Hakim Jonas Ghoula.*
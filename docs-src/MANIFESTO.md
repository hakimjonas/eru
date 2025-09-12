### The Eru Manifesto (Revised)

**Our Vision:** To create `Eru`, the definitive effect system for the discerning Scala 3 developer. `Eru` is not an alternative to existing frameworks; it is a new benchmark for what is possible when a library is designed from first principles to leverage the full power, purity, and expressiveness of a modern type system.

Its worth is demonstrated not by ecosystem breadth, but by the integrity of its own design, its lawfulness, and its operational reliability.

---

### Pillar I: Foundational Correctness
*(Purity and Type Precision)*

Correctness is the non-negotiable substrate; everything else is built upon a deterministic, lawful core. We will pursue a level of correctness so profound that it becomes invisible, allowing the developer to build with absolute confidence.

-   **Pure Program Representation:** `Eru[E, A]` is a total, immutable description of a program. All interactions with the outside world are explicitly suspended within the `Eru` context to preserve referential transparency.
-   **Type-Directed Guarantees:** The Scala 3 type system is our primary tool for guaranteeing correctness. We use opaque types, GADTs, and compositional types to encode domain and effect constraints, preventing errors at compile time rather than deferring them to runtime checks.
-   **Law Validation:** `Eru` is provably lawful. Its adherence to the fundamental laws of functional programming is asserted with property-based tests to keep its behavioral contracts precise.

---

### Pillar II: Pragmatic Ergonomics
*(Direct Power, Minimal Ceremony)*

Power should not require verbosity. The developer experience must be joyful and intuitive.

-   **Fluent Core API:** Common, powerful operations like retries, timeouts, caching, and resource patterns are provided as first-class, discoverable combinators directly on the `Eru` type.
-   **A Focus on Clarity:** The API is designed to be minimal and direct, avoiding unnecessary ceremony and favoring a clear, straightforward style of composition.

---

### Pillar III: Guided Correctness
*(The Easiest Path is the Best Path)*

The architecture should guide developers toward the most correct, performant, and resource-safe solutions.

-   **Resource Discipline by Design:** `Eru.Resource` and the `bracket`/`ensure` patterns encode acquisition, use, and release sequencing so that the most ergonomic path is also the safest one.
-   **Managed Concurrency:** High-level concurrency primitives abstract away the complexities of scheduling and isolation, making it easier to write correct concurrent code.
-   **Explicit Blocking Boundaries:** Interacting with legacy or JVM-based blocking code is done via a clear `Eru.blocking(...)` construct, protecting the application's responsiveness by default.

---

### Pillar IV: Runtime Observability
*(A Transparent, Inspectable Runtime)*

A running program should not be a black box. `Eru` is built from the ground up to be transparent and introspective.

-   **Structured Failure Data:** Errors in Eru are rich, structured data, not just opaque strings or exceptions.
-   **Low-Overhead Tracing Hooks:** Optional event emission enables timing, causality, and correlation without requiring invasive instrumentation.
-   **Unified Observer Interface:** The `EruObserver` provides a single, stable integration surface for logging, metrics, and tracing backends.

---

This manifesto defines `Eru`'s identity: a pioneer, not a follower. It is a testament to the idea that a library can be simultaneously pure, correct, powerful, and a profound joy to use. The existing validation library `Valar` will be rebased on `Eru` in its next release, unifying effect, error, and validation semantics.

---

*Eru is designed and developed by Hakim Jonas Ghoula.*
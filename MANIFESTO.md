# The Eru Manifesto

**Our Vision:** To create `Eru`, the definitive effect system for the discerning Scala 3 developer. `Eru` is not an alternative to existing frameworks; it is a new benchmark for what is possible when a library is designed from first principles to leverage the full power, purity, and expressiveness of a modern type system.

Its worth is proven not by the ecosystem that surrounds it, but by the integrity of its own design and the excellence of its implementation.

---

### Pillar I: Correctness as the Unseen Foundation
*(Championing Purity and Type-Driven Design)*

Before a system can be ergonomic, it must be correct. Correctness is the non-negotiable bedrock of `Eru`. We will pursue a level of correctness so profound that it becomes invisible, allowing the developer to build with absolute confidence.

- **Purity is Law:** The `Eru[A]` data type is and always will be a pure, immutable description of a program. All interactions with the outside world are explicitly suspended within the `Eru` context, ensuring referential transparency and predictable composition.
- **The Type System is a Prover:** We will use the full arsenal of the Scala 3 type system as our primary tool for guaranteeing correctness, including opaque types for domain integrity and compositional types for precision.
- **Algebraic Soundness:** `Eru` will be provably lawful. Its adherence to the fundamental laws of functional programming will be verified through rigorous, property-based testing, ensuring it is a sound and reliable tool for composition.

---

### Pillar II: Radical Ergonomics
*(Effortless Power)*

A correct system that is difficult to use is a failed system. `Eru`'s central mission is to make powerful functional programming feel effortless, intuitive, and joyful.

- **A Fluent, Discoverable API:** The API will be designed to feel like a natural extension of the language. Common operations like retries, timeouts, and caching will be built-in, discoverable methods on the `Eru` type itself.
- **Metaprogramming as a DX Tool:** We will use Scala 3's metaprogramming features to eliminate boilerplate and enhance the developer experience, providing immense power through elegant, single-line expressions.

---

### Pillar III: A "Pit of Success"
*(The Easiest Path is the Best Path)*

The architecture of `Eru` will actively guide developers toward the most correct, performant, and resource-safe solutions.

- **Resource Safety by Design:** The `Eru.Resource` pattern for managing resources will be so ergonomic and well-integrated that writing code with it will be easier and more natural than writing unsafe code.
- **Concurrency Without Fear:** `Eru` will provide high-level, intuitive constructs for managing concurrency, handling all complex, low-level details of fiber scheduling and thread pool management.
- **Blocking is Explicit and Safe:** Interacting with legacy, blocking code will be done via a clear `Eru.blocking(...)` construct, protecting the application's responsiveness by default.

---

### Pillar IV: Exceptional Observability
*(A Transparent Runtime)*

A running program should not be a black box. `Eru` will be built from the ground up to be transparent and introspective.

- **Diagnostics as a Core Feature:** Errors in `Eru` will be rich, structured data, not just exceptions.
- **Built-in, Lightweight Tracing:** `Eru`'s runtime will be instrumented to provide detailed, low-overhead diagnostic information about a program's execution.
- **A Standardized Hook for Integration:** The `EruObserver` pattern will be the single, clean entry point for integrating with the wider ecosystem of metrics, logging, and tracing platforms.

---

This manifesto defines `Eru`'s identity. It is not a follower, but a pioneer—a testament to the idea that a library can be simultaneously pure, correct, powerful, and a profound joy to use.

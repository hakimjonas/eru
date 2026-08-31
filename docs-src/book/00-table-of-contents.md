# The Eru Book

*A progressive guide to effect-driven development*

---

## Table of contents

### Part I: Foundation and philosophy

[Chapter 1: The Eru vision](01-the-eru-vision.md)
Why effect systems matter and the four principles that guide Eru's design.

[Chapter 2: Your first steps](02-your-first-steps.md)
A hands-on introduction starting with "Hello, Eru!", built through examples and exercises.

### Part II: Core concepts

[Chapter 3: The Eru type](03-the-eru-type-deep-dive.md)
`Eru[E, A]` as data and as computation. The GADT design and the mental models that go with it.

[Chapter 4: Sequencing and composition](04-sequencing-composition.md)
Using `map`, `flatMap`, and `zip`. When each combinator applies and what the runtime optimizes.

[Chapter 5: Error handling](05-error-handling.md)
Typed errors vs exceptions. Recovery patterns and error accumulation.

[Chapter 6: API reference and patterns](06-api-reference-patterns.md)
A tour of Eru's combinators and the patterns they support.

### Part III: Resources and safety

[Chapter 7: Resource management](07-resource-management.md)
The `ensure` and `bracket` combinators, cleanup ordering, and resource patterns.

[Chapter 8: Backends and execution models](08-backends-and-execution-models.md)
The backend SPI, the JVM virtual thread backend, and the sequential fallback. How the runtime selects its execution strategy.

### Part IV: Concurrency

[Chapter 9: Introduction to fibers](09-introduction-to-fibers.md)
What fibers are, the fork/await model, and how interruption behaves.

[Chapter 10: Advanced concurrency patterns](10-advanced-concurrency-patterns.md)
Racing, parallel processing, and coordination. Resource-bounded concurrency.

### Part V: Production readiness

[Chapter 11: Observability and debugging](11-observability-debugging.md)
`EruObserver`, event categories, and debugging with error contexts.

[Chapter 12: Performance and optimization](12-performance-optimization.md)
Eru's performance characteristics, measurement, and tuning.

### Part VI: Integration

[Chapter 13: Integration patterns](13-integration-patterns.md)
Working with legacy code, blocking operations, and third-party libraries.

[Chapter 14: Libraries around Eru](14-the-eru-ecosystem.md)
Migration from other effect systems and adoption guidance.

---

## How to use this book

If you're new to effect systems: start with Part I and work through in order. Each chapter builds on the previous one.

If you're experienced with other effect systems: read Chapter 1, then skip to Part II for Eru's specifics.

If you're looking for a particular pattern: each chapter includes examples and can serve as reference material.

For teams adopting Eru: Parts III through V cover production concerns.

---

## Conventions

- Code examples are validated with mdoc: they compile and run against the current Eru version
- Key concepts are highlighted in bold when first introduced
- Exercises at chapter ends help solidify understanding
- Real-world examples connect concepts to practical applications

---

*Begin with [Chapter 1: The Eru vision](01-the-eru-vision.md).*

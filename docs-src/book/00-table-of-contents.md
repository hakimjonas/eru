# The Eru Book

*A Progressive Guide to Effect-Driven Development*

---

## Table of Contents

### Part I: Foundation & Philosophy

**[Chapter 1: The Eru Vision](01-the-eru-vision.md)**  
Why effect systems matter and the Four Pillars that guide Eru's design. Understanding the philosophy before the practice.

**[Chapter 2: Your First Steps](02-your-first-steps.md)**  
Hands-on introduction starting with "Hello, Eru!" Building confidence through practical examples and guided exercises.

### Part II: Core Concepts

**[Chapter 3: The Eru Type Deep Dive](03-the-eru-type-deep-dive.md)**  
Understanding `Eru[E, A]` as both data and computation. The GADT design and mental models for effective use.

**[Chapter 4: Sequencing & Composition](04-sequencing-composition.md)**  
Using `map`, `flatMap`, and `zip`. Performance optimizations and when to use each combinator.

**[Chapter 5: Error Handling](05-error-handling.md)**  
Typed errors vs exceptions. Recovery patterns and error accumulation techniques.

**[Chapter 6: API Reference & Patterns](06-api-reference-patterns.md)**  
Comprehensive guide to Eru's ergonomic patterns. Essential reference for effective Eru programming.

### Part III: Resource & Safety  

**[Chapter 7: Resource Management](07-resource-management.md)**  
The `ensure` combinator and resource cleanup patterns. Making resource safety reliable and composable.

**[Chapter 8: Cross-Platform Development](08-cross-platform-development.md)**  
JVM vs Native execution models. Writing platform-agnostic code with consistent APIs.

### Part IV: Concurrency

**[Chapter 9: Introduction to Fibers](09-introduction-to-fibers.md)**  
What fibers are and why they matter. Basic patterns and structured concurrency principles.

**[Chapter 10: Advanced Concurrency Patterns](10-advanced-concurrency-patterns.md)**
Racing, parallel processing, and coordination. Resource-bounded concurrency patterns.

### Part V: Production Readiness

**[Chapter 11: Observability & Debugging](11-observability-debugging.md)**
`EruObserver` and tracing patterns. Making program execution transparent and debuggable.

**[Chapter 12: Performance & Optimization](12-performance-optimization.md)**
Understanding Eru's performance characteristics. Benchmarking and measurement techniques.

### Part VI: Ecosystem & Integration

**[Chapter 13: Integration Patterns](13-integration-patterns.md)**
Working with legacy code, blocking operations, and third-party libraries.

**[Chapter 14: The Eru Ecosystem](14-the-eru-ecosystem.md)**
Valar integration, community patterns, and migration strategies.

---

## How to Use This Book

**If you're new to effect systems**: Start with Part I and work through sequentially. Each chapter builds on the previous one.

**If you're experienced with other effect systems**: You might skip to Part II after reading Chapter 1, focusing on Eru's unique approaches.

**If you're looking for specific patterns**: Each chapter includes practical examples and can serve as reference material.

**For teams adopting Eru**: Parts III-V cover production concerns and advanced patterns essential for robust systems.

---

## Conventions

Throughout this book, we use these conventions:

- **Code examples** are validated with mdoc—they compile and run against the current Eru version
- **Key concepts** are highlighted in bold when first introduced  
- **Exercises** at chapter ends help solidify understanding
- **Real-world examples** connect concepts to practical applications

---

*Begin your journey with [Chapter 1: The Eru Vision](01-the-eru-vision.md).*
# Contributing to Eru

Thank you for your interest in contributing to Eru! This guide will help you understand the project structure, development workflow, and guidelines for contributing effectively.

## Quick Start

1. **Fork and clone** the repository
2. **Install dependencies**: Ensure you have JDK 21+ and sbt installed
3. **Run tests**: Execute `./run-all-tests.sh` to verify everything works
4. **Make changes** following our development workflow below
5. **Submit a PR** with clear description and tests

## Development Workflow

### Essential Commands

```bash
# Format code and apply automatic fixes
sbt prepare

# Validate code quality and documentation
sbt check

# Run all tests in isolation (recommended)
./run-all-tests.sh

# Individual platform testing
sbt testJVM              # All JVM tests
sbt testNative           # All Native tests
sbt testIntegration      # Integration tests (JVM only)

# Optimized testing (fastest)
sbt testAllOptimized     # Pre-compile everything, then run tests
```

### Development Cycle

Follow this cycle for all contributions:

1. **Understand** - Read existing code and tests thoroughly
2. **Implement** - Make your changes following our guidelines
3. **Test** - Write comprehensive tests for your changes
4. **Check** - Run `sbt check` to validate code quality
5. **Prepare** - Run `sbt prepare` to format and fix code

**Never skip the Check and Prepare steps** - they ensure consistency and catch issues early.

## Project Structure

```
eru/
├── eru-core/           # Core effect type and operations (cross-platform)
│   └── src/main/scala/net/ghoula/eru/
│       ├── Eru.scala   # Main effect type (704 lines)
│       ├── Exit.scala  # Exit modeling
│       └── ...
├── eru-runtime/        # Runtime with concurrency support
│   ├── jvm/           # JVM-specific runtime (Virtual Threads)
│   ├── native/        # Native-specific runtime (synchronous)
│   └── shared/        # Cross-platform runtime code
├── eru-bench-jvm/     # JMH performance benchmarks
├── eru-integration-test/ # End-to-end integration tests
├── docs-src/          # Documentation source
├── examples/          # Usage examples and migration guides
└── tools/            # Development and benchmarking tools
```

## Code Quality Standards

### Four Pillars Framework

All contributions must align with Eru's four pillars:

1. **Correctness as Foundation** - Correctness is non-negotiable
2. **Radical Ergonomics** - Joyful, intuitive developer experience
3. **Guided Correctness** - Easy path must be the correct path
4. **Exceptional Observability** - Runtime must not be a black box

### Scala 3 Requirements

- Use `enum` for ADTs (not sealed traits)
- Use `opaque type` for domain integrity
- Use `extension` methods for fluent APIs
- Use `given`/`using` for typeclasses
- Leverage intersection (`&`) and union (`|`) types

### Documentation Standards

- **Zero inline comments** - Code must be self-documenting
- **Complete Scaladoc** for all public APIs with examples
- **Stack safety warnings** for construction methods
- **Migration examples** for new patterns

### Testing Requirements

- **Comprehensive tests** with full logical coverage
- **Cross-platform tests** for core functionality
- **Integration tests** for complex scenarios
- **Property-based tests** where applicable
- **Performance benchmarks** for critical paths

## Stack Safety Guidelines

⚠️ **Critical**: Always follow stack-safe patterns when writing Eru code:

### ✅ Safe Patterns

```scala
// Use iterative builders for loops
Eru.iterate(0)(i => Eru.succeed(i + 1))(_ >= 10000)

// Use foldLeft for accumulation
values.foldLeft(Eru.succeed(0)) { (acc, v) =>
  acc.flatMap(total => Eru.succeed(total + v))
}

// Use traverse/sequence for collections
Eru.traverse(items)(item => processItem(item))
```

### ❌ Avoid These Patterns

```scala
// DON'T: Recursive Eru construction - Scala stack overflow
def recursive(n: Int): Eru[Nothing, Int] =
  if (n <= 0) Eru.succeed(0)
  else Eru.succeed(n).flatMap(_ => recursive(n - 1))
```

**Key insight**: Eru makes `flatMap` chains stack-safe, but you must build those chains without Scala recursion.

## Testing Guidelines

### Test Isolation

Due to resource contention between concurrent test suites, always use:

```bash
./run-all-tests.sh  # Isolated test runner - ALWAYS use this
```

This prevents thread pool exhaustion and coordination deadlocks that cause hanging tests.

### Platform Testing

- **JVM tests**: Include concurrency and virtual thread scenarios
- **Native tests**: Focus on synchronous operations only
- **Integration tests**: End-to-end scenarios with realistic workloads

### Writing Tests

```scala
// Follow existing test patterns
class YourFeatureSpec extends EruSpecification {

  "Your feature" should {
    "handle normal case" in {
      val result = yourImplementation.unsafeRunSync()
      result must_== expectedValue
    }

    "handle error case" in {
      val result = yourFailingImplementation.runAttempt()
      result must_== Result.Failure(expectedError)
    }

    "be stack safe" in {
      val largeChain = Eru.iterateN(0, 10000)(i => Eru.succeed(i + 1))
      largeChain.unsafeRunSync() must_== 10000
    }
  }
}
```

## Performance Expectations

Eru achieves exceptional performance (4,756-160,143 ops/ms, 50-80x faster than Cats Effect). When contributing:

- **Maintain performance standards** - benchmark critical paths
- **Profile before optimizing** - use JMH benchmarks for validation
- **Avoid allocations** in hot paths
- **Leverage GADT optimizations** in the interpreter

### Benchmarking

```bash
# Quick performance check
./run-fair-benchmarks.sh core

# Full benchmark suite
./run-fair-benchmarks.sh all

# Individual benchmark
sbt "eruBenchJVM/Jmh/run CoreOperationsBench.eruSucceed"
```

## Documentation

### API Documentation

All public APIs must have complete Scaladoc:

```scala
/** Brief description of what this method does.
  *
  * Longer description with usage patterns, examples, and warnings.
  * Include stack safety notes for construction methods.
  *
  * @param param1 description of parameter
  * @param param2 description of parameter
  * @tparam E the error type
  * @tparam A the success type
  * @return description of return value
  *
  * @example
  * {{{
  * val program = Eru.succeed(42).map(_ * 2)
  * program.unsafeRunSync() // 84
  * }}}
  */
def yourMethod[E, A](param1: String, param2: Int): Eru[E, A]
```

### User Documentation

When adding new features:

1. **Update API.md** with new method signatures
2. **Add examples** to appropriate documentation chapters
3. **Create runnable examples** in the `examples/` directory
4. **Update migration guides** if relevant

### Building Documentation

```bash
sbt docs              # Validate documentation examples
sbt docsApi           # Generate ScalaDoc
sbt docsSite          # Generate complete site
```

## Pull Request Guidelines

### PR Description Template

```markdown
## Summary

Brief description of changes and motivation.

## Changes

- [ ] Core functionality changes
- [ ] Runtime changes
- [ ] Documentation updates
- [ ] Examples added/updated
- [ ] Breaking changes (require major version)

## Testing

- [ ] All tests pass (`./run-all-tests.sh`)
- [ ] New tests added for new functionality
- [ ] Performance benchmarks run (if applicable)
- [ ] Cross-platform compatibility verified

## Checklist

- [ ] Code follows stack safety guidelines
- [ ] Documentation updated (API.md, examples, etc.)
- [ ] `sbt prepare` run successfully
- [ ] `sbt check` passes without issues
- [ ] Examples run successfully
```

### Review Process

1. **Automated checks** run on all PRs
2. **Code review** by maintainers
3. **Performance validation** for critical changes
4. **Documentation review** for user-facing changes
5. **Final approval** and merge

## Common Contribution Areas

### High-Impact Areas

1. **Performance optimizations** - Profile and improve hot paths
2. **Documentation improvements** - Better examples and guides
3. **Cross-platform support** - Native runtime enhancements
4. **Observability features** - Enhanced debugging and monitoring
5. **Migration tools** - Easier adoption from other effect systems

### Good First Issues

- Documentation typos and improvements
- Additional examples for common patterns
- Test coverage improvements
- Performance benchmark additions
- Cross-platform test consistency

## Getting Help

- **GitHub Issues** - Bug reports and feature requests
- **GitHub Discussions** - Questions and community help
- **Code Review** - Detailed feedback on contributions

## Release Process

### Versioning

Eru follows semantic versioning:
- **Major** (1.x.x) - Breaking changes
- **Minor** (x.1.x) - New features, backward compatible
- **Patch** (x.x.1) - Bug fixes, backward compatible

### Release Checklist

1. All tests passing across platforms
2. Documentation updated and validated
3. Performance benchmarks stable
4. Examples working correctly
5. CHANGELOG.md updated

## Code of Conduct

We are committed to providing a welcoming and inclusive environment. Please be respectful in all interactions and follow these principles:

- **Be respectful** - Treat everyone with kindness and respect
- **Be constructive** - Provide helpful feedback and suggestions
- **Be inclusive** - Welcome contributors of all backgrounds and experience levels
- **Be patient** - Help others learn and grow

## Architecture Notes

### Cross-Platform Design

- **Core module**: Pure, platform-agnostic effect operations
- **Runtime module**: Platform-specific execution strategies
- **Shared abstractions**: Common interfaces and types
- **Platform backends**: JVM (virtual threads) and Native (synchronous)

### GADT Implementation

Eru uses Scala 3 enums to implement a GADT (Generalized Algebraic Data Type):

```scala
enum Eru[+E, +A] {
  case Succeed[+A](value: A) extends Eru[Nothing, A]
  case Fail[+E](error: E) extends Eru[E, Nothing]
  case FlatMap[E, A, B](source: Eru[E, A], f: A => Eru[E, B]) extends Eru[E, B]
  // ... more constructors
}
```

This enables:
- **Compile-time optimizations** - Fusion and elimination
- **Type safety** - Prevent invalid states
- **Performance** - Zero-cost abstractions

### Interpreter Design

The interpreter uses trampolining for stack safety and optimization passes for performance:

1. **Construction-time fusion** - Combine adjacent operations
2. **Trampolined execution** - Stack-safe `flatMap` chains
3. **Continuation optimization** - Minimize allocations
4. **Platform adaptation** - JVM vs Native execution strategies

---

Thank you for contributing to Eru! Your efforts help make Scala effect programming more joyful and accessible for everyone.
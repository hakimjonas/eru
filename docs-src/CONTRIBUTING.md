# Contributing to Eru

Thank you for your interest in contributing to Eru! This guide will help you understand the project structure, development workflow, and guidelines for contributing effectively.

## Quick start

1. **Fork and clone** the repository
2. **Install dependencies**: Ensure you have JDK 25+ and sbt installed
3. **Run tests**: Execute `sbt testAll testIntegration` to verify everything works
4. **Make changes** following our development workflow below
5. **Submit a PR** with clear description and tests

## Development workflow

### Essential commands

```bash
# Format code and apply automatic fixes
sbt prepare

# Validate code formatting and lint rules
sbt check

# Run all tests
sbt testAll testIntegration

# Individual testing
sbt testAll               # All core + runtime tests
sbt testIntegration       # Integration tests

# Individual module testing (for faster feedback)
sbt eruCore/test          # Core tests
sbt eruRuntime/test       # Runtime tests
```

### Development cycle

Follow this cycle for all contributions:

1. **Understand** - Read existing code and tests thoroughly
2. **Implement** - Make your changes following our guidelines
3. **Test** - Write tests for your changes
4. **Check** - Run `sbt check` to validate code quality
5. **Prepare** - Run `sbt prepare` to format and fix code

**Never skip the Check and Prepare steps** - they ensure consistency and catch issues early.

## Project structure

```
eru/
├── eru-core/           # Core effect type and operations
│   └── src/main/scala/net/ghoula/eru/
│       ├── Eru.scala   # Main effect type
│       ├── Exit.scala  # Exit modeling
│       └── ...
├── eru-runtime/        # Runtime with Virtual Thread concurrency
│   └── src/main/scala/net/ghoula/eru/
│       ├── EruRuntime.scala    # Runtime entry point
│       └── internal/           # Concurrency backend SPI + JVM backend
├── eru-integration-test/ # End-to-end integration tests
├── docs-src/          # Documentation source
├── examples/          # Usage examples and migration guides
```

## Code quality standards

### Four pillars

All contributions must align with Eru's four pillars:

1. **Foundational Correctness** - Correctness is non-negotiable
2. **Pragmatic Ergonomics** - Direct, low-ceremony developer experience
3. **Guided Correctness** - Easy path must be the correct path
4. **Runtime Observability** - Runtime must not be a black box

### Scala 3 requirements

- Use `enum` for ADTs (not sealed traits)
- Use `opaque type` for domain integrity
- Use `extension` methods for fluent APIs
- Use `given`/`using` for typeclasses
- Use intersection (`&`) and union (`|`) types

### Documentation standards

- **Zero inline comments** - Code must be self-documenting
- **Complete Scaladoc** for all public APIs with examples
- **Stack safety warnings** for construction methods
- **Migration examples** for new patterns

### Testing requirements

- **Tests** with full logical coverage
- **Integration tests** for complex scenarios
- **Property-based tests** where applicable

## Stack safety guidelines

⚠️ **Critical**: Always follow stack-safe patterns when writing Eru code:

### ✅ Safe patterns

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

### ❌ Avoid these patterns

```scala
// DON'T: Recursive Eru construction - Scala stack overflow
def recursive(n: Int): Eru[Nothing, Int] =
  if (n <= 0) Eru.succeed(0)
  else Eru.succeed(n).flatMap(_ => recursive(n - 1))
```

**Key insight**: Eru makes `flatMap` chains stack-safe, but you must build those chains without Scala recursion.

## Testing guidelines

### Running tests

Run the full test suite with:

```bash
sbt testAll testIntegration
```

Or run individual test suites for faster feedback:

```bash
sbt eruCore/test            # Core tests
sbt eruRuntime/test         # Runtime tests
sbt eruIntegrationTest/test # Integration tests
```

### Writing tests

```scala
// Follow existing test patterns
class YourFeatureSpec extends EruTestSuite {

  "Your feature" should {
    "handle normal case" in {
      val result = yourImplementation.unsafeRunSync()
      assertEquals(result, expectedValue)
    }

    "handle error case" in {
      val result = yourFailingImplementation.runAttempt()
      assertEquals(result, Result.Failure(expectedError))
    }

    "be stack safe" in {
      val largeChain = Eru.iterateN(0, 10000)(i => Eru.succeed(i + 1))
      assertEquals(largeChain.unsafeRunSync(), 10000)
    }
  }
}
```

## Documentation

### API documentation

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

### User documentation

When adding new features:

1. **Update API.md** with new method signatures
2. **Add examples** to appropriate documentation chapters
3. **Create runnable examples** in the `examples/` directory
4. **Update migration guides** if relevant

### Building documentation

```bash
sbt docs              # Validate documentation examples and regenerate root markdown
sbt docsWatch         # Watch mode for documentation validation
sbt checkExamples     # Compile the examples against the public API
```

## Pull request guidelines

### PR description template

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

- [ ] All tests pass (`sbt testAll testIntegration`)
- [ ] New tests added for new functionality

## Checklist

- [ ] Code follows stack safety guidelines
- [ ] Documentation updated (API.md, examples, etc.)
- [ ] `sbt prepare` run successfully
- [ ] `sbt check` passes without issues
- [ ] Examples run successfully
```

### Review process

1. **Automated checks** run on all PRs
2. **Code review** by maintainers
3. **Performance validation** for critical changes
4. **Documentation review** for user-facing changes
5. **Final approval** and merge

## Common contribution areas

### High-impact areas

1. **Performance optimizations** - Profile and improve hot paths
2. **Documentation improvements** - Better examples and guides
3. **Observability features** - Enhanced debugging and monitoring
4. **Migration tools** - Easier adoption from other effect systems
5. **Backend SPI extensions** - New concurrency backends via `ConcurrencyBackend`

### Good first issues

- Documentation typos and improvements
- Additional examples for common patterns
- Test coverage improvements

## Getting help

- **GitHub Issues** - Bug reports and feature requests
- **Code Review** - Detailed feedback on contributions

## Release process

### Versioning

Eru follows semantic versioning:
- **Major** (1.x.x) - Breaking changes
- **Minor** (x.1.x) - New features, backward compatible
- **Patch** (x.x.1) - Bug fixes, backward compatible

### Release checklist

1. All tests passing
2. Documentation updated and validated
3. Examples working correctly
4. CHANGELOG.md updated

## Code of conduct

We are committed to providing a welcoming and inclusive environment. Please be respectful in all interactions and follow these principles:

- **Be respectful** - Treat everyone with kindness and respect
- **Be constructive** - Provide helpful feedback and suggestions
- **Be inclusive** - Welcome contributors of all backgrounds and experience levels
- **Be patient** - Help others learn and grow

## Architecture notes

### Backend SPI design

- **Core module**: Pure effect operations, independent of execution strategy
- **Runtime module**: Execution backends behind the `ConcurrencyBackend` SPI
- **JVM backend**: Virtual Threads with structured concurrency and a non-blocking timer wheel
- **Sequential fallback**: `SharedSynchronousBackend` for deterministic single-threaded execution

### GADT implementation

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
- **Construction-time optimizations** - Adjacent operations are fused (e.g. `MapChain` merges consecutive `map` calls)
- **Type safety** - Prevent invalid states
- **Performance** - A fast-path interpreter avoids allocation for simple chains

### Interpreter design

The interpreter uses a `@tailrec` state machine for stack safety and optimization passes for performance:

1. **Construction-time fusion** - Combine adjacent operations
2. **Tail-recursive state machine** - Stack-safe `flatMap` chains via `EvalState` ADT
3. **Continuation optimization** - Minimize allocations

---

Thank you for contributing to Eru.

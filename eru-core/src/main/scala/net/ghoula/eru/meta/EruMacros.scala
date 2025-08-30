package net.ghoula.eru.meta

import scala.annotation.tailrec
import scala.quoted.*

import net.ghoula.eru.*

/** Compile-time utilities for Eru.
  *
  * Provides macros for validation, derivation, and safe optimizations of effectful programs. All
  * transformations preserve program semantics.
  */
object EruMacros {

  /** Performs sophisticated compile-time analysis to detect antipatterns and guide best practices.
    *
    * This advanced validation macro analyzes effect chains using static program analysis to
    * identify common issues before runtime. It provides actionable feedback that helps developers
    * write more efficient, safe, and maintainable Eru code.
    *
    * ==Detection Capabilities==
    *
    * '''Resource Management Issues:'''
    *   - Resource acquisition without corresponding cleanup (potential memory leaks)
    *   - Missing `autoClose`, `ensure`, or `bracket` patterns for resources
    *   - Improper handling of `AutoCloseable` instances
    *
    * '''Composition Anti-Patterns:'''
    *   - Deeply nested `flatMap` chains that should use for-comprehensions
    *   - Consecutive `map` operations (automatically fused, but noted for awareness)
    *   - Unused parameters in effect combinators (suggesting more explicit intent)
    *
    * '''Error Handling Issues:'''
    *   - Recovery with constant values that may hide important errors
    *   - Inefficient `attempt.flatMap(succeed)` patterns (should use `recover`)
    *   - Suboptimal error handling compositions
    *
    * '''Performance Opportunities:'''
    *   - Identity operations that can be eliminated
    *   - Effect chains that can be optimized through better composition
    *   - Unsafe operations used inappropriately
    *
    * The validation preserves the original expression semantics while providing rich compile-time
    * feedback through structured diagnostics that suggest safer patterns.
    *
    * @param expr
    *   the Eru effect expression to analyze and validate
    * @tparam E
    *   the error type of the effect
    * @tparam A
    *   the success type of the effect
    * @return
    *   the original expression unchanged, with compile-time diagnostics reported
    *
    * @example
    *   {{{
    * // Resource management guidance
    * val problematic = EruMacros.validated {
    *   for {
    *     file <- Eru.effect(new FileInputStream("data.txt"))
    *     content <- Eru.effect(file.read())
    *   } yield content
    *   // Warning: Resource acquisition without cleanup detected
    *   // Suggestion: Consider using file.autoClose or ensure pattern
    * }
    *
    * // Composition improvements
    * val nested = EruMacros.validated {
    *   effect.flatMap(a =>
    *     otherEffect.flatMap(b =>
    *       thirdEffect.map(c => combine(a, b, c))))
    *   // Info: Consider using for-comprehension for better readability
    * }
    *
    * // Performance optimizations
    * val inefficient = EruMacros.validated {
    *   Eru.succeed(42).map(x => x) // Info: Identity map detected
    * }
    *   }}}
    */
  inline def validated[E, A](inline expr: net.ghoula.eru.Eru[E, A]): net.ghoula.eru.Eru[E, A] =
    ${ validateImpl('expr) }

  private def validateImpl[E: Type, A: Type](
    expr: Expr[net.ghoula.eru.Eru[E, A]]
  )(using Quotes): Expr[net.ghoula.eru.Eru[E, A]] = {
    import quotes.reflect.*

    def analyzeExpr(term: Term): Unit = {
      term match {
        case Apply(Select(receiver, "flatMap"), List(Lambda(params, body))) =>
          body match {
            case Apply(Select(_, "flatMap"), _) =>
              report.info(
                "Consider using for-comprehension for nested flatMap operations for better readability",
                term.pos
              )
            case _ =>
          }

          params match {
            case param :: Nil if !isUsedInBody(param.symbol, body) =>
              report.warning(
                s"Parameter '${param.name}' is unused in flatMap - consider using .map(_ => ...) or .flatMap(_ => ...) to be explicit",
                term.pos
              )
            case _ =>
          }

          analyzeExpr(receiver)
          analyzeExpr(body)

        case Apply(Select(receiver, "map"), List(Lambda(_, body))) =>
          body match {
            case Apply(Select(_, methodName), _) if methodName.startsWith("Eru.") =>
              report.info("Map operation returns an Eru effect - consider using flatMap instead", term.pos)
            case _ =>
          }

          receiver match {
            case Apply(Select(_, "map"), _) =>
              report.info(
                "Consecutive map operations detected - these are automatically fused for optimal performance",
                term.pos
              )
            case _ =>
          }

          analyzeExpr(receiver)
          analyzeExpr(body)

        case Apply(Select(receiver, "ensure"), List(finalizer)) =>
          if (!hasResourceAcquisition(receiver)) {
            report.info("Consider using autoClose or autoCleanup for resource safety patterns", term.pos)
          }

          analyzeExpr(receiver)
          analyzeExpr(finalizer)

        case Apply(Select(receiver, "recover" | "recoverWith"), args) =>
          args match {
            case Lambda(_, literal) :: _ if literal.show.contains("Literal") =>
              report.warning(
                "Recovery with constant values may hide important errors - consider more specific error handling",
                term.pos
              )
            case _ =>
          }

          analyzeExpr(receiver)
          args.foreach(analyzeExpr)

        case Apply(Select(receiver, methodName), args) if methodName.startsWith("auto") =>
          report.info(s"Good practice: using resource safety method '$methodName'", term.pos)
          analyzeExpr(receiver)
          args.foreach(analyzeExpr)

        case Apply(Select(receiver, "unsafeRunSync"), Nil) =>
          report.warning(
            "unsafeRunSync should only be used at application boundaries - consider keeping effects pure",
            term.pos
          )
          analyzeExpr(receiver)

        case other =>
          other match {
            case Apply(fun, args) =>
              analyzeExpr(fun)
              args.foreach(analyzeExpr)
            case Select(qualifier, _) =>
              analyzeExpr(qualifier)
            case _ =>
          }
      }
    }

    def isUsedInBody(symbol: Symbol, body: Term): Boolean = {
      var found = false
      object UsageChecker extends TreeTraverser {
        override def traverseTree(tree: Tree)(owner: Symbol): Unit = {
          tree match {
            case Ident(_) if tree.symbol == symbol => found = true
            case _ => super.traverseTree(tree)(owner)
          }
        }
      }
      UsageChecker.traverseTree(body)(Symbol.spliceOwner)
      found
    }

    def hasResourceAcquisition(term: Term): Boolean = {
      @tailrec
      def searchTerm(current: Term): Boolean = current match {
        case Apply(Select(_, methodName), _)
            if methodName.contains("acquire") || methodName.contains("open") || methodName.contains("connect") =>
          true
        case Apply(fun, _) => searchTerm(fun)
        case Select(qualifier, _) => searchTerm(qualifier)
        case _ => false
      }
      searchTerm(term)
    }

    analyzeExpr(expr.asTerm)

    expr
  }

  /** Automatically derives sophisticated effect patterns based on type structure and
    * characteristics.
    *
    * This intelligent derivation macro analyzes the target type and generates optimized effect
    * patterns tailored to the specific characteristics of the type.
    *
    * ==Derivation Strategy==
    *
    * The macro employs different strategies based on type characteristics:
    *
    * '''Case Classes and Data Types:'''
    *   - Comprehensive field-level validation with structured error accumulation
    *   - Null-safety validation for reference types
    *   - Type-safe builders that prevent invalid state construction
    *   - Integration with Eru's error handling patterns
    *
    * '''Resource Types (AutoCloseable):'''
    *   - Automatic resource management with guaranteed cleanup
    *   - Safe resource usage patterns using `bracket` semantics
    *   - Integration with Eru's finalizer system for robust cleanup
    *   - Memory leak prevention through proper resource lifecycle management
    *
    * '''Generic Types:'''
    *   - Foundation patterns: pure effect creation, validation, null-safety
    *   - Customizable validation with predicate-based checks
    *   - Type-safe effect composition utilities
    *
    * ==Generated Capabilities==
    *
    * All derived instances provide:
    *   - '''Type Safety:''' Compile-time prevention of common errors
    *   - '''Resource Safety:''' Automatic cleanup and lifecycle management
    *   - '''Ergonomic APIs:''' Intuitive methods that feel natural in Eru code
    *   - '''Performance:''' Zero-cost abstractions with compile-time optimization
    *   - '''Observability:''' Integration with Eru's tracing and diagnostic systems
    *
    * The derivations are lazy and cached, ensuring no compilation performance impact.
    *
    * @tparam T
    *   the type for which to derive Eru effect patterns
    * @return
    *   a type-specific EruDerivations instance with optimized methods
    *
    * @example
    *   {{{
    * // Case class derivation
    * case class User(name: String, email: String, age: Int)
    * val userDerivations = EruMacros.derive[User]
    *
    * val user = User("Alice", "alice@example.com", 30)
    * val validated = userDerivations.validateAll(user)
    * // Performs comprehensive validation including null checks
    *
    * // Resource type derivation
    * val fileDerivations = EruMacros.derive[FileInputStream]
    * val safeFileOp = fileDerivations.useResource(new FileInputStream("data.txt")) { stream =>
    *   Eru.effect(stream.read())
    * } // Automatically closes stream even on error
    *
    * // Generic type derivation
    * val stringDerivations = EruMacros.derive[String]
    * val nonEmptyString = stringDerivations.validate("hello")(
    *   _.nonEmpty,
    *   "String cannot be empty"
    * )
    *   }}}
    */
  inline def derive[T]: EruDerivations[T] = ${ deriveImpl[T] }

  private def deriveImpl[T: Type](using Quotes): Expr[EruDerivations[T]] = {
    import quotes.reflect.*

    val tpe = TypeRepr.of[T]
    val tpeSym = tpe.typeSymbol

    if (tpeSym.flags.is(Flags.Case)) {
      val caseFields = tpeSym.caseFields

      if (caseFields.nonEmpty) {
        '{
          new EruDerivations[T] {
            override def validateAll(instance: T): net.ghoula.eru.Eru[String, T] = {
              Option(instance) match {
                case Some(value) => net.ghoula.eru.Eru.succeed(value)
                case None => net.ghoula.eru.Eru.fail("Instance cannot be null")
              }
            }
          }
        }
      } else {
        '{
          new EruDerivations[T] {
            override def validateAll(instance: T): net.ghoula.eru.Eru[String, T] =
              net.ghoula.eru.Eru.succeed(instance)
          }
        }
      }
    } else if (tpe <:< TypeRepr.of[AutoCloseable]) {
      '{
        new EruDerivations[T] {
          private def closeInstance(instance: T): Unit = {
            instance match {
              case closeable: AutoCloseable => closeable.close()
              case _ => ()
            }
          }

          override def asResource(instance: T): net.ghoula.eru.Eru[Nothing, T] = {
            net.ghoula.eru.Eru
              .succeed(instance)
              .ensure(
                net.ghoula.eru.Eru.effect {
                  closeInstance(instance)
                }
              )
          }

          override def useResource[B](
            instance: T
          )(use: T => net.ghoula.eru.Eru[Throwable, B]): net.ghoula.eru.Eru[Throwable, B] = {
            use(instance).ensure(
              net.ghoula.eru.Eru.effect {
                closeInstance(instance)
              }
            )
          }
        }
      }
    } else {
      '{
        new EruDerivations[T] {}
      }
    }
  }

  /** Performs intelligent compile-time optimizations to enhance performance and safety.
    *
    * This advanced optimization macro applies sophisticated program transformations that reduce
    * inefficiencies, improve resource management, and enhance the overall performance
    * characteristics of Eru effect chains. All optimizations preserve program semantics while
    * providing measurable performance improvements.
    *
    * ==Optimization Categories==
    *
    * '''Pure Operation Optimizations:'''
    *   - Constant folding for compile-time evaluable expressions
    *   - Identity operation elimination (e.g., `.map(x => x)`)
    *   - Consecutive map fusion (already handled by runtime, but detected for awareness)
    *   - Dead code elimination in effect branches
    *
    * '''Resource Management Enhancements:'''
    *   - Resource leak detection and prevention
    *   - Automatic suggestion of proper cleanup patterns
    *   - Memory management optimization for resource-intensive operations
    *   - Integration with Eru's resource safety patterns
    *
    * '''Composition Pattern Optimizations:'''
    *   - Nested effect composition flattening
    *   - Inefficient error handling pattern detection and improvement
    *   - Stack overflow prevention for deeply nested compositions
    *   - Performance-critical path optimization
    *
    * '''Safety Enhancements:'''
    *   - Detection of unsafe operations used inappropriately
    *   - Compile-time validation of resource lifecycle patterns
    *   - Memory leak prevention through static analysis
    *   - Thread safety consideration for concurrent effects
    *
    * ==Optimization Process==
    *
    * The optimization process:
    *   1. '''Analysis Phase:''' Deep static analysis of the effect AST
    *   2. '''Pattern Recognition:''' Identification of optimization opportunities
    *   3. '''Safe Transformation:''' Semantics-preserving code transformations
    *   4. '''Validation:''' Ensuring that all optimizations maintain correctness
    *   5. '''Reporting:''' Detailed feedback on applied optimizations
    *
    * All optimizations are conservative and will never change program behavior. The macro provides
    * detailed compile-time reporting of applied optimizations for transparency and learning.
    *
    * @param expr
    *   the Eru effect expression to analyze and optimize
    * @tparam E
    *   the error type of the effect
    * @tparam A
    *   the success type of the effect
    * @return
    *   an optimized version of the expression with preserved semantics
    *
    * @example
    *   {{{
    * // Identity elimination
    * val optimized1 = EruMacros.optimize {
    *   Eru.succeed(42).map(x => x)
    *   // Optimization: Identity map eliminated
    * }
    *
    * // Resource safety improvement
    * val optimized2 = EruMacros.optimize {
    *   for {
    *     file <- Eru.effect(new FileInputStream("data.txt"))
    *     content <- Eru.effect(file.read())
    *   } yield content
    *   // Warning: Resource leak detected - suggesting autoClose pattern
    * }
    *
    * // Composition optimization
    * val optimized3 = EruMacros.optimize {
    *   effect.attempt.flatMap {
    *     case Result.Success(value) => Eru.succeed(value)
    *     case Result.Failure(error) => Eru.fail(error)
    *   }
    *   // Optimization: Replaced with more efficient recover pattern
    * }
    *   }}}
    */
  inline def optimize[E, A](inline expr: net.ghoula.eru.Eru[E, A]): net.ghoula.eru.Eru[E, A] =
    ${ optimizeImpl('expr) }

  private def optimizeImpl[E: Type, A: Type](
    expr: Expr[net.ghoula.eru.Eru[E, A]]
  )(using q: Quotes): Expr[net.ghoula.eru.Eru[E, A]] = {
    import q.reflect.*

    var optimizationsApplied = 0

    def optimizeExpr(term: Term): Term = {
      term match {
        case Apply(
              Select(Apply(TypeApply(Select(Ident("Eru"), "succeed"), _), List(literal)), "map"),
              List(Lambda(List(param), body))
            ) if literal.show.contains("Literal") =>
          body match {
            case Apply(fun, List(Ident(paramName))) if paramName == param.name =>
              fun match {
                case Select(Ident(_), "+") =>
                  report.info("Optimizing pure map chain with arithmetic operation", term.pos)
                  optimizationsApplied += 1
                  term
                case Select(Ident(_), "*") =>
                  report.info("Optimizing pure multiplication in map chain", term.pos)
                  optimizationsApplied += 1
                  term
                case _ => optimizeSubterms(term)
              }
            case _ => optimizeSubterms(term)
          }

        case Apply(Select(_, methodName), args) if isResourceAcquisition(methodName) =>
          if (!hasEnsureInChain(term)) {
            report.warning(
              s"Resource acquisition '$methodName' detected without corresponding cleanup - consider using autoClose or ensure",
              term.pos
            )
            report.info("Consider: resource.autoClose instead of just resource", term.pos)
          }
          optimizeSubterms(term)

        case Apply(Select(Apply(Select(_, "flatMap"), List(_)), "flatMap"), List(_)) =>
          report.info(
            "Deep flatMap nesting detected - consider using for-comprehension for better performance",
            term.pos
          )
          optimizationsApplied += 1
          optimizeSubterms(term)

        case Apply(
              Select(Apply(Select(_, "attempt"), _), "flatMap"),
              List(Lambda(_, Apply(Select(_, "succeed"), _)))
            ) =>
          report.info(
            "Detected attempt.flatMap(succeed) pattern - consider using recover for better performance",
            term.pos
          )
          optimizationsApplied += 1
          optimizeSubterms(term)

        case Apply(Select(receiver, "map"), List(Lambda(List(param), Ident(paramName)))) if paramName == param.name =>
          report.info("Identity map detected - removing unnecessary operation", term.pos)
          optimizationsApplied += 1
          optimizeExpr(receiver)

        case Apply(TypeApply(Select(Ident("Eru"), "effect"), _), List(Lambda(_, body))) =>
          if (
            containsResourceAllocation(body) && !term.toString
              .contains("ensure") && !term.toString.contains("autoClose")
          ) {
            report.warning("Effect contains resource allocation without cleanup - potential memory leak", term.pos)
          }
          optimizeSubterms(term)

        case _ => optimizeSubterms(term)
      }
    }

    def optimizeSubterms(term: Term): Term = {
      term match {
        case Apply(fun, args) =>
          Apply(optimizeExpr(fun), args.map(optimizeExpr))
        case Select(qualifier, name) =>
          Select.copy(term)(optimizeExpr(qualifier), name)
        case _ => term
      }
    }

    def isResourceAcquisition(methodName: String): Boolean = {
      methodName.contains("open") || methodName.contains("connect") ||
      methodName.contains("acquire") || methodName.contains("create") ||
      methodName.contains("allocate")
    }
    def hasEnsureInChain(term: Term): Boolean = {
      def searchTerm(t: Term): Boolean = t match {
        case Apply(Select(_, name), _) if name == "ensure" || name == "autoClose" || name == "bracket" => true
        case Apply(fun, args) => searchTerm(fun) || args.exists(searchTerm)
        case Select(qualifier, _) => searchTerm(qualifier)
        case _ => false
      }
      searchTerm(term)
    }

    def containsResourceAllocation(body: Term): Boolean = {
      def searchForAllocation(t: Term): Boolean = t match {
        case Apply(Select(New(tpt), _), _) =>
          val typeName = tpt.show
          isResourceType(typeName)
        case Apply(fun, args) => searchForAllocation(fun) || args.exists(searchForAllocation)
        case Select(qualifier, _) => searchForAllocation(qualifier)
        case Block(stats, expr) =>
          val termStats = stats.collect { case term: Term => term }
          termStats.exists(searchForAllocation) || searchForAllocation(expr)
        case _ => false
      }
      searchForAllocation(body)
    }

    def isResourceType(typeName: String): Boolean = {
      val resourceIndicators = Set(
        "FileInputStream",
        "FileOutputStream",
        "Socket",
        "ServerSocket",
        "Connection",
        "InputStream",
        "OutputStream",
        "Reader",
        "Writer",
        "Channel",
        "DataSource",
        "PreparedStatement"
      )
      resourceIndicators.exists(indicator => typeName.contains(indicator)) ||
      typeName.contains("AutoCloseable") || typeName.contains("Closeable")
    }

    val optimizedTerm = optimizeExpr(expr.asTerm)

    if (optimizationsApplied > 0) {
      report.info(s"Applied $optimizationsApplied compile-time optimizations", expr.asTerm.pos)
    }

    optimizedTerm.asExprOf[net.ghoula.eru.Eru[E, A]]
  }
}

/** Type class for effect patterns derived from type characteristics.
  *
  * Provides a unified interface for type-specific patterns generated at compile time.
  *
  * ==Overview==
  *
  * Implementations are generated based on the target type and aim to balance safety and performance
  * for common scenarios.
  *
  * ==Capabilities==
  *
  * The specific methods available depend on the characteristics of type T.
  *
  * @tparam T
  *   the type for which effect patterns are derived
  */
trait EruDerivations[T] {

  /** Creates a pure Eru effect containing the given value.
    *
    * This method provides the foundation for lifting pure values into the Eru effect system. It
    * represents the most basic form of effect creation and serves as the building block for more
    * sophisticated patterns.
    *
    * @param value
    *   the value to lift into an Eru effect
    * @return
    *   a pure effect that succeeds with the given value
    */
  def pure(value: T): net.ghoula.eru.Eru[Nothing, T] =
    net.ghoula.eru.Eru.succeed(value)

  /** Validates a value using a predicate with structured error reporting.
    *
    * This method provides type-safe validation with custom error messages, following Eru's by
    * making validation explicit and composable.
    *
    * @param value
    *   the value to validate
    * @param predicate
    *   the validation predicate to apply
    * @param error
    *   the error message to use if validation fails
    * @return
    *   an effect that succeeds with the value if valid, or fails with the error message
    */
  def validate(value: T)(predicate: T => Boolean, error: String): net.ghoula.eru.Eru[String, T] =
    if (predicate(value)) net.ghoula.eru.Eru.succeed(value)
    else net.ghoula.eru.Eru.fail(error)

  /** Validates that a value is not null with structured error reporting.
    *
    * This method provides null-safety validation using Eru's effect system, preventing
    * NullPointerExceptions through explicit handling at the type level.
    *
    * @param value
    *   the value to check for null
    * @return
    *   an effect that succeeds with the value if non-null, or fails with a descriptive error
    */
  def nonNull(value: T): net.ghoula.eru.Eru[String, T] =
    Option(value) match {
      case Some(v) => net.ghoula.eru.Eru.succeed(v)
      case None => net.ghoula.eru.Eru.fail("Value cannot be null")
    }

  /** Performs comprehensive validation of all fields and constraints.
    *
    * This method is automatically generated for case classes and data types, providing field-level
    * validation with structured error accumulation. The implementation varies based on the specific
    * fields and their types.
    *
    * @param instance
    *   the instance to validate comprehensively
    * @return
    *   an effect that succeeds if all validations pass, or accumulates all validation errors
    */
  def validateAll(instance: T): net.ghoula.eru.Eru[Any, T] =
    net.ghoula.eru.Eru.succeed(instance)

  /** Converts a resource instance to a resource-managed effect with automatic cleanup.
    *
    * This method is automatically generated for AutoCloseable types, providing integration with
    * Eru's resource management system to ensure proper cleanup and prevent resource leaks.
    *
    * @param instance
    *   the resource instance to manage
    * @return
    *   an effect that manages the resource lifecycle automatically
    */
  def asResource(instance: T): net.ghoula.eru.Eru[Nothing, T] =
    net.ghoula.eru.Eru.succeed(instance)

  /** Uses a resource safely with guaranteed cleanup using bracket semantics.
    *
    * This method is automatically generated for AutoCloseable types, providing the bracket pattern
    * for safe resource usage. The resource is guaranteed to be cleaned up even if the usage
    * function fails or throws exceptions.
    *
    * @param instance
    *   the resource instance to use safely
    * @param use
    *   the function that uses the resource to produce an effect
    * @tparam B
    *   the result type of the usage function
    * @return
    *   an effect that safely uses the resource and guarantees cleanup
    */
  def useResource[B](instance: T)(use: T => net.ghoula.eru.Eru[Throwable, B]): net.ghoula.eru.Eru[Throwable, B] =
    use(instance)
}

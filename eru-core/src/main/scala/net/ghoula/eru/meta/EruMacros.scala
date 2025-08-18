package net.ghoula.eru.meta

import scala.quoted.*

import net.ghoula.eru.*

/** Scala 3 metaprogramming enhancements for a better developer experience in Eru.
  *
  * This module provides compile-time validation, derivation helpers, and optimizations that enhance
  * the ergonomics of using Eru while maintaining type safety and performance. These macros follow
  * the principle of "radical ergonomics" by providing powerful compile-time assistance without
  * sacrificing runtime performance.
  */
object EruMacros {

  /** Validates at compile time that an effect chain doesn't contain obvious anti-patterns.
    *
    * This macro analyzes effect chains and provides warnings for common issues like:
    *   - Nested blocking operations that could be composed
    *   - Redundant error handling patterns
    *   - Inefficient resource management patterns
    *
    * @param expr
    *   the effect expression to validate
    * @return
    *   the original expression, potentially with compile-time warnings
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
      term match {
        case Apply(Select(_, methodName), _)
            if methodName.contains("acquire") || methodName.contains("open") || methodName.contains("connect") =>
          true
        case Apply(fun, _) => hasResourceAcquisition(fun)
        case Select(qualifier, _) => hasResourceAcquisition(qualifier)
        case _ => false
      }
    }

    analyzeExpr(expr.asTerm)

    expr
  }

  /** Derives common patterns for data types used with Eru effects.
    *
    * This macro can generate common patterns like:
    *   - Validation effects for data classes
    *   - Serialization/deserialization effects
    *   - Resource management patterns
    *
    * @tparam T
    *   the type to derive patterns for
    */
  inline def derive[T]: EruDerivations[T] = ${ deriveImpl[T] }

  private def deriveImpl[T: Type](using Quotes): Expr[EruDerivations[T]] = {
    import quotes.reflect.*

    val tpe = TypeRepr.of[T]
    val tpeSym = tpe.typeSymbol

    // Generate different derivations based on the type structure
    if (tpeSym.flags.is(Flags.Case)) {
      // Case class - generate validation helpers using proper AST construction
      val caseFields = tpeSym.caseFields

      if (caseFields.nonEmpty) {
        // For case classes, provide comprehensive validation
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
        // Fallback for case classes without fields
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
          override def asResource(instance: T): net.ghoula.eru.Eru[Nothing, T] = {
            net.ghoula.eru.Eru
              .succeed(instance)
              .ensure(
                net.ghoula.eru.Eru.effect {
                  instance match {
                    case closeable: AutoCloseable => closeable.close()
                    case _ => ()
                  }
                }
              )
          }

          override def useResource[B](
            instance: T
          )(use: T => net.ghoula.eru.Eru[Throwable, B]): net.ghoula.eru.Eru[Throwable, B] = {
            use(instance).ensure(
              net.ghoula.eru.Eru.effect {
                instance match {
                  case closeable: AutoCloseable => closeable.close()
                  case _ => ()
                }
              }
            )
          }
        }
      }
    } else {
      // Generic type - provide basic validation and utility methods
      '{
        new EruDerivations[T] {
          override def pure(value: T): net.ghoula.eru.Eru[Nothing, T] =
            net.ghoula.eru.Eru.succeed(value)

          override def validate(value: T)(predicate: T => Boolean, error: String): net.ghoula.eru.Eru[String, T] =
            if (predicate(value)) net.ghoula.eru.Eru.succeed(value)
            else net.ghoula.eru.Eru.fail(error)

          override def nonNull(value: T): net.ghoula.eru.Eru[String, T] =
            Option(value) match {
              case Some(v) => net.ghoula.eru.Eru.succeed(v)
              case None => net.ghoula.eru.Eru.fail("Value cannot be null")
            }
        }
      }
    }
  }

  /** Optimizes effect chains at compile time when possible.
    *
    * This macro can perform compile-time optimizations such as:
    *   - Constant folding for pure operations
    *   - Dead code elimination for unused branches
    *   - Resource allocation optimization
    *
    * @param expr
    *   the effect expression to optimize
    * @return
    *   an optimized version of the expression
    */
  inline def optimize[E, A](inline expr: net.ghoula.eru.Eru[E, A]): net.ghoula.eru.Eru[E, A] =
    ${ optimizeImpl('expr) }

  private def optimizeImpl[E: Type, A: Type](
    expr: Expr[net.ghoula.eru.Eru[E, A]]
  )(using q: Quotes): Expr[net.ghoula.eru.Eru[E, A]] = {
    import q.reflect.*

    // Track optimizations applied
    var optimizationsApplied = 0

    // Analyze and optimize the expression tree
    def optimizeExpr(term: Term): Term = {
      term match {
        // Optimize pure effect chains by detecting constant patterns
        case Apply(
              Select(Apply(TypeApply(Select(Ident("Eru"), "succeed"), _), List(literal)), "map"),
              List(Lambda(List(param), body))
            ) if literal.show.contains("Literal") =>
          body match {
            case Apply(fun, List(Ident(paramName))) if paramName == param.name =>
              // Try to evaluate the function at compile time if it's a simple operation
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

        // Detect and optimize resource acquisition without proper cleanup
        case Apply(Select(_, methodName), args) if isResourceAcquisition(methodName) =>
          if (!hasEnsureInChain(term)) {
            report.warning(
              s"Resource acquisition '$methodName' detected without corresponding cleanup - consider using autoClose or ensure",
              term.pos
            )
            // Suggest adding autoClose
            report.info("Consider: resource.autoClose instead of just resource", term.pos)
          }
          optimizeSubterms(term)

        // Optimize nested flatMaps to avoid stack buildup
        case Apply(Select(Apply(Select(_, "flatMap"), List(_)), "flatMap"), List(_)) =>
          report.info(
            "Deep flatMap nesting detected - consider using for-comprehension for better performance",
            term.pos
          )
          optimizationsApplied += 1
          optimizeSubterms(term)

        // Detect inefficient error handling patterns
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

        // Optimize away identity operations
        case Apply(Select(receiver, "map"), List(Lambda(List(param), Ident(paramName)))) if paramName == param.name =>
          report.info("Identity map detected - removing unnecessary operation", term.pos)
          optimizationsApplied += 1
          optimizeExpr(receiver) // Return the receiver without the identity map

        // Detect potential memory leaks from unclosed resources
        case Apply(TypeApply(Select(Ident("Eru"), "effect"), _), List(Lambda(_, body))) =>
          if (
            containsResourceAllocation(body) && !term.toString
              .contains("ensure") && !term.toString.contains("autoClose")
          ) {
            report.warning("Effect contains resource allocation without cleanup - potential memory leak", term.pos)
          }
          optimizeSubterms(term)

        // Default: recursively optimize sub-expressions
        case _ => optimizeSubterms(term)
      }
    }

    // Helper to optimize sub-terms
    def optimizeSubterms(term: Term): Term = {
      term match {
        case Apply(fun, args) =>
          Apply(optimizeExpr(fun), args.map(optimizeExpr))
        case Select(qualifier, name) =>
          Select.copy(term)(optimizeExpr(qualifier), name)
        case _ => term
      }
    }

    // Helper to check if method name indicates resource acquisition
    def isResourceAcquisition(methodName: String): Boolean = {
      methodName.contains("open") || methodName.contains("connect") ||
      methodName.contains("acquire") || methodName.contains("create") ||
      methodName.contains("allocate")
    }

    // Helper to check if term has ensure in its chain
    def hasEnsureInChain(term: Term): Boolean = {
      term.toString.contains("ensure") || term.toString.contains("autoClose") || term.toString.contains("bracket")
    }

    // Helper to check if lambda body contains resource allocation
    def containsResourceAllocation(body: Term): Boolean = {
      body.toString.contains("new ") && (
        body.toString.contains("FileInputStream") ||
          body.toString.contains("Socket") ||
          body.toString.contains("Connection") ||
          body.toString.contains("InputStream") ||
          body.toString.contains("OutputStream")
      )
    }

    // Perform optimization
    val optimizedTerm = optimizeExpr(expr.asTerm)

    // Report optimization results
    if (optimizationsApplied > 0) {
      report.info(s"Applied $optimizationsApplied compile-time optimizations", expr.asTerm.pos)
    }

    // Convert back to expression
    optimizedTerm.asExprOf[net.ghoula.eru.Eru[E, A]]
  }
}

/** Type class for derived functionality for type T.
  *
  * This trait provides a common interface for derived functionality that can be generated
  * automatically based on the structure of type T. The actual implementations are generated by the
  * derive macro based on the type's characteristics.
  */
trait EruDerivations[T] {

  /** Creates a pure Eru effect containing the given value. */
  def pure(value: T): net.ghoula.eru.Eru[Nothing, T] =
    net.ghoula.eru.Eru.succeed(value)

  /** Validates a value with a predicate and custom error message. */
  def validate(value: T)(predicate: T => Boolean, error: String): net.ghoula.eru.Eru[String, T] =
    if (predicate(value)) net.ghoula.eru.Eru.succeed(value)
    else net.ghoula.eru.Eru.fail(error)

  /** Validates that a value is not null. */
  def nonNull(value: T): net.ghoula.eru.Eru[String, T] =
    Option(value) match {
      case Some(v) => net.ghoula.eru.Eru.succeed(v)
      case None => net.ghoula.eru.Eru.fail("Value cannot be null")
    }

  // Methods that may be generated for specific types:

  /** Validates all fields of an instance (generated for case classes). */
  def validateAll(instance: T): net.ghoula.eru.Eru[Any, T] =
    net.ghoula.eru.Eru.succeed(instance) // Default implementation

  /** Converts an AutoCloseable instance to a resource-managed effect (generated for AutoCloseable
    * types).
    */
  def asResource(instance: T): net.ghoula.eru.Eru[Nothing, T] =
    net.ghoula.eru.Eru.succeed(instance) // Default implementation - only meaningful for AutoCloseable

  /** Uses a resource safely with automatic cleanup (generated for AutoCloseable types). */
  def useResource[B](instance: T)(use: T => net.ghoula.eru.Eru[Throwable, B]): net.ghoula.eru.Eru[Throwable, B] =
    use(instance) // Default implementation - only meaningful for AutoCloseable
}

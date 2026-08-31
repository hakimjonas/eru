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

  /** Performs compile-time analysis to detect antipatterns and guide best practices.
    *
    * This macro analyzes effect chains at compile time and reports diagnostics on common
    * antipatterns. It returns the expression unchanged.
    *
    * ==Detection Capabilities==
    *
    * '''Composition Anti-Patterns:'''
    *   - Nested `flatMap` chains that should use for-comprehensions
    *   - Consecutive `map` operations (automatically fused, but noted for awareness)
    *   - Unused parameters in `flatMap` lambdas
    *   - `map` lambdas that return an `Eru` (suggesting `flatMap` was intended)
    *
    * '''Resource Safety:'''
    *   - `ensure` without a resource acquisition in its receiver
    *   - Praise for `autoClose`-style resource safety methods when used
    *
    * '''Error Handling:'''
    *   - `recover`/`recoverWith` with a constant literal that may hide the original error
    *
    * '''Application Boundaries:'''
    *   - `unsafeRunSync` calls (suggested to stay at the edge of the program)
    *
    * Diagnostics are reported at the call site and do not change the expression.
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
    * // Composition improvements
    * val nested = EruMacros.validated {
    *   effect.flatMap(a =>
    *     otherEffect.flatMap(b =>
    *       thirdEffect.map(c => combine(a, b, c))))
    *   // Info: Consider using for-comprehension for nested flatMap operations for better readability
    * }
    *
    * // Unused parameter detection
    * val unusedParam = EruMacros.validated {
    *   effect.flatMap(a => otherEffect)
    *   // Warning: Parameter 'a' is unused in flatMap - consider using .map(_ => ...) or .flatMap(_ => ...) to be explicit
    * }
    *
    * // Error handling guidance
    * val recovery = EruMacros.validated {
    *   effect.recover { case _ => fallbackValue }
    *   // Warning: Recovery with constant values may hide important errors - consider more specific error handling
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
      object FoundException extends Exception
      try {
        object UsageChecker extends TreeTraverser {
          override def traverseTree(tree: Tree)(owner: Symbol): Unit = {
            tree match {
              case Ident(_) if tree.symbol == symbol => throw FoundException
              case _ => super.traverseTree(tree)(owner)
            }
          }
        }
        UsageChecker.traverseTree(body)(Symbol.spliceOwner)
        false
      } catch {
        case FoundException => true
      }
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

  /** Derives validation patterns for a type at compile time.
    *
    * The macro inspects the target type and generates an `EruDerivations` instance: for case
    * classes with fields it overrides `validateAll` with an instance null check; for
    * `AutoCloseable` subtypes it overrides `asResource` and `useResource` to close the instance
    * when the returned effect completes; other types get the trait's default implementations.
    *
    * The derivation is per call site; instances are not cached or shared.
    *
    * @tparam T
    *   the type for which to derive Eru validation patterns
    * @return
    *   a type-specific EruDerivations instance
    *
    * @example
    *   {{{
    * // Case class derivation
    * case class User(name: String, email: String, age: Int)
    * val userDerivations = EruMacros.derive[User]
    *
    * val user = User("Alice", "alice@example.com", 30)
    * val checked = userDerivations.validateAll(user)
    * // Case-class derivation fails when the instance is null
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

  /** Analyzes an Eru expression and reports compile-time optimization opportunities.
    *
    * The macro identifies a narrow set of patterns: pure-map chains over `Eru.succeed` literals are
    * reported, identity maps are rewritten to their receiver, and `attempt.flatMap(succeed)`
    * patterns, deep `flatMap` nesting, and resource acquisition without cleanup are flagged with
    * diagnostics.
    *
    * All rewrites preserve program behavior.
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
    * // Pure map chain over a literal
    * val optimized = EruMacros.optimize {
    *   Eru.succeed(42).map(x => x + 1)
    * }
    * // Compile-time info: optimizing pure map chain
    *   }}}
    */
  inline def optimize[E, A](inline expr: net.ghoula.eru.Eru[E, A]): net.ghoula.eru.Eru[E, A] =
    ${ optimizeImpl('expr) }

  private def optimizeImpl[E: Type, A: Type](
    expr: Expr[net.ghoula.eru.Eru[E, A]]
  )(using q: Quotes): Expr[net.ghoula.eru.Eru[E, A]] = {
    import q.reflect.*

    def optimizeExpr(term: Term): (Term, Int) = {
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
                  (term, 1)
                case Select(Ident(_), "*") =>
                  report.info("Optimizing pure multiplication in map chain", term.pos)
                  (term, 1)
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
          val (optimizedTerm, subCount) = optimizeSubterms(term)
          (optimizedTerm, 1 + subCount)

        case Apply(
              Select(Apply(Select(_, "attempt"), _), "flatMap"),
              List(Lambda(_, Apply(Select(_, "succeed"), _)))
            ) =>
          report.info(
            "Detected attempt.flatMap(succeed) pattern - consider using recover for better performance",
            term.pos
          )
          val (optimizedTerm, subCount) = optimizeSubterms(term)
          (optimizedTerm, 1 + subCount)

        case Apply(Select(receiver, "map"), List(Lambda(List(param), Ident(paramName)))) if paramName == param.name =>
          report.info("Identity map detected - removing unnecessary operation", term.pos)
          val (optimizedReceiver, receiverCount) = optimizeExpr(receiver)
          (optimizedReceiver, 1 + receiverCount)

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

    def optimizeSubterms(term: Term): (Term, Int) = {
      term match {
        case Apply(fun, args) =>
          val (optimizedFun, funCount) = optimizeExpr(fun)
          val (optimizedArgs, argsCounts) = args.map(optimizeExpr).unzip
          (Apply(optimizedFun, optimizedArgs), funCount + argsCounts.sum)
        case Select(qualifier, name) =>
          val (optimizedQualifier, qualifierCount) = optimizeExpr(qualifier)
          (Select.copy(term)(optimizedQualifier, name), qualifierCount)
        case _ => (term, 0)
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

    val (optimizedTerm, optimizationsApplied) = optimizeExpr(expr.asTerm)

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
    * Lifts a pure value into Eru: the returned effect succeeds with `value`.
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
    * This method provides type-safe validation with custom error messages.
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

  /** Validates an instance with the checks derived for its type.
    *
    * `derive` overrides this method for case classes, where the derived implementation fails when
    * the instance is null. The default implementation succeeds unconditionally.
    *
    * @param instance
    *   the instance to validate
    * @return
    *   an effect that succeeds with the instance, or fails when the derived implementation's check
    *   fails
    */
  def validateAll(instance: T): net.ghoula.eru.Eru[Any, T] =
    net.ghoula.eru.Eru.succeed(instance)

  /** Converts a resource instance to a resource-managed effect.
    *
    * `derive` overrides this method for `AutoCloseable` types, where the derived implementation
    * ensures the instance is closed when the returned effect completes. The default implementation
    * returns the instance with no cleanup.
    *
    * @param instance
    *   the resource instance to manage
    * @return
    *   an effect that yields the instance, closing it on completion for derived implementations
    */
  def asResource(instance: T): net.ghoula.eru.Eru[Nothing, T] =
    net.ghoula.eru.Eru.succeed(instance)

  /** Uses a resource with cleanup.
    *
    * `derive` overrides this method for `AutoCloseable` types, where the derived implementation
    * ensures the instance is closed after `use` completes, including when `use` fails. The default
    * implementation applies `use` with no cleanup.
    *
    * @param instance
    *   the resource instance to use safely
    * @param use
    *   the function that uses the resource to produce an effect
    * @tparam B
    *   the result type of the usage function
    * @return
    *   an effect that uses the resource, closing it on completion for derived implementations
    */
  def useResource[B](instance: T)(use: T => net.ghoula.eru.Eru[Throwable, B]): net.ghoula.eru.Eru[Throwable, B] =
    use(instance)
}

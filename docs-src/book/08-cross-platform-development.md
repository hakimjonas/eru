# Chapter 8: Cross-Platform Development

*"One codebase, multiple targets. Eru's cross-platform design lets you write once, run everywhere."*

Eru is designed from the ground up as a cross-platform effect system. Your core business logic can run unchanged on both the JVM and Scala Native, with only runtime differences in how concurrency is handled. This chapter explores the execution models and patterns for writing truly portable code.

## Understanding Platform Differences

Eru's architecture cleanly separates pure effect descriptions from platform-specific runtime execution:

```scala mdoc
import net.ghoula.eru.prelude.*

// This core business logic works identically on JVM and Native
case class User(id: Int, name: String, email: String)

def validateUser(user: User): Eru[String, User] = {
  if (user.email.contains("@")) {
    Eru.succeed(user)
  } else {
    Eru.fail("Invalid email format")
  }
}

def processUser(user: User): Eru[String, String] = {
  validateUser(user).map { validUser =>
    s"Processing user ${validUser.name} (${validUser.email})"
  }
}

// Test the business logic
val testUser = User(1, "Alice", "alice@example.com")
val result = processUser(testUser).unsafeRunSync()
println(result)
```

The same `Eru` programs work on both platforms - only the runtime execution differs.

## Platform-Specific Capabilities

### JVM Execution Model

On the JVM, Eru provides full concurrency support with fibers and structured concurrency:

```scala mdoc
// JVM: Full concurrency support available
def simulateJVMOperation(): Eru[String, String] = Eru.effect {
  // On JVM: Can use virtual threads, complex concurrency
  s"JVM operation completed at ${System.currentTimeMillis()}"
}.mapError(_.getMessage)

val jvmResult = simulateJVMOperation().unsafeRunSync()
println(jvmResult)
```

### Native Execution Model

Scala Native execution focuses on synchronous operations with excellent startup performance:

```scala mdoc
// Native: Synchronous operations, fast startup
def simulateNativeOperation(): Eru[String, String] = Eru.effect {
  // On Native: Optimized for single-threaded, low-latency execution
  s"Native operation completed at ${System.currentTimeMillis()}"
}.mapError(_.getMessage)

val nativeResult = simulateNativeOperation().unsafeRunSync()
println(nativeResult)
```

The API is identical - the difference is in runtime characteristics, not code structure.

## Writing Platform-Agnostic Code

### Core Business Logic Patterns

Keep your domain logic pure and platform-independent:

```scala mdoc
// Domain modeling works identically everywhere
enum ValidationError:
  case EmptyName, InvalidEmail, InvalidAge

case class Customer(name: String, email: String, age: Int)

object CustomerValidation {
  def validateName(name: String): Eru[ValidationError, String] =
    if (name.trim.nonEmpty) Eru.succeed(name.trim) else Eru.fail(ValidationError.EmptyName)
  
  def validateEmail(email: String): Eru[ValidationError, String] =
    if (email.contains("@")) Eru.succeed(email) else Eru.fail(ValidationError.InvalidEmail)
  
  def validateAge(age: Int): Eru[ValidationError, Int] =
    if (age >= 18) Eru.succeed(age) else Eru.fail(ValidationError.InvalidAge)
  
  def validateCustomer(name: String, email: String, age: Int): Eru[ValidationError, Customer] = {
    for {
      validName  <- validateName(name)
      validEmail <- validateEmail(email)
      validAge   <- validateAge(age)
    } yield Customer(validName, validEmail, validAge)
  }
}

// Test validation logic - works identically on both platforms
val validationTest = CustomerValidation.validateCustomer("Alice", "alice@example.com", 25)
val validationResult = validationTest.unsafeRunSync()
println(s"Validated customer: $validationResult")
```

### Resource Management Patterns

Resource cleanup works consistently across platforms:

```scala mdoc
case class FileHandle(name: String) {
  def read(): String = s"Contents of $name"
  def close(): Unit = println(s"Closed $name")
}

def openFile(name: String): Eru[String, FileHandle] = 
  Eru.succeed(FileHandle(name))

def readFile(handle: FileHandle): Eru[String, String] = 
  Eru.succeed(handle.read())

// Resource management pattern works on both JVM and Native
def safeFileRead(filename: String): Eru[String, String] = {
  openFile(filename).flatMap { handle =>
    readFile(handle).ensure(Eru.effect(handle.close()).mapError(_.getMessage))
  }
}

val fileResult = safeFileRead("config.txt").unsafeRunSync()
println(fileResult)
```

### Error Handling Patterns

Error recovery strategies remain consistent:

```scala mdoc
def unreliableService(): Eru[String, String] = {
  // Simulate occasional failures
  if (System.currentTimeMillis() % 3 == 0) {
    Eru.succeed("Service response")
  } else {
    Eru.fail("Service temporarily unavailable")
  }
}

def robustServiceCall(): Eru[String, String] = {
  unreliableService().fallback {
    case "Service temporarily unavailable" => "Using cached data"
    case "Network timeout" => "Using default response"
  }
}

val serviceResult = robustServiceCall().unsafeRunSync()
println(serviceResult)
```

## Platform-Specific Optimizations

### JVM Optimizations

On the JVM, you can leverage virtual threads and complex I/O patterns:

```scala mdoc
// JVM-specific: Can handle blocking I/O safely
def jvmFileOperation(filename: String): Eru[Throwable, String] = Eru.effect {
  // This would use virtual threads on JVM for non-blocking execution
  s"JVM processed file: $filename"
}

// JVM example maintains the same Eru patterns
val jvmFileResult = jvmFileOperation("large-file.txt").mapError(_.getMessage).attempt.unsafeRunSync()
println(s"JVM file operation: $jvmFileResult")
```

### Native Optimizations

On Native, focus on fast startup and minimal overhead:

```scala mdoc
// Native-optimized: Fast startup, minimal allocation
def nativeCalculation(numbers: List[Int]): Eru[String, Int] = Eru.effect {
  // Native excels at CPU-intensive, synchronous operations
  numbers.sum
}.mapError(_.getMessage)

val numbers = (1 to 100).toList
val calculationResult = nativeCalculation(numbers).unsafeRunSync()
println(s"Native calculation result: $calculationResult")
```

## Configuration and Environment

### Environment-Aware Configuration

Handle platform differences through configuration, not code branching:

```scala mdoc
case class AppConfig(
  maxConnections: Int,
  timeoutMs: Int,
  platformName: String
)

def loadConfig(): Eru[String, AppConfig] = Eru.effect {
  // Configuration loading works the same way on both platforms
  val platform = System.getProperty("java.vm.name", "unknown")
  
  if (platform.contains("Native")) {
    AppConfig(maxConnections = 1, timeoutMs = 1000, platformName = "Native")
  } else {
    AppConfig(maxConnections = 100, timeoutMs = 5000, platformName = "JVM")
  }
}.mapError(_.getMessage)

def createService(config: AppConfig): Eru[String, String] = {
  Eru.succeed(s"Service configured for ${config.platformName} with ${config.maxConnections} connections")
}

// Configuration-driven platform adaptation
val configuredService = for {
  config  <- loadConfig()
  service <- createService(config)
} yield service

val configResult = configuredService.unsafeRunSync()
println(configResult)
```

### Feature Detection Patterns

Use capability detection rather than platform detection:

```scala mdoc
trait PlatformCapabilities {
  def supportsConcurrency: Boolean
  def supportsNativeIO: Boolean
  def supportsVirtualThreads: Boolean
}

object JVMCapabilities extends PlatformCapabilities {
  def supportsConcurrency = true
  def supportsNativeIO = true
  def supportsVirtualThreads = true
}

object NativeCapabilities extends PlatformCapabilities {
  def supportsConcurrency = false
  def supportsNativeIO = true  
  def supportsVirtualThreads = false
}

// Detect capabilities at runtime (simplified example)
def detectCapabilities(): PlatformCapabilities = {
  if (System.getProperty("java.vm.name", "").contains("Native")) {
    NativeCapabilities
  } else {
    JVMCapabilities
  }
}

def adaptToCapabilities(caps: PlatformCapabilities): Eru[String, String] = {
  if (caps.supportsConcurrency) {
    Eru.succeed("Using concurrent processing")
  } else {
    Eru.succeed("Using sequential processing")
  }
}

val capabilityTest = adaptToCapabilities(detectCapabilities()).unsafeRunSync()
println(capabilityTest)
```

## When to Write Platform-Specific Code

While the goal is to be platform-agnostic, you may occasionally need to call a platform-specific API or handle platform differences. The best way to handle this is by defining a common interface in your shared code and implementing it in your JVM and Native projects.

### Platform-Specific Service Pattern

```scala mdoc
// shared/src/main/scala/PlatformService.scala
trait PlatformService {
  def getPlatformInfo: Eru[Nothing, String]
  def getAvailableMemory: Eru[String, Long]
  def getCurrentTime: Eru[Nothing, Long]
}

// JVM implementation (would be in jvm/src/main/scala/)
class JVMPlatformService extends PlatformService {
  def getPlatformInfo: Eru[Nothing, String] = 
    Eru.succeed("JVM Platform")
  
  def getAvailableMemory: Eru[String, Long] = 
    Eru.effect(Runtime.getRuntime.freeMemory()).mapError(_.getMessage)
  
  def getCurrentTime: Eru[Nothing, Long] = 
    Eru.succeed(System.currentTimeMillis())
}

// Native implementation (would be in native/src/main/scala/)
class NativePlatformService extends PlatformService {
  def getPlatformInfo: Eru[Nothing, String] = 
    Eru.succeed("Native Platform")
  
  def getAvailableMemory: Eru[String, Long] = 
    Eru.succeed(1024L * 1024L * 512L) // Fixed for demonstration
  
  def getCurrentTime: Eru[Nothing, Long] = 
    Eru.succeed(System.currentTimeMillis())
}

// Application code uses the abstraction
def demonstratePlatformService(service: PlatformService): Eru[String, String] = {
  for {
    platform <- service.getPlatformInfo
    memory   <- service.getAvailableMemory
    time     <- service.getCurrentTime
  } yield s"Platform: $platform, Memory: ${memory / 1024 / 1024}MB, Time: $time"
}

// Usage with dependency injection
val platformService: PlatformService = new JVMPlatformService() // Or NativePlatformService
val platformInfo = demonstratePlatformService(platformService).attempt.unsafeRunSync()
println(s"Platform info: $platformInfo")
```

### Conditional Compilation Pattern

For smaller platform differences, you can use simple conditional logic:

```scala mdoc
def getPlatformSpecificValue(): String = {
  val vmName = System.getProperty("java.vm.name", "unknown")
  if (vmName.contains("Native")) {
    "Native-optimized value"
  } else {
    "JVM-optimized value"  
  }
}

def platformAwareOperation(): Eru[String, String] = {
  Eru.effect {
    val platformValue = getPlatformSpecificValue()
    s"Using $platformValue for this operation"
  }.mapError(_.getMessage)
}

val platformResult = platformAwareOperation().unsafeRunSync()
println(platformResult)
```

### Build Configuration Pattern

For complex platform differences, use build configuration:

```scala mdoc
// Configuration that changes based on platform
case class PlatformConfig(
  maxConcurrentConnections: Int,
  defaultTimeoutMs: Int,
  useNativeIO: Boolean
)

def loadPlatformConfig(): PlatformConfig = {
  val isNative = System.getProperty("java.vm.name", "").contains("Native")
  
  if (isNative) {
    PlatformConfig(
      maxConcurrentConnections = 1,      // Native: Single-threaded
      defaultTimeoutMs = 1000,           // Native: Lower latency
      useNativeIO = true
    )
  } else {
    PlatformConfig(
      maxConcurrentConnections = 100,    // JVM: Highly concurrent
      defaultTimeoutMs = 5000,           // JVM: Higher tolerance
      useNativeIO = false
    )
  }
}

def createPlatformOptimizedService(): Eru[String, String] = {
  val config = loadPlatformConfig()
  
  Eru.succeed {
    s"Service configured for ${if (config.useNativeIO) "Native" else "JVM"} with ${config.maxConcurrentConnections} max connections"
  }
}

val serviceConfig = createPlatformOptimizedService().unsafeRunSync()
println(serviceConfig)
```

The key principle is to **isolate platform differences behind abstractions** while keeping your core business logic completely platform-agnostic.

## Testing Across Platforms

### Shared Test Logic

Write tests that verify behavior on both platforms:

```scala mdoc
// Test suite that works on both JVM and Native
def testBusinessLogic(): Eru[String, Boolean] = {
  val testCases = List(
    ("valid@email.com", true),
    ("invalid-email", false),
    ("another@valid.com", true)
  )
  
  val results = testCases.map { (email, expected) =>
    val validation = if (email.contains("@")) Eru.succeed(true) else Eru.fail("Invalid")
    val result = validation.attempt.map(_.isSuccess)
    result.unsafeRunSync() == expected
  }
  
  Eru.succeed(results.forall(identity))
}

val testResult = testBusinessLogic().unsafeRunSync()
println(s"All tests passed: $testResult")
```

### Platform-Specific Test Helpers

Create abstractions for platform differences in testing:

```scala mdoc
trait TestEnvironment {
  def createTempFile(): Eru[String, String]
  def cleanup(): Eru[String, Unit]
}

// This would be implemented differently for JVM vs Native
class PortableTestEnvironment extends TestEnvironment {
  def createTempFile(): Eru[String, String] = 
    Eru.succeed("temp-file-" + System.currentTimeMillis())
  
  def cleanup(): Eru[String, Unit] = 
    Eru.effect(println("Cleanup completed")).mapError(_.getMessage)
}

def runPortableTest(env: TestEnvironment): Eru[String, String] = {
  for {
    tempFile <- env.createTempFile()
    result   <- Eru.succeed(s"Test used file: $tempFile")
    _        <- env.cleanup()
  } yield result
}

val portableTest = runPortableTest(new PortableTestEnvironment()).unsafeRunSync()
println(portableTest)
```

## Migration Strategies

### Gradual Migration Pattern

Start with shared core logic, then add platform-specific features:

```scala mdoc
// Phase 1: Core business logic (works everywhere)
def processOrder(orderId: Int): Eru[String, String] = {
  Eru.succeed(s"Order $orderId processed")
}

// Phase 2: Add platform-aware optimizations while keeping the same interface
def optimizedProcessOrder(orderId: Int): Eru[String, String] = {
  val baseProcessing = processOrder(orderId)
  
  // Same API, different internal optimizations per platform
  baseProcessing.map { result =>
    s"$result (optimized for current platform)"
  }
}

val migrationTest = optimizedProcessOrder(12345).unsafeRunSync()
println(migrationTest)
```

### Legacy Integration Pattern

Wrap existing platform-specific code in Eru effects:

```scala mdoc
// Legacy system integration - works on both platforms
def legacyDatabaseCall(query: String): String = {
  // Simulate legacy database call
  s"Legacy result for: $query"
}

def modernDatabaseCall(query: String): Eru[String, String] = {
  Eru.effect(legacyDatabaseCall(query)).mapError(_.getMessage)
}

// Modern code can use the wrapped legacy system
val legacyIntegration = modernDatabaseCall("SELECT * FROM users").unsafeRunSync()
println(legacyIntegration)
```

## Performance Considerations

### JVM Performance Characteristics

```scala mdoc
// JVM: Optimized for throughput and concurrent workloads
def jvmOptimizedBatch(items: List[String]): Eru[String, List[String]] = {
  // On JVM: Can process large batches efficiently
  Eru.succeed(items.map(item => s"JVM-processed: $item"))
}

val jvmBatch = jvmOptimizedBatch(List("item1", "item2", "item3")).unsafeRunSync()
println(s"JVM batch result: ${jvmBatch.size} items")
```

### Native Performance Characteristics

```scala mdoc
// Native: Optimized for latency and startup time
def nativeOptimizedSingle(item: String): Eru[String, String] = {
  // On Native: Excels at single-item, low-latency processing
  Eru.succeed(s"Native-processed: $item")
}

val nativeSingle = nativeOptimizedSingle("critical-item").unsafeRunSync()
println(s"Native single result: $nativeSingle")
```

## Key Takeaways

Cross-platform development with Eru provides several advantages:

**Unified Programming Model**: The same `Eru[E, A]` programs work identically on both JVM and Native platforms.

**Platform-Appropriate Execution**: The runtime adapts to platform capabilities while maintaining consistent APIs.

**Configuration-Driven Adaptation**: Handle platform differences through configuration rather than code branching.

**Testable Across Platforms**: Shared test logic validates behavior consistency across execution environments.

**Performance Optimization**: Each platform can optimize for its strengths while maintaining code compatibility.

**Gradual Migration**: Start with core logic and gradually add platform-specific optimizations.

## What's Next

In Chapter 9, we'll explore Eru's concurrency model with fibers and structured concurrency patterns, focusing on the JVM runtime capabilities while understanding how the patterns apply to both platforms.

---

*"The best cross-platform code is the code that doesn't know it's cross-platform."*
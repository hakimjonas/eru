#!/usr/bin/env scala

//> using scala "3.7.2"
//> using repository "https://oss.sonatype.org/content/repositories/snapshots/"
//> using dep "net.ghoula::eru-core:0.9-SNAPSHOT"
//> using dep "net.ghoula::eru-runtime:0.9-SNAPSHOT"

import net.ghoula.eru.prelude.*

@main def verifySnapshot(): Unit = {
  println("🔍 Verifying Eru SNAPSHOT accessibility...")

  // Test basic functionality
  val test = for {
    _ <- Eru.effect(println("✅ Basic Eru.effect works"))
    ref <- Eru.ref(42)
    value <- ref.get
    _ <- Eru.effect(println(s"✅ Ref operations work: $value"))
  } yield value

  val result = test.unsafeRunSync()
  println(s"✅ SNAPSHOT verification complete: $result")
  println("🚀 Ready for Valar integration!")
}

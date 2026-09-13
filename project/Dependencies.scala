import sbt._

object Dependencies {
  val munitVersion = "1.3.6"
  val munitScalacheckVersion = "1.3.1"
  val munit = "org.scalameta" %% "munit" % munitVersion
  val munitScalacheck = "org.scalameta" %% "munit-scalacheck" % munitScalacheckVersion
}

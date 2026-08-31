import sbt._

object Dependencies {
  val munitVersion = "1.3.5"
  val munitScalacheckVersion = "1.3.0"

  val munit = "org.scalameta" %% "munit" % munitVersion
  val munitScalacheck = "org.scalameta" %% "munit-scalacheck" % munitScalacheckVersion
}

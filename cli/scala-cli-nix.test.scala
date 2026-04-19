//> using dep org.scalameta::munit::1.3.0

class DirectiveParsingTests extends munit.FunSuite {

  // --- parseDirectivesFromLines ---

  test("no directives: defaults to jvm, no version") {
    val (platforms, versions) = parseDirectivesFromLines(List(
      "object Main extends App { println(\"hello\") }"
    ))
    assertEquals(platforms, List("jvm"))
    assertEquals(versions, List(None))
  }

  test("single platform: jvm") {
    val (platforms, _) = parseDirectivesFromLines(List("//> using platform jvm"))
    assertEquals(platforms, List("jvm"))
  }

  test("single platform: native") {
    val (platforms, _) = parseDirectivesFromLines(List("//> using platform native"))
    assertEquals(platforms, List("native"))
  }

  test("single platform: scala-native normalised to native") {
    val (platforms, _) = parseDirectivesFromLines(List("//> using platform scala-native"))
    assertEquals(platforms, List("native"))
  }

  test("multiple platforms on one line") {
    val (platforms, _) = parseDirectivesFromLines(List("//> using platform jvm native"))
    assertEquals(platforms, List("jvm", "native"))
  }

  test("platforms directive (plural)") {
    val (platforms, _) = parseDirectivesFromLines(List("//> using platforms jvm native"))
    assertEquals(platforms, List("jvm", "native"))
  }

  test("platforms directive with scala-native normalised") {
    val (platforms, _) = parseDirectivesFromLines(List("//> using platforms jvm scala-native"))
    assertEquals(platforms, List("jvm", "native"))
  }

  test("platform directives across multiple lines are merged and deduplicated") {
    val (platforms, _) = parseDirectivesFromLines(List(
      "//> using platform jvm",
      "//> using platform native",
      "//> using platform jvm"
    ))
    assertEquals(platforms, List("jvm", "native"))
  }

  test("single scala version") {
    val (_, versions) = parseDirectivesFromLines(List("//> using scala 3.6.4"))
    assertEquals(versions, List(Some("3.6.4")))
  }

  test("multiple scala versions on one line") {
    val (_, versions) = parseDirectivesFromLines(List("//> using scala 3.6.4 3.5.0"))
    assertEquals(versions, List(Some("3.6.4"), Some("3.5.0")))
  }

  test("scala version directives across multiple lines are merged and deduplicated") {
    val (_, versions) = parseDirectivesFromLines(List(
      "//> using scala 3.6.4",
      "//> using scala 3.5.0",
      "//> using scala 3.6.4"
    ))
    assertEquals(versions, List(Some("3.6.4"), Some("3.5.0")))
  }

  test("scalacOption directive is not mistaken for scala version") {
    val (_, versions) = parseDirectivesFromLines(List(
      "//> using scalacOption -no-indent"
    ))
    assertEquals(versions, List(None))
  }

  test("scalafixDependency directive is not mistaken for scala version") {
    val (_, versions) = parseDirectivesFromLines(List(
      "//> using scalafix some.Rule"
    ))
    assertEquals(versions, List(None))
  }

  test("cross: both platform and scala version") {
    val (platforms, versions) = parseDirectivesFromLines(List(
      "//> using platform jvm native",
      "//> using scala 3.6.4"
    ))
    assertEquals(platforms, List("jvm", "native"))
    assertEquals(versions, List(Some("3.6.4")))
  }

  test("realistic header: cross example") {
    val (platforms, versions) = parseDirectivesFromLines(List(
      "//> using platform jvm native",
      "//> using scala 3.6.4",
      "//> using dep org.typelevel::cats-effect::3.7.0",
      "",
      "import cats.effect.{IO, IOApp}",
      "object Main extends IOApp.Simple { def run = IO.unit }"
    ))
    assertEquals(platforms, List("jvm", "native"))
    assertEquals(versions, List(Some("3.6.4")))
  }

  test("realistic header: jvm-only with dep directives") {
    val (platforms, versions) = parseDirectivesFromLines(List(
      "//> using scala 3.8.3",
      "//> using dep org.typelevel::cats-effect:3.7.0",
      "object Foo"
    ))
    assertEquals(platforms, List("jvm"))
    assertEquals(versions, List(Some("3.8.3")))
  }

  // --- targetKey ---

  test("targetKey: single platform, single version -> platform name only") {
    val t = Target("jvm", Some("3.6.4"))
    assertEquals(targetKey(t, List("jvm"), List(Some("3.6.4"))), "jvm")
  }

  test("targetKey: multiple platforms, single version -> platform name only") {
    val jvm = Target("jvm", Some("3.6.4"))
    val nat = Target("native", Some("3.6.4"))
    val platforms = List("jvm", "native")
    val versions = List(Some("3.6.4"))
    assertEquals(targetKey(jvm, platforms, versions), "jvm")
    assertEquals(targetKey(nat, platforms, versions), "native")
  }

  test("targetKey: single platform, multiple versions -> version only") {
    val t1 = Target("jvm", Some("3.6.4"))
    val t2 = Target("jvm", Some("3.5.0"))
    val platforms = List("jvm")
    val versions = List(Some("3.6.4"), Some("3.5.0"))
    assertEquals(targetKey(t1, platforms, versions), "3.6.4")
    assertEquals(targetKey(t2, platforms, versions), "3.5.0")
  }

  test("targetKey: multiple platforms, multiple versions -> platform-version") {
    val t = Target("native", Some("3.6.4"))
    val platforms = List("jvm", "native")
    val versions = List(Some("3.6.4"), Some("3.5.0"))
    assertEquals(targetKey(t, platforms, versions), "native-3.6.4")
  }
}

//> using dep org.scalameta::munit::1.3.0

class TargetKeyTests extends munit.FunSuite {

  test("targetKey: single platform, single version -> platform name only") {
    val t = Target("jvm", Some("3.6.4"))
    assertEquals(targetKey(t, List(t)), "jvm")
  }

  test("targetKey: multiple platforms, single version -> platform name only") {
    val jvm = Target("jvm", Some("3.6.4"))
    val nat = Target("native", Some("3.6.4"))
    val all = List(jvm, nat)
    assertEquals(targetKey(jvm, all), "jvm")
    assertEquals(targetKey(nat, all), "native")
  }

  test("targetKey: single platform, multiple versions -> version only") {
    val t1 = Target("jvm", Some("3.6.4"))
    val t2 = Target("jvm", Some("3.5.0"))
    val all = List(t1, t2)
    assertEquals(targetKey(t1, all), "3.6.4")
    assertEquals(targetKey(t2, all), "3.5.0")
  }

  test("targetKey: multiple platforms, multiple versions -> platform-version") {
    val all = List(
      Target("jvm", Some("3.6.4")),
      Target("jvm", Some("3.5.0")),
      Target("native", Some("3.6.4")),
      Target("native", Some("3.5.0"))
    )
    val t = Target("native", Some("3.6.4"))
    assertEquals(targetKey(t, all), "native-3.6.4")
  }
}

class ParseDeclaredDepsTests extends munit.FunSuite {

  test("no <dependencies> block: returns empty") {
    val pom = """<?xml version="1.0"?>
                |<project>
                |  <groupId>foo</groupId>
                |  <artifactId>bar</artifactId>
                |  <version>1.0</version>
                |</project>""".stripMargin
    assertEquals(parseDeclaredDeps(pom), Nil)
  }

  test("single dependency") {
    val pom = """<dependencies>
                |  <dependency>
                |    <groupId>org.example</groupId>
                |    <artifactId>foo</artifactId>
                |    <version>1.2.3</version>
                |  </dependency>
                |</dependencies>""".stripMargin
    assertEquals(parseDeclaredDeps(pom), List(("org.example", "foo", "1.2.3")))
  }

  test("multiple dependencies in order") {
    val pom = """<dependencies>
                |  <dependency>
                |    <groupId>a</groupId><artifactId>x</artifactId><version>1</version>
                |  </dependency>
                |  <dependency>
                |    <groupId>b</groupId><artifactId>y</artifactId><version>2</version>
                |  </dependency>
                |</dependencies>""".stripMargin
    assertEquals(parseDeclaredDeps(pom), List(("a", "x", "1"), ("b", "y", "2")))
  }

  test("dependency with exclusions and scope") {
    val pom = """<dependencies>
                |  <dependency>
                |    <groupId>org.scala-native</groupId>
                |    <artifactId>scalalib_native0.5_2.13</artifactId>
                |    <version>2.13.8+0.5.2</version>
                |    <scope>compile</scope>
                |    <exclusions>
                |      <exclusion>
                |        <groupId>org.scala-lang</groupId>
                |        <artifactId>scala-library</artifactId>
                |      </exclusion>
                |    </exclusions>
                |  </dependency>
                |</dependencies>""".stripMargin
    assertEquals(
      parseDeclaredDeps(pom),
      List(("org.scala-native", "scalalib_native0.5_2.13", "2.13.8+0.5.2"))
    )
  }

  test("ignores deps in <dependencyManagement>") {
    val pom = """<dependencyManagement>
                |  <dependencies>
                |    <dependency>
                |      <groupId>managed</groupId><artifactId>m</artifactId><version>9</version>
                |    </dependency>
                |  </dependencies>
                |</dependencyManagement>
                |<dependencies>
                |  <dependency>
                |    <groupId>real</groupId><artifactId>r</artifactId><version>1</version>
                |  </dependency>
                |</dependencies>""".stripMargin
    val result = parseDeclaredDeps(pom)
    assert(result.contains(("real", "r", "1")), s"expected real:r:1 in $result")
    assert(
      !result.exists(_._1 == "managed"),
      s"managed dep should be excluded, got $result"
    )
  }

  test("dep missing version is dropped") {
    val pom = """<dependencies>
                |  <dependency>
                |    <groupId>a</groupId><artifactId>x</artifactId>
                |  </dependency>
                |  <dependency>
                |    <groupId>b</groupId><artifactId>y</artifactId><version>2</version>
                |  </dependency>
                |</dependencies>""".stripMargin
    assertEquals(parseDeclaredDeps(pom), List(("b", "y", "2")))
  }

  test("property placeholders are returned verbatim (caller filters)") {
    val pom = """<dependencies>
                |  <dependency>
                |    <groupId>org.example</groupId>
                |    <artifactId>foo</artifactId>
                |    <version>${project.version}</version>
                |  </dependency>
                |</dependencies>""".stripMargin
    assertEquals(
      parseDeclaredDeps(pom),
      List(("org.example", "foo", "${project.version}"))
    )
  }
}

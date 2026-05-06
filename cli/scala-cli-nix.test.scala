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

class ParseImportedBomsTests extends munit.FunSuite {

  test("no <dependencyManagement>: empty") {
    val pom = "<project><dependencies></dependencies></project>"
    assertEquals(parseImportedBoms(pom, None), Nil)
  }

  test("import scope, type pom: returned") {
    val pom = """<dependencyManagement>
                |  <dependencies>
                |    <dependency>
                |      <groupId>g</groupId>
                |      <artifactId>bom</artifactId>
                |      <version>1.0</version>
                |      <type>pom</type>
                |      <scope>import</scope>
                |    </dependency>
                |  </dependencies>
                |</dependencyManagement>""".stripMargin
    assertEquals(parseImportedBoms(pom, None), List(("g", "bom", "1.0")))
  }

  test("non-import deps in <dependencyManagement> are excluded") {
    val pom = """<dependencyManagement>
                |  <dependencies>
                |    <dependency>
                |      <groupId>g</groupId><artifactId>x</artifactId><version>1</version>
                |    </dependency>
                |    <dependency>
                |      <groupId>g</groupId><artifactId>bom</artifactId><version>2</version>
                |      <type>pom</type><scope>import</scope>
                |    </dependency>
                |  </dependencies>
                |</dependencyManagement>""".stripMargin
    assertEquals(parseImportedBoms(pom, None), List(("g", "bom", "2")))
  }

  test("${project.version} resolved against passed projectVersion") {
    val pom = """<dependencyManagement>
                |  <dependencies>
                |    <dependency>
                |      <groupId>g</groupId>
                |      <artifactId>bom-internal</artifactId>
                |      <version>${project.version}</version>
                |      <type>pom</type>
                |      <scope>import</scope>
                |    </dependency>
                |  </dependencies>
                |</dependencyManagement>""".stripMargin
    assertEquals(
      parseImportedBoms(pom, Some("2.29.12")),
      List(("g", "bom-internal", "2.29.12"))
    )
  }

  test("${project.version} dropped when no projectVersion known") {
    val pom = """<dependencyManagement>
                |  <dependencies>
                |    <dependency>
                |      <groupId>g</groupId>
                |      <artifactId>bom-internal</artifactId>
                |      <version>${project.version}</version>
                |      <type>pom</type>
                |      <scope>import</scope>
                |    </dependency>
                |  </dependencies>
                |</dependencyManagement>""".stripMargin
    assertEquals(parseImportedBoms(pom, None), Nil)
  }

  test("other property placeholders dropped") {
    val pom = """<dependencyManagement>
                |  <dependencies>
                |    <dependency>
                |      <groupId>g</groupId>
                |      <artifactId>bom</artifactId>
                |      <version>${other.ver}</version>
                |      <type>pom</type>
                |      <scope>import</scope>
                |    </dependency>
                |  </dependencies>
                |</dependencyManagement>""".stripMargin
    assertEquals(parseImportedBoms(pom, Some("1.0")), Nil)
  }

  test("does not pick up imports outside <dependencyManagement>") {
    val pom = """<dependencies>
                |  <dependency>
                |    <groupId>g</groupId>
                |    <artifactId>bom</artifactId>
                |    <version>1</version>
                |    <type>pom</type>
                |    <scope>import</scope>
                |  </dependency>
                |</dependencies>""".stripMargin
    assertEquals(parseImportedBoms(pom, None), Nil)
  }

  test("substitutes from passed property map") {
    val pom = """<dependencyManagement>
                |  <dependencies>
                |    <dependency>
                |      <groupId>org.junit</groupId>
                |      <artifactId>junit-bom</artifactId>
                |      <version>${junit5.version}</version>
                |      <type>pom</type>
                |      <scope>import</scope>
                |    </dependency>
                |  </dependencies>
                |</dependencyManagement>""".stripMargin
    assertEquals(
      parseImportedBoms(pom, None, Map("junit5.version" -> "5.10.0")),
      List(("org.junit", "junit-bom", "5.10.0"))
    )
  }
}

class PropertiesTests extends munit.FunSuite {

  test("parseProperties: extracts <name>value</name> pairs") {
    val pom = """<project>
                |  <properties>
                |    <foo.bar>1.2.3</foo.bar>
                |    <baz>hello</baz>
                |  </properties>
                |</project>""".stripMargin
    assertEquals(
      parseProperties(pom),
      Map("foo.bar" -> "1.2.3", "baz" -> "hello")
    )
  }

  test("parseProperties: missing <properties> -> empty") {
    assertEquals(parseProperties("<project></project>"), Map.empty)
  }

  test("substituteProperties: simple substitution") {
    assertEquals(
      substituteProperties("${foo}", Map("foo" -> "bar")),
      "bar"
    )
  }

  test("substituteProperties: indirect substitution resolves") {
    val props = Map("a" -> "${b}", "b" -> "value")
    assertEquals(substituteProperties("${a}", props), "value")
  }

  test("substituteProperties: unresolved placeholder kept verbatim") {
    assertEquals(
      substituteProperties("${unknown}", Map.empty),
      "${unknown}"
    )
  }

  test("substituteProperties: mix of resolved and literal") {
    assertEquals(
      substituteProperties("v=${ver}-final", Map("ver" -> "1")),
      "v=1-final"
    )
  }
}

class ParsePomVersionTests extends munit.FunSuite {

  test("top-level <version> wins") {
    val pom = """<project>
                |  <groupId>g</groupId>
                |  <artifactId>a</artifactId>
                |  <version>2.0</version>
                |  <parent><groupId>p</groupId><artifactId>pp</artifactId><version>1.0</version></parent>
                |</project>""".stripMargin
    assertEquals(parsePomVersion(pom), Some("2.0"))
  }

  test("falls back to <parent>/<version> when top-level is missing") {
    val pom = """<project>
                |  <artifactId>a</artifactId>
                |  <parent><groupId>p</groupId><artifactId>pp</artifactId><version>1.0</version></parent>
                |</project>""".stripMargin
    assertEquals(parsePomVersion(pom), Some("1.0"))
  }

  test("ignores versions inside <dependencies> / <dependencyManagement>") {
    val pom = """<project>
                |  <artifactId>a</artifactId>
                |  <parent><artifactId>pp</artifactId><version>1.0</version></parent>
                |  <dependencies>
                |    <dependency><groupId>x</groupId><artifactId>y</artifactId><version>9.9</version></dependency>
                |  </dependencies>
                |</project>""".stripMargin
    assertEquals(parsePomVersion(pom), Some("1.0"))
  }
}

class ParsePomCoordsTests extends munit.FunSuite {

  test("all coords from top-level when present") {
    val pom = """<project>
                |  <groupId>g</groupId>
                |  <artifactId>a</artifactId>
                |  <version>1.0</version>
                |</project>""".stripMargin
    assertEquals(parsePomCoords(pom), Some(("g", "a", "1.0")))
  }

  test("groupId + version inherited from parent") {
    val pom = """<project>
                |  <artifactId>a</artifactId>
                |  <parent><groupId>p</groupId><artifactId>pp</artifactId><version>1.0</version></parent>
                |</project>""".stripMargin
    assertEquals(parsePomCoords(pom), Some(("p", "a", "1.0")))
  }

  test("no artifactId -> None") {
    val pom = """<project>
                |  <groupId>g</groupId><version>1</version>
                |</project>""".stripMargin
    assertEquals(parsePomCoords(pom), None)
  }

  test("ignores <build>/<plugins> coords when picking top-level group") {
    val pom = """<project>
                |  <parent>
                |    <groupId>real.group</groupId>
                |    <artifactId>parent</artifactId>
                |    <version>2.0</version>
                |  </parent>
                |  <artifactId>child</artifactId>
                |  <build>
                |    <plugins>
                |      <plugin>
                |        <groupId>org.apache.maven.plugins</groupId>
                |        <artifactId>maven-jar-plugin</artifactId>
                |      </plugin>
                |    </plugins>
                |  </build>
                |</project>""".stripMargin
    assertEquals(parsePomCoords(pom), Some(("real.group", "child", "2.0")))
  }

  test("ignores <dependencies> coords") {
    val pom = """<project>
                |  <parent>
                |    <groupId>real.group</groupId>
                |    <artifactId>p</artifactId>
                |    <version>1.0</version>
                |  </parent>
                |  <artifactId>child</artifactId>
                |  <dependencies>
                |    <dependency>
                |      <groupId>some.dep</groupId>
                |      <artifactId>x</artifactId>
                |      <version>9</version>
                |    </dependency>
                |  </dependencies>
                |</project>""".stripMargin
    assertEquals(parsePomCoords(pom), Some(("real.group", "child", "1.0")))
  }
}

class RepoBaseFromCoordsTests extends munit.FunSuite {

  test("Maven Central layout: strips suffix") {
    val url =
      "https://repo1.maven.org/maven2/software/amazon/awssdk/core/2.29.12/core-2.29.12.pom"
    assertEquals(
      repoBaseFromCoords(url, "software.amazon.awssdk", "core", "2.29.12"),
      "https://repo1.maven.org/maven2/"
    )
  }

  test("custom repo base") {
    val url = "https://my-repo.example.com/m2/com/foo/bar/1.0/bar-1.0.pom"
    assertEquals(
      repoBaseFromCoords(url, "com.foo", "bar", "1.0"),
      "https://my-repo.example.com/m2/"
    )
  }

  test("non-matching coords -> empty (skip)") {
    val url = "https://repo1.maven.org/maven2/g/a/1.0/a-1.0.pom"
    assertEquals(repoBaseFromCoords(url, "wrong", "a", "1.0"), "")
  }

  test("non-pom URL -> empty") {
    val url = "https://repo1.maven.org/maven2/g/a/1.0/a-1.0.jar"
    assertEquals(repoBaseFromCoords(url, "g", "a", "1.0"), "")
  }
}

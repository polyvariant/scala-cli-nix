//> using test.dep org.scalameta::munit::1.3.0

class MainSuite extends munit.FunSuite {
  test("greeting is correct") {
    assertEquals(Main.greeting, "hello from munit test framework!")
  }
}

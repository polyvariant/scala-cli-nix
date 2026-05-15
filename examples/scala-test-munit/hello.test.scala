//> using test.dep org.scalameta::munit::1.2.4

class MainSuite extends munit.FunSuite {
  test("greeting is correct") {
    assertEquals(Main.greeting, "hello from munit test framework!")
  }
}

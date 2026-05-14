//> using test.dep org.scalatest::scalatest::3.2.19

import org.scalatest.funsuite.AnyFunSuite

class MainTests extends AnyFunSuite {
  test("greeting is correct") {
    assert(Main.greeting == "hello from scalatest test framework!")
  }
}

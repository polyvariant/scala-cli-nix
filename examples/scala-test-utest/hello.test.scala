//> using test.dep com.lihaoyi::utest::0.8.5
//> using testFramework utest.runner.Framework

import utest._

object MainTests extends TestSuite {
  val tests = Tests {
    test("greeting is correct") {
      assert(Main.greeting == "hello from utest test framework!")
    }
  }
}

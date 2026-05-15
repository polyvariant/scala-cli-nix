//> using test.dep dev.zio::zio-test::2.1.26
//> using test.dep dev.zio::zio-test-sbt::2.1.26
//> using testFramework zio.test.sbt.ZTestFramework

import zio.test._

object MainSpec extends ZIOSpecDefault {
  def spec = suite("MainSpec")(
    test("greeting is correct") {
      assertTrue(Main.greeting == "hello from zio-test test framework!")
    }
  )
}

//> using test.dep com.disneystreaming::weaver-cats::0.8.4
//> using testFramework weaver.framework.CatsEffect

import weaver._

object MainSuite extends FunSuite {
  test("greeting is correct") {
    expect(Main.greeting == "hello from scala native!")
  }
}

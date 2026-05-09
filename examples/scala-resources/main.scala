//> using platform jvm scala-native
//> using scala 3.8.3
//> using nativeVersion 0.5.11
//> using resourceDir resources

object Main {

  def main(args: Array[String]): Unit = {
    val stream = getClass.getResourceAsStream("/greeting.txt")
    if (stream == null) throw RuntimeException("resource not found")
    val text =
      try new String(stream.readAllBytes(), "UTF-8")
      finally stream.close()
    print(text)
  }

}

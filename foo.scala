//> using dep org.typelevel::cats-effect:3.7.0
//> using scala 3.8.3
import cats.effect.*

object Main extends IOApp.Simple {
  def run = IO.println("hello world!")
}

//> using platform scala-native
//> using scala 3.6.4
//> using dep org.typelevel::cats-effect::3.7.0

import cats.effect.{IO, IOApp}

object Main extends IOApp.Simple {
  def run: IO[Unit] = IO.println("hello from scala native with cats-effect!")
}

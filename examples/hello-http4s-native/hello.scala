//> using platform scala-native
//> using scala 3.6.4
//> using dep org.typelevel::cats-effect::3.7.0
//> using dep org.http4s::http4s-ember-server::0.23.34
//> using dep org.http4s::http4s-dsl::0.23.34

import cats.effect.{IO, IOApp}
import com.comcast.ip4s.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder

object Main extends IOApp.Simple {
  def run: IO[Unit] = {
    val routes = HttpRoutes.of[IO] { case GET -> Root =>
      Ok("hello from http4s native!")
    }
    EmberServerBuilder
      .default[IO]
      .withHost(ipv4"0.0.0.0")
      .withPort(port"8080")
      .withHttpApp(routes.orNotFound)
      .build
      .useForever
  }
}

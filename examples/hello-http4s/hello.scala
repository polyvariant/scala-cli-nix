//> using platforms jvm scala-native
//> using scala 3.8.3
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
    val platform = sys.env.getOrElse("PLATFORM", "unknown")

    val routes = HttpRoutes.of[IO] { case GET -> Root =>
      Ok(s"hello from http4s $platform!")
    }

    val portNumber =
      sys.env.get("PORT").flatMap(_.toIntOption).getOrElse(8080)

    val portTyped =
      Port
        .fromInt(portNumber)
        .getOrElse(sys.error(s"invalid port: $portNumber"))

    EmberServerBuilder
      .default[IO]
      .withHost(ipv4"0.0.0.0")
      .withPort(portTyped)
      .withHttpApp(routes.orNotFound)
      .build
      .useForever
  }
}

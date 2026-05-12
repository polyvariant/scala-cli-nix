//> using platform scala-native
//> using scala 3.8.3
//> using dep io.github.cquiroz::scala-java-time::2.6.0

// Regression guard. scala-java-time transitively pulls
// `portable-scala-reflect_native0.5_2.13`, which declares
// `scalalib_native0.5_2.13:2.13.8+0.5.2` directly. At lock time, our generator
// also resolves scala-cli's injected native runtime deps (scala3lib_native,
// javalib_native at the latest version). The latest scala3lib excludes
// scalalib_2.13, so the only path to scalalib_2.13 in the combined graph is
// portable-scala-reflect's pinned 2.13.8+0.5.2 — that's the version scala-cli
// asks Coursier for at offline build time.
//
// If the lock generator resolves user libs *separately* from the native
// runtime deps, an older transitive scala3lib (3.3.x) is part of the user-libs
// graph and pulls a higher scalalib_2.13 (e.g. 2.13.17+0.5.9). The lockfile
// then has the wrong JAR and `scala-cli package --offline` fails.
//
// Building this example end-to-end confirms the combined-resolution path.

import java.time.Instant

object Main {
  def main(args: Array[String]): Unit = {
    val _ = Instant.now()
    println("hello from evicted-2.13!")
  }
}

//> using scala 3.8.3
//> using scalacOption -no-indent
//> using dep io.get-coursier:interface:1.0.29-M3
//> using dep com.lihaoyi::upickle:4.1.0
//> using dep org.typelevel::cats-effect:3.7.0
//> using dep co.fs2::fs2-io:3.13.0
//> using dep com.github.alexarchambault::case-app:2.1.0

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.syntax.all.*
import caseapp.*
import caseapp.core.RemainingArgs
import caseapp.core.app.CaseApp
import caseapp.core.commandparser.RuntimeCommandParser
import coursierapi.*
import fs2.Stream
import fs2.io.file.{Files, Path}
import fs2.io.process.ProcessBuilder
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import scala.jdk.CollectionConverters.*
import upickle.default.*

// --- JSON model ---

case class ArtifactEntry(url: String, sha256: String) derives ReadWriter

case class LockFile(
    version: Int,
    scalaVersion: String,
    mainClass: String,
    exportHash: String,
    sources: List[String],
    compiler: List[ArtifactEntry],
    libraryDependencies: List[ArtifactEntry]
) derives ReadWriter

// --- Console helpers ---

object C {
  val bold = "\u001b[1m"
  val green = "\u001b[0;32m"
  val blue = "\u001b[0;34m"
  val yellow = "\u001b[0;33m"
  val red = "\u001b[0;31m"
  val dim = "\u001b[2m"
  val reset = "\u001b[0m"
}

def info(msg: String): IO[Unit] =
  IO.consoleForIO.errorln(s"${C.blue}i${C.reset} $msg")
def success(msg: String): IO[Unit] =
  IO.consoleForIO.errorln(s"${C.green}done${C.reset} $msg")
def step(msg: String): IO[Unit] =
  IO.consoleForIO.errorln(s"${C.bold}> $msg${C.reset}")
def warn(msg: String): IO[Unit] =
  IO.consoleForIO.errorln(s"${C.yellow}warn${C.reset} $msg")
def error(msg: String): IO[Unit] =
  IO.consoleForIO.errorln(s"${C.red}error${C.reset} $msg")
def errln(msg: String): IO[Unit] = IO.consoleForIO.errorln(msg)

// --- Hashing ---

def sha256Base64(path: Path): IO[String] =
  Files[IO].readAll(path).compile.to(Array).map { bytes =>
    val digest = MessageDigest.getInstance("SHA-256")
    Base64.getEncoder.encodeToString(digest.digest(bytes))
  }

def sha1Hex(s: String): String = {
  val digest = MessageDigest.getInstance("SHA-1")
  digest.digest(s.getBytes("UTF-8")).map("%02x".format(_)).mkString
}

// --- Process helpers ---

/** Run a process and capture stdout as a string. Stderr is inherited. */
def exec(command: String, args: String*): IO[String] =
  ProcessBuilder(command, args.toList)
    .spawn[IO]
    .use { proc =>
      proc.stdout
        .through(fs2.text.utf8.decode)
        .compile
        .string
        .flatTap(_ =>
          proc.exitValue.flatMap { code =>
            IO.raiseError(
              new RuntimeException(s"$command exited with code $code")
            ).whenA(code != 0)
          }
        )
    }

/** Run a process, discard output, return exit code. */
def execCode(command: String, args: String*): IO[Int] =
  ProcessBuilder(command, args.toList)
    .spawn[IO]
    .use { proc =>
      proc.stdout.compile.drain *>
        proc.stderr.compile.drain *>
        proc.exitValue
    }

// --- File helpers ---

def writeFile(path: Path, content: String): IO[Unit] =
  Stream
    .emit(content)
    .through(Files[IO].writeUtf8(path))
    .compile
    .drain

def readFile(path: Path): IO[String] =
  Files[IO].readUtf8(path).compile.string

// --- Coursier helpers ---

def fetchArtifacts(deps: Dependency*): IO[List[(Artifact, File)]] =
  IO.blocking {
    val result = Fetch
      .create()
      .addDependencies(deps*)
      .fetchResult()
    result.getArtifacts().asScala.toList.map(e => (e.getKey, e.getValue))
  }

val cacheDir: File = Cache.create().getLocation()
val cachePath: Path = Path.fromNioPath(cacheDir.toPath)

def cacheFileForUrl(url: String): File = {
  val relative = url.replaceFirst("://", "/")
  File(cacheDir, relative)
}

def cachePathForUrl(url: String): Path =
  Path.fromNioPath(cacheFileForUrl(url).toPath)

def findPomForJar(jarUrl: String): IO[Option[Path]] = {
  val directPom = cachePathForUrl(jarUrl.stripSuffix(".jar") + ".pom")
  Files[IO].exists(directPom).flatMap {
    case true  => IO.pure(Some(directPom))
    case false =>
      // Classifier JAR: .../artifactId/version/artifactId-version-classifier.jar
      val jarFile = cacheFileForUrl(jarUrl)
      val dir = jarFile.getParentFile
      val version = dir.getName
      val artifactId = dir.getParentFile.getName
      val basePom =
        Path.fromNioPath(File(dir, s"$artifactId-$version.pom").toPath)
      Files[IO].exists(basePom).map(Option.when(_)(basePom))
  }
}

def urlForCachePath(file: Path): String = {
  val relative = file.toString.stripPrefix(cachePath.toString + "/")
  val proto = relative.takeWhile(_ != '/')
  val rest = relative.drop(proto.length + 1)
  s"$proto://$rest"
}

def extractParent(pomPath: Path): IO[Option[(String, String, String)]] =
  readFile(pomPath).map { content =>
    val parentPattern = "(?s)<parent>\\s*(.*?)</parent>".r
    parentPattern.findFirstMatchIn(content).flatMap { m =>
      val body = m.group(1)
      for {
        g <- "<groupId>(.*?)</groupId>".r.findFirstMatchIn(body).map(_.group(1))
        a <- "<artifactId>(.*?)</artifactId>".r
          .findFirstMatchIn(body)
          .map(_.group(1))
        v <- "<version>(.*?)</version>".r.findFirstMatchIn(body).map(_.group(1))
      } yield (g, a, v)
    }
  }

def collectParentPoms(pomPath: Path): IO[List[ArtifactEntry]] = {
  def loop(current: Path, acc: List[ArtifactEntry]): IO[List[ArtifactEntry]] =
    extractParent(current).flatMap {
      case Some((groupId, artifactId, version)) =>
        val groupPath = groupId.replace('.', '/')
        val parentPomRelative =
          s"https/repo1.maven.org/maven2/$groupPath/$artifactId/$version/$artifactId-$version.pom"
        val parentPomPath = cachePath / parentPomRelative
        Files[IO].exists(parentPomPath).flatMap {
          case true =>
            val url =
              s"https://repo1.maven.org/maven2/$groupPath/$artifactId/$version/$artifactId-$version.pom"
            sha256Base64(parentPomPath).flatMap { hash =>
              loop(parentPomPath, ArtifactEntry(url, hash) :: acc)
            }
          case false =>
            IO.pure(acc.reverse)
        }
      case None =>
        IO.pure(acc.reverse)
    }
  loop(pomPath, Nil)
}

def collectEntries(artifacts: List[(Artifact, File)]): IO[List[ArtifactEntry]] =
  artifacts
    .flatTraverse { (artifact, file) =>
      val url = artifact.getUrl
      val artifactPath = Path.fromNioPath(file.toPath)

      sha256Base64(artifactPath).flatMap { jarHash =>
        val jarEntry = ArtifactEntry(url, jarHash)

        findPomForJar(url).flatMap {
          case None          => IO.pure(List(jarEntry))
          case Some(pomPath) =>
            val pomUrl = {
              val direct = url.replaceFirst("\\.jar$", ".pom")
              if (cachePathForUrl(direct).toString == pomPath.toString) direct
              else urlForCachePath(pomPath)
            }
            sha256Base64(pomPath).flatMap { pomHash =>
              collectParentPoms(pomPath).map { parentEntries =>
                jarEntry :: ArtifactEntry(pomUrl, pomHash) :: parentEntries
              }
            }
        }
      }
    }
    .map(_.distinctBy(_.url))

// --- scala-cli helpers ---

val resolveScalaCli: IO[String] =
  execCode("which", "real-scala-cli").map {
    case 0 => "real-scala-cli"
    case _ => "scala-cli"
  }

// --- Option case classes ---

case class LockOptions()
case class InitOptions()

// --- Lock command ---

def lock(inputs: List[String]): IO[ExitCode] = {
  val inputArgs = if (inputs.isEmpty) List(".") else inputs

  for {
    scalaCli <- resolveScalaCli
    cwd <- Files[IO].currentWorkingDirectory
    lockfilePath = cwd / "scala.lock.json"

    _ <- step("Exporting project info...")
    exportJson <- exec(
      scalaCli,
      ("--power" :: "export" :: "--json" :: "--server=false" :: "--offline" :: inputArgs)*
    )

    export_ = ujson.read(exportJson)
    canonicalExport = ujson.write(export_, indent = 2, sortKeys = true)
    exportHash = sha1Hex(canonicalExport + "\n")

    lockExists <- Files[IO].exists(lockfilePath)
    isUpToDate <-
      if (lockExists)
        readFile(lockfilePath).map { content =>
          val existing = ujson.read(content)
          existing.obj.get("exportHash").map(_.str).contains(exportHash)
        }
      else
        IO.pure(false)

    result <-
      if (isUpToDate)
        info("Lock is up to date.").as(ExitCode.Success)
      else
        doLock(scalaCli, inputArgs, export_, exportHash, lockfilePath, cwd)
  } yield result
}

private def doLock(
    scalaCli: String,
    inputArgs: List[String],
    export_ : ujson.Value,
    exportHash: String,
    lockfilePath: Path,
    cwd: Path
): IO[ExitCode] = {
  val scalaVersion = export_("scalaVersion").str
  val sources = export_("scopes")("main")("sources").arr.map { s =>
    s.str
      .stripPrefix(cwd.toString + "/")
      .stripPrefix("/private" + cwd.toString + "/")
  }.toList
  val deps = export_("scopes")("main")("dependencies").arr.map { d =>
    s"${d("groupId").str}:${d("artifactId")("fullName").str}:${d("version").str}"
  }.toList

  val scalaMajor = scalaVersion.takeWhile(_ != '.')

  scalaMajor match {
    case "3" | "2" =>
      val (compilerArtifact, libraryArtifact) = scalaMajor match {
        case "3" =>
          (
            Dependency.of("org.scala-lang", "scala3-compiler_3", scalaVersion),
            Dependency.of("org.scala-lang", "scala3-library_3", scalaVersion)
          )
        case _ =>
          (
            Dependency.of("org.scala-lang", "scala-compiler", scalaVersion),
            Dependency.of("org.scala-lang", "scala-library", scalaVersion)
          )
      }

      for {
        _ <- info(s"Scala version: ${C.bold}$scalaVersion${C.reset}")
        _ <- info(s"Sources: ${C.bold}${sources.size}${C.reset} files")
        _ <- info(s"Found ${C.bold}${deps.size}${C.reset} dependencies")

        _ <- step("Discovering main class...")
        mainClass <- exec(
          scalaCli,
          ("--power" :: "run" :: "--main-class-list" :: "--server=false" :: "--offline" :: inputArgs)*
        )
          .map(_.linesIterator.next())
        _ <- info(s"Main class: ${C.bold}$mainClass${C.reset}")

        _ <- step("Fetching compiler dependencies...")
        compilerArtifacts <- fetchArtifacts(compilerArtifact)
        _ <- info(
          s"Compiler: ${C.bold}${compilerArtifacts.size}${C.reset} artifacts"
        )

        _ <- step("Fetching library dependencies...")
        libDeps = libraryArtifact +: deps.map { dep =>
          val parts = dep.split(":")
          Dependency.of(parts(0), parts(1), parts(2))
        }
        libArtifacts <- fetchArtifacts(libDeps*)
        _ <- info(
          s"Libraries: ${C.bold}${libArtifacts.size}${C.reset} artifacts (transitive)"
        )

        _ <- step("Hashing artifacts...")
        compilerEntries <- collectEntries(compilerArtifacts)
        libEntries <- collectEntries(libArtifacts)

        _ <- step("Writing lockfile...")
        lockFile = LockFile(
          version = 3,
          scalaVersion = scalaVersion,
          mainClass = mainClass,
          exportHash = exportHash,
          sources = sources,
          compiler = compilerEntries,
          libraryDependencies = libEntries
        )
        _ <- writeFile(
          lockfilePath,
          ujson.write(writeJs(lockFile), indent = 2) + "\n"
        )
        _ <- success(s"Wrote ${C.bold}scala.lock.json${C.reset}")
      } yield ExitCode.Success

    case _ =>
      error(
        s"Unsupported Scala major version: $scalaMajor (from $scalaVersion)"
      )
        .as(ExitCode.Error)
  }
}

// --- Init command ---

def init(inputs: List[String]): IO[ExitCode] =
  for {
    cwd <- Files[IO].currentWorkingDirectory
    scalaFiles <- Files[IO]
      .list(cwd)
      .filter(_.extName == ".scala")
      .compile
      .toList
    result <-
      if (scalaFiles.isEmpty)
        error("No .scala files found in current directory.").as(ExitCode.Error)
      else
        doInit(cwd)
  } yield result

private def doInit(cwd: Path): IO[ExitCode] = {
  val pname = cwd.fileName.toString

  val writeDerivation: IO[List[String]] =
    Files[IO].exists(cwd / "derivation.nix").flatMap {
      case true =>
        warn("derivation.nix already exists, skipping.").as(Nil)
      case false =>
        step("Writing derivation.nix...") *>
          writeFile(
            cwd / "derivation.nix",
            s"""{ scala-cli-nix }:
               |
               |scala-cli-nix.buildScalaCliApp {
               |  pname = "$pname";
               |  version = "0.1.0";
               |  src = ./.;
               |  lockFile = ./scala.lock.json;
               |}
               |""".stripMargin
          ) *>
          success(s"Wrote ${C.bold}derivation.nix${C.reset}")
            .as(List("derivation.nix"))
    }

  val writeFlake: IO[List[String]] =
    Files[IO].exists(cwd / "flake.nix").flatMap {
      case true =>
        errln("") *>
          warn("flake.nix already exists. Add the following to your flake:") *>
          errln("") *>
          errln(s"  ${C.bold}1.${C.reset} Add the input:") *>
          errln("") *>
          errln(
            s"""    ${C.dim}scala-cli-nix.url = "github:scala-nix/scala-cli-nix";${C.reset}"""
          ) *>
          errln(
            s"""    ${C.dim}scala-cli-nix.inputs.nixpkgs.follows = "nixpkgs";${C.reset}"""
          ) *>
          errln("") *>
          errln(s"  ${C.bold}2.${C.reset} Apply the overlay to nixpkgs:") *>
          errln("") *>
          errln(s"    ${C.dim}pkgs = import nixpkgs {${C.reset}") *>
          errln(s"    ${C.dim}  inherit system;${C.reset}") *>
          errln(
            s"    ${C.dim}  overlays = [ scala-cli-nix.overlays.default ];${C.reset}"
          ) *>
          errln(s"    ${C.dim}};${C.reset}") *>
          errln("") *>
          errln(s"  ${C.bold}3.${C.reset} Add the package:") *>
          errln("") *>
          errln(
            s"    ${C.dim}packages.default = pkgs.callPackage ./derivation.nix { };${C.reset}"
          ) *>
          errln("") *>
          errln(
            s"  ${C.bold}4.${C.reset} Add to your devShell (uses wrapped scala-cli with auto-locking):"
          ) *>
          errln("") *>
          errln(s"    ${C.dim}pkgs.scala-cli${C.reset}") *>
          errln(s"    ${C.dim}pkgs.scala-cli-nix-cli${C.reset}") *>
          errln("").as(Nil)
      case false =>
        step("Writing flake.nix...") *>
          writeFile(
            cwd / "flake.nix",
            """|{
               |  inputs = {
               |    nixpkgs.url = "github:NixOS/nixpkgs";
               |    scala-cli-nix.url = "github:scala-nix/scala-cli-nix";
               |    scala-cli-nix.inputs.nixpkgs.follows = "nixpkgs";
               |  };
               |
               |  outputs = { nixpkgs, scala-cli-nix, ... }:
               |    let
               |      forAllSystems = nixpkgs.lib.genAttrs [ "x86_64-linux" "aarch64-darwin" "x86_64-darwin" ];
               |    in {
               |      packages = forAllSystems (system:
               |        let
               |          pkgs = import nixpkgs {
               |            inherit system;
               |            overlays = [ scala-cli-nix.overlays.default ];
               |          };
               |        in {
               |          default = pkgs.callPackage ./derivation.nix { };
               |        }
               |      );
               |
               |      devShells = forAllSystems (system:
               |        let
               |          pkgs = import nixpkgs {
               |            inherit system;
               |            overlays = [ scala-cli-nix.overlays.default ];
               |          };
               |        in {
               |          default = pkgs.mkShell {
               |            buildInputs = [
               |              pkgs.scala-cli
               |              pkgs.scala-cli-nix-cli
               |            ];
               |          };
               |        }
               |      );
               |    };
               |}
               |""".stripMargin
          ) *>
          success(s"Wrote ${C.bold}flake.nix${C.reset}")
            .as(List("flake.nix"))
    }

  for {
    _ <- errln("")
    _ <- errln(
      s"${C.bold}Initializing scala-cli-nix project: ${C.green}$pname${C.reset}"
    )
    _ <- errln("")
    derivationFiles <- writeDerivation
    flakeFiles <- writeFlake
    _ <- errln("")
    _ <- lock(Nil)
    _ <- errln("")
    files = (derivationFiles ++ flakeFiles) :+ "scala.lock.json"
    isGit <- execCode("git", "rev-parse", "--is-inside-work-tree")
    _ <-
      if (isGit == 0)
        step("Staging generated files...") *>
          exec("git", ("add" :: files)*).void *>
          success(s"Staged ${C.bold}${files.mkString(" ")}${C.reset}")
      else
        IO.unit
    _ <- errln(
      s"${C.bold}Done!${C.reset} Run ${C.green}nix build${C.reset} to build your project."
    )
  } yield ExitCode.Success
}

// --- Main ---

private def parseOpts[T: Parser: Help](
    args: List[String]
): IO[Option[(T, RemainingArgs)]] =
  CaseApp.detailedParseWithHelp[T](args) match {
    case Left(err)              => IO.consoleForIO.errorln(err.message).as(None)
    case Right((_, true, _, _)) => IO.println(CaseApp.helpMessage[T]).as(None)
    case Right((Left(err), _, _, _)) =>
      IO.consoleForIO.errorln(err.message).as(None)
    case Right((Right(opts), false, _, rest)) => IO.pure(Some((opts, rest)))
  }

object ScalaCliNix extends IOApp {

  private val subcommandMap: Map[List[String], List[String] => IO[ExitCode]] =
    Map(
      List("init") -> { args =>
        parseOpts[InitOptions](args).flatMap {
          case None            => IO.pure(ExitCode.Success)
          case Some((_, rest)) => init(rest.remaining.toList)
        }
      },
      List("lock") -> { args =>
        parseOpts[LockOptions](args).flatMap {
          case None            => IO.pure(ExitCode.Success)
          case Some((_, rest)) => lock(rest.remaining.toList)
        }
      }
    )

  override def run(args: List[String]): IO[ExitCode] =
    RuntimeCommandParser.parse(subcommandMap, args) match {
      case None =>
        errln(
          s"${C.bold}scala-cli-nix${C.reset} — Nix packaging for scala-cli apps"
        ) *>
          errln("") *>
          errln(
            s"  ${C.bold}init${C.reset}    Scaffold flake.nix, derivation.nix, and generate lockfile"
          ) *>
          errln(
            s"  ${C.bold}lock${C.reset}    Regenerate the lockfile from scala-cli sources in ."
          ) *>
          IO.pure(ExitCode.Error)
      case Some((_, handler, rest)) => handler(rest)
    }
}

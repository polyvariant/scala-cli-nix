//> using scala 3.8.3
//> using scalacOption -no-indent
//> using dep io.get-coursier:interface:1.0.29-M3
//> using dep io.circe::circe-generic:0.14.15
//> using dep io.circe::circe-parser:0.14.15
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
import io.circe.{Codec, Decoder, Json, Printer}
import io.circe.parser.parse as parseJson
import io.circe.syntax.*
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import scala.jdk.CollectionConverters.*

// --- JSON model (lockfile) ---

case class ArtifactEntry(url: String, sha256: String) derives Codec.AsObject

case class NativeLockDeps(
    scalaNativeVersion: String,
    compilerPlugins: List[ArtifactEntry],
    runtimeDependencies: List[ArtifactEntry],
    toolingDependencies: List[ArtifactEntry]
) derives Codec.AsObject

case class LockFile(
    version: Int,
    scalaVersion: String,
    platform: String,
    exportHash: String,
    sources: List[String],
    compiler: List[ArtifactEntry],
    libraryDependencies: List[ArtifactEntry],
    native: Option[NativeLockDeps]
) derives Codec.AsObject

// --- JSON model (scala-cli export, only the fields we use) ---

case class ExportArtifactId(fullName: String) derives Decoder

case class ExportDependency(
    groupId: String,
    artifactId: ExportArtifactId,
    version: String
) derives Decoder

case class ExportScope(
    sources: List[String],
    dependencies: List[ExportDependency]
)
object ExportScope {
  given Decoder[ExportScope] = Decoder.instance { c =>
    for {
      sources <- c.get[List[String]]("sources")
      deps <- c.getOrElse[List[ExportDependency]]("dependencies")(Nil)
    } yield ExportScope(sources, deps)
  }
}

case class NativeOptionsExport(
    scalaNativeVersion: String,
    compilerPlugins: List[ExportDependency],
    runtimeDependencies: List[ExportDependency],
    toolingDependencies: List[ExportDependency]
) derives Decoder

case class ExportInfo(
    scalaVersion: String,
    platform: Option[String],
    nativeOptions: Option[NativeOptionsExport],
    scopes: Map[String, ExportScope]
) derives Decoder

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
  IO.consoleForIO.errorln(s"${C.blue}ℹ${C.reset}  $msg")
def success(msg: String): IO[Unit] =
  IO.consoleForIO.errorln(s"${C.green}✔${C.reset}  $msg")
def step(msg: String): IO[Unit] =
  IO.consoleForIO.errorln(s"${C.bold}▶ $msg${C.reset}")
def warn(msg: String): IO[Unit] =
  IO.consoleForIO.errorln(s"${C.yellow}⚠${C.reset}  $msg")
def error(msg: String): IO[Unit] =
  IO.consoleForIO.errorln(s"${C.red}✖${C.reset}  $msg")
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
  val relative = url.replaceFirst("://", "/").replace("+", "%2B")
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
  val rest = relative.drop(proto.length + 1).replace("%2B", "+")
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

val hashPrinter: Printer = Printer.noSpaces.copy(sortKeys = true)
val lockfilePrinter: Printer =
  Printer.spaces2.copy(sortKeys = true, colonLeft = "", dropNullValues = true)

/** Compute lockfile content without writing it. Returns None if up to date. */
def computeLock(inputs: List[String]): IO[Option[String]] = {
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

    rawJson <- IO.fromEither(
      parseJson(exportJson).leftMap(e =>
        new RuntimeException(s"Failed to parse export JSON: ${e.message}")
      )
    )
    canonicalExport = hashPrinter.print(rawJson)
    exportHash = sha1Hex(canonicalExport + "\n")

    lockExists <- Files[IO].exists(lockfilePath)
    isUpToDate <-
      if (lockExists)
        readFile(lockfilePath).map { content =>
          parseJson(content).toOption
            .flatMap(_.hcursor.get[String]("exportHash").toOption)
            .contains(exportHash)
        }
      else
        IO.pure(false)

    result <-
      if (isUpToDate)
        info("Lock is up to date.").as(None)
      else {
        val exportInfo = rawJson
          .as[ExportInfo]
          .leftMap(e =>
            new RuntimeException(s"Failed to decode export JSON: ${e.message}")
          )
        IO.fromEither(exportInfo)
          .flatMap(computeLockContent(scalaCli, inputArgs, _, exportHash, cwd))
          .map(Some(_))
      }
  } yield result
}

def lock(inputs: List[String]): IO[ExitCode] =
  computeLock(inputs).flatMap {
    case None          => IO.pure(ExitCode.Success)
    case Some(content) =>
      for {
        cwd <- Files[IO].currentWorkingDirectory
        _ <- step("Writing lockfile...")
        _ <- writeFile(cwd / "scala.lock.json", content)
        _ <- success(s"Wrote ${C.bold}scala.lock.json${C.reset}")
      } yield ExitCode.Success
  }

private def computeLockContent(
    scalaCli: String,
    inputArgs: List[String],
    export_ : ExportInfo,
    exportHash: String,
    cwd: Path
): IO[String] = {
  val scalaVersion = export_.scalaVersion
  val platform = export_.platform.getOrElse("JVM")
  val mainScope = export_.scopes.getOrElse("main", ExportScope(Nil, Nil))
  val sources = mainScope.sources.map { s =>
    s
      .stripPrefix(cwd.toString + "/")
      .stripPrefix("/private" + cwd.toString + "/")
  }
  val deps = mainScope.dependencies.map { d =>
    s"${d.groupId}:${d.artifactId.fullName}:${d.version}"
  }

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

      def toDeps(eds: List[ExportDependency]): List[Dependency] =
        eds.map(d => Dependency.of(d.groupId, d.artifactId.fullName, d.version))

      for {
        _ <- info(s"Scala version: ${C.bold}$scalaVersion${C.reset}")
        _ <- info(s"Platform: ${C.bold}$platform${C.reset}")
        _ <- info(s"Sources: ${C.bold}${sources.size}${C.reset} files")
        _ <- info(s"Found ${C.bold}${deps.size}${C.reset} dependencies")

        _ <- step("Fetching compiler dependencies...")
        compilerArtifacts <- fetchArtifacts(compilerArtifact)
        _ <- info(
          s"Compiler: ${C.bold}${compilerArtifacts.size}${C.reset} artifacts"
        )

        _ <- step("Fetching library dependencies...")
        userDeps = deps.map { dep =>
          val parts = dep.split(":")
          Dependency.of(parts(0), parts(1), parts(2))
        }
        // For Native: combine library + native compilerPlugins + runtimeDependencies
        // into a single resolution to match scala-cli's behavior.
        // Tooling deps use a different Scala version (2.12) and stay separate.
        nativeNonToolingDeps = export_.nativeOptions.toList.flatMap { opts =>
          toDeps(opts.compilerPlugins) ++ toDeps(opts.runtimeDependencies)
        }
        allLibDeps = (libraryArtifact +: userDeps) ++ nativeNonToolingDeps
        libArtifacts <- fetchArtifacts(allLibDeps*)
        _ <- info(
          s"Libraries: ${C.bold}${libArtifacts.size}${C.reset} artifacts (transitive)"
        )

        nativeLockDeps <- export_.nativeOptions.traverse { opts =>
          step("Fetching native tooling dependencies...") *>
            fetchArtifacts(toDeps(opts.toolingDependencies)*)
              .flatMap(collectEntries)
              .map(NativeLockDeps(opts.scalaNativeVersion, Nil, Nil, _))
              .flatTap { n =>
                info(
                  s"Native tooling: ${C.bold}${n.toolingDependencies.size}${C.reset} artifacts"
                )
              }
        }

        _ <- step("Hashing artifacts...")
        compilerEntries <- collectEntries(compilerArtifacts)
        libEntries <- collectEntries(libArtifacts)

        lockFile = LockFile(
          version = 5,
          scalaVersion = scalaVersion,
          platform = platform,
          exportHash = exportHash,
          sources = sources,
          compiler = compilerEntries,
          libraryDependencies = libEntries,
          native = nativeLockDeps
        )
      } yield lockfilePrinter.print(lockFile.asJson) + "\n"

    case _ =>
      error(
        s"Unsupported Scala major version: $scalaMajor (from $scalaVersion)"
      ) *>
        IO.raiseError(
          new RuntimeException(
            s"Unsupported Scala major version: $scalaMajor (from $scalaVersion)"
          )
        )
  }
}

// --- Init command ---

def init(inputs: List[String]): IO[ExitCode] =
  for {
    cwd <- Files[IO].currentWorkingDirectory
    lockExists <- Files[IO].exists(cwd / "scala.lock.json")
    result <-
      if (lockExists)
        info(
          s"${C.bold}scala.lock.json${C.reset} already exists, running ${C.bold}lock${C.reset} instead."
        ) *> lock(inputs)
      else
        Files[IO]
          .list(cwd)
          .filter(_.extName == ".scala")
          .compile
          .toList
          .flatMap { scalaFiles =>
            if (scalaFiles.isEmpty)
              error("No .scala files found in current directory.")
                .as(ExitCode.Error)
            else
              doInit(cwd)
          }
  } yield result

private def doInit(cwd: Path): IO[ExitCode] = {
  val pname = cwd.fileName.toString

  val prepareDerivation: IO[(List[(Path, String)], List[String])] =
    Files[IO].exists(cwd / "derivation.nix").flatMap {
      case true =>
        warn("derivation.nix already exists, skipping.")
          .as((Nil, Nil))
      case false =>
        val content =
          s"""{ scala-cli-nix }:
             |
             |scala-cli-nix.buildScalaCliApp {
             |  pname = "$pname";
             |  version = "0.1.0";
             |  src = ./.;
             |  lockFile = ./scala.lock.json;
             |}
             |""".stripMargin
        IO.pure(
          (List(cwd / "derivation.nix" -> content), List("derivation.nix"))
        )
    }

  val prepareFlake: IO[(List[(Path, String)], List[String])] =
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
          errln("").as((Nil, Nil))
      case false =>
        val content =
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
        IO.pure((List(cwd / "flake.nix" -> content), List("flake.nix")))
    }

  for {
    _ <- errln("")
    _ <- errln(
      s"${C.bold}Initializing scala-cli-nix project: ${C.green}$pname${C.reset}"
    )
    _ <- errln("")
    derivation <- prepareDerivation
    flake <- prepareFlake
    _ <- errln("")
    lockContent <- computeLock(Nil)
    pendingFiles =
      derivation._1 ++ flake._1 ++ lockContent
        .map(c => (cwd / "scala.lock.json") -> c)
        .toList
    fileNames = derivation._2 ++ flake._2 ++
      lockContent.map(_ => "scala.lock.json").toList
    _ <- step("Writing files...")
    _ <- pendingFiles.traverse_ { case (path, content) =>
      writeFile(path, content)
    }
    _ <- pendingFiles.traverse_ { case (path, _) =>
      success(s"Wrote ${C.bold}${path.fileName}${C.reset}")
    }
    _ <- errln("")
    isGit <- execCode("git", "rev-parse", "--is-inside-work-tree")
    _ <-
      if (isGit == 0 && fileNames.nonEmpty)
        step("Staging generated files...") *>
          exec("git", ("add" :: fileNames)*).void *>
          success(s"Staged ${C.bold}${fileNames.mkString(" ")}${C.reset}")
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

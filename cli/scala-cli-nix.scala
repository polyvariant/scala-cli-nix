//> using scala 3.8.3
//> using scalacOption -no-indent
//> using dep io.get-coursier:interface:1.0.29-M3
//> using dep io.circe::circe-generic::0.14.15
//> using dep io.circe::circe-parser::0.14.15
//> using dep org.typelevel::cats-effect::3.7.0
//> using dep co.fs2::fs2-io::3.13.0
//> using dep com.github.alexarchambault::case-app::2.1.0

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

case class TargetLock(
    scalaVersion: String,
    platform: String,
    exportHash: String,
    compiler: List[ArtifactEntry],
    libraryDependencies: List[ArtifactEntry],
    native: Option[NativeLockDeps]
) derives Codec.AsObject

case class LockFile(
    version: Int,
    sources: List[String],
    targets: Map[String, TargetLock]
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

// TODO: hardcoded Maven Central URL. Should support custom repositories from
// scala-cli `using resolvers` directives. Also affects collectDeclaredPoms below.
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

/** Pure version of extractDeclaredDeps. Returns (groupId, artifactId, version)
 *  tuples for each <dependency> in the POM's <dependencies> section.
 *  Excludes deps inside <dependencyManagement>.
 *  May include unresolved property placeholders like ${project.version} —
 *  caller filters those out.
 */
def parseDeclaredDeps(pomContent: String): List[(String, String, String)] = {
  val withoutManagement =
    "(?s)<dependencyManagement>.*?</dependencyManagement>".r
      .replaceAllIn(pomContent, "")
  val depsBlock = "(?s)<dependencies>\\s*(.*?)</dependencies>".r
    .findFirstMatchIn(withoutManagement)
    .map(_.group(1))
    .getOrElse("")
  val depPattern = "(?s)<dependency>\\s*(.*?)</dependency>".r
  depPattern.findAllMatchIn(depsBlock).toList.flatMap { m =>
    val body = m.group(1)
    for {
      g <- "<groupId>(.*?)</groupId>".r.findFirstMatchIn(body).map(_.group(1))
      a <- "<artifactId>(.*?)</artifactId>".r.findFirstMatchIn(body).map(_.group(1))
      v <- "<version>(.*?)</version>".r.findFirstMatchIn(body).map(_.group(1))
    } yield (g, a, v)
  }
}

def extractDeclaredDeps(pomPath: Path): IO[List[(String, String, String)]] =
  readFile(pomPath).map(parseDeclaredDeps)

/** Walk all resolved POMs to find declared deps and fetch their JAR + POM
 *  individually. This captures evicted versions that scala-cli may try to fetch
 *  during offline resolution.
 *
 *  Each declared dep is fetched as its own resolution. Unlike `withTransitive(false)`
 *  which fails on some artifacts in the Coursier interface, this works reliably.
 *  Failures are tolerated (e.g. for placeholder/marker artifacts).
 */
def collectDeclaredPoms(
    resolvedPomPaths: List[Path],
    resolvedUrls: Set[String]
): IO[List[ArtifactEntry]] =
  resolvedPomPaths.flatTraverse(extractDeclaredDeps).flatMap { allDeclared =>
    val candidates = allDeclared.distinct
      .filterNot { (_, _, v) => v.contains("$") }
      // Skip resolved winners (their JAR URL would already appear in resolvedUrls)
      .filterNot { (g, a, v) =>
        resolvedUrls.exists { u =>
          u.contains(s"${g.replace('.', '/')}/$a/$v/")
        }
      }

    candidates.flatTraverse { (g, a, v) =>
      val dep = Dependency.of(g, a, v)
      fetchArtifacts(dep).attempt.flatMap {
        case Right(arts) => collectEntriesNoRecurse(arts)
        case Left(_)     => IO.pure(List.empty[ArtifactEntry])
      }
    }
  }

/** Like collectEntries but does not recursively walk declared deps (avoids infinite loop). */
def collectEntriesNoRecurse(artifacts: List[(Artifact, File)]): IO[List[ArtifactEntry]] =
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

def collectEntries(artifacts: List[(Artifact, File)]): IO[List[ArtifactEntry]] = {
  val resolved: IO[(List[ArtifactEntry], List[Path])] =
    artifacts.foldLeftM((List.empty[ArtifactEntry], List.empty[Path])) {
      case ((entries, poms), (artifact, file)) =>
        val url = artifact.getUrl
        val artifactPath = Path.fromNioPath(file.toPath)

        sha256Base64(artifactPath).flatMap { jarHash =>
          val jarEntry = ArtifactEntry(url, jarHash)

          findPomForJar(url).flatMap {
            case None          => IO.pure((jarEntry :: entries, poms))
            case Some(pomPath) =>
              val pomUrl = {
                val direct = url.replaceFirst("\\.jar$", ".pom")
                if (cachePathForUrl(direct).toString == pomPath.toString) direct
                else urlForCachePath(pomPath)
              }
              sha256Base64(pomPath).flatMap { pomHash =>
                collectParentPoms(pomPath).map { parentEntries =>
                  val newEntries =
                    jarEntry :: ArtifactEntry(pomUrl, pomHash) :: parentEntries ++ entries
                  (newEntries, pomPath :: poms)
                }
              }
          }
        }
    }

  for {
    (entries, pomPaths) <- resolved
    resolvedUrls = entries.map(_.url).toSet
    declaredPoms <- collectDeclaredPoms(pomPaths, resolvedUrls)
  } yield (entries ++ declaredPoms).distinctBy(_.url)
}

// --- scala-cli helpers ---

val resolveScalaCli: IO[String] =
  execCode("which", "real-scala-cli").map {
    case 0 => "real-scala-cli"
    case _ => "scala-cli"
  }

// --- Directive parsing ---

/** A cross-build target: one (platform, scalaVersion) combination. */
case class Target(platform: String, scalaVersion: Option[String]) {
  /** Platform name for the --platform flag (jvm -> jvm, native -> scala-native). */
  def platformFlag: String = platform match {
    case "native" => "scala-native"
    case other    => other
  }

  /** Platform name stored in the lockfile (jvm -> JVM, native -> Native). */
  def platformLock: String = platform match {
    case "native" => "Native"
    case _        => "JVM"
  }
}

/** Pure directive extraction from source lines.
 *  Returns (platforms, scalaVersions). Defaults: platforms=["jvm"], scalaVersions=[None].
 */
def parseDirectivesFromLines(
    lines: List[String]
): (List[String], List[Option[String]]) = {
  val platformPattern = "^//>\\s+using\\s+platforms?\\s+(.+)$".r
  val platforms = lines
    .collect {
      case line if platformPattern.findFirstMatchIn(line.trim).isDefined =>
        platformPattern.findFirstMatchIn(line.trim).get.group(1).trim.split("\\s+").toList
    }
    .flatten
    .map {
      case "scala-native" => "native"
      case other          => other
    }
    .distinct

  val scalaVersions = lines
    .collect {
      case line
          if line.trim.startsWith("//> using scala ") &&
            !line.trim.startsWith("//> using scalac") &&
            !line.trim.startsWith("//> using scalafix") =>
        line.trim.stripPrefix("//> using scala").trim.split("\\s+").toList
    }
    .flatten
    .distinct

  val resolvedPlatforms =
    if (platforms.isEmpty) List("jvm") else platforms
  val resolvedVersions: List[Option[String]] =
    if (scalaVersions.isEmpty) List(None)
    else scalaVersions.map(Some(_))

  (resolvedPlatforms, resolvedVersions)
}

/**
 * Parse //> using platform[s] and //> using scala directives from Scala source files.
 * Returns (platforms, scalaVersions). Defaults: platforms=["jvm"], scalaVersions=[None].
 */
def parseDirectives(
    inputArgs: List[String],
    cwd: Path
): IO[(List[String], List[Option[String]])] = {
  val sourcePaths: fs2.Stream[IO, Path] =
    if (inputArgs == List(".") || inputArgs.isEmpty)
      Files[IO].list(cwd).filter(_.extName == ".scala")
    else
      fs2.Stream.emits(inputArgs.map { arg =>
        if (arg.endsWith(".scala")) cwd / arg else cwd / (arg + ".scala")
      })

  sourcePaths
    .evalMap(readFile)
    .compile
    .toList
    .map { contents =>
      parseDirectivesFromLines(contents.flatMap(_.linesIterator.toList))
    }
}

/** Compute the lock key for a target given the full set of platforms and versions. */
def targetKey(
    target: Target,
    allPlatforms: List[String],
    allVersions: List[Option[String]]
): String = {
  val multiPlatform = allPlatforms.size > 1
  val multiVersion = allVersions.size > 1
  (multiPlatform, multiVersion) match {
    case (true, true)  => s"${target.platform}-${target.scalaVersion.getOrElse("default")}"
    case (true, false) => target.platform
    case (false, true) => target.scalaVersion.getOrElse("default")
    case (false, false) => target.platform
  }
}

// --- Option case classes ---

case class LockOptions()
case class InitOptions()

// --- Lock command ---

val hashPrinter: Printer = Printer.noSpaces.copy(sortKeys = true)
val lockfilePrinter: Printer =
  Printer.spaces2.copy(sortKeys = true, colonLeft = "", dropNullValues = true)

/** Compute lockfile content without writing it. Always recomputes from scratch. */
def computeLock(inputs: List[String]): IO[String] = {
  val inputArgs = if (inputs.isEmpty) List(".") else inputs

  for {
    scalaCli <- resolveScalaCli
    cwd <- Files[IO].currentWorkingDirectory

    (allPlatforms, allVersions) <- parseDirectives(inputArgs, cwd)
    targets = for {
      platform <- allPlatforms
      version  <- allVersions
    } yield Target(platform, version)

    _ <- info(
      s"Targets: ${C.bold}${targets.map(t => targetKey(t, allPlatforms, allVersions)).mkString(", ")}${C.reset}"
    )

    // Read sources once from any target's export (they're shared across targets, at least for now that's the assumption)
    _ <- step("Discovering sources...")
    firstTarget = targets.head
    firstExportJson <- exec(
      scalaCli,
      ("--power" :: "export" :: "--json" :: "--server=false" :: "--offline" ::
        "--platform" :: firstTarget.platformFlag ::
        firstTarget.scalaVersion.toList.flatMap(v => List("--scala-version", v)) ++
        inputArgs)*
    )
    firstExport <- IO.fromEither(
      parseJson(firstExportJson)
        .flatMap(_.as[ExportInfo])
        .leftMap(e => new RuntimeException(s"Failed to parse export JSON: ${e.getMessage}"))
    )
    sources = {
      val mainScope = firstExport.scopes.getOrElse("main", ExportScope(Nil, Nil))
      mainScope.sources.map { s =>
        s.stripPrefix(cwd.toString + "/")
          .stripPrefix("/private" + cwd.toString + "/")
      }
    }

    targetLocks <- targets.traverse { target =>
      val key = targetKey(target, allPlatforms, allVersions)
      computeTargetLock(scalaCli, inputArgs, cwd, target, key).map(key -> _)
    }

    lockFile = LockFile(
      version = 6,
      sources = sources,
      targets = targetLocks.toMap
    )
  } yield lockfilePrinter.print(lockFile.asJson) + "\n"
}

private def computeTargetLock(
    scalaCli: String,
    inputArgs: List[String],
    cwd: Path,
    target: Target,
    key: String
): IO[TargetLock] = {
  val versionArgs =
    target.scalaVersion.toList.flatMap(v => List("--scala-version", v))
  val exportArgs =
    "--power" :: "export" :: "--json" :: "--server=false" :: "--offline" ::
      "--platform" :: target.platformFlag :: versionArgs ++ inputArgs

  for {
    _ <- step(s"Exporting target ${C.bold}$key${C.reset}...")
    exportJson <- exec(scalaCli, exportArgs*)
    rawJson <- IO.fromEither(
      parseJson(exportJson).leftMap(e =>
        new RuntimeException(s"Failed to parse export JSON: ${e.message}")
      )
    )
    exportHash = sha1Hex(hashPrinter.print(rawJson) + "\n")
    export_ <- IO.fromEither(
      rawJson
        .as[ExportInfo]
        .leftMap(e =>
          new RuntimeException(s"Failed to decode export JSON: ${e.message}")
        )
    )
    result <- computeTargetLockContent(scalaCli, inputArgs, cwd, target, key, export_, exportHash)
  } yield result
}

private def computeTargetLockContent(
    scalaCli: String,
    inputArgs: List[String],
    cwd: Path,
    target: Target,
    key: String,
    export_ : ExportInfo,
    exportHash: String
): IO[TargetLock] = {
  val scalaVersion = export_.scalaVersion
  val scalaMajor = scalaVersion.takeWhile(_ != '.')
  val mainScope = export_.scopes.getOrElse("main", ExportScope(Nil, Nil))
  val deps = mainScope.dependencies.map { d =>
    s"${d.groupId}:${d.artifactId.fullName}:${d.version}"
  }

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
        _ <- info(s"Platform: ${C.bold}${target.platformLock}${C.reset}")
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
        allLibDeps = libraryArtifact +: userDeps
        libArtifacts <- fetchArtifacts(allLibDeps*)
        _ <- info(
          s"Libraries: ${C.bold}${libArtifacts.size}${C.reset} artifacts (transitive)"
        )

        nativeLockDeps <- export_.nativeOptions.traverse { opts =>
          // Compiler plugins + runtime deps resolved together, separately from user libs.
          // This ensures they stay at scalaNativeVersion and don't get evicted by user deps.
          val nativeDeps = toDeps(opts.compilerPlugins) ++ toDeps(opts.runtimeDependencies)

          for {
            _ <- step("Fetching native dependencies...")
            nativeArtifacts <- fetchArtifacts(nativeDeps*)
            _ <- info(
              s"Native: ${C.bold}${nativeArtifacts.size}${C.reset} artifacts"
            )
            _ <- step("Fetching native tooling dependencies...")
            toolingArtifacts <- fetchArtifacts(toDeps(opts.toolingDependencies)*)
            _ <- info(
              s"Native tooling: ${C.bold}${toolingArtifacts.size}${C.reset} artifacts"
            )
            nativeEntries <- collectEntries(nativeArtifacts)
            toolingEntries <- collectEntries(toolingArtifacts)
          } yield NativeLockDeps(opts.scalaNativeVersion, nativeEntries, Nil, toolingEntries)
        }

        _ <- step("Hashing artifacts...")
        compilerEntries <- collectEntries(compilerArtifacts)
        libEntries <- collectEntries(libArtifacts)
      } yield TargetLock(
        scalaVersion = scalaVersion,
        platform = target.platformLock,
        exportHash = exportHash,
        compiler = compilerEntries,
        libraryDependencies = libEntries,
        native = nativeLockDeps
      )

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

def lock(inputs: List[String]): IO[ExitCode] =
  for {
    content         <- computeLock(inputs)
    cwd             <- Files[IO].currentWorkingDirectory
    lockfilePath    = cwd / "scala.lock.json"
    existingContent <- Files[IO].exists(lockfilePath).ifM(readFile(lockfilePath), IO.pure(""))
    _ <-
      if (existingContent == content)
        info("Lock is up to date.")
      else
        step("Writing lockfile...") *>
          writeFile(lockfilePath, content) *>
          success(s"Wrote ${C.bold}scala.lock.json${C.reset}")
  } yield ExitCode.Success

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

  def prepareDerivation(isCross: Boolean): IO[(List[(Path, String)], List[String])] =
    Files[IO].exists(cwd / "derivation.nix").flatMap {
      case true =>
        warn("derivation.nix already exists, skipping.")
          .as((Nil, Nil))
      case false =>
        val buildFn = if (isCross) "buildScalaCliApps" else "buildScalaCliApp"
        val content =
          s"""{ scala-cli-nix }:
             |
             |scala-cli-nix.$buildFn {
             |  pname = "$pname";
             |  version = "0.1.0";
             |  src = ./.;
             |  lockFile = ./scala.lock.json;
             |}
             |""".stripMargin
        IO.pure((List(cwd / "derivation.nix" -> content), List("derivation.nix")))
    }

  def prepareFlake(isCross: Boolean): IO[(List[(Path, String)], List[String])] =
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
          errln(s"  ${C.bold}3.${C.reset} Add the package(s):") *>
          errln("") *>
          errln(
            if (isCross)
              s"    ${C.dim}# buildScalaCliApps returns an attrset — flatten into packages:${C.reset}\n" +
              s"    ${C.dim}pkgs.callPackage ./derivation.nix { }${C.reset}"
            else
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
        // For cross projects, flatten the attrset from buildScalaCliApps into named packages
        // (e.g. packages.${system}.jvm, packages.${system}.native) — no default package.
        val packagesBody =
          if (isCross)
            """|          pkgs.callPackage ./derivation.nix { }"""
          else
            """|          default = pkgs.callPackage ./derivation.nix { };"""
        val content =
          s"""|{
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
              |$packagesBody
              |          }
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
    (platforms, versions) <- parseDirectives(Nil, cwd)
    isCross = platforms.size > 1 || versions.size > 1
    derivation <- prepareDerivation(isCross)
    flake <- prepareFlake(isCross)
    _ <- errln("")
    lockContent <- computeLock(Nil)
    pendingFiles =
      derivation._1 ++ flake._1 ++ List((cwd / "scala.lock.json") -> lockContent)
    fileNames = derivation._2 ++ flake._2 ++ List("scala.lock.json")
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

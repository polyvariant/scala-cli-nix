//> using scala 3.8.3
//> using scalacOption -no-indent
//> using dep io.get-coursier:interface:1.0.29-M4
//> using dep io.circe::circe-generic::0.14.15
//> using dep io.circe::circe-parser::0.14.15
//> using dep org.typelevel::cats-effect::3.7.0
//> using dep co.fs2::fs2-io::3.13.0
//> using dep com.github.alexarchambault::case-app::2.1.0
//> using dep org.scala-lang.modules::scala-xml::2.4.0

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import caseapp.*
import caseapp.core.RemainingArgs
import caseapp.core.app.Command
import caseapp.core.app.CommandsEntryPoint
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
import scala.xml.{Node, NodeSeq, XML}

// --- JSON model (lockfile) ---

case class ArtifactEntry(url: String, sha256: String) derives Codec.AsObject

case class NativeLockDeps(
    scalaNativeVersion: String,
    compilerPlugins: List[ArtifactEntry],
    runtimeDependencies: List[ArtifactEntry],
    toolingDependencies: List[ArtifactEntry]
) derives Codec.AsObject

case class TestLock(
    sources: List[String],
    libraryDependencies: List[ArtifactEntry]
) derives Codec.AsObject

case class TargetLock(
    scalaVersion: String,
    platform: String,
    exportHash: String,
    compiler: List[ArtifactEntry],
    libraryDependencies: List[ArtifactEntry],
    native: Option[NativeLockDeps],
    test: Option[TestLock]
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

/** Run a process and capture stdout as a string. Stderr is forwarded to our
  * stderr.
  */
def exec(command: String, args: String*): IO[String] =
  ProcessBuilder(command, args.toList)
    .spawn[IO]
    .use { proc =>
      val stdout = proc.stdout.through(fs2.text.utf8.decode).compile.string
      val stderr = proc.stderr.through(fs2.io.stderr[IO]).compile.drain
      (stdout, stderr).parTupled.flatMap { case (out, _) =>
        proc.exitValue.flatMap { code =>
          IO.raiseError(
            new RuntimeException(s"$command exited with code $code")
          ).whenA(code != 0)
            .as(out)
        }
      }
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

/** Parse a POM as XML. Throws on malformed XML — POMs in the Coursier cache are
  * well-formed by construction.
  */
private def loadPom(content: String): Node = XML.loadString(content)

private def childText(parent: NodeSeq, label: String): Option[String] =
  (parent \ label).headOption.map(_.text.trim).filter(_.nonEmpty)

private def parseGAV(node: NodeSeq): Option[(String, String, String)] =
  for {
    g <- childText(node, "groupId")
    a <- childText(node, "artifactId")
    v <- childText(node, "version")
  } yield (g, a, v)

def parseParent(content: String): Option[(String, String, String)] =
  (loadPom(content) \ "parent").headOption.flatMap(parseGAV(_))

def extractParent(pomPath: Path): IO[Option[(String, String, String)]] =
  readFile(pomPath).map(parseParent)

/** Given a POM URL and its coordinates, derive the repository base URL by
  * stripping the Maven layout suffix
  * `<groupPath>/<artifact>/<version>/<artifact>-<version>.pom`. Returns `""`
  * when the URL doesn't match (e.g. classifier'd POM, non-Maven repo layout) —
  * treated as "skip" by downstream callers.
  */
def repoBaseFromCoords(
    pomUrl: String,
    groupId: String,
    artifactId: String,
    version: String
): String = {
  val groupPath = groupId.replace('.', '/')
  val suffix = s"$groupPath/$artifactId/$version/$artifactId-$version.pom"
  if (pomUrl.endsWith(suffix)) pomUrl.dropRight(suffix.length)
  else ""
}

/** Parse the POM's <properties> block (top-level only) into a name->value map.
  * Values may themselves contain placeholders (`${name}`) that resolve against
  * properties higher in the parent chain — caller is responsible for combining
  * maps and resolving recursively if needed.
  */
def parseProperties(content: String): Map[String, String] =
  (loadPom(content) \ "properties").headOption
    .map { props =>
      props.child.collect { case e: scala.xml.Elem =>
        e.label -> e.text.trim
      }.toMap
    }
    .getOrElse(Map.empty)

/** Substitute `${name}` placeholders in `value` using `props`. Resolves
  * indirections by re-substituting until stable or until a fixed iteration cap
  * is hit. Unresolved placeholders are left intact.
  */
def substituteProperties(value: String, props: Map[String, String]): String = {
  val placeholder = "\\$\\{([^}]+)\\}".r
  def step(s: String): String =
    placeholder.replaceAllIn(
      s,
      m =>
        java.util.regex.Matcher
          .quoteReplacement(props.getOrElse(m.group(1), m.matched))
    )
  Iterator
    .iterate(value)(step)
    .sliding(2)
    .find(p => p.head == p.last)
    .map(_.head)
    .getOrElse(value)
}

/** Best-effort POM coordinate extraction. Falls back to <parent>'s groupId and
  * version when the POM itself doesn't redeclare them.
  */
def parsePomCoords(content: String): Option[(String, String, String)] = {
  val root = loadPom(content)
  val parent = (root \ "parent").headOption
  val artifactId = childText(root, "artifactId")
  val groupId =
    childText(root, "groupId").orElse(parent.flatMap(childText(_, "groupId")))
  val version =
    childText(root, "version").orElse(parent.flatMap(childText(_, "version")))
  (groupId, artifactId, version).tupled
}

/** Repo base for a POM, derived by parsing its coordinates from the POM content
  * and matching against `pomUrl`. Returns `""` when coords can't be parsed or
  * the URL doesn't follow Maven layout.
  */
def repoBaseForPom(pomPath: Path, pomUrl: String): IO[String] =
  readFile(pomPath).map { content =>
    parsePomCoords(content) match {
      case Some((g, a, v)) => repoBaseFromCoords(pomUrl, g, a, v)
      case None            => ""
    }
  }

/** Walk a POM's parent chain and merge their <properties> blocks, with
  * descendants overriding ancestors (Maven inheritance semantics). The POM at
  * `pomPath` is included.
  */
def collectAccumulatedProperties(
    pomPath: Path,
    repoBase: String
): IO[Map[String, String]] = {
  def loop(
      current: Path,
      acc: Map[String, String]
  ): IO[Map[String, String]] =
    readFile(current).flatMap { content =>
      // Parent's properties go in first so child's override on conflict.
      val ownProps = parseProperties(content)
      extractParent(current).flatMap {
        case Some((g, a, v)) if repoBase.nonEmpty =>
          val groupPath = g.replace('.', '/')
          val parentUrl = s"$repoBase$groupPath/$a/$v/$a-$v.pom"
          val parentPath = cachePathForUrl(parentUrl)
          Files[IO].exists(parentPath).flatMap {
            case true =>
              loop(parentPath, acc).map(parentAcc => parentAcc ++ ownProps)
            case false => IO.pure(acc ++ ownProps)
          }
        case _ => IO.pure(acc ++ ownProps)
      }
    }
  loop(pomPath, Map.empty)
}

def collectParentPoms(
    pomPath: Path,
    repoBase: String
): IO[List[ArtifactEntry]] = {
  def loop(current: Path, acc: List[ArtifactEntry]): IO[List[ArtifactEntry]] =
    extractParent(current).flatMap {
      case Some((groupId, artifactId, version)) if repoBase.nonEmpty =>
        val groupPath = groupId.replace('.', '/')
        val url =
          s"$repoBase$groupPath/$artifactId/$version/$artifactId-$version.pom"
        val parentPomPath = cachePathForUrl(url)
        Files[IO].exists(parentPomPath).flatMap {
          case true =>
            sha256Base64(parentPomPath).flatMap { hash =>
              loop(parentPomPath, ArtifactEntry(url, hash) :: acc)
            }
          case false =>
            IO.pure(acc.reverse)
        }
      case _ =>
        IO.pure(acc.reverse)
    }
  loop(pomPath, Nil)
}

/** Capture POM-only artifacts imported as BOMs in the given POM's
  * <dependencyManagement>. Recursively follows the BOM's own parent chain and
  * any nested BOM imports. Returns an empty list when the BOM POM is absent
  * from the Coursier cache (e.g. because Coursier didn't end up needing it
  * during lock-time resolution).
  */
/** Collect all (g,a,v) BOM imports declared by `pomPath` and any POM in its
  * parent chain, with property substitution at each level using that level's
  * accumulated properties.
  */
private def collectBomCoordsFromChain(
    pomPath: Path,
    repoBase: String
): IO[List[(String, String, String)]] = {
  def loop(
      current: Path,
      acc: List[(String, String, String)]
  ): IO[List[(String, String, String)]] =
    for {
      content <- readFile(current)
      props <- collectAccumulatedProperties(current, repoBase)
      projectVersion = parsePomVersion(content)
      boms = parseImportedBoms(content, projectVersion, props)
      next <- extractParent(current).flatMap {
        case Some((g, a, v)) if repoBase.nonEmpty =>
          val groupPath = g.replace('.', '/')
          val parentUrl = s"$repoBase$groupPath/$a/$v/$a-$v.pom"
          val parentPath = cachePathForUrl(parentUrl)
          Files[IO].exists(parentPath).flatMap {
            case true  => loop(parentPath, acc ++ boms)
            case false => IO.pure(acc ++ boms)
          }
        case _ => IO.pure(acc ++ boms)
      }
    } yield next
  loop(pomPath, Nil)
}

def collectImportedBoms(
    pomPath: Path,
    repoBase: String,
    visited: Set[String] = Set.empty
): IO[List[ArtifactEntry]] =
  if (repoBase.isEmpty) IO.pure(Nil)
  else
    collectBomCoordsFromChain(pomPath, repoBase).flatMap { boms =>
      boms.distinct
        .foldLeftM((List.empty[ArtifactEntry], visited)) {
          case ((acc, seen), (g, a, v)) =>
            val key = s"$g:$a:$v"
            if (seen.contains(key)) IO.pure((acc, seen))
            else {
              val groupPath = g.replace('.', '/')
              val url = s"$repoBase$groupPath/$a/$v/$a-$v.pom"
              val bomPomPath = cachePathForUrl(url)
              Files[IO].exists(bomPomPath).flatMap {
                case false => IO.pure((acc, seen + key))
                case true  =>
                  for {
                    hash <- sha256Base64(bomPomPath)
                    bomEntry = ArtifactEntry(url, hash)
                    parents <- collectParentPoms(bomPomPath, repoBase)
                    nestedSeen = seen + key
                    nested <- collectImportedBoms(
                      bomPomPath,
                      repoBase,
                      nestedSeen
                    )
                  } yield (acc ++ (bomEntry :: parents) ++ nested, nestedSeen)
              }
            }
        }
        .map(_._1)
    }

/** Pure version of extractDeclaredDeps. Returns (groupId, artifactId, version)
  * tuples for each <dependency> in the POM's <dependencies> section. Excludes
  * deps inside <dependencyManagement>. May include unresolved property
  * placeholders like ${project.version} — caller filters those out.
  */
def parseDeclaredDeps(pomContent: String): List[(String, String, String)] = {
  val root = loadPom(pomContent)
  (root \ "dependencies" \ "dependency").toList.flatMap(parseGAV(_))
}

def extractDeclaredDeps(pomPath: Path): IO[List[(String, String, String)]] =
  readFile(pomPath).map(parseDeclaredDeps)

/** Top-level POM version. Falls back to the parent's <version> when missing
  * (POM inherits the version from its parent).
  */
def parsePomVersion(pomContent: String): Option[String] = {
  val root = loadPom(pomContent)
  childText(root, "version").orElse {
    (root \ "parent").headOption.flatMap(childText(_, "version"))
  }
}

/** Returns BOM imports declared inside <dependencyManagement>: deps with
  * <scope>import</scope> (typically also <type>pom</type>). Resolves
  * `${project.version}` against the containing POM's version when known.
  */
def parseImportedBoms(
    pomContent: String,
    projectVersion: Option[String],
    properties: Map[String, String] = Map.empty
): List[(String, String, String)] = {
  val root = loadPom(pomContent)
  val effectiveProps =
    properties ++ projectVersion.map("project.version" -> _).toMap
  val deps = root \ "dependencyManagement" \ "dependencies" \ "dependency"
  deps.toList.flatMap { dep =>
    val isImport = childText(dep, "scope").contains("import")
    if (!isImport) Nil
    else
      parseGAV(dep).toList.flatMap { (g, a, v) =>
        val resolved = substituteProperties(v, effectiveProps)
        Option.when(!resolved.contains("$"))((g, a, resolved))
      }
  }
}

/** Walk all resolved POMs to find declared deps and fetch their JAR + POM
  * individually. This captures evicted versions that scala-cli may try to fetch
  * during offline resolution.
  *
  * Each declared dep is fetched as its own resolution. Unlike
  * `withTransitive(false)` which fails on some artifacts in the Coursier
  * interface, this works reliably. Failures are tolerated (e.g. for
  * placeholder/marker artifacts).
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

/** Like collectEntries but does not recursively walk declared deps (avoids
  * infinite loop).
  */
def collectEntriesNoRecurse(
    artifacts: List[(Artifact, File)]
): IO[List[ArtifactEntry]] =
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
              for {
                repoBase <- repoBaseForPom(pomPath, pomUrl)
                parentEntries <- collectParentPoms(pomPath, repoBase)
                bomEntries <- collectImportedBoms(pomPath, repoBase)
              } yield jarEntry :: ArtifactEntry(
                pomUrl,
                pomHash
              ) :: parentEntries ++ bomEntries
            }
        }
      }
    }
    .map(_.distinctBy(_.url))

def collectEntries(
    artifacts: List[(Artifact, File)]
): IO[List[ArtifactEntry]] = {
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
                for {
                  repoBase <- repoBaseForPom(pomPath, pomUrl)
                  parentEntries <- collectParentPoms(pomPath, repoBase)
                  bomEntries <- collectImportedBoms(pomPath, repoBase)
                } yield {
                  val newEntries =
                    jarEntry :: ArtifactEntry(
                      pomUrl,
                      pomHash
                    ) :: parentEntries ++ bomEntries ++ entries
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

// When launched via the Nix-wrapped `scala-cli-nix`, the wrapper sets
// SCALA_CLI_NIX_SCALA_CLI to an absolute path of the bundled scala-cli build.
// Outside that wrapper (e.g. running the CLI directly during development),
// fall back to whatever `scala-cli` is on PATH.
val resolveScalaCli: IO[String] =
  IO(sys.env.getOrElse("SCALA_CLI_NIX_SCALA_CLI", "scala-cli"))

// --- Target discovery ---

/** A cross-build target: one (platform, scalaVersion) combination. */
case class Target(platform: String, scalaVersion: Option[String]) {

  /** Platform name for the --platform flag (jvm -> jvm, native ->
    * scala-native).
    */
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

/** One entry in the JSON output of `scala-cli --power list-targets`. */
case class ListTargetEntry(platform: String, scalaVersion: Option[String])
    derives Decoder

/** Ask scala-cli for the full build matrix. The CLI emits one entry per
  * declared (platform, scalaVersion) combination, so we don't have to parse
  * `using` directives ourselves.
  */
def listTargets(scalaCli: String, inputArgs: List[String]): IO[List[Target]] =
  exec(scalaCli, ("--power" :: "list-targets" :: inputArgs)*)
    .flatMap { json =>
      IO.fromEither(
        parseJson(json)
          .flatMap(_.as[List[ListTargetEntry]])
          .leftMap(e =>
            new RuntimeException(
              s"Failed to parse list-targets JSON: ${e.getMessage}"
            )
          )
      )
    }
    .map(_.map { e =>
      val platform = e.platform match {
        case "Native" => "native"
        case _        => "jvm"
      }
      Target(platform, e.scalaVersion)
    })

/** Compute the lock key for a target given the full target list. */
def targetKey(target: Target, allTargets: List[Target]): String = {
  val multiPlatform = allTargets.map(_.platform).distinct.sizeIs > 1
  val multiVersion = allTargets.map(_.scalaVersion).distinct.sizeIs > 1
  (multiPlatform, multiVersion) match {
    case (true, true) =>
      s"${target.platform}-${target.scalaVersion.getOrElse("default")}"
    case (true, false)  => target.platform
    case (false, true)  => target.scalaVersion.getOrElse("default")
    case (false, false) => target.platform
  }
}

// --- Option case classes ---

case class LockOptions()
case class InitOptions(
    ref: Option[String] = None
)

// --- Lock command ---

val hashPrinter: Printer = Printer.noSpaces.copy(sortKeys = true)
val lockfilePrinter: Printer =
  Printer.spaces2.copy(sortKeys = true, colonLeft = "", dropNullValues = true)

/** Compute lockfile content without writing it. Always recomputes from scratch.
  */
def computeLock(inputs: List[String]): IO[String] = {
  val inputArgs = if (inputs.isEmpty) List(".") else inputs

  for {
    scalaCli <- resolveScalaCli
    cwd <- Files[IO].currentWorkingDirectory

    targets <- listTargets(scalaCli, inputArgs)

    _ <- info(
      s"Targets: ${C.bold}${targets.map(t => targetKey(t, targets)).mkString(", ")}${C.reset}"
    )

    // Read sources once from any target's export (they're shared across targets, at least for now that's the assumption)
    _ <- step("Discovering sources...")
    firstTarget = targets.head
    firstExportJson <- exec(
      scalaCli,
      ("--power" :: "export" :: "--json" :: "--server=false" :: "--offline" ::
        "--platform" :: firstTarget.platformFlag ::
        firstTarget.scalaVersion.toList
          .flatMap(v => List("--scala-version", v)) ++
        inputArgs)*
    )
    firstExport <- IO.fromEither(
      parseJson(firstExportJson)
        .flatMap(_.as[ExportInfo])
        .leftMap(e =>
          new RuntimeException(s"Failed to parse export JSON: ${e.getMessage}")
        )
    )
    sources = firstExport.scopes
      .getOrElse("main", ExportScope(Nil, Nil))
      .sources
      .map(stripCwd(cwd))

    targetLocks <- targets.traverse { target =>
      val key = targetKey(target, targets)
      computeTargetLock(scalaCli, inputArgs, cwd, target, key).map(key -> _)
    }

    lockFile = LockFile(
      version = 7,
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
    result <- computeTargetLockContent(
      scalaCli,
      inputArgs,
      cwd,
      target,
      key,
      export_,
      exportHash
    )
  } yield result
}

private def stripCwd(cwd: Path)(s: String): String =
  s.stripPrefix(cwd.toString + "/")
    .stripPrefix("/private" + cwd.toString + "/")

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
  val testScope = export_.scopes.get("test")
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
          val nativeDeps =
            toDeps(opts.compilerPlugins) ++ toDeps(opts.runtimeDependencies)

          for {
            _ <- step("Fetching native dependencies...")
            nativeArtifacts <- fetchArtifacts(nativeDeps*)
            _ <- info(
              s"Native: ${C.bold}${nativeArtifacts.size}${C.reset} artifacts"
            )
            _ <- step("Fetching native tooling dependencies...")
            toolingArtifacts <- fetchArtifacts(
              toDeps(opts.toolingDependencies)*
            )
            _ <- info(
              s"Native tooling: ${C.bold}${toolingArtifacts.size}${C.reset} artifacts"
            )
            nativeEntries <- collectEntries(nativeArtifacts)
            toolingEntries <- collectEntries(toolingArtifacts)
          } yield NativeLockDeps(
            opts.scalaNativeVersion,
            nativeEntries,
            Nil,
            toolingEntries
          )
        }

        // Test scope: scala-cli's test scope includes both main and test
        // dependencies in a single combined resolution. We capture the full
        // transitive set as the test classpath. The JVM test-runner is part of
        // the test scope's `dependencies` (scala-cli's `export --json` injects
        // it for the Test scope on JVM). For Native, `test-interface` is pulled
        // in transitively by the test framework (e.g. munit-native).
        testLock <- testScope
          .filter(s =>
            s.sources.nonEmpty || s.dependencies != mainScope.dependencies
          )
          .traverse { tScope =>
            val testDeps = tScope.dependencies.map(d =>
              Dependency.of(d.groupId, d.artifactId.fullName, d.version)
            )
            val allTestDeps = libraryArtifact +: testDeps
            for {
              _ <- step("Fetching test dependencies...")
              testArtifacts <- fetchArtifacts(allTestDeps*)
              _ <- info(
                s"Test: ${C.bold}${testArtifacts.size}${C.reset} artifacts (transitive)"
              )
              testEntries <- collectEntries(testArtifacts)
            } yield TestLock(
              sources = tScope.sources.map(stripCwd(cwd)),
              libraryDependencies = testEntries
            )
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
        native = nativeLockDeps,
        test = testLock
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
    content <- computeLock(inputs)
    cwd <- Files[IO].currentWorkingDirectory
    lockfilePath = cwd / "scala.lock.json"
    existingContent <- Files[IO]
      .exists(lockfilePath)
      .ifM(readFile(lockfilePath), IO.pure(""))
    _ <-
      if (existingContent == content)
        info("Lock is up to date.")
      else
        step("Writing lockfile...") *>
          writeFile(lockfilePath, content) *>
          success(s"Wrote ${C.bold}scala.lock.json${C.reset}")
  } yield ExitCode.Success

// --- Init command ---

private val ShaPattern = "^[0-9a-f]{40}$".r

private def pinSuffix(value: String): String =
  if (ShaPattern.matches(value)) s"?rev=$value"
  else s"?ref=$value"

def resolveScalaCliNixUrl(ref: Option[String]): IO[String] = {
  val baseUrl = "github:scala-nix/scala-cli-nix"
  ref match {
    case None                         => IO.pure(baseUrl)
    case Some(value) if value.isEmpty => IO.pure(baseUrl)
    case Some(value)                  => IO.pure(s"$baseUrl${pinSuffix(value)}")
  }
}

def init(inputs: List[String], ref: Option[String]): IO[ExitCode] =
  for {
    cwd <- Files[IO].currentWorkingDirectory
    lockExists <- Files[IO].exists(cwd / "scala.lock.json")
    result <-
      if (lockExists)
        info(
          s"${C.bold}scala.lock.json${C.reset} already exists, running ${C.bold}lock${C.reset} instead."
        ) *> lock(inputs)
      else if (inputs.nonEmpty)
        doInit(cwd, inputs, ref)
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
              doInit(cwd, inputs, ref)
          }
  } yield result

private def doInit(
    cwd: Path,
    inputs: List[String],
    ref: Option[String]
): IO[ExitCode] = {
  val pname = cwd.fileName.toString

  def prepareDerivation(
      isCross: Boolean
  ): IO[(List[(Path, String)], List[String])] =
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
        IO.pure(
          (List(cwd / "derivation.nix" -> content), List("derivation.nix"))
        )
    }

  def prepareFlake(
      isCross: Boolean,
      scalaCliNixUrl: String
  ): IO[(List[(Path, String)], List[String])] =
    Files[IO].exists(cwd / "flake.nix").flatMap {
      case true =>
        errln("") *>
          warn("flake.nix already exists. Add the following to your flake:") *>
          errln("") *>
          errln(s"  ${C.bold}1.${C.reset} Add the input:") *>
          errln("") *>
          errln(
            s"""    ${C.dim}scala-cli-nix.url = "$scalaCliNixUrl";${C.reset}"""
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
            s"  ${C.bold}4.${C.reset} Expose tests as checks (so ${C.dim}nix flake check${C.reset} runs them):"
          ) *>
          errln("") *>
          errln(
            s"    ${C.dim}checks.$${system} = pkgs.scala-cli-nix.collectChecks self.packages.$${system};${C.reset}"
          ) *>
          errln("") *>
          errln(
            s"  ${C.bold}5.${C.reset} Add to your devShell (provides ${C.dim}scala-cli-nix${C.reset} and the ${C.dim}scn${C.reset} alias):"
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
              |    scala-cli-nix.url = "$scalaCliNixUrl";
              |    scala-cli-nix.inputs.nixpkgs.follows = "nixpkgs";
              |  };
              |
              |  outputs = { self, nixpkgs, scala-cli-nix, ... }:
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
              |      # Pull `passthru.tests` from every package into checks, so
              |      # `nix flake check` runs the test scope of each target.
              |      checks = forAllSystems (system:
              |        let
              |          pkgs = import nixpkgs {
              |            inherit system;
              |            overlays = [ scala-cli-nix.overlays.default ];
              |          };
              |        in pkgs.scala-cli-nix.collectChecks self.packages.$${system}
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
    scalaCli <- resolveScalaCli
    scalaCliNixUrl <- resolveScalaCliNixUrl(ref)
    targets <- listTargets(scalaCli, inputs)
    isCross = targets.sizeIs > 1
    derivation <- prepareDerivation(isCross)
    flake <- prepareFlake(isCross, scalaCliNixUrl)
    _ <- errln("")
    lockContent <- computeLock(inputs)
    pendingFiles =
      derivation._1 ++ flake._1 ++ List(
        (cwd / "scala.lock.json") -> lockContent
      )
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

/** Run an `IO[ExitCode]` and exit the JVM with the resulting code. We bridge
  * here because case-app's `Command.run` is sync and `Unit`-returning, while
  * our commands are written as `IO`. Any uncaught error short-circuits to a
  * non-zero exit.
  */
private def runIO(program: IO[ExitCode]): Unit = {
  val code = program.unsafeRunSync()
  if (code != ExitCode.Success) sys.exit(code.code)
}

object ScalaCliNix extends CommandsEntryPoint {
  override def progName: String = "scala-cli-nix"
  override def description: String = "Nix packaging for scala-cli apps"
  override def commands: Seq[Command[?]] = Seq(InitCommand, LockCommand)
  override def enableCompleteCommand: Boolean = true
  override def enableCompletionsCommand: Boolean = true

  private object InitCommand extends Command[InitOptions] {
    override def name: String = "init"
    override def run(options: InitOptions, args: RemainingArgs): Unit =
      runIO(init(args.remaining.toList, options.ref))
  }

  private object LockCommand extends Command[LockOptions] {
    override def name: String = "lock"
    override def run(options: LockOptions, args: RemainingArgs): Unit =
      runIO(lock(args.remaining.toList))
  }
}

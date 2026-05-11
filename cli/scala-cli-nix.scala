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
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import caseapp.*
import caseapp.core.RemainingArgs
import caseapp.core.app.Command
import caseapp.core.app.CommandsEntryPoint
import coursierapi.*
import fs2.Stream
import fs2.hashing.{HashAlgorithm, Hashing}
import fs2.io.file.{Files, Path}
import fs2.io.process.ProcessBuilder
import io.circe.{Codec, Decoder, Json, Printer}
import io.circe.parser.parse as parseJson
import io.circe.syntax.*
import java.io.File
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
    resourceDirs: List[String],
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

/** sbt-specific lock section. Populated by `lock-sbt`; absent otherwise.
  *
  * Two artifact groupings:
  *   - `bootJars`: the 83-ish *resolved-winners* JAR URLs from
  *     `org.scala-sbt:sbt:<sbtVersion>` resolution. These are the ones the
  *     launcher places in its flat boot-dir layout — no POMs, no evicted
  *     versions. Including evicted versions in the boot dir causes `sbt#4955`
  *     ("unable to detect Scala version").
  *   - `bootCoursierCache`: the full transitive set (~295 entries: JARs + POMs
  *     + parent POMs + evicted versions). These go into COURSIER_CACHE so sbt's
  *     own offline Coursier resolution can find what it needs.
  *
  * `scalaInstance` and `compilerBridge` are likewise grouped with their POMs
  * for offline Coursier resolution. `launcherJar` is the
  * `org.scala-sbt:sbt-launch:<sbtVersion>` jar we exec directly — using
  * nixpkgs' bundled launcher fails on any sbt version that doesn't match its
  * embedded Scala bootstrap version.
  */
case class SbtLock(
    sbtVersion: String,
    scalaBootVersion: String,
    mainClass: Option[String],
    launcherJar: ArtifactEntry,
    bootJars: List[ArtifactEntry],
    bootCoursierCache: List[ArtifactEntry],
    scalaInstance: List[ArtifactEntry],
    compilerBridge: List[ArtifactEntry]
) derives Codec.AsObject

case class LockFile(
    version: Int,
    sources: List[String],
    resourceDirs: List[String],
    targets: Map[String, TargetLock],
    sbt: Option[SbtLock] = None
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
    resourceDirs: List[String],
    dependencies: List[ExportDependency]
)
object ExportScope {
  given Decoder[ExportScope] = Decoder.instance { c =>
    for {
      sources <- c.get[List[String]]("sources")
      resourceDirs <- c.getOrElse[List[String]]("resourceDirs")(Nil)
      deps <- c.getOrElse[List[ExportDependency]]("dependencies")(Nil)
    } yield ExportScope(sources, resourceDirs, deps)
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

/** Persisted entry for a hashed file. `size`+`mtime` form a cheap freshness
  * stamp: if either differs from the file's current attributes, we recompute.
  * Coursier-cached artifacts at non-SNAPSHOT coordinates are immutable, so this
  * is overwhelmingly cache-hit territory; SNAPSHOTs and re-downloads still get
  * the right answer because their mtime changes.
  */
case class HashCacheEntry(size: Long, mtime: Long, sha256: String)
    derives Codec.AsObject

case class HashCacheFile(version: Int, entries: Map[String, HashCacheEntry])
    derives Codec.AsObject

private val HashCacheVersion = 1

case class HashCacheStats(hits: Int, misses: Int)

class HashCache(
    state: Ref[IO, Map[String, HashCacheEntry]],
    stats: Ref[IO, HashCacheStats],
    val location: Path
) {
  def get(path: Path): IO[String] =
    Files[IO].getBasicFileAttributes(path).flatMap { attrs =>
      val key = path.toString
      val size = attrs.size
      val mtime = attrs.lastModifiedTime.toMillis
      state.get.flatMap { m =>
        m.get(key) match {
          case Some(e) if e.size == size && e.mtime == mtime =>
            stats.update(s => s.copy(hits = s.hits + 1)).as(e.sha256)
          case _ =>
            computeSha256(path).flatTap { hash =>
              state.update(
                _.updated(key, HashCacheEntry(size, mtime, hash))
              ) *>
                stats.update(s => s.copy(misses = s.misses + 1))
            }
        }
      }
    }

  def currentStats: IO[HashCacheStats] = stats.get

  def save: IO[Unit] =
    state.get
      .flatMap { entries =>
        val payload = HashCacheFile(HashCacheVersion, entries)
        val parent = location.parent.getOrElse(location)
        Files[IO].createDirectories(parent) *>
          writeFile(location, lockfilePrinter.print(payload.asJson) + "\n")
      }
      .attempt
      .void
}

private def computeSha256(path: Path): IO[String] =
  Files[IO]
    .readAll(path)
    .through(Hashing[IO].hash(HashAlgorithm.SHA256))
    .compile
    .lastOrError
    .map(h => Base64.getEncoder.encodeToString(h.bytes.toArray))

object HashCache {

  /** Resolve the on-disk cache location, honoring `XDG_CACHE_HOME` and falling
    * back to `~/.cache/scala-cli-nix/hashes.json`.
    */
  def defaultLocation: IO[Path] = IO {
    val xdg = sys.env.get("XDG_CACHE_HOME").filter(_.nonEmpty)
    val base = xdg.getOrElse(sys.props("user.home") + "/.cache")
    Path(base) / "scala-cli-nix" / "hashes.json"
  }

  /** Load the cache from `location`. Missing or unparseable files give an empty
    * cache — never fatal.
    */
  def load(location: Path): IO[HashCache] =
    for {
      entries <- Files[IO]
        .exists(location)
        .flatMap {
          case false => IO.pure(Map.empty[String, HashCacheEntry])
          case true  =>
            readFile(location).attempt.map {
              case Right(content) =>
                parseJson(content)
                  .flatMap(_.as[HashCacheFile])
                  .toOption
                  .filter(_.version == HashCacheVersion)
                  .map(_.entries)
                  .getOrElse(Map.empty)
              case Left(_) => Map.empty
            }
        }
      stateRef <- Ref.of[IO, Map[String, HashCacheEntry]](entries)
      statsRef <- Ref.of[IO, HashCacheStats](HashCacheStats(0, 0))
    } yield new HashCache(stateRef, statsRef, location)
}

def sha256Base64(path: Path)(using cache: HashCache): IO[String] =
  cache.get(path)

def sha1Hex(s: String): String =
  Hashing
    .hashPureStream(HashAlgorithm.SHA1, Stream.emits(s.getBytes("UTF-8")))
    .bytes
    .toArray
    .map("%02x".format(_))
    .mkString

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
)(using HashCache): IO[List[ArtifactEntry]] = {
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
)(using HashCache): IO[List[ArtifactEntry]] =
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
  *
  * Dependencies without a `<version>` element (version inherited from the
  * parent POM's `<dependencyManagement>`) are returned with the empty string as
  * version — the caller should resolve those via
  * `collectAccumulatedDependencyManagement`.
  */
def parseDeclaredDeps(pomContent: String): List[(String, String, String)] = {
  val root = loadPom(pomContent)
  (root \ "dependencies" \ "dependency").toList.flatMap { dep =>
    for {
      g <- childText(dep, "groupId")
      a <- childText(dep, "artifactId")
    } yield (g, a, childText(dep, "version").getOrElse(""))
  }
}

def extractDeclaredDeps(pomPath: Path): IO[List[(String, String, String)]] =
  readFile(pomPath).map(parseDeclaredDeps)

/** Extract (groupId, artifactId) → version entries from a POM's own
  * `<dependencyManagement>`, excluding scope=import BOMs (those are handled via
  * `parseImportedBoms`).
  */
def parseDependencyManagement(
    pomContent: String,
    properties: Map[String, String] = Map.empty
): Map[(String, String), String] = {
  val root = loadPom(pomContent)
  val entries = root \ "dependencyManagement" \ "dependencies" \ "dependency"
  entries.toList.flatMap { dep =>
    val isImport = childText(dep, "scope").contains("import")
    if (isImport) Nil
    else
      parseGAV(dep).toList.flatMap { (g, a, v) =>
        val resolved = substituteProperties(v, properties)
        Option.when(!resolved.contains("$"))(((g, a), resolved))
      }
  }.toMap
}

/** Walk a POM's parent chain and merge `<dependencyManagement>` entries.
  * Descendants override ancestors. Scope=import BOMs are NOT expanded here —
  * use them as a starting point only.
  */
def collectAccumulatedDependencyManagement(
    pomPath: Path,
    repoBase: String
): IO[Map[(String, String), String]] = {
  def loop(
      current: Path,
      acc: Map[(String, String), String]
  ): IO[Map[(String, String), String]] =
    readFile(current).flatMap { content =>
      val props = parseProperties(content)
      val own = parseDependencyManagement(content, props)
      extractParent(current).flatMap {
        case Some((g, a, v)) if repoBase.nonEmpty =>
          val groupPath = g.replace('.', '/')
          val parentUrl = s"$repoBase$groupPath/$a/$v/$a-$v.pom"
          val parentPath = cachePathForUrl(parentUrl)
          Files[IO].exists(parentPath).flatMap {
            case true =>
              loop(parentPath, acc).map(parentAcc => parentAcc ++ own)
            case false => IO.pure(acc ++ own)
          }
        case _ => IO.pure(acc ++ own)
      }
    }
  loop(pomPath, Map.empty)
}

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
)(using HashCache): IO[List[ArtifactEntry]] =
  // For each resolved POM, gather (g,a,v) declared deps plus a
  // dependencyManagement map (POM + ancestors) to fill in missing versions.
  resolvedPomPaths
    .flatTraverse { pomPath =>
      for {
        content <- readFile(pomPath)
        repoBase <- repoBaseForPom(pomPath, urlForCachePath(pomPath))
        depMgmt <- collectAccumulatedDependencyManagement(pomPath, repoBase)
        declared = parseDeclaredDeps(content)
        // Resolve missing versions from depMgmt.
        withVersion = declared.flatMap { case (g, a, v) =>
          val resolved = if (v.isEmpty) depMgmt.get((g, a)) else Some(v)
          resolved.map(rv => (g, a, rv))
        }
      } yield withVersion
    }
    .flatMap { allDeclared =>
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
)(using HashCache): IO[List[ArtifactEntry]] =
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
)(using HashCache): IO[List[ArtifactEntry]] = {
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
case class LockSbtOptions()
case class InitOptions(
    ref: Option[String] = None
)

// --- Lock command ---

val hashPrinter: Printer = Printer.noSpaces.copy(sortKeys = true)
val lockfilePrinter: Printer =
  Printer.spaces2.copy(sortKeys = true, colonLeft = "", dropNullValues = true)

/** Compute lockfile content without writing it. Always recomputes from scratch.
  */
def computeLock(inputs: List[String])(using HashCache): IO[String] = {
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
    firstMainScope = firstExport.scopes
      .getOrElse("main", ExportScope(Nil, Nil, Nil))
    sources = firstMainScope.sources.map(stripCwd(cwd))
    resourceDirs = firstMainScope.resourceDirs.map(stripCwd(cwd))

    targetLocks <- targets.traverse { target =>
      val key = targetKey(target, targets)
      computeTargetLock(scalaCli, inputArgs, cwd, target, key).map(key -> _)
    }

    lockFile = LockFile(
      version = 9,
      sources = sources,
      resourceDirs = resourceDirs,
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
)(using HashCache): IO[TargetLock] = {
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
)(using HashCache): IO[TargetLock] = {
  val scalaVersion = export_.scalaVersion
  val scalaMajor = scalaVersion.takeWhile(_ != '.')
  val mainScope = export_.scopes.getOrElse("main", ExportScope(Nil, Nil, Nil))
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
              resourceDirs = tScope.resourceDirs.map(stripCwd(cwd)),
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

/** Load the persistent hash cache, run `body` with it in scope, and save the
  * cache on the way out (even on failure, so partial progress isn't lost).
  */
def withHashCache[A](body: HashCache ?=> IO[A]): IO[A] =
  for {
    location <- HashCache.defaultLocation
    cache <- HashCache.load(location)
    result <- body(using cache).guarantee {
      cache.currentStats.flatMap { s =>
        info(
          s"Hash cache: ${C.bold}${s.hits}${C.reset} hits, ${C.bold}${s.misses}${C.reset} misses ${C.dim}(${cache.location})${C.reset}"
        )
      } *> cache.save
    }
  } yield result

def lock(inputs: List[String]): IO[ExitCode] = withHashCache {
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
}

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
): IO[ExitCode] = withHashCache {
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
        // For cross projects, callPackage returns an attrset { jvm = ...; native = ...; }
        // which becomes the value of `packages.${system}` directly. For single-target,
        // wrap it as { default = ...; } so `nix build` works without a target name.
        val packagesExpr =
          if (isCross) "pkgs.callPackage ./derivation.nix { }"
          else "{ default = pkgs.callPackage ./derivation.nix { }; }"
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
              |        in $packagesExpr
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

// --- sbt lock command (POC) ---
//
// Locks an sbt-managed project by injecting a one-off task that emits a JSON
// manifest of (scalaVersion, mainClass, sources, declared libraryDependencies),
// then reusing the existing Coursier resolution + hashing pipeline to produce
// a standard v8 lockfile with a single `jvm` target. The build path in lib.nix
// is unchanged — scala-cli compiles the sources inside the sandbox just like
// it would for a scala-cli-native project.

private val SbtManifestSentinelBegin = "##SCN_MANIFEST_BEGIN##"
private val SbtManifestSentinelEnd = "##SCN_MANIFEST_END##"

// Injected sbt task. Avoid triple-quoted strings here so it can live inside
// a Scala 3 raw-triple-quoted string literal on the host side.
//
// Emits a JSON manifest with:
//   - scalaVersion, scalaBootVersion (sbt's own Scala), sbtVersion
//   - mainClass (if uniquely determined by Compile / mainClass)
//   - sources (relative paths under baseDirectory)
//   - dependencies (declared libraryDependencies, with cross-suffix applied)
//
// We don't ask sbt for scalaInstance / compiler-bridge paths — the CLI
// re-resolves both from canonical coords via Coursier, which lets the
// `collectEntries` walk capture parent POMs / BOM imports / evicted
// versions needed for offline resolution inside the Nix sandbox.
private val SbtManifestSbt: String =
  """TaskKey[Unit]("scnManifest") := {
    |  val sv = scalaVersion.value
    |  val sbv = scalaBinaryVersion.value
    |  val sbtVer = sbtVersion.value
    |  val scalaBootVersion = scala.util.Properties.versionNumberString
    |  val mc = (Compile / mainClass).value
    |  val declared = libraryDependencies.value
    |  val deps: Seq[(String, String, String)] = declared.flatMap { m =>
    |    val isRuntime = m.configurations.forall(c => c == "compile" || c == "runtime" || c == "default")
    |    if (!isRuntime) None
    |    else {
    |      val isScalaStd =
    |        m.organization == "org.scala-lang" &&
    |          (m.name == "scala-library" || m.name == "scala3-library" ||
    |           m.name == "scala-compiler" || m.name == "scala3-compiler" ||
    |           m.name == "scala-reflect")
    |      if (isScalaStd) None
    |      else {
    |        val name = m.crossVersion match {
    |          case _: CrossVersion.Binary => m.name + "_" + sbv
    |          case _: CrossVersion.Full   => m.name + "_" + sv
    |          case _                      => m.name
    |        }
    |        Some((m.organization, name, m.revision))
    |      }
    |    }
    |  }.distinct
    |  val mainSources = (Compile / sources).value
    |  val base = baseDirectory.value.toPath
    |  val rel = mainSources.map(f => base.relativize(f.toPath).toString).sorted
    |  def q(s: String): String =
    |    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    |  def arr(items: Seq[String]): String = items.mkString("[", ",", "]")
    |  val depsJson = deps.map { case (g, a, v) =>
    |    "{\"groupId\":" + q(g) + ",\"artifactId\":" + q(a) + ",\"version\":" + q(v) + "}"
    |  }
    |  val srcJson = rel.map(q)
    |  val mainJson = mc match { case Some(m) => q(m); case None => "null" }
    |  val payload =
    |    "{\"scalaVersion\":" + q(sv) +
    |      ",\"scalaBootVersion\":" + q(scalaBootVersion) +
    |      ",\"sbtVersion\":" + q(sbtVer) +
    |      ",\"mainClass\":" + mainJson +
    |      ",\"sources\":" + arr(srcJson) +
    |      ",\"dependencies\":" + arr(depsJson) + "}"
    |  println("##SCN_MANIFEST_BEGIN##")
    |  println(payload)
    |  println("##SCN_MANIFEST_END##")
    |}
    |""".stripMargin

case class SbtManifestDep(groupId: String, artifactId: String, version: String)
    derives Decoder
case class SbtManifest(
    scalaVersion: String,
    scalaBootVersion: String,
    sbtVersion: String,
    mainClass: Option[String],
    sources: List[String],
    dependencies: List[SbtManifestDep]
) derives Decoder

private def runSbtManifest(projectDir: Path): IO[SbtManifest] = {
  val manifestSbt = projectDir / "scn-manifest.sbt"
  val writeManifest = writeFile(manifestSbt, SbtManifestSbt)
  val cleanupManifest = Files[IO].deleteIfExists(manifestSbt).void
  val runSbt =
    ProcessBuilder("sbt", List("-no-colors", "-batch", "scnManifest"))
      .withWorkingDirectory(projectDir)
      .spawn[IO]
      .use { proc =>
        val stdout = proc.stdout.through(fs2.text.utf8.decode).compile.string
        val stderr = proc.stderr.through(fs2.io.stderr[IO]).compile.drain
        (stdout, stderr).parTupled.flatMap { case (out, _) =>
          proc.exitValue.flatMap { code =>
            IO.raiseError(
              new RuntimeException(s"sbt exited with code $code")
            ).whenA(code != 0)
              .as(out)
          }
        }
      }
  (writeManifest *> runSbt).guarantee(cleanupManifest).flatMap { out =>
    val beginIdx = out.indexOf(SbtManifestSentinelBegin)
    val endIdx = out.indexOf(SbtManifestSentinelEnd)
    if (beginIdx < 0 || endIdx < 0)
      IO.raiseError(
        new RuntimeException(
          s"sbt output did not contain manifest sentinels. Output:\n$out"
        )
      )
    else {
      val payload =
        out.substring(beginIdx + SbtManifestSentinelBegin.length, endIdx).trim
      IO.fromEither(
        parseJson(payload)
          .flatMap(_.as[SbtManifest])
          .leftMap(e =>
            new RuntimeException(s"Failed to parse sbt manifest: $e\n$payload")
          )
      )
    }
  }
}

/** Coordinates Coursier should resolve to get the scalaInstance for the given
  * Scala version. Scala 3's compiler is what sbt uses for compilation; scaladoc
  * is bundled with it transitively in the Zinc scalaInstance.
  */
private def scalaInstanceDeps(scalaVersion: String): List[Dependency] = {
  val major = scalaVersion.takeWhile(_ != '.')
  major match {
    case "3" =>
      List(
        Dependency.of("org.scala-lang", "scala3-compiler_3", scalaVersion),
        Dependency.of("org.scala-lang", "scaladoc_3", scalaVersion)
      )
    case "2" =>
      List(
        Dependency.of("org.scala-lang", "scala-compiler", scalaVersion)
      )
    case other =>
      sys.error(s"Unsupported Scala major version: $other")
  }
}

/** Compiler bridge coordinates. For Scala 3 it's a published binary; for Scala
  * 2 it's the source jar that sbt compiles on first use.
  */
private def compilerBridgeDep(
    scalaVersion: String,
    sbtVersion: String
): Dependency = {
  val major = scalaVersion.takeWhile(_ != '.')
  major match {
    case "3" =>
      Dependency.of("org.scala-lang", "scala3-sbt-bridge", scalaVersion)
    case "2" =>
      val binaryVersion = scalaVersion.split('.').take(2).mkString(".")
      Dependency.of(
        "org.scala-sbt",
        s"compiler-bridge_$binaryVersion",
        sbtVersion
      )
    case other =>
      sys.error(s"Unsupported Scala major version: $other")
  }
}

def computeSbtLock(projectDir: Path, manifest: SbtManifest)(using
    HashCache
): IO[String] =
  for {
    _ <- info(s"Scala version: ${C.bold}${manifest.scalaVersion}${C.reset}")
    _ <- info(s"sbt version: ${C.bold}${manifest.sbtVersion}${C.reset}")
    _ <- info(
      s"sbt's Scala (boot): ${C.bold}${manifest.scalaBootVersion}${C.reset}"
    )
    _ <- info(
      s"Main class: ${C.bold}${manifest.mainClass.getOrElse("<unset>")}${C.reset}"
    )
    _ <- info(
      s"Found ${C.bold}${manifest.dependencies.size}${C.reset} declared dependencies"
    )

    // --- User runtime dependencies (transitive) ---
    userDeps = manifest.dependencies.map(d =>
      Dependency.of(d.groupId, d.artifactId, d.version)
    )
    _ <- step("Fetching user runtime dependencies...")
    userArtifacts <- fetchArtifacts(userDeps*)
    _ <- info(
      s"User deps: ${C.bold}${userArtifacts.size}${C.reset} artifacts (transitive)"
    )

    // --- sbt launcher JAR (org.scala-sbt:sbt-launch:<sbtVersion>) ---
    // We exec this jar directly with `java -jar`, bypassing pkgs.sbt's
    // launcher (which is bound to a different Scala bootstrap version and
    // fails to load older sbt projects offline).
    _ <- step(s"Fetching sbt launcher (sbt ${manifest.sbtVersion})...")
    launcherDep = Dependency.of(
      "org.scala-sbt",
      "sbt-launch",
      manifest.sbtVersion
    )
    launcherArtifacts <- fetchArtifacts(launcherDep)
    launcherJarArtifact = launcherArtifacts
      .find(_._1.getUrl.endsWith("sbt-launch-" + manifest.sbtVersion + ".jar"))
      .getOrElse(
        sys.error(s"sbt-launch:${manifest.sbtVersion} did not resolve a jar")
      )

    // --- sbt boot artifacts (org.scala-sbt:sbt:<sbtVersion>) ---
    _ <- step(s"Fetching sbt boot artifacts (sbt ${manifest.sbtVersion})...")
    bootDep = Dependency.of("org.scala-sbt", "sbt", manifest.sbtVersion)
    bootArtifacts <- fetchArtifacts(bootDep)
    _ <- info(
      s"sbt boot: ${C.bold}${bootArtifacts.size}${C.reset} artifacts (resolved)"
    )

    // --- scalaInstance: resolved via Coursier from canonical coords ---
    // We deliberately re-resolve (rather than trust sbt's path list) so the
    // full transitive set goes through `collectEntries` — that walks parent
    // POMs + BOM imports + evicted-version POMs, all of which sbt's
    // lm-coursier may need at update time inside the sandbox.
    _ <- step("Fetching scalaInstance...")
    scalaInstanceCoursierDeps = scalaInstanceDeps(manifest.scalaVersion)
    scalaInstanceArtifacts <- fetchArtifacts(scalaInstanceCoursierDeps*)
    _ <- info(
      s"scalaInstance: ${C.bold}${scalaInstanceArtifacts.size}${C.reset} artifacts (transitive)"
    )

    // --- Compiler bridge ---
    _ <- step("Fetching compiler bridge...")
    bridgeArtifacts <- fetchArtifacts(
      compilerBridgeDep(manifest.scalaVersion, manifest.sbtVersion)
    )

    // --- Hash launcher ---
    _ <- step("Hashing sbt launcher...")
    launcherEntry <- {
      val (artifact, file) = launcherJarArtifact
      val path = Path.fromNioPath(file.toPath)
      sha256Base64(path).map(h => ArtifactEntry(artifact.getUrl, h))
    }

    // --- Hash everything else (all via collectEntries so parent POMs +
    // BOM imports + evicted-version POMs come along for offline resolution) ---
    _ <- step("Hashing user deps...")
    userEntries <- collectEntries(userArtifacts)

    // Boot dir layout: ONLY the resolved-winner JARs (~83). Including
    // evicted versions makes sbt-launch fail with #4955.
    _ <- step("Hashing sbt boot jars (flat layout)...")
    bootJarEntries <- bootArtifacts.traverse { case (artifact, file) =>
      val path = Path.fromNioPath(file.toPath)
      sha256Base64(path).map(h => ArtifactEntry(artifact.getUrl, h))
    }

    // Coursier cache contents: full transitive set including parent POMs +
    // BOMs + evicted versions, so sbt's own offline Coursier resolution
    // succeeds at update time.
    _ <- step("Hashing sbt boot Coursier cache (full transitive)...")
    bootCacheEntries <- collectEntries(bootArtifacts)

    _ <- step("Hashing scalaInstance (full transitive)...")
    scalaInstanceEntries <- collectEntries(scalaInstanceArtifacts)

    _ <- step("Hashing compiler bridge (full transitive)...")
    bridgeEntries <- collectEntries(bridgeArtifacts)

    sbtLock = SbtLock(
      sbtVersion = manifest.sbtVersion,
      scalaBootVersion = manifest.scalaBootVersion,
      mainClass = manifest.mainClass,
      launcherJar = launcherEntry,
      bootJars = bootJarEntries,
      bootCoursierCache = bootCacheEntries,
      scalaInstance = scalaInstanceEntries.distinctBy(_.url),
      compilerBridge = bridgeEntries.distinctBy(_.url)
    )

    // The `targets` block describes runtime classpath the wrapper uses.
    // We reuse it with `compiler = Nil` (sbt-in-sandbox handles compilation;
    // we don't need a separate compiler resolution here) and put user deps
    // in `libraryDependencies`.
    target = TargetLock(
      scalaVersion = manifest.scalaVersion,
      platform = "JVM",
      exportHash =
        sha1Hex(s"sbt|${manifest.sbtVersion}|${manifest.scalaVersion}"),
      compiler = Nil,
      libraryDependencies = userEntries,
      native = None,
      test = None
    )

    lockFile = LockFile(
      version = 9,
      sources = manifest.sources,
      resourceDirs = Nil,
      targets = Map("jvm" -> target),
      sbt = Some(sbtLock)
    )
  } yield lockfilePrinter.print(lockFile.asJson) + "\n"

def lockSbt: IO[ExitCode] = withHashCache {
  for {
    cwd <- Files[IO].currentWorkingDirectory
    _ <- step("Running sbt manifest task...")
    manifest <- runSbtManifest(cwd)
    content <- computeSbtLock(cwd, manifest)
    lockfilePath = cwd / "scala.lock.json"
    existing <- Files[IO]
      .exists(lockfilePath)
      .ifM(readFile(lockfilePath), IO.pure(""))
    _ <-
      if (existing == content) info("Lock is up to date.")
      else
        step("Writing lockfile...") *>
          writeFile(lockfilePath, content) *>
          success(s"Wrote ${C.bold}scala.lock.json${C.reset}")
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
  override def commands: Seq[Command[?]] =
    Seq(InitCommand, LockCommand, LockSbtCommand)
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

  private object LockSbtCommand extends Command[LockSbtOptions] {
    override def name: String = "lock-sbt"
    override def run(options: LockSbtOptions, args: RemainingArgs): Unit =
      runIO(lockSbt)
  }
}

//> using scala 3.8.3
//> using dep io.get-coursier:interface:1.0.29-M3
//> using dep com.lihaoyi::os-lib:0.11.4
//> using dep com.lihaoyi::upickle:4.1.0

import coursierapi.*
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import scala.annotation.tailrec
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
  libraryDependencies: List[ArtifactEntry],
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

def info(msg: String): Unit = System.err.println(s"${C.blue}i${C.reset} $msg")
def success(msg: String): Unit = System.err.println(s"${C.green}done${C.reset} $msg")
def step(msg: String): Unit = System.err.println(s"${C.bold}> $msg${C.reset}")
def error(msg: String): Unit = System.err.println(s"${C.red}error${C.reset} $msg")

// --- Hashing ---

def sha256Base64(file: File): String = {
  val digest = MessageDigest.getInstance("SHA-256")
  val bytes = Files.readAllBytes(file.toPath)
  Base64.getEncoder.encodeToString(digest.digest(bytes))
}

def sha1Hex(s: String): String = {
  val digest = MessageDigest.getInstance("SHA-1")
  digest.digest(s.getBytes("UTF-8")).map("%02x".format(_)).mkString
}

// --- Coursier helpers ---

def fetchArtifacts(deps: Dependency*): List[(Artifact, File)] = {
  val result = Fetch.create()
    .addDependencies(deps*)
    .fetchResult()
  result.getArtifacts().asScala.toList.map(e => (e.getKey, e.getValue))
}

val cacheDir: File = Cache.create().getLocation()

/** Given a URL, return the cache file path (Coursier layout: https/host/path). */
def cacheFileForUrl(url: String): File = {
  val relative = url.replaceFirst("://", "/")
  File(cacheDir, relative)
}

/** Try to find the POM file for a JAR in the Coursier cache. */
def findPomForJar(jarUrl: String): Option[File] = {
  // Direct replacement: foo.jar -> foo.pom
  val directPom = cacheFileForUrl(jarUrl.stripSuffix(".jar") + ".pom")
  if (directPom.exists()) {
    Some(directPom)
  } else {
    // Classifier JAR: .../artifactId/version/artifactId-version-classifier.jar
    // POM is: .../artifactId/version/artifactId-version.pom
    val jarFile = cacheFileForUrl(jarUrl)
    val dir = jarFile.getParentFile
    val version = dir.getName
    val artifactId = dir.getParentFile.getName
    val basePom = File(dir, s"$artifactId-$version.pom")
    Option.when(basePom.exists())(basePom)
  }
}

/** Reconstruct URL from a file in the Coursier cache. */
def urlForCacheFile(file: File): String = {
  val relative = file.getAbsolutePath.stripPrefix(cacheDir.getAbsolutePath + "/")
  val proto = relative.takeWhile(_ != '/')
  val rest = relative.drop(proto.length + 1)
  s"$proto://$rest"
}

/** Extract parent POM coordinates from a POM file using regex. */
def extractParent(pomFile: File): Option[(String, String, String)] = {
  val content = new String(Files.readAllBytes(pomFile.toPath), "UTF-8")
  val parentPattern = "(?s)<parent>\\s*(.*?)</parent>".r
  parentPattern.findFirstMatchIn(content).flatMap { m =>
    val body = m.group(1)
    for {
      g <- "<groupId>(.*?)</groupId>".r.findFirstMatchIn(body).map(_.group(1))
      a <- "<artifactId>(.*?)</artifactId>".r.findFirstMatchIn(body).map(_.group(1))
      v <- "<version>(.*?)</version>".r.findFirstMatchIn(body).map(_.group(1))
    } yield (g, a, v)
  }
}

/** Walk the parent POM chain, collecting entries. */
def collectParentPoms(pomFile: File): List[ArtifactEntry] = {
  @tailrec
  def loop(current: File, acc: List[ArtifactEntry]): List[ArtifactEntry] = {
    extractParent(current) match {
      case Some((groupId, artifactId, version)) =>
        val groupPath = groupId.replace('.', '/')
        val parentPomRelative = s"https/repo1.maven.org/maven2/$groupPath/$artifactId/$version/$artifactId-$version.pom"
        val parentPomFile = File(cacheDir, parentPomRelative)
        if (parentPomFile.exists()) {
          val url = s"https://repo1.maven.org/maven2/$groupPath/$artifactId/$version/$artifactId-$version.pom"
          val entry = ArtifactEntry(url, sha256Base64(parentPomFile))
          loop(parentPomFile, entry :: acc)
        } else {
          acc.reverse
        }
      case None =>
        acc.reverse
    }
  }
  loop(pomFile, Nil)
}

/** Collect entries (JAR + POM + parent POMs) for a list of fetched artifacts. */
def collectEntries(artifacts: List[(Artifact, File)]): List[ArtifactEntry] = {
  artifacts.flatMap { (artifact, file) =>
    val url = artifact.getUrl
    val jarEntry = ArtifactEntry(url, sha256Base64(file))

    val pomEntries = findPomForJar(url).toList.flatMap { pomFile =>
      val pomUrl = {
        val direct = url.replaceFirst("\\.jar$", ".pom")
        if (cacheFileForUrl(direct).getAbsolutePath == pomFile.getAbsolutePath) direct
        else urlForCacheFile(pomFile)
      }
      ArtifactEntry(pomUrl, sha256Base64(pomFile)) :: collectParentPoms(pomFile)
    }

    jarEntry :: pomEntries
  }.distinctBy(_.url)
}

// --- scala-cli helpers ---

val scalaCli: String = {
  if (os.proc("which", "real-scala-cli").call(check = false).exitCode == 0) "real-scala-cli"
  else "scala-cli"
}

// --- Lock command ---

def lock(inputs: List[String]): Unit = {
  val inputArgs = if (inputs.isEmpty) List(".") else inputs
  val lockfilePath = os.pwd / "scala.lock.json"

  step("Exporting project info...")
  val exportJson = os.proc(scalaCli, "--power", "export", "--json", inputArgs)
    .call(stderr = os.Pipe)
    .out.text()
  val export_ = ujson.read(exportJson)

  // Compute export hash for staleness detection
  val canonicalExport = ujson.write(export_, indent = 2, sortKeys = true)
  val exportHash = sha1Hex(canonicalExport + "\n")

  // Check staleness: if lockfile exists and hash matches, skip
  if (os.exists(lockfilePath)) {
    val existing = ujson.read(os.read(lockfilePath))
    val existingHash = existing.obj.get("exportHash").map(_.str).getOrElse("")
    if (existingHash == exportHash) {
      info("Lock is up to date.")
      return
    }
  }

  val scalaVersion = export_("scalaVersion").str
  info(s"Scala version: ${C.bold}$scalaVersion${C.reset}")

  val pwd = os.pwd.toString
  val sources = export_("scopes")("main")("sources").arr.map { s =>
    s.str
      .stripPrefix(pwd + "/")
      .stripPrefix("/private" + pwd + "/")
  }.toList
  info(s"Sources: ${C.bold}${sources.size}${C.reset} files")

  val deps = export_("scopes")("main")("dependencies").arr.map { d =>
    s"${d("groupId").str}:${d("artifactId")("fullName").str}:${d("version").str}"
  }.toList
  info(s"Found ${C.bold}${deps.size}${C.reset} dependencies")

  step("Discovering main class...")
  val mainClass = os.proc(scalaCli, "--power", "run", "--main-class-list", inputArgs)
    .call(stderr = os.Pipe)
    .out.text().linesIterator.next()
  info(s"Main class: ${C.bold}$mainClass${C.reset}")

  val scalaMajor = scalaVersion.takeWhile(_ != '.')

  val (compilerArtifact, libraryArtifact) = scalaMajor match {
    case "3" =>
      (
        Dependency.of("org.scala-lang", "scala3-compiler_3", scalaVersion),
        Dependency.of("org.scala-lang", "scala3-library_3", scalaVersion),
      )
    case "2" =>
      (
        Dependency.of("org.scala-lang", "scala-compiler", scalaVersion),
        Dependency.of("org.scala-lang", "scala-library", scalaVersion),
      )
    case _ =>
      error(s"Unsupported Scala major version: $scalaMajor (from $scalaVersion)")
      sys.exit(1)
  }

  step("Fetching compiler dependencies...")
  val compilerArtifacts = fetchArtifacts(compilerArtifact)
  info(s"Compiler: ${C.bold}${compilerArtifacts.size}${C.reset} artifacts")

  step("Fetching library dependencies...")
  val libDeps = libraryArtifact +: deps.map { dep =>
    val parts = dep.split(":")
    Dependency.of(parts(0), parts(1), parts(2))
  }
  val libArtifacts = fetchArtifacts(libDeps*)
  info(s"Libraries: ${C.bold}${libArtifacts.size}${C.reset} artifacts (transitive)")

  step("Hashing artifacts...")
  val compilerEntries = collectEntries(compilerArtifacts)
  val libEntries = collectEntries(libArtifacts)

  step("Writing lockfile...")
  val lockFile = LockFile(
    version = 3,
    scalaVersion = scalaVersion,
    mainClass = mainClass,
    exportHash = exportHash,
    sources = sources,
    compiler = compilerEntries,
    libraryDependencies = libEntries,
  )
  os.write.over(lockfilePath, ujson.write(writeJs(lockFile), indent = 2) + "\n")
  success(s"Wrote ${C.bold}scala.lock.json${C.reset}")
}

// --- Init command ---

def init(args: List[String]): Unit = {
  val scalaFiles = os.list(os.pwd).filter(_.ext == "scala")
  if (scalaFiles.isEmpty) {
    error("No .scala files found in current directory.")
    sys.exit(1)
  }

  val pname = os.pwd.last
  var generatedFiles = List.empty[String]

  System.err.println()
  System.err.println(s"${C.bold}Initializing scala-cli-nix project: ${C.green}$pname${C.reset}")
  System.err.println()

  // Generate derivation.nix
  if (os.exists(os.pwd / "derivation.nix")) {
    System.err.println(s"${C.yellow}warn${C.reset} derivation.nix already exists, skipping.")
  } else {
    step("Writing derivation.nix...")
    generatedFiles = "derivation.nix" :: generatedFiles
    os.write(
      os.pwd / "derivation.nix",
      s"""{ scala-cli-nix }:
         |
         |scala-cli-nix.buildScalaCliApp {
         |  pname = "$pname";
         |  version = "0.1.0";
         |  src = ./.;
         |  lockFile = ./scala.lock.json;
         |}
         |""".stripMargin,
    )
    success(s"Wrote ${C.bold}derivation.nix${C.reset}")
  }

  // Generate or advise on flake.nix
  if (os.exists(os.pwd / "flake.nix")) {
    System.err.println()
    System.err.println(s"${C.yellow}warn${C.reset} flake.nix already exists. Add the following to your flake:")
    System.err.println()
    System.err.println(s"  ${C.bold}1.${C.reset} Add the input:")
    System.err.println()
    System.err.println(s"""    ${C.dim}scala-cli-nix.url = "github:scala-nix/scala-cli-nix";${C.reset}""")
    System.err.println(s"""    ${C.dim}scala-cli-nix.inputs.nixpkgs.follows = "nixpkgs";${C.reset}""")
    System.err.println()
    System.err.println(s"  ${C.bold}2.${C.reset} Apply the overlay to nixpkgs:")
    System.err.println()
    System.err.println(s"    ${C.dim}pkgs = import nixpkgs {${C.reset}")
    System.err.println(s"    ${C.dim}  inherit system;${C.reset}")
    System.err.println(s"    ${C.dim}  overlays = [ scala-cli-nix.overlays.default ];${C.reset}")
    System.err.println(s"    ${C.dim}};${C.reset}")
    System.err.println()
    System.err.println(s"  ${C.bold}3.${C.reset} Add the package:")
    System.err.println()
    System.err.println(s"    ${C.dim}packages.default = pkgs.callPackage ./derivation.nix { };${C.reset}")
    System.err.println()
    System.err.println(s"  ${C.bold}4.${C.reset} Add to your devShell (uses wrapped scala-cli with auto-locking):")
    System.err.println()
    System.err.println(s"    ${C.dim}pkgs.scala-cli${C.reset}")
    System.err.println(s"    ${C.dim}pkgs.scala-cli-nix-cli${C.reset}")
    System.err.println()
  } else {
    step("Writing flake.nix...")
    generatedFiles = "flake.nix" :: generatedFiles
    os.write(
      os.pwd / "flake.nix",
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
         |""".stripMargin,
    )
    success(s"Wrote ${C.bold}flake.nix${C.reset}")
  }

  System.err.println()
  lock(Nil)
  System.err.println()

  val files = generatedFiles.reverse :+ "scala.lock.json"

  // Stage generated files if in a git repo
  if (os.proc("git", "rev-parse", "--is-inside-work-tree").call(check = false).exitCode == 0) {
    step("Staging generated files...")
    os.proc("git", "add", files).call()
    success(s"Staged ${C.bold}${files.mkString(" ")}${C.reset}")
  }

  System.err.println(s"${C.bold}Done!${C.reset} Run ${C.green}nix build${C.reset} to build your project.")
}

// --- Main ---

@main def run(args: String*): Unit = {
  args.toList match {
    case "init" :: rest => init(rest)
    case "lock" :: rest => lock(rest)
    case _ =>
      System.err.println(s"${C.bold}scala-cli-nix${C.reset} — Nix packaging for scala-cli apps")
      System.err.println()
      System.err.println(s"  ${C.bold}init${C.reset}    Scaffold flake.nix, derivation.nix, and generate lockfile")
      System.err.println(s"  ${C.bold}lock${C.reset}    Regenerate the lockfile from scala-cli sources in .")
      sys.exit(1)
  }
}

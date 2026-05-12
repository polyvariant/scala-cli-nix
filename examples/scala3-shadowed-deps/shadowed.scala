//> using scala 3.8.3
//> using dep org.scala-lang.modules::scala-xml::2.4.0
//> using dep co.fs2::fs2-io::3.13.0

import scala.xml.XML

// Regression guard. `collectDeclaredPoms` walks every resolved POM and
// fetches declared deps individually so scala-cli's offline resolver can see
// them. Before the fix it also dropped each declared dep's JAR into
// `libraryDependencies`, even when that coordinate was already covered by the
// main resolution winner — at runtime both JARs landed on the classpath and
// the older one (sorted first by URL) shadowed the winner's classes.
//
// fs2-io's transitive POM set declares an older `scala-xml_3` (currently
// 2.1.0). With our explicit `scala-xml:2.4.0`, Coursier picks 2.4.0 for the
// classpath — but the buggy `collectDeclaredPoms` would also pull in 2.1.0's
// JAR. We compile against 2.4.0 (`Node.child` returns `immutable.Seq`); the
// older class returns `collection.Seq`, so the runtime linkage trips a
// `NoSuchMethodError`.
//
// We invoke `.child` through `Node` (NodeSeq#headOption returns `Option[Node]`,
// not `Option[Elem]`) so the bytecode references `Node.child`. `Elem.child`
// has bridge overloads that mask the bug; `Node.child` doesn't.
object Main {
  def main(args: Array[String]): Unit = {
    val root = XML.loadString("<a><b><c/><c/></b></a>")
    val firstChild = (root \ "b").headOption.get
    val grandchildren = firstChild.child
    val seq: scala.collection.immutable.Seq[scala.xml.Node] = grandchildren
    println(s"hello from shadowed-deps! grandchildren=${seq.size}")
  }
}

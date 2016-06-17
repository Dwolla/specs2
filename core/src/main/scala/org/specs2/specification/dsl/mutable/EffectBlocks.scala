package org.specs2
package specification
package dsl
package mutable

import org.specs2.control.{Throwablex, Throwables}
import org.specs2.execute.ExecuteException
import Throwablex._
import scala.util.control.NonFatal
import scalaz.TreeLoc
import scalaz.Tree._
import scalaz.std.anyVal._

/**
 * This class tracks "nested" effects.
 *
 * It is used to create nested blocks in mutable specifications and make sure
 * that we can control which blocks to evaluate based on a target "path" in the tree
 * of blocks
 */
private[specs2]
case class EffectBlocks(var mode: EffectBlocksMode = Record) {
  /**
   * This tree loc contains the "path" of Examples and Actions when they are created in a block creating another fragment
   * For example:
   *
   * "this" should {
   *   "create an example" >> ok
   * }
   *
   * The execution of the block above creates a Text fragment followed by an example. The blocksTree tree tracks the order of creation
   * so that we can attach a "block creation path" to the Example showing which fragment creation precedes him. This knowledge is used to
   * run a specification in the "isolation" mode were the changes in local variables belonging to blocks are not seen by
   * other examples
   */
  private var blocksTree: TreeLoc[Int] = leaf(0).loc

  /** list of actions to create fragments */
  private val effects: scala.collection.mutable.ListBuffer[() => Unit] =
    new scala.collection.mutable.ListBuffer()

  /** stop evaluating effects */
  private var stop = false

  /** get the node number on the current branch and add 1 */
  private def nextNodeNumber = blocksTree.lastChild.map(_.getLabel + 1).getOrElse(0)

  type Effect = () => Unit

  /**
   * Recording mode
   * we execute effects as they arrive just to be able to compute effect paths for each block
   */
  def record = {
    mode = Record
    this
  }


  /**
   * Replay mode, we only execute effects but which are on a given path
   */
  def replay(targetPath: EffectPath) = {
    mode = Replay
    blocksTree = leaf(0).loc
    def runPath(path: Seq[Int]): Unit = {
      path.toList match {
        case n :: remainingPath =>
          val effect = effects.drop(n).headOption
          effects.clear
          effect.foreach(_())
          runPath(remainingPath)

        case Nil => ()
      }
    }
    runPath(targetPath.path.drop(1))
    this
  }

  /** @return the current path to root */
  def effectPath = EffectPath(blocksTree.lastChild.getOrElse(blocksTree).path.reverse:_*)

  def tree = blocksTree.toTree.drawTree

  def nestBlock(block: =>Any) = {
    // store effects
    effect(tryBlock(block))

    // in record mode run them right away
    if (mode == Record) {
      blocksTree = blocksTree.insertDownLast(nextNodeNumber)
      tryBlock(block)
      blocksTree = blocksTree.getParent
    }
    this
  }

  def addBlock = {
    if (mode == Record)
      blocksTree = blocksTree.addChild(nextNodeNumber)
    this
  }

  private def tryBlock(block: =>Any) =
    try { block; () }
    catch {
      case e: ExecuteException => throw SpecificationCreationExpectationException(e)
      case NonFatal(e)         => throw SpecificationCreationException(e)
    }

  private def effect(a: =>Unit) =
    effects.append(() => a)

  implicit class TreeLocx[T](t: TreeLoc[T]) {
    def getParent = t.parent.getOrElse(t)
    def addChild(c: T) = t.insertDownLast(leaf(c)).getParent
    def insertDownLast(c: T) = t.insertDownLast(leaf(c))
  }
}

case class SpecificationCreationExpectationException(t: ExecuteException) extends Exception {
  override def getMessage: String =
    s"""|
        |An expectation was executed during the creation of the specification: ${t.getMessage}.
        |This means that you have some code which should be enclosed in an example. Instead of writing:
        |
        | "this is a block of examples" in {
        |   // test something
        |   1 must_== 2
        |   "first example" in { 1 must_== 1 }
        |   "second example" in { 1 must_== 1 }
        | }
        |
        |You should write:
        |
        | "this is a block of examples" in {
        |   "example zero" in {
        |     // test something
        |     1 must_== 2
        |   }
        |   "first example" in { 1 must_== 1 }
        |   "second example" in { 1 must_== 1 }
        | }
        |
        |EXCEPTION
        |
        |${Throwables.renderWithStack(t)}
        |
        |CAUSED BY
        |
        |${t.chainedExceptions.map(Throwables.renderWithStack).mkString("\n")}
        |
        |""".stripMargin
}


case class SpecificationCreationException(t: Throwable) extends Exception {
  override def getMessage: String =
    s"""|
        |An exception was raised during the creation of the specification: ${t.getMessage}.
        |This means that you have some code which should be enclosed in an example. Instead of writing:
        |
        | "this is a block of examples" in {
        |   // set-up something
        |   createDatabase
        |   "first example" in { 1 must_== 1 }
        |   "second example" in { 1 must_== 1 }
        | }
        |
        |You should write:
        |
        | "this is a block of examples" in {
        |   "the setup must be ok" in {
        |     createDatabase must not(throwAn[Exception])
        |   }
        |   "first example" in { 1 must_== 1 }
        |   "second example" in { 1 must_== 1 }
        | }
        |
        | Be careful because in the specification above the expectation might be that the
        | database will be created before the "first" and "second" examples. This will NOT be the case
        | unless you mark the specification as `sequential`. You can also have a look at the `BeforeEach/BeforeAll` traits
        | to implement this kind of functionality.
        |
        |EXCEPTION
        |
        |${Throwables.renderWithStack(t)}
        |
        |CAUSED BY
        |
        |${t.chainedExceptions.map(Throwables.renderWithStack).mkString("\n")}
        |
        |""".stripMargin
}

private[specs2]
case class EffectPath(path: Vector[Int] = Vector()) {
  def startsWith(other: EffectPath) = path.take(other.path.size) == other.path
}
private[specs2]
object EffectPath {
  def apply(path: Int*): EffectPath =
    new EffectPath(Vector(path:_*))
}



private[specs2]
sealed trait EffectBlocksMode

private[specs2]
object Record extends EffectBlocksMode

private[specs2]
object Replay extends EffectBlocksMode

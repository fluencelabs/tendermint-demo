package kvstore

import kvstore.MerkleUtil._

import scala.collection.immutable.HashMap
import scala.util.Try

case class Node(children: NodeStorage, value: Option[String], merkleHash: Option[MerkleHash]) {
  def merkelize(): Node =
    if (merkleHash.isDefined)
      this
    else {
      val newChildren = children.mapValues(x => x.merkelize())
      val withNewChildren = Node(newChildren, value, None)
      Node(newChildren, value, Some(mergeMerkle(withNewChildren.merkleItems(), HEX_BASED_MERKLE_MERGE)))
    }

  private def merkleItems(): List[MerkleHash] =
    singleMerkle(value.getOrElse("")) :: children.flatMap(x => List(singleMerkle(x._1), x._2.merkleHash.get)).toList

  def getProof(key: String): List[List[MerkleHash]] = {
    if (key.isEmpty)
      List(merkleItems())
    else {
      val (next, rest) = splitPath(key)
      merkleItems() :: children(next).getProof(rest)
    }
  }

  def longValue: Option[Long] = value.flatMap(x => Try(x.toLong).toOption)

  def add(key: String, value: String): Node = {
    val rangeKeyValuePattern = "(\\d{1,8})-(\\d{1,8}):(.+)".r

    key match {
      case rangeKeyValuePattern(rangeStartStr, rangeEndStr, keyPattern) =>
        val rangeStart = rangeStartStr.toInt
        val rangeEnd = rangeEndStr.toInt
        System.out.println(s"setting range from=$rangeStart to=$rangeEnd keyPattern=$keyPattern valuePattern=$value")

        var currentNode = this
        for (index <- rangeStart until rangeEnd) {
          var key = keyPattern
          var effectiveValue = value
          for (hexPosition <- 0 to 6) {
            val target = "@" + hexPosition
            val replacement = ((index >> hexPosition * 4) & 0xf).toHexString
            key = key.replace(target, replacement)
            effectiveValue = effectiveValue.replace(target, replacement)
          }
          System.out.println(s"setting key=$key value=$effectiveValue")
          currentNode = currentNode.addValue(key, effectiveValue)
        }

        currentNode
      case _ =>
        System.out.println(s"setting key=$key value=$value")
        addValue(key, value)
    }
  }

  private def addValue(key: String, value: String): Node = {
    if (key.isEmpty)
      Node(children, Some(value), None)
    else {
      val (next, rest) = splitPath(key)
      Node(children + (next -> children.getOrElse(next, Node.emptyNode).addValue(rest, value)), this.value, None)
    }
  }

  def getNode(key: String): Option[Node] = {
    if (key.isEmpty)
      Some(this)
    else {
      val (next, rest) = splitPath(key)
      children.get(next).flatMap(_.getNode(rest))
    }
  }

  def getValue(key: String): Option[String] = getNode(key).flatMap(_.value)

  def getLongValue(key: String): Option[Long] = getNode(key).flatMap(_.longValue)

  def listChildren(key: String): Option[List[String]] = {
    if (key.isEmpty)
      Some(children.keys.toList)
    else {
      val (next, rest) = splitPath(key)
      children.get(next).flatMap(_.listChildren(rest))
    }
  }

  private def splitPath(path: String): (String, String) = {
    val (next, rest) = path.span(_ != '/')
    (next, rest.replaceFirst("/", ""))
  }
}

object Node {
  val emptyNode: Node = Node(HashMap.empty[String, Node], None, None)
}
package kvstore

import java.nio.ByteBuffer

import com.github.jtendermint.jabci.api._
import com.github.jtendermint.jabci.socket.TSocket
import com.github.jtendermint.jabci.types.{ResponseCheckTx, _}
import com.google.protobuf.ByteString

import scala.collection.mutable.ArrayBuffer

object KVStoreServerRunner extends IDeliverTx with ICheckTx with ICommit with IQuery {

  def main(args: Array[String]): Unit = {
    KVStoreServerRunner.start()
  }

  private val storage: ArrayBuffer[Node] = new ArrayBuffer[Node]()

  private var consensusRoot: Node = Node.emptyNode

  @volatile
  private var mempoolRoot: Node = Node.emptyNode

  def start(): Unit = {
    System.out.println("starting KVStore")
    val socket = new TSocket

    socket.registerListener(this)

    val t = new Thread(() => socket.start(46658))
    t.setName("KVStore server Main Thread")
    t.start()
    while (true) {
      Thread.sleep(1000L)
    }
  }

  override def receivedDeliverTx(req: RequestDeliverTx): ResponseDeliverTx = {
    val tx = req.getTx.toStringUtf8
    val txPayload = tx.split("###")(0)

    val binaryOpPattern = "(.+)=(.+):(.*),(.*)".r
    val unaryOpPattern = "(.+)=(.+):(.*)".r
    val plainValuePattern = "(.+)=(.*)".r

    val result = txPayload match {
      case "BAD_DELIVER" => Left("BAD_DELIVER")
      case binaryOpPattern(key, op, arg1, arg2) =>
        op match {
          case "sum" => SumOperation(arg1, arg2)(consensusRoot, key)
          case _ => Left("Unknown binary op")
        }
      case unaryOpPattern(key, op, arg) =>
        op match {
          case "get" => GetOperation(arg)(consensusRoot, key)
          case "increment" => IncrementOperation(arg)(consensusRoot, key)
          case "factorial" => FactorialOperation(arg)(consensusRoot, key)
          case "hiersum" => HierarchicalSumOperation(arg)(consensusRoot, key)
          case _ => Left("Unknown unary op")
        }
      case plainValuePattern(key, value) => SetValueOperation(value)(consensusRoot, key)
      case key => SetValueOperation(key)(consensusRoot, key)
    }

    result match {
      case Right((newRoot, info)) =>
        consensusRoot = newRoot
        ResponseDeliverTx.newBuilder.setCode(CodeType.OK).setInfo(info).build
      case Left(message) => ResponseDeliverTx.newBuilder.setCode(CodeType.BAD).setLog(message).build
    }
  }

  override def requestCheckTx(req: RequestCheckTx): ResponseCheckTx = {
    // check mempoolRoot

    val tx = req.getTx.toStringUtf8
    if (tx == "BAD_CHECK") {
      System.out.println(s"CheckTx: $tx BAD")
      ResponseCheckTx.newBuilder.setCode(CodeType.BAD).setLog("BAD_CHECK").build
    } else {
      System.out.println(s"CheckTx: $tx OK")
      ResponseCheckTx.newBuilder.setCode(CodeType.OK).build
    }
  }

  override def requestCommit(requestCommit: RequestCommit): ResponseCommit = {
    consensusRoot = consensusRoot.merkelize()

    val buf = ByteBuffer.allocate(MerkleUtil.merkleSize)
    buf.put(consensusRoot.merkleHash.get)
    buf.rewind

    storage.append(consensusRoot)
    mempoolRoot = consensusRoot

    ResponseCommit.newBuilder.setData(ByteString.copyFrom(buf)).build
  }

  override def requestQuery(req: RequestQuery): ResponseQuery = {
    val height = if (req.getHeight != 0) req.getHeight.toInt - 1 else storage.size - 1
    val root = storage(height)
    val getPattern = "get:(.*)".r
    val lsPattern = "ls:(.*)".r

    val query = req.getData.toStringUtf8
    query match {
      case getPattern(key) =>
        val result = root.getValue(key)
        val proof = if (result.isDefined && req.getProve) twoLevelMerkleListToString(root.getProof(key)) else ""

        ResponseQuery.newBuilder.setCode(CodeType.OK)
          .setValue(ByteString.copyFromUtf8(result.getOrElse("")))
          .setProof(ByteString.copyFromUtf8(proof))
          .build
      case lsPattern(key) =>
        val result = root.listChildren(key)
        val proof = if (result.isDefined && req.getProve) twoLevelMerkleListToString(root.getProof(key)) else ""

        ResponseQuery.newBuilder.setCode(CodeType.OK)
          .setValue(ByteString.copyFromUtf8(result.map(x => x.mkString(" ")).getOrElse("")))
          .setProof(ByteString.copyFromUtf8(proof))
          .build
      case _ =>
        ResponseQuery.newBuilder.setCode(CodeType.BAD).setLog("Invalid query path. Got " + query).build
    }
  }

  private def twoLevelMerkleListToString(list: List[List[MerkleHash]]): String =
    list.map(level => level.map(MerkleUtil.merkleHashToHex).mkString(" ")).mkString(", ")
}

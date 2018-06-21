package kvstore

import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

import com.github.jtendermint.jabci.api._
import com.github.jtendermint.jabci.types.{ResponseCheckTx, _}
import com.google.protobuf.ByteString

import scala.util.Try
import scala.util.matching.Regex

/**
  * Tendermint establishes 3 socket connections with the app:
  * - Mempool for CheckTx
  * - Consensus for DeliverTx and Commit
  * - Info for Query
  *
  * According to specification the app maintains separate in-memory states for every connections:
  * – [[ABCIHandler.consensusRoot]]: the latest state modified with every successful transaction on DeliverTx
  * – [[ABCIHandler.storage]]: array of committed snapshots for Info connection
  * – [[ABCIHandler.mempoolRoot]]: the latest committed snapshot for Mempool connection (not used currently)
  */
class ABCIHandler(val serverIndex: Int) extends IDeliverTx with ICheckTx with ICommit with IQuery {
  @volatile var state: BlockchainState = BlockchainState()

  private val storage: CopyOnWriteArrayList[Node] = new CopyOnWriteArrayList[Node]()
  @volatile private var mempoolRoot: Node = Node.emptyNode


  override def requestCheckTx(req: RequestCheckTx): ResponseCheckTx = {
    // no transaction processing logic currently
    // mempoolRoot is intended to be used here as the latest committed state

    val tx = req.getTx.toStringUtf8
    if (tx == "BAD_CHECK") {
      System.out.println(s"CheckTx BAD: $tx")
      ResponseCheckTx.newBuilder.setCode(CodeType.BAD).setLog("BAD_CHECK").build
    } else {
      System.out.println(s"CheckTx OK: $tx")
      ResponseCheckTx.newBuilder.setCode(CodeType.OK).build
    }
  }


  private var consensusRoot: Node = Node.emptyNode
  private var currentBlockHasTransactions: Boolean = false

  private val binaryOpPattern: Regex = "(.+)=(.+)\\((.*),(.*)\\)".r
  private val unaryOpPattern: Regex = "(.+)=(.+)\\((.*)\\)".r
  private val plainValuePattern: Regex = "(.+)=(.*)".r

  override def receivedDeliverTx(req: RequestDeliverTx): ResponseDeliverTx = {
    currentBlockHasTransactions = true

    val tx = req.getTx.toStringUtf8
    System.out.println(s"DeliverTx: $tx")
    val txPayload = tx.split("###")(0)

    val result = txPayload match {
      case response@"BAD_DELIVER" => Left(response)
      case binaryOpPattern(key, op, arg1, arg2) =>
        op match {
          case "sum" => SumOperation(arg1, arg2)(consensusRoot, key)
          case _ => Left("Unknown binary op")
        }
      case unaryOpPattern(key, op, arg) =>
        op match {
          case "copy" => CopyOperation(arg)(consensusRoot, key)
          case "increment" => IncrementOperation(arg)(consensusRoot, key)
          case "factorial" => FactorialOperation(arg)(consensusRoot, key)
          case "hiersum" => HierarchicalSumOperation(arg)(consensusRoot, key)
          case _ => Left("Unknown unary op")
        }
      case plainValuePattern(key, value) =>
        if (key == "wrong" && value.contains(serverIndex.toString))
          SetValueOperation("wrong" + value)(consensusRoot, key)
        else
          SetValueOperation(value)(consensusRoot, key)
      case key => SetValueOperation(key)(consensusRoot, key)
    }

    result match {
      case Right((newRoot, info)) =>
        consensusRoot = newRoot
        ResponseDeliverTx.newBuilder.setCode(CodeType.OK).setInfo(info).build
      case Left(message) => ResponseDeliverTx.newBuilder.setCode(CodeType.BAD).setLog(message).build
    }
  }

  override def requestCommit(requestCommit: RequestCommit): ResponseCommit = {
    consensusRoot = consensusRoot.merkelize()

    val buffer = ByteBuffer.wrap(consensusRoot.merkleHash.get)

    storage.add(consensusRoot)
    mempoolRoot = consensusRoot

    state = BlockchainState(storage.size, consensusRoot.merkleHash, state.lastAppHash, currentBlockHasTransactions, System.currentTimeMillis())
    currentBlockHasTransactions = false
    System.out.println(s"Commit: height=${state.lastCommittedHeight} hash=${state.lastAppHash.map(MerkleUtil.merkleHashToHex).getOrElse("EMPTY")}")

    ResponseCommit.newBuilder.setData(ByteString.copyFrom(buffer)).build
  }


  override def requestQuery(req: RequestQuery): ResponseQuery = {
    val height = Try(req.getHeight.toInt).toOption.filter(_ > 0).getOrElse(state.lastCommittedHeight)
    val root = storage.get(height - 1) // storage is 0-indexed, but heights are 1-indexed
    val getPattern = "get:(.*)".r
    val lsPattern = "ls:(.*)".r

    val query = req.getData.toStringUtf8
    val (resultKey, result) = query match {
      case getPattern(key) => (key, root.getValue(key))
      case lsPattern(key) => (key, root.listChildren(key).map(x => x.mkString(" ")))
      case _ =>
        return ResponseQuery.newBuilder.setCode(CodeType.BAD).setLog("Invalid query path. Got " + query).build
    }

    val proof = if (result.isDefined && req.getProve) MerkleUtil.twoLevelMerkleListToString(root.getProof(resultKey)) else ""

    ResponseQuery.newBuilder.setCode(CodeType.OK)
      .setValue(ByteString.copyFromUtf8(result.getOrElse("")))
      .setProof(ByteString.copyFromUtf8(proof))
      .build
  }
}

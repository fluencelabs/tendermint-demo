package kvstore

import java.io.{DataOutputStream, InputStreamReader}
import java.net.{HttpURLConnection, URL}

import com.google.gson.{GsonBuilder, JsonParser}

import scala.collection.JavaConverters._
import scala.util.Try

/**
  * ABCI app monitor
  * Signals 'DISAGREEMENT WITH CLUSTER QUORUM!' when local node and cluster quorum have different app hashes
  * Signals 'NO CLUSTER QUORUM!' when cluster cannot make a new block verifying latest app hash in appropriate time
  *
  * @param handler handler to monitor
  */
class ServerMonitor(handler: ABCIHandler) extends Runnable {
  private val judgeEndpoint = "http://localhost:8080"

  private val monitorPeriod = 1000
  private val checkEmptyBlockThreshold = 5000
  private val checkForLocalEmptyBlockAlertThreshold = 15000

  private val parser = new JsonParser()
  private val gson = new GsonBuilder().create()

  override def run(): Unit = {
    while (true) {
      Thread.sleep(monitorPeriod)

      val state = handler.state
      val clusterHash = getAppHashFromPeers(state.lastCommittedHeight).getOrElse("")
      val localHash = state.lastVerifiableAppHash.map(MerkleUtil.merkleHashToHex).getOrElse("")

      if (clusterHash != localHash) {
        throw new IllegalStateException(s"Cluster quorum has unexpected app hash for previous block '$clusterHash' '$localHash'")
      }

      val timeWaiting = timeWaitingForEmptyBlock(state)
      val status = if (timeWaiting <= checkEmptyBlockThreshold)
        "OK"
      else {
        val nextClusterHash = getAppHashFromPeers(state.lastCommittedHeight + 1)

        if (nextClusterHash.isEmpty) {
          "No quorum"
        } else if (nextClusterHash != state.lastAppHash.map(MerkleUtil.merkleHashToHex)) {
          "Disagreement with quorum"
        } else if (timeWaiting > checkForLocalEmptyBlockAlertThreshold) {
          throw new IllegalStateException("Cluster quorum committed correct block without local Tendermint")
        } else {
          "OK"
        }
      }

      submitToJudge(state, status)
    }
  }

  def timeWaitingForEmptyBlock(state: BlockchainState): Long =
    if (state.lastBlockHasTransactions) System.currentTimeMillis() - state.lastBlockTimestamp else 0L

  def getAppHashFromPeers(height: Int): Option[String] =
    ClusterUtil.peerRPCAddresses(handler.serverIndex)
      .map(address => getAppHashFromPeer(address, height))
      .find(_.isDefined)
      .flatten

  def getAppHashFromPeer(peerAddress: String, height: Int): Option[String] =
    Try(new URL(peerAddress + "/block?height=" + height).openStream).toOption
      .map(input => parser.parse(new InputStreamReader(input, "UTF-8")))
      .filter(response => !response.getAsJsonObject.has("error"))
      .map(response => response.getAsJsonObject.get("result")
        .getAsJsonObject.get("block_meta")
        .getAsJsonObject.get("header")
        .getAsJsonObject.get("app_hash")
        .getAsString
        .toLowerCase)

  def submitToJudge(state: BlockchainState, status: String): Unit = {
    val submitPath = judgeEndpoint + "/submit/" + handler.serverIndex

    val connection = new URL(submitPath).openConnection().asInstanceOf[HttpURLConnection]
    connection.setRequestMethod("POST")
    connection.setRequestProperty("Content-Type", "application/json")
    connection.setDoOutput(true)

    val out = new DataOutputStream(connection.getOutputStream)

    out.writeBytes(gson.toJson(mapAsJavaMap(Map(
      "status" -> status,
      "height" -> state.lastCommittedHeight,
      "app_hash" -> state.lastAppHash.map(MerkleUtil.merkleHashToHex).getOrElse("empty"))
    )))
    out.flush()
    out.close()

    connection.getResponseCode
    connection.disconnect()
  }
}

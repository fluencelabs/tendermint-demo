package kvstore

import java.io.InputStreamReader
import java.net.URL

import com.google.gson.JsonParser

import scala.util.Try

/**
  * ABCI app monitor
  * Signals 'DISAGREEMENT WITH CLUSTER QUORUM!' when local node and cluster quorum have different app hashes
  * Signals 'NO CLUSTER QUORUM!' when cluster cannot make a new block verifying latest app hash in appropriate time
  * @param handler handler to monitor
  */
class ServerMonitor(handler: ABCIHandler) extends Runnable {
  private val monitorPeriod = 1000
  private val checkEmptyBlockThreshold = 5000
  private val checkForLocalEmptyBlockAlertThreshold = 15000
  private val parser = new JsonParser()

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
      if (timeWaiting > checkEmptyBlockThreshold) {
        val nextClusterHash = getAppHashFromPeers(state.lastCommittedHeight + 1)

        if (nextClusterHash.isEmpty) {
          System.out.println("NO CLUSTER QUORUM!")
        } else if (nextClusterHash != state.lastAppHash.map(MerkleUtil.merkleHashToHex)) {
          System.out.println("DISAGREEMENT WITH CLUSTER QUORUM!")
        } else if (timeWaiting > checkForLocalEmptyBlockAlertThreshold) {
          throw new IllegalStateException("Cluster quorum committed correct block without local Tendermint")
        }
      }
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
}

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
  private val parser = new JsonParser()

  override def run(): Unit = {
    while (true) {
      Thread.sleep(1000)
      val clusterHash = getAppHashFromPeers(handler.lastCommittedHeight + 1).getOrElse("")
      val localHash = handler.lastVerifiableAppHash.map(MerkleUtil.merkleHashToHex).getOrElse("")

      if (clusterHash != localHash) {
        throw new IllegalStateException("Cluster quorum has unexpected app hash for previous block")
      }

      if (timeWaitingForEmptyBlock > 3000) {
        val nextClusterHash = getAppHashFromPeers(handler.lastCommittedHeight + 2)

        if (nextClusterHash.isEmpty) {
          System.out.println("NO CLUSTER QUORUM!")
        } else if (nextClusterHash != handler.lastAppHash.map(MerkleUtil.merkleHashToHex)) {
          System.out.println("DISAGREEMENT WITH CLUSTER QUORUM!")
        } else if (timeWaitingForEmptyBlock > 15000) {
          throw new IllegalStateException("Cluster quorum committed correct block without local Tendermint")
        }
      }
    }
  }

  def timeWaitingForEmptyBlock: Long = if (handler.lastBlockHasTransactions) System.currentTimeMillis() - handler.lastBlockTimestamp else 0L

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

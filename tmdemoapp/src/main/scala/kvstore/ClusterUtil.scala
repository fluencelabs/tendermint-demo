package kvstore

object ClusterUtil {
  val defaultABCIPort: Int = 46658

  def abciPortToServerIndex(port: Int): Int = (port - 46058) / 100

  def localRPCAddress(localServerIndex: Int): String = s"http://localhost:46${localServerIndex}57"

  def peerRPCAddresses(localServerIndex: Int): Stream[String] =
    (0 until 4).toStream.filter(_ != localServerIndex).map(index => s"http://localhost:46${index}57")
}

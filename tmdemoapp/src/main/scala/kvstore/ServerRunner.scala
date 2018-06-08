package kvstore

import com.github.jtendermint.jabci.socket.TSocket

object ServerRunner {

  def main(args: Array[String]): Unit = {
    val port = if (args.length > 0) args(0).toInt else ClusterUtil.defaultABCIPort
    ServerRunner.start(port)
  }

  def start(port: Int): Unit = {
    System.out.println("starting KVStore")
    val socket = new TSocket

    val abciHandler = new ABCIHandler(ClusterUtil.abciPortToServerIndex(port))
    socket.registerListener(abciHandler)

    val monitorThread = new Thread(new ServerMonitor(abciHandler))
    monitorThread.setName("Monitor")
    monitorThread.start()

    val socketThread = new Thread(() => socket.start(port))
    socketThread.setName("Socket")
    socketThread.start()
    socketThread.join()
  }
}

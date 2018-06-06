package kvstore

import com.github.jtendermint.jabci.socket.TSocket

object ServerRunner {

  def main(args: Array[String]): Unit = {
    val port = if (args.length > 0) args(0).toInt else 46658
    ServerRunner.start(port)
  }

  def start(port: Int): Unit = {
    System.out.println("starting KVStore")
    val socket = new TSocket

    socket.registerListener(ABCIHandler)

    val t = new Thread(() => socket.start(port))
    t.setName("KVStore server Main Thread")
    t.start()
    while (true) {
      Thread.sleep(1000L)
    }
  }
}

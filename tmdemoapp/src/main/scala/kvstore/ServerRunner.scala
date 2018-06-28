/*
 * Copyright 2018 Fluence Labs Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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

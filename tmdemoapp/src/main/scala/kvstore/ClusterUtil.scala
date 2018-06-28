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

object ClusterUtil {
  val defaultABCIPort: Int = 46658

  def abciPortToServerIndex(port: Int): Int = (port - 46058) / 100

  def localRPCAddress(localServerIndex: Int): String = s"http://localhost:46${localServerIndex}57"

  def peerRPCAddresses(localServerIndex: Int): Stream[String] =
    (0 until 4).toStream.filter(_ != localServerIndex).map(index => s"http://localhost:46${index}57")
}

package kvstore

case class BlockchainState(
                            lastCommittedHeight: Int = 0,
                            lastAppHash: Option[MerkleHash] = None,
                            lastVerifiableAppHash: Option[MerkleHash] = None,
                            lastBlockHasTransactions: Boolean = false,
                            lastBlockTimestamp: Long = 0
                          )

package kvstore

import org.bouncycastle.jcajce.provider.digest.SHA3

sealed trait MerkleMergeRule
case object BINARY_BASED_MERKLE_MERGE extends MerkleMergeRule
case object HEX_BASED_MERKLE_MERGE extends MerkleMergeRule

object MerkleUtil {
  val merkleSize: Int = 32

  def singleMerkle(data: String): MerkleHash = new SHA3.Digest256().digest(data.getBytes)

  def mergeMerkle(parts: List[MerkleHash], mergeRule: MerkleMergeRule): MerkleHash =
    mergeRule match {
      case BINARY_BASED_MERKLE_MERGE => new SHA3.Digest256().digest(parts.fold(new Array[Byte](0))(_ ++ _))
      case HEX_BASED_MERKLE_MERGE => new SHA3.Digest256().digest(parts.map(merkleHashToHex).mkString(" ").getBytes)
    }

  def merkleHashToHex(merkleHash: MerkleHash): String =
    merkleHash.map("%02x".format(_)).mkString

  def twoLevelMerkleListToString(list: List[List[MerkleHash]]): String =
    list.map(level => level.map(MerkleUtil.merkleHashToHex).mkString(" ")).mkString(", ")
}

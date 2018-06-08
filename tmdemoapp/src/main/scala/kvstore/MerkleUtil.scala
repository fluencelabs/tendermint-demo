package kvstore

import org.bouncycastle.jcajce.provider.digest.SHA3

sealed trait MerkleMergeRule
case object BinaryBasedMerkleMerge extends MerkleMergeRule
case object HexBasedMerkleMerge extends MerkleMergeRule

object MerkleUtil {
  val merkleSize: Int = 32

  def singleHash(data: String): MerkleHash = new SHA3.Digest256().digest(data.getBytes)

  def mergeMerkle(parts: List[MerkleHash], mergeRule: MerkleMergeRule): MerkleHash =
    mergeRule match {
      case BinaryBasedMerkleMerge => new SHA3.Digest256().digest(parts.fold(Array.emptyByteArray)(_ ++ _))
      case HexBasedMerkleMerge => new SHA3.Digest256().digest(parts.map(merkleHashToHex).mkString(" ").getBytes)
    }

  def merkleHashToHex(merkleHash: MerkleHash): String =
    merkleHash.map("%02x".format(_)).mkString

  def twoLevelMerkleListToString(list: List[List[MerkleHash]]): String =
    list.map(level => level.map(MerkleUtil.merkleHashToHex).mkString(" ")).mkString(", ")
}

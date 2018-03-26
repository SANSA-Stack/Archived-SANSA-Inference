package net.sansa_stack.inference.spark.utils.rdd

import scala.reflect.ClassTag

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet
import org.apache.jena.graph.Triple

class SetRDDHashSetPartition(val set: ObjectOpenHashSet[Triple],
                             numFactsGenerated: Long = 0,
                             numFactsDerived: Long = 0)
                            (implicit val cTag: ClassTag[Triple])
  extends SetRDDPartition[Triple](numFactsGenerated, numFactsDerived) with Serializable {

  def this(set: ObjectOpenHashSet[Triple]) = this(set, 0, 0)

  override def size: Long = set.size

  override def iterator: Iterator[Triple] = new FastTripleIteratorWrapper(set)

  override def union(otherPart: SetRDDPartition[Triple], rddId: Int): SetRDDHashSetPartition = {
    val start = System.currentTimeMillis()
    val newPartition = otherPart match {
      case otherPart: SetRDDHashSetPartition =>
        val set : ObjectOpenHashSet[Triple] = {
          this.set.addAll(otherPart.set)
          this.set
        }
        new SetRDDHashSetPartition(set)

      case other => union(otherPart.iterator, rddId)
    }

    info("Union set size %s for rdd %s took %s ms".format(this.set.size, rddId, System.currentTimeMillis() - start))
    newPartition
  }

  override def union(iter: Iterator[Triple], rddId: Int): SetRDDHashSetPartition = {
    val start = System.currentTimeMillis()
    // add items to the existing set
    val newSet = this.set
    while (iter.hasNext)
      newSet.add(iter.next())

    info("Union set size %s for rdd %s took %s ms".format(this.set.size, rddId, System.currentTimeMillis() - start))
    new SetRDDHashSetPartition(newSet)
  }

  override def diff(iter: Iterator[Triple], rddId: Int): SetRDDHashSetPartition = {
    val start = System.currentTimeMillis()
    val diffSet = new ObjectOpenHashSet[Triple]()
    var numFactsGenerated: Long = 0
    while (iter.hasNext) {
      numFactsGenerated += 1
      val triple = iter.next()
      if(!this.set.contains(triple)) {
        diffSet.add(triple)
      }
    }
    info("Diff set size %s for rdd %s took %s ms".format(diffSet.size, rddId, System.currentTimeMillis() - start))
    new SetRDDHashSetPartition(diffSet, numFactsGenerated, diffSet.size)
  }
}

object SetRDDHashSetPartition {
  def apply(iter: Iterator[Triple]): SetRDDHashSetPartition = {
    val set = new ObjectOpenHashSet[Triple]()

    while (iter.hasNext)
      set.add(iter.next())

    new SetRDDHashSetPartition(set)
  }
}
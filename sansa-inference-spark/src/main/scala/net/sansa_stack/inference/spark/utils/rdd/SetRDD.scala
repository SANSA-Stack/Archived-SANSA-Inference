package net.sansa_stack.inference.spark.utils.rdd

import scala.reflect.ClassTag

import org.apache.jena.graph.Triple
import org.apache.spark._
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.storage.StorageLevel

class SetRDD(var partitionsRDD: RDD[SetRDDPartition[Triple]])
  extends RDD[Triple](partitionsRDD.context, List(new OneToOneDependency(partitionsRDD))) {

  protected def self: SetRDD = this

  setName("SetRDD")

  override val partitioner = partitionsRDD.partitioner

  override protected def getPreferredLocations(s: Partition): Seq[String] =
    partitionsRDD.preferredLocations(s)

  override protected def getPartitions: Array[Partition] = partitionsRDD.partitions

  override def persist(newLevel: StorageLevel = StorageLevel.MEMORY_ONLY): this.type = {
    partitionsRDD.persist(newLevel)
    this
  }

  override def unpersist(blocking: Boolean = true): this.type = {
    partitionsRDD.unpersist(blocking)
    this
  }

  override def cache(): this.type = this.persist()

  override def getStorageLevel: StorageLevel = partitionsRDD.getStorageLevel

  override def checkpoint(): Unit = {
    partitionsRDD.checkpoint()
  }

  override def isCheckpointed: Boolean = {
    firstParent[SetRDDPartition[InternalRow]].isCheckpointed
  }

  override def getCheckpointFile: Option[String] = {
    partitionsRDD.getCheckpointFile
  }

  override def localCheckpoint(): this.type = {
    partitionsRDD.localCheckpoint()
    this
  }

  override def mapPartitions[U: ClassTag](f: Iterator[Triple] => Iterator[U],
                                          preservesPartitioning: Boolean = false): RDD[U] = {
    partitionsRDD.mapPartitions(iter => f(iter.next().iterator), preservesPartitioning)
  }

//  override def mapPartitionsInternal[U: ClassTag](f: Iterator[Triple] => Iterator[U],
//                                                  preservesPartitioning: Boolean = false): RDD[U] = {
//    partitionsRDD.mapPartitionsInternal(iter => f(iter.next().iterator), preservesPartitioning)
//  }

  override def setName(_name: String): this.type = {
    name = _name
    partitionsRDD.setName(_name)
    this
  }

  override def count(): Long = {
    partitionsRDD.map(_.size).reduce(_ + _)
  }

  override def computeOrReadCheckpoint(split: Partition, context: TaskContext): Iterator[Triple] = {
    if (isCheckpointed) {
      firstParent[SetRDDPartition[Triple]].iterator(split, context).next.iterator
    } else {
      compute(split, context)
    }
  }

  override def compute(split: Partition, context: TaskContext): Iterator[Triple] = {
    firstParent[SetRDDPartition[Triple]].iterator(split, context).next.iterator
  }

  def diff(other: RDD[Triple]): SetRDD = {
    val diffRDD = other match {
      case other: SetRDD if partitioner == other.partitioner =>
        this.zipSetRDDPartitions(other)((thisIter, otherIter) => {
          val thisPart = thisIter.next()
          val otherPart = otherIter.next()
          Iterator(thisPart.diff(otherPart, id))
        })
      case _ =>
        this.zipPartitionsWithOther(other)((thisIter, otherIter) => {
          val thisPart = thisIter.next()
          Iterator(thisPart.diff(otherIter, id))
        })
    }
    diffRDD.setName("SetRDD.diffRDD")
  }

  override def union(other: RDD[Triple]): SetRDD = {
    val unionRDD = other match {
      case other: SetRDD if partitioner == other.partitioner =>
        this.zipSetRDDPartitions(other)((thisIter, otherIter) => {
          val thisPart = thisIter.next()
          val otherPart = otherIter.next()
          Iterator(thisPart.union(otherPart, id))
        })
      case _ =>
        this.zipPartitionsWithOther(other)((thisIter, otherIter) => {
          val thisPart = thisIter.next()
          Iterator(thisPart.union(otherIter, id))
        })
    }
    unionRDD.setName("SetRDD.unionRDD")
  }

  private def zipSetRDDPartitions(other: SetRDD)
                                 (f: (Iterator[SetRDDPartition[Triple]], Iterator[SetRDDPartition[Triple]])
                                   => Iterator[SetRDDPartition[Triple]]): SetRDD = {
    val rdd = partitionsRDD.zipPartitions(other.partitionsRDD, true)(f)
    new SetRDD(rdd)
  }

  private def zipPartitionsWithOther(other: RDD[Triple])
                                    (f: (Iterator[SetRDDPartition[Triple]], Iterator[Triple])
                                      => Iterator[SetRDDPartition[Triple]]): SetRDD = {
    val rdd = partitionsRDD.zipPartitions(other, true)(f)
    new SetRDD(rdd)
  }
}

object SetRDD {
  def apply(rdd: RDD[Triple]): SetRDD = {
    val setRDDPartitions = rdd.mapPartitions[SetRDDPartition[Triple]] (iter =>
      Iterator (SetRDDHashSetPartition(iter)), true)
    new SetRDD(setRDDPartitions)
  }
}

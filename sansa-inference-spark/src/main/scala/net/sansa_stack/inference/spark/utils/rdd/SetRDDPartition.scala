package net.sansa_stack.inference.spark.utils.rdd

import net.sansa_stack.inference.utils.Logging

abstract class SetRDDPartition[T](val numFactsGenerated: Long, val numFactsDerived: Long)
  extends Logging {

  def this() = this(0, 0)

  def self: SetRDDPartition[T] = this

  def size: Long

  def iterator: Iterator[T]

  def union(other: SetRDDPartition[T], id: Int): SetRDDPartition[T]

  def union(iter: Iterator[T], id: Int): SetRDDPartition[T]

  def diff(other: SetRDDPartition[T], id: Int): SetRDDPartition[T] = diff(other.iterator, id)

  def diff(iter: Iterator[T], id: Int): SetRDDPartition[T]
}
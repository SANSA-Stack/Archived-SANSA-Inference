package net.sansa_stack.inference.spark.utils.rdd

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet
import org.apache.jena.graph.Triple

/**
  * @author Lorenz Buehmann
  */
class FastTripleIteratorWrapper(set: ObjectOpenHashSet[Triple]) extends Iterator[Triple] {
  val rawIter = set.iterator()

  override final def hasNext(): Boolean = {
    rawIter.hasNext
  }

  override final def next(): Triple = {
    rawIter.next()
  }
}

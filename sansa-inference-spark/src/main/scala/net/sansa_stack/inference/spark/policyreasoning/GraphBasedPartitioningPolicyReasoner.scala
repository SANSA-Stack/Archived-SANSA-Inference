package net.sansa_stack.inference.spark.policyreasoning

import java.io.{File, FileWriter}
import java.util.stream.Collectors

import net.sansa_stack.query.spark.graph.jena.SparqlParser
import net.sansa_stack.query.spark.graph.jena.model.{IntermediateResult, SparkExecutionModel, Config => JenaModelConfig}
import net.sansa_stack.rdf.spark.partition.graph.algo.{ObjectHashPartition, PartitionAlgo, PathPartition, SOHashPartition, SubjectHashPartition}
import org.aksw.owl2sparql.OWLClassExpressionToSPARQLConverter
import org.apache.commons.io.FileUtils
import org.apache.jena.graph.Node
import org.apache.jena.riot.Lang
import org.semanticweb.owlapi.apibinding.OWLManager
import org.semanticweb.owlapi.model.{OWLClass, OWLClassExpression}
import org.semanticweb.owlapi.reasoner.OWLReasoner
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory
import uk.ac.manchester.cs.owl.owlapi.InternalizedEntities

import scala.collection.JavaConverters._


object PartitionType extends Enumeration {
  type PartitionType = Value
  val SubjectHash, ObjectHash, SubjectObjectHash, Path = Value
}


case class GraphBasedPartitioningPolicyReasonerConfig(
                                                       ontologyFilePath: String = "/tmp/special.owl",
                                                       consentFilePath: String = "/tmp/consent.ttl",
                                                       logFilePath: String = "/tmp/log.nt",
                                                       partitionType: PartitionType.PartitionType = PartitionType.SubjectHash)


object GraphBasedPartitioningPolicyReasoner {
  private val parser =
    new scopt.OptionParser[GraphBasedPartitioningPolicyReasonerConfig](
      "Graph partitioning-based policy reasoner") {
      head("Graph partitioning-based policy reasoner", "0.0.1")

      opt[String]('o', "ontologyfile")
        .action((value, config) => config.copy(ontologyFilePath = value))
        .required()

      opt[String]('c', "consentfile")
        .action((value, config) => config.copy(consentFilePath = value))
        .required()

      opt[String]('l', "logfile")
        .action((value, config) => config.copy(logFilePath = value))
        .required()

      opt[String]('p', "partitiontype")
        .text("one of S (subject hash), O (object hash), SO (subject object " +
          "hash), P (path)")
        .action((value, config) => value match {
          case "S" => config.copy(partitionType = PartitionType.SubjectHash)
          case "O" => config.copy(partitionType = PartitionType.ObjectHash)
          case "SO" => config.copy(partitionType = PartitionType.SubjectObjectHash)
          case "P" => config.copy(partitionType = PartitionType.Path)
        })
        .required()

      help("help").text("prints this usage text")
    }

  private var varCntr = 0
  private def getVarStr(): String = {
    varCntr += 1

    s"?allSuperClassesVar$varCntr"
  }

  private def extendSPARQLQueryWithSuperClasses(sparqlQuery: String, reasoner: OWLReasoner): String = {
    val atomicClasses = reasoner.getRootOntology.signature().collect(Collectors.toList()).asScala.filter(_.isOWLClass)
    var resultSPARQLQueryString = sparqlQuery
    atomicClasses.foreach(cls => {
      if (sparqlQuery.contains(cls.toString)) {
        val superClasses =
          reasoner.getSuperClasses(cls.asInstanceOf[OWLClass], false)
            .entities().collect(Collectors.toList()).asScala
            .filter(!_.equals(InternalizedEntities.OWL_THING))

        if (superClasses.size > 1) {
          val varStr = getVarStr()
          resultSPARQLQueryString = resultSPARQLQueryString.replace(cls.toString, varStr)

          val superClassesStr = superClasses.map(_.toString).mkString(", ")
          resultSPARQLQueryString = "}\\s$".r.replaceAllIn(
            resultSPARQLQueryString, s"  FILTER($varStr NOT IN ($superClassesStr))\n  }\n")
        }
      }
    })
    resultSPARQLQueryString
  }

  private def checkForViolation(
                                 user: String,
                                 ce: OWLClassExpression,
                                 converter: OWLClassExpressionToSPARQLConverter,
                                 reasoner: OWLReasoner,
                                 modelConfig: JenaModelConfig.type,
                                 partitionType: PartitionType.PartitionType): (Boolean, Seq[String]) = {
    var coveringPolicyFound = false
    var violatingLogEntryIRIs = Seq.empty[String]

    var sparqlQuery = converter.asQuery(ce, "?s").toString()
    sparqlQuery = extendSPARQLQueryWithSuperClasses(sparqlQuery, reasoner)

    val queryFile = new File(FileUtils.getTempDirectoryPath, "graph_partition_query.sparql")
    val writer = new FileWriter(queryFile)
    writer.write(sparqlQuery)
    writer.close()

    modelConfig.setInputQueryFile(queryFile.getAbsolutePath)
    SparkExecutionModel.createSparkSession()
    val session = SparkExecutionModel.getSession
    val g = SparkExecutionModel.getGraph
    // TODO: make this configurable
    val numParts = g.edges.partitions.length
    var partitionAlgorithm: PartitionAlgo[Node, Node] = null

    partitionType match {
      case PartitionType.SubjectHash => partitionAlgorithm =
        new SubjectHashPartition[Node, Node](g, session, numParts)
      case PartitionType.ObjectHash => partitionAlgorithm =
        new ObjectHashPartition[Node, Node](g, session, numParts)
      case PartitionType.SubjectObjectHash => partitionAlgorithm =
        new SOHashPartition[Node, Node](g, session, numParts)
      case PartitionType.Path => partitionAlgorithm =
        new PathPartition[Node, Node](g, session, numParts)
    }
    // TODO: make number of iterations configurable
//    partitionAlgorithm.setNumIterations(23)

    SparkExecutionModel.loadGraph(partitionAlgorithm.partitionBy().cache())

    val parser = new SparqlParser(modelConfig.getInputQueryFile)
    parser.getOps.foreach(op => op.execute())

    val results = IntermediateResult.getFinalResult.cache()
    violatingLogEntryIRIs = results.collect().map(_.toString())

    if (violatingLogEntryIRIs.nonEmpty) {
      coveringPolicyFound = true
    }

    queryFile.delete()

    (coveringPolicyFound, violatingLogEntryIRIs)
  }

  def run(
           ontologyFilePath: String, consentFilePath: String,
           logFilePath: String, partitionType: PartitionType.PartitionType): Unit = {

    val modelConfig = JenaModelConfig
      .setAppName("Graph partitioning-based policy reasoner")
      .setInputGraphFile(logFilePath).setLang(Lang.NTRIPLES)
      .setMaster("local[*]")

    // Read ontology
    val man = OWLManager.createOWLOntologyManager()
    val ont = man.loadOntologyFromOntologyDocument(new File(ontologyFilePath))
    val reasonerFactory = new StructuralReasonerFactory()
    val reasoner = reasonerFactory.createReasoner(ont)

    // read consent class expressions
    val userPolicies: Map[String, Set[OWLClassExpression]] =
      PolicyReader.readPolicyFile(consentFilePath)

    // further tools
    val converter = new OWLClassExpressionToSPARQLConverter()

    userPolicies.foreach(userPolicy => {
      val user = userPolicy._1
      val policyCEs = userPolicy._2

      var coveringPolicyFound = false
      var usersViolatingEntries = Seq.empty[String]

      policyCEs.takeWhile(_ => !coveringPolicyFound).foreach(ce => {
        val res: (Boolean, Seq[String]) =
          checkForViolation(user, ce, converter, reasoner, modelConfig, partitionType)
        coveringPolicyFound = coveringPolicyFound || res._1
        usersViolatingEntries = usersViolatingEntries ++ res._2
      })

      if (!coveringPolicyFound) {
        println(s"Found logged data of user $user that is not covered by any " +
          s"policy. The entries are:")
        usersViolatingEntries.foreach(println)
      } else {
        println(s"No violations found for user $user")
      }
    })
  }

  def main(args: Array[String]): Unit = {
    parser.parse(args, GraphBasedPartitioningPolicyReasonerConfig()) match {
      case Some(config) =>
        val ontologyFilePath = config.ontologyFilePath
        val consentFilePath = config.consentFilePath
        val logFilePath = config.logFilePath
        val partitionType = config.partitionType
        run(ontologyFilePath, consentFilePath, logFilePath, partitionType)
      case _ =>
    }
  }
}

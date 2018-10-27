package net.sansa_stack.inference.spark.policyreasoning

import java.io.{File, FileWriter}
import java.util.stream.Collectors

import net.sansa_stack.query.spark.semantic.QuerySystem
import net.sansa_stack.rdf.spark.partition.semantic.RdfPartition
import org.aksw.owl2sparql.OWLClassExpressionToSPARQLConverter
import org.apache.commons.io.FileUtils
import org.apache.jena.graph.Triple
import org.apache.jena.riot.Lang
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.semanticweb.owlapi.apibinding.OWLManager
import org.semanticweb.owlapi.model.{OWLClass, OWLClassExpression}
import org.semanticweb.owlapi.reasoner.OWLReasoner
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory
import uk.ac.manchester.cs.owl.owlapi.InternalizedEntities

import scala.collection.JavaConverters._

case class SemanticPartitioningBasedPolicyReasonerConfig(
    ontologyFilePath: String = "/tmp/special.owl",
    consentFilePath: String = "/tmp/consent.ttl",
    logFilePath: String = "/tmp/log.nt",
    resultsDirPath: String = "/tmp/semantic_partitioning_res")


object SemanticPartitioningBasedPolicyReasoner {
  private val parser =
    new scopt.OptionParser[SemanticPartitioningBasedPolicyReasonerConfig](
      "Semantic partitioning-based policy reasoner") {
      head("Semantic partitioning-based policy reasoner", "0.0.1")

      opt[String]('o', "ontologyfile")
        .action((value, config) => config.copy(ontologyFilePath = value))

      opt[String]('c', "consentfile")
        .action((value, config) => config.copy(consentFilePath = value))

      opt[String]('l', "logfile")
        .action((value, config) => config.copy(logFilePath = value))

      opt[String]('r', "resultsdir")
          .action((value, config) => config.copy(resultsDirPath = value))

      help("help").text("prints this usage text")
    }

  /**
    * PW: Not sure why I have to define this to initialize the sem. partitioning
    * stuff and I don't know any 'workaround'.
    */
  val symbol = Map(
    "space" -> " " * 5,
    "blank" -> " ",
    "tabs" -> "\t",
    "newline" -> "\n",
    "colon" -> ":",
    "comma" -> ",",
    "hash" -> "#",
    "slash" -> "/",
    "question-mark" -> "?",
    "exclamation-mark" -> "!",
    "curly-bracket-left" -> "{",
    "curly-bracket-right" -> "}",
    "round-bracket-left" -> "(",
    "round-bracket-right" -> ")",
    "less-than" -> "<",
    "greater-than" -> ">",
    "at" -> "@",
    "dot" -> ".",
    "dots" -> "...",
    "asterisk" -> "*",
    "up-arrows" -> "^^"
  )

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
            resultSPARQLQueryString, s"  FILTER($varStr IN ($superClassesStr))\n  }\n")
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
                                 partition: RDD[String],
                                 resultsDirPath: String,
                                 spark: SparkSession
                               ): (Boolean, Seq[String]) = {
    var coveringPolicyFound = false
    var violatingLogEntryIRIs = Seq.empty[String]

    val resultsDir = new File(resultsDirPath)
    FileUtils.deleteDirectory(resultsDir)

    var sparqlQuery = converter.asQuery(ce, "?s").toString()
    sparqlQuery = extendSPARQLQueryWithSuperClasses(sparqlQuery, reasoner)

    val sparqlQueryFile = new File(FileUtils.getTempDirectory, "query.sparql")
    val fileWriter = new FileWriter(sparqlQueryFile)
    fileWriter.write(sparqlQuery)
    fileWriter.close()

    val qs = new QuerySystem(
      symbol,
      partition,
      sparqlQueryFile.getAbsolutePath,
      resultsDir.getAbsolutePath,
      spark.sparkContext.defaultMinPartitions)
    qs.run()

    // TODO: get results from part-0000* files in $resultsDir

    sparqlQueryFile.delete()

    (coveringPolicyFound, violatingLogEntryIRIs)
  }

  def run(
           ontologyFilePath: String, consentFilePath: String,
           logFilePath: String, resultsDirPath: String): Unit = {

    val man = OWLManager.createConcurrentOWLOntologyManager()
    val ont = man.loadOntologyFromOntologyDocument(new File(ontologyFilePath))
    val reasonerFactory = new StructuralReasonerFactory()
    val reasoner = reasonerFactory.createReasoner(ont)
    val converter = new OWLClassExpressionToSPARQLConverter()

    val spark = SparkSession.builder
      .master("local[*]")
      .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .appName("Semantic partitioning-based policy reasoner")
      .getOrCreate()

    import net.sansa_stack.rdf.spark.io._
    val log: RDD[Triple] = spark.rdf(Lang.NTRIPLES)(logFilePath)

    val partition: RDD[String] = new RdfPartition(
      symbol, log, "DUMMY_PATH_THAT_IS_NEVER_USED",
      spark.sparkContext.defaultMinPartitions).partitionGraph()

    val userPolicies: Map[String, Set[OWLClassExpression]] =
      PolicyReader.readPolicyFile(consentFilePath)

    userPolicies.foreach(userPolicy => {
      val user = userPolicy._1
      val policyCEs = userPolicy._2
      var coveringPolicyFound = false
      var usersViolatingEntries = Seq.empty[String]

      policyCEs.takeWhile(_ => !coveringPolicyFound).foreach(ce => {
        val res: (Boolean, Seq[String]) =
          checkForViolation(user, ce, converter, reasoner, partition, resultsDirPath, spark)

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
    parser.parse(args, SemanticPartitioningBasedPolicyReasonerConfig()) match {
      case Some(config) =>
        val ontologyFilePath: String = config.ontologyFilePath
        val consentFilePath: String = config.consentFilePath
        val logFilePath: String = config.logFilePath
        val resultsDirPath: String = config.resultsDirPath
        run(ontologyFilePath, consentFilePath, logFilePath, resultsDirPath)
      case _ =>
    }
  }
}

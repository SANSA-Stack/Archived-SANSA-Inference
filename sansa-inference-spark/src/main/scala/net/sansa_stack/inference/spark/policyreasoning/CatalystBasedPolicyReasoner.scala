package net.sansa_stack.inference.spark.policyreasoning

import java.io.File
import java.util.stream.Collectors

import net.sansa_stack.query.spark.sparqlify.SparqlifyUtils3
import net.sansa_stack.rdf.common.partition.core.RdfPartitionDefault
import net.sansa_stack.rdf.spark.partition.core.RdfPartitionUtilsSpark
import org.aksw.owl2sparql.OWLClassExpressionToSPARQLConverter
import org.aksw.sparqlify.core.domain.input.SparqlSqlStringRewrite
import org.aksw.sparqlify.core.interfaces.SparqlSqlStringRewriter
import org.apache.jena.graph.Triple
import org.apache.jena.query.{Query, QueryFactory}
import org.apache.jena.riot.Lang
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{Row, SparkSession}
import org.semanticweb.owlapi.apibinding.OWLManager

import scala.collection.JavaConverters._
import org.semanticweb.owlapi.model.{OWLClass, OWLClassExpression, OWLObjectHasValue, OWLObjectIntersectionOf, OWLObjectSomeValuesFrom}
import org.semanticweb.owlapi.reasoner.OWLReasoner
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory
import uk.ac.manchester.cs.owl.owlapi.OWLClassImpl


case class CatalystBasedPolicyReasonerConfig(
                                              ontologyFilePath: String = "/tmp/special.owl",
                                              consentFilePath: String = "/tmp/consent.ttl",
                                              logFilePath: String = "/tmp/log.nt")


object CatalystBasedPolicyReasoner {
  private val parser =
    new scopt.OptionParser[CatalystBasedPolicyReasonerConfig]("Catalyst-based policy reasoner") {
      head("Catalyst-based policy reasoner", "0.0.1")

      opt[String]('o', "ontologyfile")
        .action((value, config) => config.copy(ontologyFilePath = value))

      opt[String]('c', "consentfile")
        .action((value, config) => config.copy(consentFilePath = value))

      opt[String]('l', "logfile")
        .action((value, config) => config.copy(logFilePath = value))

      help("help").text("prints this usage text")
  }

  def getClasses(ce: OWLClassExpression): Set[OWLClass] =
    ce match {
      case ce: OWLObjectIntersectionOf =>
        ce.operands().collect(
          Collectors.toList()).asScala.flatMap(op => getClasses(op)).toSet
      case ce: OWLObjectSomeValuesFrom =>
        getClasses(ce.getFiller)
      case ce: OWLObjectHasValue =>
        Set(new OWLClassImpl(ce.getFiller.asOWLNamedIndividual().getIRI))
      case _ => throw new RuntimeException(s"class ${ce.getClass} not handled")
    }

  def checkForViolation(
                         user: String,
                         ce: OWLClassExpression,
                         converter: OWLClassExpressionToSPARQLConverter,
                         rewriter: SparqlSqlStringRewriter,
                         reasoner: OWLReasoner,
                         spark: SparkSession): (Boolean, Seq[String]) = {

    var coveringPolicyFound = false
    var violatingLogEntryIRIs = Seq.empty[String]

    val sparqlQuery = converter.asQuery(ce, "?s")

    /* Example for a rewritten SQL query:
     * SELECT DISTINCT `a_135`.`s` `h_230`
     * FROM
     *   `http://www.specialprivacy.eu/langs/usage-policy#hasRecipient` `a_132`,
     *   `http://www.specialprivacy.eu/langs/usage-policy#hasStorage` `a_133`,
     *   `http://www.specialprivacy.eu/langs/usage-policy#hasPurpose` `a_136`,
     *   `http://www.specialprivacy.eu/langs/usage=policy#hasProcessing` `a_137`,
     *   `http://www.specialprivacy.eu/langs/usage-policy#hasData` `a_134`,
     *   `http://www.specialprivacy.eu/langs/splog#logEntryContent` `a_135`,
     *   `http://www.specialprivacy.eu/langs/splog#dataSubject` `a_138`
     * WHERE
     *   (`a_132`.`s` = `a_133`.`s`)
     * AND
     *   (`a_132`.`s` = `a_134`.`s`)
     * AND
     *   (`a_132`.`s` = `a_135`.`o`)
     * AND
     *   (`a_132`.`s` = `a_136`.`s`)
     * AND
     *   (`a_132`.`s` = `a_137`.`s`)
     * AND
     *   (`a_135`.`s` = `a_138`.`s`)
     * AND
     *   (`a_134`.`o` = CAST('http://www.specialprivacy.eu/vocabs/data#Preference' AS string))
     * AND
     *   (`a_133`.`o` = CAST('http://www.specialprivacy.eu/vocabs/locations#OurServers' AS string))
     * AND
     *   (`a_136`.`o` = CAST('http://www.specialprivacy.eu/vocabs/purposes#News' AS string))
     * AND
     *   (`a_138`.`o` = CAST('http://www.example.com/users/a1c76f92-401e-44a2-ac92-4433ed02ad9a' AS string))
     * AND
     *   (`a_137`.`o` = CAST('http://www.specialprivacy.eu/vocabs/processing#Move' AS string))
     * AND
     *   (`a_132`.`o` = CAST('http://www.specialprivacy.eu/vocabs/recipients#Public' AS string))
     */
    val sqlQuery: SparqlSqlStringRewrite =
      rewriter.rewrite(QueryFactory.create(sparqlQuery))
    var sqlQueryStr = sqlQuery.getSqlQueryString

    val atomicClssinSignature = getClasses(ce)


    /*
     * Replace all string occurrences of an atomic class IRI in the SQL query,
     * e.g.
     *
     *   `a_132`.`o` = CAST('http://www.specialprivacy.eu/vocabs/recipients#Public' AS string)
     *
     * with an IN( ) filter which contains all superclasses of the respective
     * class, e.g.
     *
     *   `a_132`.`o` IN(CAST('http://www.specialprivacy.eu/vocabs/recipients#Public' AS string), \
     *      CAST('http://www.specialprivacy.eu/vocabs/recipients#AnyRecipient' AS string))
     *
     * The set of superclasses also includes the respective class.
     * This replacement is not performed when the respective class does not
     * have any superclass except owl:Thing.
     */
    atomicClssinSignature.foreach(cls => {
      val subClsss: Set[OWLClass] =
        reasoner.getSubClasses(cls).getFlattened.asScala.toSet[OWLClass]
          .filter(!_.isOWLNothing) ++ Set(cls)

      val subClassesStr = subClsss.map(c => s"CAST('${c.getIRI}' AS string)").mkString(", ")

      if (subClsss.size > 1) {
        sqlQueryStr = sqlQueryStr.replace(
          s"= CAST('${cls.getIRI}' AS string)",
          s"IN ($subClassesStr)")
      }
    })

    /*
     * Log structure:
     *
     * logEntries:004c10ed
     *     :dataSubject users:314e462f ;
     *     :logEntryContent logEntryContents:8b0479d8 ;
     *     :transactionTime "2018-10-18T19:38:39"^^xsd:dateTime ;
     *     a :LogEntry .
     *
     * logEntryContents:8b0479d8
     *     :hasData data:Preference ;
     *     :hasProcessing processing:Anonymize ;
     *     :hasPurpose purposes:Education ;
     *     :hasRecipient recipients:Public ;
     *     :hasStorage locations:ProcessorServers .
     *
     * $sqlQueryStr returns all log entry content resources
     * (logEntryContents:XYZ) that comply with the policy class expression.
     * Accordingly the overall query returns all log entry content resources
     * of the user $user which don't comply with the policy class expression
     * $ce
     */
    val overallQueryStr =
      s"""
         |SELECT `logEntryContent`.`o` `entry`
         |FROM
         |  `http://www.specialprivacy.eu/langs/splog#dataSubject` `dataSubject`,
         |  `http://www.specialprivacy.eu/langs/splog#logEntryContent` `logEntryContent`
         |WHERE
         |  (`logEntryContent`.`s` NOT IN (
         |     $sqlQueryStr ))
         |AND
         |  (`dataSubject`.`s` = `logEntryContent`.`s`)
         |AND
         |  (`dataSubject`.`o` = CAST('$user' AS string))
       """.stripMargin

    val violatingEntries = spark.sql(overallQueryStr)

    if (violatingEntries.count() == 0) {
      coveringPolicyFound = true
    } else {
      val entries: Seq[String] = violatingEntries.collect().map {
        case Row(e) => e.asInstanceOf[String]
        case _ => null
      }.filter(_ != null)
      violatingLogEntryIRIs = violatingLogEntryIRIs ++ entries
    }

    (coveringPolicyFound, violatingLogEntryIRIs)
  }

  def run(ontologyFilePath: String, consentFilePath: String, logFilePath: String): Unit = {
    val man = OWLManager.createOWLOntologyManager()
    val ont = man.loadOntologyFromOntologyDocument(new File(ontologyFilePath))
    val reasonerFactory = new StructuralReasonerFactory()
    val reasoner = reasonerFactory.createReasoner(ont)
    val converter = new OWLClassExpressionToSPARQLConverter

    val spark = SparkSession.builder
      .master("local")
      .appName("spark session example")
      .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .config("spark.kryo.registrator", String.join(
        ", ",
        "net.sansa_stack.rdf.spark.io.JenaKryoRegistrator",
        "net.sansa_stack.query.spark.sparqlify.KryoRegistratorSparqlify"))
      .config("spark.default.parallelism", "4")
      .config("spark.sql.shuffle.partitions", "4")
      .config("spark.eventLog.enabled", "false")
      .getOrCreate()
    spark.conf.set("spark.sql.crossJoin.enabled", "true")

    import net.sansa_stack.rdf.spark.io._
    val graphRdd: RDD[Triple] = spark.rdf(Lang.NTRIPLES)(logFilePath)
    val partitions: Map[RdfPartitionDefault, RDD[Row]] =
      RdfPartitionUtilsSpark.partitionGraph(graphRdd)
    val rewriter: SparqlSqlStringRewriter =
      SparqlifyUtils3.createSparqlSqlRewriter(spark, partitions)

    val userPolicies: Map[String, Set[OWLClassExpression]] =
      PolicyReader.readPolicyFile(consentFilePath)

    userPolicies.foreach(userPolicy => {
      val user = userPolicy._1
      val policyCEs = userPolicy._2
      var coveringPolicyFound = false

      var usersViolatingEntries = Seq.empty[String]

      policyCEs.iterator.takeWhile(_ => !coveringPolicyFound).foreach(ce => {
        val res: (Boolean, Seq[String]) =
          checkForViolation(user, ce, converter, rewriter, reasoner, spark)
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
    parser.parse(args, CatalystBasedPolicyReasonerConfig()) match {
      case Some(config) =>
        val ontologyFilePath = config.ontologyFilePath
        val consentFilePath = config.consentFilePath
        val logFilePath = config.logFilePath
        run(ontologyFilePath, consentFilePath, logFilePath)
      case _ =>
    }
  }
}

package net.sansa_stack.inference.spark.policyreasoning

import scala.collection.JavaConverters._

import org.apache.jena.query.QueryExecutionFactory
import org.apache.jena.rdf.model.{Property, Resource}
import org.apache.jena.rdf.model.impl.{PropertyImpl, ResourceImpl}
import org.apache.jena.riot.RDFDataMgr
import org.semanticweb.owlapi.apibinding.OWLManager
import org.semanticweb.owlapi.model.OWLClassExpression
import uk.ac.manchester.cs.owl.owlapi.{OWLObjectHasValueImpl, OWLObjectIntersectionOfImpl, OWLObjectSomeValuesFromImpl}

object PolicyReader {
  val df = OWLManager.getOWLDataFactory
  val consentClass: Resource = new ResourceImpl(
    "http://www.specialprivacy.eu/vocabs/policy#Consent")
  val hasDataSubjectProperty: Property = new PropertyImpl(
    "http://www.specialprivacy.eu/langs/usage-policy#hasDataSubject")
  val logDataSubjectProperty: Property = new PropertyImpl(
    "http://www.specialprivacy.eu/langs/splog#dataSubject")
  val hasPolicyProperty: Property = new PropertyImpl(
    "http://www.specialprivacy.eu/vocabs/policy#simplePolicy")
  val hasDataProperty: Property = new PropertyImpl(
    "http://www.specialprivacy.eu/langs/usage-policy#hasData")
  val hasPurposeProperty: Property = new PropertyImpl(
    "http://www.specialprivacy.eu/langs/usage-policy#hasPurpose")
  val hasRecipientProperty: Property = new PropertyImpl(
    "http://www.specialprivacy.eu/langs/usage-policy#hasRecipient")
  val hasStorageProperty: Property = new PropertyImpl(
    "http://www.specialprivacy.eu/langs/usage-policy#hasStorage")
  val hasProcessingProperty: Property = new PropertyImpl(
    "http://www.specialprivacy.eu/langs/usage=policy#hasProcessing")
  val hasLogEntryContentProperty: Property = new PropertyImpl(
    "http://www.specialprivacy.eu/langs/splog#logEntryContent")

  def readPolicyFile(policyFilePath: String): Map[String, Set[OWLClassExpression]] = {
    val dataset = RDFDataMgr.loadDataset(policyFilePath)
    val model = dataset.getDefaultModel

    val queryStr =
      s"""
         |SELECT *
         |WHERE {
         |  ?s <${hasDataSubjectProperty.toString}> ?user .
         |  ?s <${hasPolicyProperty.toString}> ?policyBNode .
         |  OPTIONAL { ?policyBNode <${hasDataProperty.toString}> ?data }
         |  OPTIONAL { ?policyBNode <${hasPurposeProperty.toString}> ?purpose }
         |  OPTIONAL { ?policyBNode <${hasRecipientProperty.toString}> ?recipient }
         |  OPTIONAL { ?policyBNode <${hasStorageProperty.toString}> ?storage }
         |  OPTIONAL { ?policyBNode <${hasProcessingProperty.toString}> ?processing }
         |}
       """.stripMargin

    var userPolicies: Set[(String, OWLClassExpression)] = Set.empty
    val qef = QueryExecutionFactory.create(queryStr, model)
    val res = qef.execSelect()
    while (res.hasNext) {
      val resEntry = res.next()

      var restrictions: Set[OWLObjectHasValueImpl] = Set.empty
      val user = resEntry.get("user")

      val data = resEntry.get("data")
      if (data != null) {
        restrictions += new OWLObjectHasValueImpl(
          df.getOWLObjectProperty(hasDataProperty.getURI),
          df.getOWLNamedIndividual(data.asResource().getURI))
      }

      val purpose = resEntry.get("purpose")
      if (purpose != null) {
        restrictions += new OWLObjectHasValueImpl(
          df.getOWLObjectProperty(hasPurposeProperty.getURI),
          df.getOWLNamedIndividual(purpose.asResource().getURI))
      }

      val recipient = resEntry.get("recipient")
      if (recipient != null) {
        restrictions += new OWLObjectHasValueImpl(
          df.getOWLObjectProperty(hasRecipientProperty.getURI),
          df.getOWLNamedIndividual(recipient.asResource().getURI))
      }

      val storage = resEntry.get("storage")
      if (storage != null) {
        restrictions += new OWLObjectHasValueImpl(
          df.getOWLObjectProperty(hasStorageProperty.getURI),
          df.getOWLNamedIndividual(storage.asResource().getURI))
      }

      val processing = resEntry.get("processing")
      if (processing != null) {
        restrictions += new OWLObjectHasValueImpl(
          df.getOWLObjectProperty(hasProcessingProperty.getURI),
          df.getOWLNamedIndividual(processing.asResource().getURI))
      }

      val wholeIntersection = new OWLObjectIntersectionOfImpl(
        restrictions.asJavaCollection)
      val userPolicy = new OWLObjectIntersectionOfImpl(Set(
        new OWLObjectHasValueImpl(
          df.getOWLObjectProperty(logDataSubjectProperty.getURI),
          df.getOWLNamedIndividual(user.asResource().getURI)),
        new OWLObjectSomeValuesFromImpl(
          df.getOWLObjectProperty(hasLogEntryContentProperty.getURI),
          wholeIntersection)).asJavaCollection)

      val e = (user.asResource().getURI, userPolicy)
      userPolicies += e
    }

    userPolicies.groupBy(_._1).map(kv => (kv._1, kv._2.map(_._2)))
  }
}

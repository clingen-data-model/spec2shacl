package org.renci.spec2shacl

import java.io.{File, FileWriter, PrintWriter}
import java.time.ZonedDateTime
import java.util.Calendar

import java.io.{File, FileInputStream, IOException, InputStream, ByteArrayOutputStream}

import org.topbraid.shacl.validation._
import org.apache.jena.ontology.OntDocumentManager
import org.apache.jena.ontology.OntModel
import org.apache.jena.ontology.OntModelSpec
import org.apache.jena.rdf.model.{Model, ModelFactory, Resource, RDFNode, RDFList}
import org.apache.jena.util.FileUtils
import org.topbraid.jenax.util.JenaUtil
import org.topbraid.jenax.util.SystemTriples
import org.topbraid.shacl.compact.SHACLC
import org.topbraid.shacl.util.SHACLSystemModel
import org.topbraid.shacl.vocabulary.DASH
import org.topbraid.shacl.vocabulary.SH
import org.topbraid.shacl.vocabulary.TOSH
import org.apache.jena.vocabulary.RDF
import org.apache.jena.vocabulary.RDFS
import org.rogach.scallop._

import com.typesafe.scalalogging.LazyLogging
import com.github.tototoshi.csv.CSVReader
import scala.collection.JavaConverters;

/**
 * Better validation errors for SHACL
 */

object ValidationErrorPrinter {
  def print(report: ValidationReport, shapesModel: OntModel, dataModel: OntModel): Unit = {
    val results = JavaConverters.asScalaBuffer(report.results).toSeq

    // 1. Group results by source shape.
    val resultsByClass = results.groupBy({ result =>
      val focusNode = result.getFocusNode
      val statement = dataModel.getProperty(focusNode.asResource, RDF.`type`)

      if (statement == null) RDFS.Class
      else statement.getObject
    })
    resultsByClass.toSeq.sortBy(_._2.size).foreach({ case (classNode, classResults) =>
      println(s"For nodes of type $classNode (${classResults.size} errors)")

      // 2. Group results by target node.
      val resultsByFocusNode = classResults.groupBy(_.getFocusNode)
      resultsByFocusNode.toSeq.sortBy(_._2.size).foreach({ case (focusNode, focusNodeResults) =>
        println(s" - In focus node $focusNode (${focusNodeResults.size} errors)")

        val resultsByPath = focusNodeResults.groupBy(_.getPath)
        resultsByPath.toSeq.sortBy(_._2.size).foreach({ case (pathNode, pathNodeResults) =>
          println(s"   - In property ${summarizeResource(pathNode)}")
          pathNodeResults.foreach(result => {
            if (result.getValue == null)
              println(s"     - ${result.getMessage} [${result.getSourceConstraintComponent}]")
            else
              println(s"     - (for value ${result.getValue}) ${result.getMessage} [${result.getSourceConstraintComponent}]")
          })
        })
      })
      println()
    })

    println(s"FAIL ${results.size} failures across ${resultsByClass.keys.size} classes.")
  }

  def summarizeResource(node: RDFNode): String = {
    if (node.canAs(classOf[RDFList])) {
      val list: RDFList = node.as(classOf[RDFList])
      JavaConverters.asScalaBuffer(list.asJavaList).toSeq.map(summarizeResource(_)).mkString(", ")
    } else if (node.asNode.isBlank) {
      val byteArray = new ByteArrayOutputStream()
      node.asResource.listProperties.toModel.write(byteArray, "TURTLE") // Accepts "JSON-LD"!
      byteArray.toString.replaceAll("\n", " ").replaceAll("\t", " ").replaceAll("\\s+", " ")
    } else {
      node.toString
    }
  }
}

/**
 * Command line configuration for Validate.
 */
class Conf(arguments: Seq[String]) extends ScallopConf(arguments) {
  val shapes = trailArg[File](
    descr = "Shapes file to validate (in Turtle)"
  )
  val data = trailArg[File](
    descr = "Data file to validate (in Turtle)"
  )
  verify()
}

/**
 * Use the given shapes file to validate the given data file.
 * This reads the data file and so can provide better error messages that
 * TopBraid's SHACL engine.
 */

object Validate extends App with LazyLogging {
  // Parse command line arguments.
  val conf = new Conf(args)

  val shapesFile = conf.shapes()
  val dataFile = conf.data()

  // Set up the base model.
  val dm = new OntDocumentManager()
  val spec = new OntModelSpec(OntModelSpec.OWL_MEM)

  // Load SHACL.
  val shaclTTL = classOf[SHACLSystemModel].getResourceAsStream("/rdf/shacl.ttl")
  val shacl = JenaUtil.createMemoryModel()
  shacl.read(shaclTTL, SH.BASE_URI, FileUtils.langTurtle)
  shacl.add(SystemTriples.getVocabularyModel())
  dm.addModel(SH.BASE_URI, shacl)

  spec.setDocumentManager(dm);

  // Load the shapes.
  val shapesModel = ModelFactory.createOntologyModel(spec)
  shapesModel.read(new FileInputStream(shapesFile), "urn:x:base", FileUtils.langTurtle)

  // Load the data model.
  val dataModel = ModelFactory.createOntologyModel(spec)
  dataModel.read(new FileInputStream(dataFile), "urn:x:base", FileUtils.langTurtle)

  // Create a validation engine.
	val engine = ValidationUtil.createValidationEngine(dataModel, shapesModel, true);
  engine.validateAll()
  val report = engine.getValidationReport

  if (report.conforms) {
    println("OK")
    System.exit(0)
  } else {
    ValidationErrorPrinter.print(report, shapesModel, dataModel)
    System.exit(1)
  }
}

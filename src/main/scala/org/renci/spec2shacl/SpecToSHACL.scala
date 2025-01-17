package org.renci.spec2shacl

import java.io.{File, FileWriter, PrintWriter}
import java.time.ZonedDateTime
import java.util.Calendar

import com.typesafe.scalalogging.LazyLogging
import com.github.tototoshi.csv.CSVReader

/**
 * SpecToSHACL reads the specification from Google Docs as exported to CSV sheets
 * in an input directory and produces SHACL shapes for the specified attributes.
 */

object SpecToSHACL extends App with LazyLogging {
  val inputDir = new File(args(0))
  val outputFile = new File(args(1))
  if (outputFile.exists && !outputFile.canWrite) throw new RuntimeException(s"Output file ${outputFile} is not writeable.")

  // Step 1. Read Type.csv to get a list of classes.
  val entities: Seq[Map[String, String]] = CSVReader.open(new File(inputDir, "Type.csv")).allWithHeaders
  val entitiesById: Map[String, Seq[Map[String, String]]] = entities.groupBy(_.getOrElse("id", "(unknown)"))

  // Step 2. Read Attribute.csv to get a list of attributes.
  val attributes: Seq[Map[String, String]] = CSVReader.open(new File(inputDir, "Attribute.csv")).allWithHeaders
  val attributesById: Map[String, Seq[Map[String, String]]] = attributes.groupBy(_.getOrElse("id", "(unknown)"))

  // Step 3. If a Type has a parentType, then it should have all the attributes of the parentType
  // as well.
  def getAllAttributes(entityId: String): Seq[Map[String, String]] = {
    if (entityId == "") return Seq()
    val attrs: Seq[Map[String, String]] = attributes.filter(attr => attr.contains("entityId") && attr("entityId") == entityId)
    val parentAttrs: Seq[Map[String, String]] = entitiesById.get(entityId).map(_.flatMap { entity: Map[String, String] =>
      entity.get("parentType").map(getAllAttributes(_)).getOrElse(Seq())
    }).getOrElse(Seq())
    parentAttrs ++ attrs
  }

  val output = new PrintWriter(new FileWriter(outputFile))
  output.println(
    s"""@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
       |@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
       |@prefix sh: <http://www.w3.org/ns/shacl#> .
       |@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
       |@prefix obo: <http://purl.obolibrary.org/obo/> .
       |@prefix skos: <http://www.w3.org/2008/05/skos#> .
       |
       |@prefix BFO: <http://purl.obolibrary.org/obo/BFO_> .
       |@prefix CG: <http://dataexchange.clinicalgenome.org/CG_> .
       |@prefix DC: <http://purl.org/dc/elements/1.1/> .
       |@prefix ERO: <http://purl.obolibrary.org/obo/ERO_> .
       |@prefix FALDO: <http://biohackathon.org/resource/faldo> .
       |@prefix GENO: <http://purl.obolibrary.org/obo/GENO_> .
       |@prefix PAV: <http://purl.org/pav/> .
       |@prefix PROV: <http://www.w3.org/ns/prov#> .
       |@prefix RDFS: <http://www.w3.org/2000/01/rdf-schema#> .
       |@prefix RO: <http://purl.obolibrary.org/obo/RO_> .
       |@prefix SEPIO: <http://purl.obolibrary.org/obo/SEPIO_> .
       |@prefix SEPIO-CG: <http://purl.obolibrary.org/obo/SEPIO-CG_> .
       |
       |@prefix cgshapes: <http://dataexchange.clinicalgenome.org/interpretation/shacl/shapes/> .
       |
       |# This SHACL file was autogenerated at ${ZonedDateTime.now()}.
       |""".stripMargin)

  val cardinalityRegex = """^(\d+)..(\d+|\*)$""".r

  val allAttributes = entitiesById.keys.toSeq.sorted.foreach(entityId => {
    val attributesAsString = getAllAttributes(entityId).map(attr => {
      val cardinalityStr = attr("cardinality") match {
        case cardinalityRegex(from, "*")  => s"sh:minCount $from"
        case cardinalityRegex(from, to)   => s"sh:minCount $from ;\n    sh:maxCount $to"
        case _                            => s"# Could not read cardinality '${attr("cardinality")}'"
      }

      // dataType can give us four pieces of information:
      //  - whether sh:nodeKind should be a literal or blank-node-or-IRI.
      //  - whether sh:dataType should be an RDF type.
      //  - whether sh:class should be set.
      //  - whether values for this property should be from a particular value set.
      val nodeKind = attr("dataType") match {
        case "string" | "integer" | "number" | "Datetime" => "sh:Literal"
        case "@id" => "sh:BlankNodeOrIRI"
        case _ => "sh:BlankNodeOrIRI"
      }

      val dataType = attr("dataType") match {
        case "string" => Some("rdf:string")
        case "integer" => Some("xsd:integer")
        case "number" => Some("xsd:decimal")
        case "Datetime" => Some("xsd:dateTime")
        case _ => None
      }

      // If dataType is set to an entity we already know about, that's our sh:class.
      val entitiesWithDataType = entities.filter(entity => attr("dataType").equals(entity("name")))

      // Is this property part of a ValueSet? If so, add constraints to ensure that
      // the value set has the right skos:inScheme. Note that this must be present,
      // whether or not the property itself is present.
      val valueSetConstraints = attr.get("@valueSetId").filter(!_.isEmpty).fold("")(valueSet => s"""
        |  sh:property [
        |    sh:name "${attr("name")} should be in ${attr.getOrElse("_valueSetLabel", valueSet)}" ;
        |    sh:path ( ${attr("iri")} [ sh:inversePath SEPIO-CG:70004 ] ) ;
        |    sh:hasValue $valueSet ;
        |    sh:minCount 1 ;
        |    sh:maxCount 1
        |  ] ;
        | """.stripMargin)

      // TODO: should we choose to close this definition using sh:closed?
      s"""  sh:property [
         |    sh:name "${attr("name")}" ;
         |    sh:description "${attr("description")}" ;
         |    sh:path ${attr("iri")} ; # ${attr("iri-label")}
         |    sh:nodeKind $nodeKind ;
         |    ${ dataType.fold("")(dataType => s"xsd:dataType $dataType ;") }
         |    ${ entitiesWithDataType.map(entity => s"sh:class ${entity("iri")} ; # ${entity("name")}").mkString("\n") }
         |    ${ entitiesWithDataType.map(entity => s"sh:node cgshapes:${entity("name")} ;").mkString("\n") }
         |    $cardinalityStr
         |  ] ;
         |  $valueSetConstraints
         | """.stripMargin
    }).mkString("\n")

    // Do we even have an entityName?
    entitiesById(entityId).flatMap(entity => {
      val entityName = entity("name")
      if (entityName.isEmpty) None
      else Some(s"""cgshapes:$entityName a sh:NodeShape ;
           |  ${if (entity.contains("iri") && !entity("iri").isEmpty) s"""sh:targetClass ${entity("iri")} ; # ${entity.getOrElse("iri-label", "unlabelled")}""" else ""}
           |${attributesAsString}
           |.
           |""".stripMargin)
    }).foreach({ str =>
      // Remove all empty lines before printing it out.
      output.println(str.split("\\s*\\n+").mkString("\n") + "\n")
    })
  })

  // Close the output file.
  output.close
}

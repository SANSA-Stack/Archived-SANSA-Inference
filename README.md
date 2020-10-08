# Archived Repository - Do not use this repository anymore!

SANSA got easier to use! All its code has been consolidated into a single repository at https://github.com/SANSA-Stack/SANSA-Stack


# SANSA Inference Layer
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/net.sansa-stack/sansa-inference-parent_2.11/badge.svg)](https://maven-badges.herokuapp.com/maven-central/net.sansa-stack/sansa-inference-parent_2.11)
[![Build Status](https://travis-ci.com/SANSA-Stack/SANSA-Inference.svg?branch=develop)](https://travis-ci.com/SANSA-Stack/SANSA-Inference)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Twitter](https://img.shields.io/twitter/follow/SANSA_Stack.svg?style=social)](https://twitter.com/SANSA_Stack)

**Table of Contents**

- [SANSA Inference Layer](#)
	- [Structure](#structure)
		- [sansa-inference-common](#sansa-inference-common)
		- [sansa-inference-spark](#sansa-inference-spark)
		- [sansa-inference-flink](#sansa-inference-flink)
		- [sansa-inference-tests](#sansa-inference-tests)
	- [Setup](#setup)
		- [Prerequisites](#prerequisites)
		- [From source](#from-source)
		- [Using Maven pre-build artifacts](#using-maven-pre-build-artifacts)
		- [Using SBT](#using-SBT)
	- [Usage](#usage)
		- [Example](#example)
	- [Supported Reasoning Profiles](#supported-reasoning-profiles)
		- [RDFS](#rdfs)
		- [RDFS Simple](#rdfs-simple)
		- [OWL Horst](#owl-horst)
  	- [How to Contribute](#how-to-contribute)


## Structure
### sansa-inference-common
* common datastructures
* rule dependency analysis 

### sansa-inference-spark
Contains the core Inference API based on Apache Spark.

### sansa-inference-flink
Contains the core Inference API based on Apache Flink.

### sansa-inference-tests
Contains common test classes and data.


## Setup
### Prerequisites
* Maven 3.x
* Java 8
* Scala 2.11 (support for Scala 2.12 once Spark moved to Scala 2.12 as well)
* Apache Spark 2.x
* Apache Flink 1.x

### From source

To install the SANSA Inference API, you need to download it via Git and install it via Maven.
```shell
git clone https://github.com/SANSA-Stack/SANSA-Inference.git
cd SANSA-Inference
mvn clean install
```
Afterwards, you have to add the dependency to your `pom.xml`

For Apache Spark
```xml
<dependency>
  <groupId>net.sansa-stack</groupId>
  <artifactId>sansa-inference-spark_2.11</artifactId>
  <version>VERSION</version>
</dependency>
```
and for Apache Flink
```xml
<dependency>
  <groupId>net.sansa-stack</groupId>
  <artifactId>sansa-inference-flink_2.11</artifactId>
  <version>VERSION</version>
</dependency>
```
with `VERSION` beeing the released version you want to use.

### Using Maven pre-build artifacts

The latest release is available in Maven Central, thus, you only have to add the following dependency to your `pom.xml`:

For Apache Spark
```xml
<dependency>
  <groupId>net.sansa-stack</groupId>
  <artifactId>sansa-inference-spark_2.11</artifactId>
  <version>0.6.0</version>
</dependency>
```
and for Apache Flink
```xml
<dependency>
  <groupId>net.sansa-stack</groupId>
  <artifactId>sansa-inference-flink_2.11</artifactId>
  <version>0.6.0</version>
</dependency>
```

### Using SBT

Add the following lines to your SBT file:

For Apache Spark add
```scala
libraryDependencies += "net.sansa-stack" % "sansa-inference-spark_2.11" % "0.6.0"
```

and for Apache Flink add
```scala
libraryDependencies += "net.sansa-stack" % "sansa-inference-flink_2.11" % "0.6.0"
```
### Using Snapshots

Snapshot version are only avalibale via our custom Maven repository located at http://maven.aksw.org/archiva/repository/snapshots .

## Usage
Besides using the Inference API in your application code, we also provide a command line interface with various options that allow for a convenient way to use the core reasoning algorithms:
```
RDFGraphMaterializer 0.6.0
Usage: RDFGraphMaterializer [options]

  -i, --input <path1>,<path2>,...
                           path to file or directory that contains the input files (in N-Triples format)
  -o, --out <directory>    the output directory
  --properties <property1>,<property2>,...
                           list of properties for which the transitive closure will be computed (used only for profile 'transitive')
  -p, --profile {rdfs | rdfs-simple | owl-horst | transitive}
                           the reasoning profile
  --single-file            write the output to a single file in the output directory
  --sorted                 sorted output of the triples (per file)
  --parallelism <value>    the degree of parallelism, i.e. the number of Spark partitions used in the Spark operations
  --help                   prints this usage text
```
This can easily be used when submitting the Job to Spark (resp. Flink), e.g. for Spark

```bash
/PATH/TO/SPARK/sbin/spark-submit [spark-options] /PATH/TO/INFERENCE-SPARK-DISTRIBUTION/FILE.jar [inference-api-arguments]
```

and for Flink

```bash
/PATH/TO/FLINK/bin/flink run [flink-options] /PATH/TO/INFERENCE-FLINK-DISTRIBUTION/FILE.jar [inference-api-arguments]
```

In addition, we also provide Shell scripts that wrap the Spark (resp. Flink) deployment and can be used by first
setting the environment variable `SPARK_HOME` (resp. `FLINK_HOME`) and then calling
```bash
/PATH/TO/INFERENCE-DISTRIBUTION/bin/cli [inference-api-arguments]
```
(Note, that setting Spark (resp. Flink) options isn't supported here and has to be done via the corresponding config files)

### Example

```bash
RDFGraphMaterializer -i /PATH/TO/FILE/test.nt -o /PATH/TO/TEST_OUTPUT_DIRECTORY/ -p rdfs
```
will compute the RDFS materialization on the data contained in `test.nt` and write the inferred RDF graph to the given directory `TEST_OUTPUT_DIRECTORY`.

## Supported Reasoning Profiles

Currently, the following reasoning profiles are supported:

##### RDFS
The RDFS reasoner can be configured to work at two different compliance levels: 

###### RDFS (Default)
This implements all of the [RDFS closure rules](https://www.w3.org/TR/rdf11-mt/#patterns-of-rdfs-entailment-informative) with the exception of bNode entailments and datatypes (**rdfD 1**). RDFS axiomatic triples are also omitted. This is an expensive mode because all statements in the data graph need to be checked for possible use of container membership properties. It also generates type assertions for all resources and properties mentioned in the data (**rdf1**, **rdfs4a**, **rdfs4b**).

###### RDFS Simple

A fragment of RDFS that covers the most relevant vocabulary, prove that it
preserves the original RDFS semantics, and avoids vocabulary and axiomatic
information that only serves to reason about the structure of the language
itself and not about the data it describes.
It is composed of the reserved vocabulary
`rdfs:subClassOf`, `rdfs:subPropertyOf`, `rdf:type`, `rdfs:domain` and `rdfs:range`.
This implements just the transitive closure of `rdfs:subClassOf` and `rdfs:subPropertyOf` relations, the `rdfs:domain` and `rdfs:range` entailments and the implications of `rdfs:subPropertyOf` and `rdfs:subClassOf` in combination with instance data. It omits all of the axiomatic triples. This is probably the most useful mode but it is a less complete implementation of the standard. 

More details can be found in

Sergio Muñoz, Jorge Pérez, Claudio Gutierrez:
    *Simple and Efficient Minimal RDFS.* J. Web Sem. 7(3): 220-234 (2009)
##### OWL Horst
OWL Horst is a fragment of OWL and was proposed by Herman ter Horst [1] defining an "intentional" version of OWL sometimes also referred to as pD\*. It can be materialized using a set of rules that is an extension of the set of RDFS rules. OWL Horst is supposed to be one of the most common OWL flavours for scalable OWL reasoning while bridging the gap between the unfeasible OWL Full and the low expressiveness of RDFS.

[1] Herman J. ter Horst:
*Completeness, decidability and complexity of entailment for RDF Schema and a semantic extension involving the OWL vocabulary.* J. Web Sem. 3(2-3): 79-115 (2005)

## How to Contribute
We always welcome new contributors to the project! Please see [our contribution guide](http://sansa-stack.net/contributing-to-sansa/) for more details on how to get started contributing to SANSA.


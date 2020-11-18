name := "soot_hopper"

version := "0.1"

scalaVersion := "2.13.1"
libraryDependencies += "ca.mcgill.sable" % "soot" % "3.3.0"
libraryDependencies += "org.scalatest" %% "scalatest" % "3.1.0" % "test"
libraryDependencies += "org.slf4j" % "slf4j-simple" % "1.7.5"
libraryDependencies += "com.fasterxml.jackson.core" % "jackson-core" % "2.7.9"
libraryDependencies += "org.scala-graph" %% "graph-core" % "1.13.2"
// https://mvnrepository.com/artifact/org.scala-graph/graph-dot
libraryDependencies += "org.scala-graph" %% "graph-dot" % "1.13.0"

//libraryDependencies += "com.regblanc" %% "scala-smtlib" % "0.2.2"

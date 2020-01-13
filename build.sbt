// Code license
licenses := Seq("MIT" -> url("https://opensource.org/licenses/MIT"))

// Scalac options.
scalaVersion := "2.13.1"
scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")
scalacOptions in Test ++= Seq("-Yrangepos")

// Set up the main class.
mainClass in Compile := Some("org.renci.spec2shacl.SpecToSHACL")

resolvers ++= Seq(
  "Labra BinTray" at "https://dl.bintray.com/labra/maven/"
)

libraryDependencies ++= {
  Seq(
    // Logging
    "com.typesafe.scala-logging"  %% "scala-logging"          % "3.9.2",
    "ch.qos.logback"              %  "logback-classic"        % "1.2.3",

    // Command line argument parsing.
    "org.rogach"                  %% "scallop"                % "3.3.2",

    // Import a SHACL library.
    "org.topbraid"                % "shacl"                   % "1.3.0",

    // Import a ShEx library.
    "es.weso"                     %% "shaclex"                % "0.1.44",

    // Add support for CSV
    "com.github.tototoshi"        %% "scala-csv"              % "1.3.6"
  )
}

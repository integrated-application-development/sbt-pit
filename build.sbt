lazy val `sbt-pit` = (project in file("."))
  .settings(
    sbtPlugin := true,
    name := "sbt-pit",
    organization := "com.integradev.3rdparty",
    version := "1.1.3-SNAPSHOT",
    crossScalaVersions := Seq("2.10.7", "2.11.12", "2.12.13"),
    libraryDependencies ++= Seq(
      "org.pitest" % "pitest" % "1.6.7",
      "org.pitest" % "pitest-aggregator" % "1.6.7",
      "org.pitest" % "pitest-html-report" % "1.6.7",
      "org.pitest" % "pitest-junit5-plugin" % "0.14"
    ),
    publishMavenStyle := false
  )

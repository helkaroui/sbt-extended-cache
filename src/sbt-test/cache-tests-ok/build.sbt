import scala.collection.Seq

ThisBuild / scalaVersion := "2.12.16"
ThisBuild / organization := "com.github.helkaroui"

lazy val root = (project in file("."))
  .enablePlugins(ExtendedCachePlugin)
  .settings(
    name := "cache-test-ok",
    version := "0.1",
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.0.8" % Test,
      "org.scalactic" %% "scalactic" % "3.0.8" % Test
    ),
    scalacOptions ++= Seq(
      "-unchecked",
      "-deprecation",
      "-feature",
      "-Xfuture",
      "-Xlint"
    ),
    javaOptions ++= Seq(
      "-Xms512M",
      "-Xmx2048M",
      "-XX:MaxPermSize=2048M",
      "-XX:+CMSClassUnloadingEnabled"
    ),
    incOptions := incOptions.value
      .withIgnoredScalacOptions(
        incOptions.value.ignoredScalacOptions ++ Array(
          "-Xplugin:.*",
          "-Ybackend-parallelism [\\d]+"
        )
      ),
    cancelable in Global := true,
    Test / fork := true,
    Test / parallelExecution := false,
    Test / cacheGenerators += Def
      .task {
        val s = (Test / test / streams).value
        val succeededFile = Defaults.succeededFile(s.cacheDirectory)
        if (succeededFile.isFile) Seq(succeededFile) else Seq.empty
      }.dependsOn(Test / test).taskValue
  )

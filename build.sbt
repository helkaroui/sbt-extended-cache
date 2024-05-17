enablePlugins(SbtPlugin)

name := "sbt-extended-cache"
organization := "com.github.helkaroui"
description := "Plugin to extend sbt cache to support more than compile cache"
version := "0.0.1-SNAPSHOT"
sbtPlugin := true
scriptedLaunchOpts += ("-Dplugin.version=" + version.value)
scriptedBufferLog := false

ThisBuild / publishTo := Some("GitHub helkaroui Apache Maven Packages" at "https://maven.pkg.github.com/helkaroui/sbt-extended-cache")
ThisBuild / publishMavenStyle := true
ThisBuild / publishConfiguration := publishConfiguration.value.withOverwrite(true)
ThisBuild / Compile / packageDoc / publishArtifact := false
ThisBuild / Compile / packageSrc / publishArtifact := false

ThisBuild / versionScheme := Some("semver-spec")

ThisBuild / credentials += Credentials(
  "GitHub Package Registry",
  "maven.pkg.github.com",
  "helkaroui",
  sys.env("GITHUB_TOKEN")
)

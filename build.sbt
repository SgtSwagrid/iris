import sbt._
import sbt.Keys._
import sbtunidoc.BaseUnidocPlugin.autoImport.*
import sbtunidoc.ScalaUnidocPlugin

val scala3 = "3.9.0"

ThisBuild / scalaVersion := scala3

ThisBuild / scalacOptions ++= Seq(
  "-explain",
  "-explain-types",
  "-explain-cyclic",
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Wunused:all",
)

// Every project is named after the library so as not to clash with the
// projects of a build that includes this one by reference. Requests go through
// the JDK's HTTP client on the JVM and the Fetch API in JavaScript, each chosen
// by its platform's own `Platform`. The matrix's sources sit in their packages'
// directories rather than under a package prefix, as IntelliJ never gives one
// to the sources the JVM and JS share.
lazy val iris = projectMatrix
  .in(file("."))
  .settings(
    name := "iris",

    // A matrix resolves sources against the working directory, which is not
    // this build's base when another build includes it by reference.
    sourceDirectory := (ThisBuild / baseDirectory).value / "src",
    Dependencies.sttpClient,
    Dependencies.circe,
    Dependencies.cats,
    Dependencies.cadmus,
    Dependencies.munit,
  )
  .jvmPlatform(scalaVersions = Seq(scala3))
  .jsPlatform(scalaVersions = Seq(scala3))

lazy val irisRoot = project
  .in(file("."))
  .enablePlugins(ScalaUnidocPlugin)
  .aggregate(iris.projectRefs *)
  .settings(
    publish / skip := true,

    // The root shares the matrix's directory, so must not build its sources.
    Compile / unmanagedSourceDirectories   := Nil,
    Test / unmanagedSourceDirectories      := Nil,
    Compile / unmanagedResourceDirectories := Nil,
    Test / unmanagedResourceDirectories    := Nil,

    // The JS side would only document the same sources again.
    ScalaUnidoc / unidoc / unidocProjectFilter := inProjects(iris.jvm(scala3)),
    ScalaUnidoc / unidoc / scalacOptions ++= Seq("-project", "Iris"),
  )

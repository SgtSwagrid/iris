import sbt._
import sbt.Keys._
import sbtunidoc.BaseUnidocPlugin.autoImport.*
import sbtunidoc.ScalaUnidocPlugin
import org.scalajs.jsenv.nodejs.NodeJSEnv

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
// projects of a build that includes this one by reference. Each module is a
// matrix built for the JVM and JavaScript, its sources in their packages'
// directories rather than under a package prefix, as IntelliJ never gives one
// to the sources the JVM and JS share.
def module(matrix: ProjectMatrix, directory: String): ProjectMatrix = matrix
  .settings(
    name := s"iris-${ file(directory).getName }",

    // A matrix resolves sources against the working directory, which is not
    // this build's base when another build includes it by reference.
    sourceDirectory := (ThisBuild / baseDirectory).value / directory / "src",
    Dependencies.munit,
  )
  .jvmPlatform(scalaVersions = Seq(scala3))
  .jsPlatform(
    scalaVersions = Seq(scala3),
    // So that a test may collect garbage, as `CollectionSuite` does.
    settings = Seq(
      Test / jsEnv := Def.uncached(
        new NodeJSEnv(NodeJSEnv.Config().withArgs(List("--expose-gc"))),
      ),
    ),
  )

// The layers, conversations, usage and schemas, with no provider and no HTTP.
lazy val irisCore = module(projectMatrix.in(file("core")), "core").settings(
  Dependencies.circe,
  Dependencies.cats,
  Dependencies.fs2,
  Dependencies.cadmus,
)

// The api layer, and what every provider shares over HTTP: requests go through
// the JDK's HTTP client on the JVM and the Fetch API in JavaScript, each chosen
// by its platform's own `Platform`.
lazy val irisHttp =
  module(projectMatrix.in(file("providers/http")), "providers/http")
    .dependsOn(irisCore)
    .settings(Dependencies.sttpClient)

lazy val irisAnthropic =
  module(projectMatrix.in(file("providers/anthropic")), "providers/anthropic")
    .dependsOn(irisHttp)

lazy val irisOpenAi =
  module(projectMatrix.in(file("providers/openai")), "providers/openai")
    .dependsOn(irisHttp)

lazy val irisGemini =
  module(projectMatrix.in(file("providers/gemini")), "providers/gemini")
    .dependsOn(irisHttp)

// Every provider, and choosing one at runtime. Its tests reach every module,
// and core's example types.
lazy val irisProviders = module(projectMatrix.in(file("providers")), "providers")
  .dependsOn(
    irisAnthropic,
    irisOpenAi,
    irisGemini,
    irisCore % "test->test",
  )

lazy val modules =
  Seq(irisCore, irisHttp, irisAnthropic, irisOpenAi, irisGemini, irisProviders)

lazy val irisRoot = project
  .in(file("."))
  .enablePlugins(ScalaUnidocPlugin)
  .aggregate(modules.flatMap(_.projectRefs) *)
  .settings(
    publish / skip := true,

    // The JS side would only document the same sources again.
    ScalaUnidoc / unidoc / unidocProjectFilter :=
      inProjects(modules.map(module => module.jvm(scala3): ProjectReference) *),
    ScalaUnidoc / unidoc / scalacOptions ++= Seq("-project", "Iris"),
  )

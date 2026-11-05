import sbt.*
import sbt.Keys.*

object Dependencies:

  object V:

    val sttpClient = "4.1.0"
    val circe      = "0.14.17"
    val cats       = "2.13.0"
    val catsEffect = "3.7.1"
    val munit      = "1.3.6"
    val cadmus     = "0.1.1"
    val fs2        = "3.14.0"

  lazy val sttpClient = libraryDependencies ++= Seq(
    "com.softwaremill.sttp.client4" %% "core" % V.sttpClient,
    "com.softwaremill.sttp.client4" %% "cats" % V.sttpClient,
    "com.softwaremill.sttp.client4" %% "fs2"  % V.sttpClient,
  )

  lazy val circe = libraryDependencies ++= Seq(
    "io.circe" %% "circe-core"   % V.circe,
    "io.circe" %% "circe-parser" % V.circe,
  )

  lazy val fs2 = libraryDependencies += "co.fs2" %% "fs2-core" % V.fs2

  lazy val cats = libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-core"           % V.cats,
    "org.typelevel" %% "cats-effect"         % V.catsEffect,
    "org.typelevel" %% "cats-effect-testkit" % V.catsEffect % Test,
  )

  lazy val cadmus =
    libraryDependencies += "com.alecdorrington" %% "cadmus" % V.cadmus

  lazy val munit = libraryDependencies ++=
    Seq("org.scalameta" %% "munit" % V.munit % Test)

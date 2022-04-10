//> using lib "org.typelevel::cats-effect:3.3.11"
//> using lib "co.fs2::fs2-io:3.2.7"
//> using scala "3.1.1"
import cats.effect.IOApp
import cats.effect.IO
import java.nio.file.Files
import java.nio.file.Paths
import cats.effect.ExitCode
import fs2.Stream
import scala.concurrent.duration.FiniteDuration
import java.nio.file.Path

case class Project(
    org: String,
    name: String,
    compileCommand: List[String],
    cleanCommand: List[String],
    shouldClone: Boolean
) {

  def show: String = s"$org/$name"

  val benchmarkRoot: Path = Paths.get(s"..")
  val projectRoot = benchmarkRoot.resolve(name)

  def exists: IO[Boolean] = IO.blocking(Files.isDirectory(projectRoot))

  def cloneFromGithub: IO[Unit] = IO
    .interruptibleMany(run("gh" :: "clone" :: show :: Nil, benchmarkRoot))

  def withClone(shouldClone: Boolean): Project =
    this.copy(shouldClone = shouldClone)

  def withCompileCommand(command: List[String]): Project =
    this.copy(compileCommand = command)

  def withSbtPrefix(prefix: List[String]) =
    copy(
      compileCommand = prefix ++ compileCommand,
      cleanCommand = prefix ++ cleanCommand
    )

  private def run(command: List[String], where: Path) = {

    import sys.process._

    IO.interruptibleMany {
      new java.lang.ProcessBuilder(command: _*)
        .directory(where.toFile)
        .inheritIO
        .!!
    }.void
  }

  def compile: IO[Unit] = run(compileCommand, projectRoot)
  def clean: IO[Unit] = run(cleanCommand, projectRoot)
}

def sbtProject(org: String, name: String) =
  Project(
    org,
    name,
    List("sbt", "compile"),
    List("sbt", "clean"),
    shouldClone = true
  )

object Projects {
  val scalatest = sbtProject("scalatest", "scalatest")
  val cats = sbtProject("typelevel", "cats")
  val catsEffect = sbtProject("typelevel", "cats-effect")
  val fs2 = sbtProject("typelevel", "fs2")
  val metals = sbtProject("scalameta", "metals")
  val steve = sbtProject("kubukoz", "steve")
  val scalaSteward = sbtProject("scala-steward", "scala-steward")
  val smithy4s = sbtProject("disneystreaming", "smithy4s")
  val weaver = sbtProject("disneystreaming", "weaver-test")
  val trading = sbtProject("gvolpe", "trading")
  val zio = sbtProject("zio", "zio")

  val workProject = sbtProject("kubukoz", "work-project")
    .withCompileCommand("sbt" :: "IntegrationTest/compile;Test/compile" :: Nil)
    .withSbtPrefix(
      "nix" :: "develop" :: "--command" :: Nil
    )
    .withClone(false)

  val scala = sbtProject("scala", "scala")
  val dotty = sbtProject("lampepfl", "dotty")
  val fs2Aws = sbtProject("laserdisc-io", "fs2-aws")
  val zioAws = sbtProject("vigoo", "zio-aws").withCompileCommand(
    "sbt" :: "-J-XX:+UseG1GC" :: "-J-Xmx8g" :: "-J-Xms8g" :: "-J-Xss16m" :: "all/compile" :: Nil
  )
}

val projects = List(
  Projects.scalatest,
  Projects.cats,
  Projects.catsEffect,
  Projects.fs2,
  Projects.metals,
  Projects.steve,
  Projects.scalaSteward,
  Projects.smithy4s,
  Projects.weaver,
  Projects.trading,
  Projects.zio,
  Projects.workProject,
  Projects.scala,
  Projects.dotty,
  Projects.fs2Aws
  // Projects.zioAws
)

import cats.implicits._
import cats.effect.implicits._

case class Result(proj: Project, times: List[FiniteDuration]) {
  def render: String =
    (proj.name :: times.map(_.toSeconds.toString))
      .mkString(", ")
}

object Main extends IOApp {
  def run(args: List[String]) = {

    val target = fs2.io.file.Path(
      args.headOption.getOrElse(
        sys.error("target path not defined, pass an arg!")
      )
    )

    val rounds = 10

    val shouldWarmup = !sys.env.contains("NO_WARMUP")

    val shouldCompile = !sys.env.contains("NO_COMPILE")

    val cloneStep: fs2.Pipe[IO, Project, Project] = _.evalTap { proj =>
      proj.exists.ifM(
        ifTrue = IO.unit,
        ifFalse = (
          IO.println(s"Cloning ${proj.show}") *>
            proj.cloneFromGithub
        ).whenA(proj.shouldClone)
      )
    }

    val warmupStep: fs2.Pipe[IO, Project, Project] =
      if (shouldWarmup)
        _.parEvalMap(3) { proj =>
          IO.println("Warming up " + proj.show) *>
            proj.compile.onError { case e =>
              cats.effect.std
                .Console[IO]
                .errorln("Failed to warmup " + proj.show)
            } *>
            IO.println("Warmed up " + proj.show)
              .as(proj)
        }
          .append(Stream.exec(IO.println("Warmed up all projects")))
      else identity

    def compileOne(proj: Project): IO[Result] = {
      IO.println("Starting process for " + proj.show) *>
        (1 to rounds).toList
          .traverse { round =>
            proj.clean *>
              proj.compile.timed
                .map(_._1)
                .flatTap { result =>
                  IO.println(
                    s"RUNNER>>> Compiled ${proj.show} in ${result.toSeconds}s (round $round)"
                  )
                }
          }
          .map(Result(proj, _))
    }

    val thenCompile: fs2.Pipe[IO, Project, Result] =
      if (shouldCompile)
        _.evalMap(compileOne)
      else _.drain

    val results = Stream
      .emits(projects)
      .through(cloneStep)
      .through(warmupStep)
      // Wait for all projects to clean before proceeding
      .bufferAll
      .through(thenCompile)

    Stream
      .emit("name, times")
      .append(results.map(_.render))
      .intersperse("\n")
      .append(Stream.emit("\n"))
      .through(fs2.text.utf8.encode[IO])
      .through(fs2.io.file.Files[IO].writeAll(target))
      .compile
      .drain
      .as(ExitCode.Success)
  }
}

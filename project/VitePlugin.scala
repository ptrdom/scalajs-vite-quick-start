import java.nio.file.Files

import org.apache.commons.io.FileUtils
import org.scalajs.sbtplugin.ScalaJSPlugin
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.fastLinkJS
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.fullLinkJS
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.scalaJSLinkerOutputDirectory
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.scalaJSStage
import org.scalajs.sbtplugin.Stage
import sbt.AutoPlugin
import sbt.Def.Initialize
import sbt.Def.task
import sbt.Def.taskDyn
import sbt.Keys._
import sbt.Project.projectToRef
import sbt._
import sbt.internal.util.ManagedLogger
import sbt.nio.Keys.watchBeforeCommand
import sbt.nio.Keys.watchOnIteration
import sbt.nio.Keys.watchOnTermination

import scala.collection.immutable.ListSet
import scala.jdk.CollectionConverters._
import scala.sys.process.ProcessLogger
import scala.sys.process.{Process => ScalaProcess}

object VitePlugin extends AutoPlugin {

  override def requires = ScalaJSPlugin

  object autoImport {
    val viteCopyResources: TaskKey[Unit] = taskKey[Unit]("")
    val viteInstall: TaskKey[Unit] =
      taskKey[Unit](
        "Copies over resources to target directory and runs `npm install`"
      )
    val viteCompile: TaskKey[Unit] =
      taskKey[Unit](
        "Compiles module and copies output to target directory."
      )

    val startVite = taskKey[Unit]("Runs `vite` on target directory.")
    val stopVite = taskKey[Unit]("Stops running `vite` on target directory.")

    val viteBuild = taskKey[Unit]("Runs `vite build` on target directory.")

    val startVitePreview =
      taskKey[Unit]("Runs `vite preview` on target directory.")
    val stopVitePreview =
      taskKey[Unit]("Stops running `vite preview` on target directory.")
  }

  import autoImport._

  private def cmd(name: String) = sys.props("os.name").toLowerCase match {
    case os if os.contains("win") => "cmd" :: "/c" :: name :: Nil
    case _                        => name :: Nil
  }

  private lazy val scalaJSTaskFiles = onScalaJSStage(
    Def.task {
      fastLinkJS.value
      (fastLinkJS / scalaJSLinkerOutputDirectory).value
    },
    Def.task {
      fullLinkJS.value
      (fullLinkJS / scalaJSLinkerOutputDirectory).value
    }
  )

  private def onScalaJSStage[A](
      onFastOpt: => Initialize[A],
      onFullOpt: => Initialize[A]
  ): Initialize[A] =
    Def.settingDyn {
      scalaJSStage.value match {
        case Stage.FastOpt => onFastOpt
        case Stage.FullOpt => onFullOpt
      }
    }

  private def eagerLogger(log: ManagedLogger) = {
    ProcessLogger(
      out => log.info(out),
      err => log.error(err)
    )
  }

  private class ProcessWrapper(
      val process: Process,
      val stdoutThread: Thread,
      val stderrThread: Thread
  )

  private def viteTask(
      start: TaskKey[Unit],
      stop: TaskKey[Unit],
      command: String
  ) = {
    var processWrapper: Option[ProcessWrapper] = None

    def terminateProcess() = {
      processWrapper.foreach { processWrapper =>
        processWrapper.stdoutThread.interrupt()
        processWrapper.stderrThread.interrupt()
        // TODO consider using reflection to keep JDK 8 compatibility
        processWrapper.process
          .descendants() // requires JDK 9+
          .forEach(process => process.destroy())
        processWrapper.process.destroy()
      }
      processWrapper = None
    }

    Seq(
      start := {
        val logger = state.value.globalLogging.full
        logger.info("Starting Vite")

        stopVite.value

        viteCompile.value

        val targetDir = (viteInstall / crossTarget).value

        // using Java Process to use `descendants`
        val pb =
          new ProcessBuilder(cmd("npm") ::: "run" :: command :: Nil: _*)
        pb.directory(targetDir)
        val p = pb.start()
        val stdoutThread = new Thread() {
          override def run(): Unit = {
            scala.io.Source
              .fromInputStream(p.getInputStream)
              .getLines
              .foreach(msg => logger.info(msg))
          }
        }
        stdoutThread.start()
        val stderrThread = new Thread() {
          override def run(): Unit = {
            scala.io.Source
              .fromInputStream(p.getErrorStream)
              .getLines
              .foreach(msg => logger.error(msg))
          }
        }
        stderrThread.start()
        processWrapper = Some(new ProcessWrapper(p, stdoutThread, stderrThread))
      },
      stop := {
        terminateProcess()
      }
    )
  }

  override lazy val projectSettings: Seq[Setting[_]] =
    inConfig(Compile)(perConfigSettings) ++
      inConfig(Test)(perConfigSettings)

  private lazy val perConfigSettings: Seq[Setting[_]] = Seq(
    viteInstall / crossTarget := {
      crossTarget.value /
        "vite" /
        (if (configuration.value == Compile) "main" else "test")
    },
    viteCopyResources := {
      val s = streams.value

      val targetDir = (viteInstall / crossTarget).value

      def copyChanges(directory: File): Unit = {
        s.log.debug(s"walking ${directory.getAbsolutePath}")
        Files
          .walk(directory.toPath)
          .iterator()
          .asScala
          .map(_.toFile)
          .filter(file => file.getAbsolutePath != directory.getAbsolutePath)
          .foreach { file =>
            if (file.isDirectory) {
              copyChanges(file)
            } else {
              val targetFile = new File(
                file.getAbsolutePath.replace(
                  (baseDirectory.value / "vite").getAbsolutePath,
                  targetDir.getAbsolutePath
                )
              )
              if (!Hash(file).sameElements(Hash(targetFile))) {
                s.log.debug(
                  s"File changed [${file.getAbsolutePath}], copying [${targetFile.getAbsolutePath}]"
                )
                IO.copyFile(
                  file,
                  targetFile
                )
                true
              } else {
                s.log.debug(s"File not changed [${file.getAbsolutePath}]")
                false
              }
            }
          }
      }
      copyChanges(baseDirectory.value / "vite")
    },
    watchSources := (watchSources.value ++ Seq(
      Watched.WatchSource(baseDirectory.value / "vite")
    )),
    viteInstall := {
      viteCopyResources.value

      val s = streams.value

      val targetDir = (viteInstall / crossTarget).value

      val lockFile = "package-lock.json"

      FileFunction.cached(
        streams.value.cacheDirectory /
          "vite" /
          (if (configuration.value == Compile) "main" else "test"),
        inStyle = FilesInfo.hash
      ) { filesToCopy =>
        filesToCopy
          .filter(_.exists())
          .foreach(file => IO.copyFile(file, targetDir / file.getName))

        ScalaProcess(cmd("npm") ::: "install" :: Nil, targetDir)
          .run(eagerLogger(s.log))
          .exitValue()

        IO.copyFile(
          targetDir / lockFile,
          baseDirectory.value / "vite" / lockFile
        )

        Set.empty
      }(
        Set(baseDirectory.value / "vite" / lockFile)
      )
    },
    viteCompile := {
      viteInstall.value

      val targetDir = (viteInstall / crossTarget).value

      scalaJSTaskFiles.value
        .listFiles()
        .foreach(file => IO.copyFile(file, targetDir / file.name))
    },
    viteBuild := {
      viteCompile.value

      val logger = state.value.globalLogging.full

      val targetDir = (viteInstall / crossTarget).value

      ScalaProcess(cmd("npm") ::: "run" :: "build" :: Nil, targetDir)
        .run(eagerLogger(logger))
        .exitValue()
    }
    // TODO figure out what makes sense here, might need to run viteBuild instead of just compile
    // (Compile / compile) := ((Compile / compile) dependsOn viteCompile).value
  ) ++ viteTask(
    startVite,
    stopVite,
    "dev"
  ) ++ viteTask(
    startVitePreview,
    stopVitePreview,
    "preview"
  )
}

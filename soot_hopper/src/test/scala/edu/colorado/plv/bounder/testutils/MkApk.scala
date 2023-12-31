package edu.colorado.plv.bounder.testutils

import better.files._
import edu.colorado.plv.bounder.BounderUtil
import edu.colorado.plv.bounder.lifestate.LifeState.Signature
import edu.colorado.plv.bounder.solver.StateSolver
import edu.colorado.plv.bounder.symbolicexecutor.AbstractInterpreter
import edu.colorado.plv.bounder.symbolicexecutor.state.Reachable
import org.slf4j.LoggerFactory

import sys.process._

object MkApk {
  def matcherToLine[M,C](matchers: Iterable[(String, String, String)],
                         src:String, interp: AbstractInterpreter[M,C]): Iterable[Int] = {
    matchers.map { case (matcher, clazz, method) =>
      val query1Line = BounderUtil.lineForRegex(matcher.r, src)
      val initialQuery = Reachable(Signature(clazz, method),
        query1Line)
      val query = initialQuery.make(interp)
      assert(query.nonEmpty)
      query1Line
    }
  }
  private val logger = LoggerFactory.getLogger("MkApk.scala")
  val RXBase = getClass.getResource("/CreateDestroySubscribe.zip").getPath
  val RXBase2 = getClass.getResource("/ReactiveX.zip").getPath
  val RXBoth =  getClass.getResource("/RxAndRxJav.zip").getPath

  /**
   *
   * @param sources map from file name to source contents
   * @param targetProject tar.gz of project to add test file
   * @param applyWithApk operation to perform on apk
   * @return result of applyWithApk
   */
  def makeApkWithSources[U](sources: Map[String,String], targetProject: String, applyWithApk: String => U):Option[U] ={
    var res:Option[U] = None
    File.usingTemporaryDirectory(){(dir:File) =>
      val baseFile = File(targetProject)
//      val copiedBase = baseFile.copyToDirectory(dir)
      val unZip = Dsl.unzip(baseFile)(dir)
      val appName = dir.glob("*").toList
      assert(appName.size == 1, s"Tmp folder should only have extracted project. Found: ${appName.mkString(" ")}")
      val appNameGotten = appName.head.name
      val appDir: File = dir / appNameGotten
      val srcDir =
        appDir / "app" / "src" / "main" / "java" / "com" / "example" / "createdestroy"

      sources.map{
        case (filename,v) =>
          (srcDir / filename).appendLines(v)
      }

      try {
        val res1 = Process("chmod +x gradlew", appDir.toJava).!!
        logger.info(s"Chmod output: $res1")
        import sys.process._

        val stdout = new StringBuilder
        val stderr = new StringBuilder
//        val status = "ls -al FRED" ! ProcessLogger(stdout append _, stderr append _)

        // Allow user to specify a non-default version of java to use
        sys.env.get("JENV_VERSION") match {
          case Some(version) =>
            if (jenvVersionIsValid(version)) {
              Process(List("jenv", "local", version)) ! ProcessLogger(v => stdout.append(v + "\n"),
                v => stderr.append(v + "\n"))
              logger.info(s"jenv stdout: $stdout")
              logger.info(s"jenv stderr: $stderr")
            } else {
              throw new RuntimeException("Invalid characters in JENV_VERSION. A jenv version should only contain"
                + " alphanumeric characters along with periods and dashes.")
            }
          case _ => ()
        }

        Process("./gradlew assembleDebug", appDir.toJava) ! ProcessLogger(v => stdout.append(v + "\n"),
          v => stderr.append(v + "\n"))
        val errString = stderr.toString
        logger.info(s"Gradle stdout: $stdout")
        logger.info(s"Gradle stderr: $errString")
        if(errString.contains("FAILURE: Build failed with an exception."))
          throw new IllegalArgumentException(errString)
        val apkFile = appDir / "app" / "build" / "outputs/apk/debug/app-debug.apk"
        res = Some(applyWithApk(apkFile.toString))
      }catch{
        case e:RuntimeException =>
          logger.error("FAILED BUILDING TEST APK")
          logger.error(e.toString)
          logger.error(e.getStackTrace.mkString("\n    "))
          throw e
      }
    }
    res
  }

  def jenvVersionIsValid(version: String): Boolean = {
    // Only allow characters known to be in the jenv names. This should prevent trivial injection attacks.
    val allowedChars: String = ('a' to 'z').mkString + ('A' to 'Z').mkString + ('0' to '9').mkString + '-' + '.'
    version.forall(allowedChars.contains(_))
  }
}
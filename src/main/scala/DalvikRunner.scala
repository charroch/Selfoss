package novoda.sbt

import java.io.File
import sbt._
import sbt.Keys._
import sbt.Build._

object DalvikPlugin extends sbt.Plugin {

  val Dalvik = config("dalvik") extend (Runtime)

  object DalvikKeys {
    val androidSource = SettingKey[File]("android-source", "root directory pointing to the source for Android")
    val dexDirectory = SettingKey[File]("dex-directory", "location of dex files")
    val bootclasspath = SettingKey[Seq[File]]("bootclasspath", "dex bootclasspath")
    val dalvikvm = SettingKey[File]("dalvikvm", "location of the dalvik vm")
    val environment = SettingKey[Seq[(String, String)]]("environment", "env variables for dalvikvm")
    val lib = SettingKey[File]("lib", "compiled so library folder")
    val androidData = SettingKey[File]("android-data", "location of android data where cache dex will be saved")
    val androidRoot = SettingKey[File]("android-root", "root folder of the compiled android source")
    val options = SettingKey[Seq[String]]("options", "Options for dalvikvm")
  }

  import DalvikKeys._

  def dalvikSettings: Seq[Setting[_]] = Seq(
    dalvikvm <<= androidSource(_ / "out/host/linux-x86/bin/dalvikvm"),
    bootclasspath <<= androidSource(bootjars(_).get),
    androidRoot <<= androidSource(_ / "out/host/linux-x86/"),
    lib <<= androidSource(_ / "out/target/product/generic_x86/system/lib/"),
    androidData <<= cacheDirectory(_ / "android-data" / "dalvik-cache"),
    environment in Dalvik := Seq(
      "ANDROID_PRINTF_LOG" -> "tag",
      "ANDROID_LOG_TAGS" -> "*:i",
      "ANDROID_DATA" -> "/tmp/test/android-data",
      "ANDROID_ROOT" -> "/var/android/linux-x86",
      "LD_LIBRARY_PATH" -> "/var/android/lib",
      "DYLD_LIBRARY_PATH" -> "/var/android/lib"
    ),

    options <<= (androidData) {
      (androidData: File) => {
        //"-Duser.dir=/tmp/test/" :: "-Djava.io.tmpdir=/tmp/test/" :: "-Xbootclasspath::/var/android/bootjars/system/framework/framework.jar:/var/android/framework/core-hostdex.jar:/var/android/framework/bouncycastle-hostdex.jar:/var/android/framework/apache-xml-hostdex.jar" :: "-Duser.language=en" :: "-Duser.region=US" :: "-Xverify:none" :: "-Xdexopt:none" :: "-Xcheck:jni" :: "-Xjnigreflimit:2000" :: Nil
        Seq("hello", "hello")
      }
    },

    dexDirectory <<= cacheDirectory(_ / "dex"),

    androidData <<= cacheDirectory(_ / "android-data"),

    runner in(Dalvik, run) <<= (dalvikvm, options) map {
      (dalvikvm: File, options: Seq[String]) =>
        new DalvikRunner(dalvikvm, options)
    },
    //run <<= runTask(fullClasspath, mainClass in run, runner in run),
    run <<= runInputTask2(Dalvik, "HelloWorld", ""),
    fullClasspath in (Dalvik) <<= (fullClasspath in Runtime, dexDirectory, streams) map {
      (cp: Classpath, cache: File, s: TaskStreams) => {
        cp.filterNot((b: Attributed[File]) => b.data.toString.contains("android"))
        cp.map((a: Attributed[File]) => dex(a.data, cache)(s.log)).flatten
      }
    }
  )

  def runInputTask2(config: Configuration, mainClass: String, baseArguments: String*) =
    inputTask {
      result =>
        (fullClasspath in config, runner in(config, run), streams, result) map {
          (cp, r, s, args) =>
            toError(r.run(mainClass, data(cp), baseArguments ++ args, s.log))
        }
    }

  def bootjars(base: File): PathFinder = (base / "out/target/product/generic_x86/dex_bootjars/system/framework/") ** "*.jar"

  type Folder = File;

  def dex(jar: File, to: Folder)(s: sbt.Logger): Classpath = {
    val outputFolder = new File(to, jar.getName)
    if (outputFolder.exists()) {
      if (jar.lastModified() < outputFolder.lastModified()) {
        s.info("%s already dexed" format jar.getName)
      }
    } else {
      outputFolder.mkdirs
      s.info("dexing %s to %s" format(jar.getName, outputFolder.getPath + "/" + jar.getName + ".dex"))

      "dx --dex --output=%s/%s %s".format(outputFolder.getAbsolutePath, jar.getName + ".dex", jar.getAbsolutePath).! match {
        case 0 => s.info("%s dexed correctly" format (jar.getName + ".dex"))
        case 2 => {
          s.warn("Failure to dex %s, will try to split into smaller jars" format jar)
          split(jar, outputFolder)(s) foreach (dex(_, outputFolder)(s));
        }
        case _ => s.error("a fatal error occured")
      }
    }
    dexFiles(outputFolder)
  }

  def createFileStructure(androidData: File) {
    new File(androidData, "dalvik-cache").mkdirs()
  }

  def split(jar: File, to: File)(logger: sbt.Logger): Seq[File] = {
    new JarSplitter(jar, to, 1024 * 10 * 10 * 10 * 3, true, 1, new java.util.HashSet[String](), logger).run
    splitFiles(to)
  }

  def dexFiles(base: File): Classpath = {
    val finder: PathFinder = (base) ** "*.dex"
    finder.classpath
  }

  def splitFiles(base: File): Seq[File] = {
    val finder: PathFinder = (base) ** "*.split.jar"
    finder.get
  }

}


class DalvikRunner(dalvikvm: File, options: Seq[String], extraEnv: (String, String)*) extends sbt.ScalaRun {

  override def run(mainClass: String, classpath: Seq[File], options: Seq[String], log: sbt.Logger) = {
    log.info("this" + options.mkString)
    classpath.foreach(println)
    Process(
      dalvikvm.getAbsolutePath :: "-classpath" :: classpath.mkString(":")
        :: "-Duser.dir=/tmp/test/"
        :: "-Djava.io.tmpdir=/tmp/test/"
        :: "-Xbootclasspath::/var/android/bootjars/system/framework/framework.jar:/var/android/framework/core-hostdex.jar:/var/android/framework/bouncycastle-hostdex.jar:/var/android/framework/apache-xml-hostdex.jar"
        :: "-Duser.language=en"
        :: "-Duser.region=US"
        :: "-Xverify:none"
        :: "-Xdexopt:none"
        :: "-Xcheck:jni"
        :: "-Xjnigreflimit:2000"
        :: "HelloWorld" :: Nil
      , Path.userHome, extraEnv).!(log) match {
      case 0 => None
      case _ => Some("failure to run")
    }
  }
}
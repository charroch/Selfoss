package novoda.sbt

import java.io.File
import sbt._
import sbt.Keys._

object DalvikPlugin extends sbt.Plugin {

  val Dalvik = config("dalvik") extend (Runtime)

  /*
    lazy val root = Project(id = "hello",
    base = file("."),
    settings = Project.defaultSettings ++ Seq(
      dexDirectory <<= cacheDirectory(_ / "dex"),
      runner in(Dalvik, run) := new DalvikRunner,
      run <<= runInputTask(Dalvik, "", ""),

      fullClasspath in (Dalvik) <<= (fullClasspath in Runtime, dexDirectory, streams) map {
        (cp: Classpath, cache, s) => {
          cp.filterNot((b: Attributed[File]) => b.data.toString.contains("android"))
          cp.map((a: Attributed[File]) => dex(a.data, cache)(s.log)).flatten
        }
      }
    )
  )
   */
  object DalvikKeys {
    val androidSource = SettingKey[File]("android-source", "root directory pointing to the source for Android")
    val dexDirectory = SettingKey[File]("dex-directory", "location of dex files")

    val dalvikVM = SettingKey[File]("dalvik-VM", "location of the dalvik vm")
    val environment = SettingKey[Seq[(String, String)]]("environment", "env variables for dalvikvm")
    val lib = SettingKey[File]("lib", "compiled so library folder")
    val androidData = SettingKey[File]("android-data", "location of android data where cache dex will be saved")
    val androidRoot = SettingKey[File]("android-root", "root folder of the compiled android source")

  }

  def dalvikSettings: Seq[Setting[_]] = Seq(
    environment in dalvik := Seq(
      "ANDROID_PRINTF_LOG" -> "tag",
      "ANDROID_LOG_TAGS" -> "*:i",
      "ANDROID_DATA" -> "/tmp/test/android-data",
      "ANDROID_ROOT" -> "/var/android/linux-x86",
      "LD_LIBRARY_PATH" -> "/var/android/lib",
      "DYLD_LIBRARY_PATH" -> "/var/android/lib"
    ),
    fullClasspath in (Dalvik) <<= (fullClasspath in Runtime, dexDirectory, streams) map {
      (cp: Classpath, cache: File, s: Stream) => {
        cp.filterNot((b: Attributed[File]) => b.data.toString.contains("android"))
        cp.map((a: Attributed[File]) => dex(a.data, cache)(s.log)).flatten
      }
    }
  )


  def dex(file: File, to: File)(s: sbt.Logger): Classpath = {
    to.mkdirs()
    s.info("dexing %s to %s" format(file.getName, to.getPath))
    "dx --dex --output=%s/%s %s".format(to.getAbsolutePath, file.getName + ".dex", file.getAbsolutePath).! match {
      case 0 => s.info("%s dexed correctly" format (file.getName + ".dex"))
      case 2 => {
        s.warn("Failure to dex %s, will try to split into smaller jars" format file)
        split(file, to)(s) foreach (dex(_, to)(s));
      }
      case _ => s.error("a fatal error occured")
    }
    dexFiles(to)
  }

  def split(jar: File, to: File)(logger: sbt.Logger): Seq[File] = {
    new JarSplitter(jar, new File("/tmp/test"), 1024 * 10 * 10 * 10 * 3, true, 1, new java.util.HashSet[String](), logger).run
    splitFiles(new File("/tmp/test"))
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


class DalvikRunner(dalvikVM: File) extends sbt.ScalaRun {

  override def run(mainClass: String, classpath: Seq[File], options: Seq[String], log: sbt.Logger) = {
    log.info("this" + mainClass)
    classpath.foreach(println)
    Process(
      "/var/android/linux-x86/bin/dalvikvm" :: "-classpath" :: classpath.mkString(":")
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
      , Path.userHome,
      "ANDROID_PRINTF_LOG" -> "tag",
      "ANDROID_LOG_TAGS" -> "*:i",
      "ANDROID_DATA" -> "/tmp/test/android-data",
      "ANDROID_ROOT" -> "/var/android/linux-x86",
      "LD_LIBRARY_PATH" -> "/var/android/lib",
      "DYLD_LIBRARY_PATH" -> "/var/android/lib").!(log) match {
      case 0 => None
      case _ => Some("failure to run")
    }
  }
}
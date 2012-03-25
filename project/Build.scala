import sbt._
import sbt.Keys._
import std.TaskStreams


/**
 * ANDROID_PRINTF_LOG=tag ANDROID_LOG_TAGS=*:i ANDROID_DATA=/tmp/test/android-data ANDROID_ROOT=/var/android/linux-x86 LD_LIBRARY_PATH=/var/android/lib DYLD_LIBRARY_PATH=/var/android/lib /var/android/linux-x86/bin/dalvikvm -classpath /var/android/bootjars/system/framework/framework.jar:/tmp/test/classes.dex:/tmp/test/scala-library-3.split.jar.dex:/tmp/test/scala-library-6.split.jar.dex:/tmp/test/scala-library-9.split.jar.dex:/tmp/test/scala-library-1.split.jar.dex:/tmp/test/scala-library-4.split.jar.dex:/tmp/test/scala-library-7.split.jar.dex:/tmp/test/scala-library-2.split.jar.dex:/tmp/test/scala-library-5.split.jar.dex:/tmp/test/scala-library-8.split.jar.dex -Duser.dir=/tmp/test/ -Djava.io.tmpdir=/tmp/test/ -Xbootclasspath::/var/android/framework/core-hostdex.jar:/var/android/framework/bouncycastle-hostdex.jar:/var/android/framework/apache-xml-hostdex.jar -Duser.language=en -Duser.region=US -Xverify:none -Xdexopt:none -Xcheck:jni -Xjnigreflimit:2000 HelloWorld
 */
object HelloBuild extends Build {

  val dexDirectory = SettingKey[File]("dex-directory", "location of dex files")
  val Dalvik = config("dalvik") extend (Runtime)

  override lazy val settings = super.settings ++
    Seq(resolvers := Seq())

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

  class DalvikRunner extends sbt.ScalaRun {
    override def run(mainClass: String, classpath: Seq[File], options: Seq[String], log: Logger) = {
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

}


import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Set
import java.util.jar.JarEntry
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.logging.Logger

object JarSplitter {
  private final val EXT: String = ".jar"
  private final val INDEX_FILE: String = "INDEX.LIST"
  private final val READ_BUFFER_SIZE_BYTES: Int = 8192
  private final val FILE_BUFFER_INITIAL_SIZE_BYTES: Int = 524288
}

class JarSplitter {
  def this(inputJar: File, outputDirectory: File, maximumSize: Int, replicateManifests: Boolean, outputDigits: Int, excludes: Set[String], logger: sbt.Logger) {
    this()
    this.inputJar = inputJar
    this.outputDirectory = outputDirectory
    this.maximumSize = maximumSize
    this.replicateManifests = replicateManifests
    this.outputDigits = outputDigits
    this.excludes = excludes
    this.logger = logger
  }

  def run: Unit = {
    this.outputDirectory.mkdirs
    var inputStream: JarInputStream = new JarInputStream(new BufferedInputStream(new FileInputStream(this.inputJar)))
    var manifest: Manifest = inputStream.getManifest
    var manifestSize: Long = 0L
    if ((manifest != null) && (this.replicateManifests)) {
      manifestSize = getManifestSize(manifest)
    }
    this.currentStream = newJarOutputStream(manifest)
    this.currentSize = manifestSize
    var readBuffer: Array[Byte] = new Array[Byte](8192)
    var fileBuffer: ByteArrayOutputStream = new ByteArrayOutputStream(524288)
    var entry: JarEntry = null
    while ((({
      entry = inputStream.getNextJarEntry;
      entry
    })) != null) {
      var name: String = entry.getName
      if (shouldIncludeFile(name)) {
        var newEntry: JarEntry = new JarEntry(entry.getName)
        newEntry.setTime(entry.getTime)
        fileBuffer.reset
        readIntoBuffer(inputStream, readBuffer, fileBuffer)
        var size: Long = fileBuffer.size
        if (this.currentSize + size >= this.maximumSize) {
          logger.info("Closing file after writing " + this.currentSize + " bytes.")
          beginNewOutputStream(manifest, manifestSize)
        }
        logger.debug("Copying entry: " + name + " (" + size + " bytes)")
        this.currentStream.putNextEntry(newEntry)
        fileBuffer.writeTo(this.currentStream)
        this.currentSize += size
      }
    }
    inputStream.close
    logger.info("Closing file after writing " + this.currentSize + " bytes.")
    this.currentStream.close
  }

  private def shouldIncludeFile(fileName: String): Boolean = {
    if (fileName.endsWith("INDEX.LIST")) {
      logger.info("Skipping jar index file: " + fileName)
      return false
    }
    import scala.collection.JavaConversions._
    for (suffix <- this.excludes) {
      if (fileName.endsWith(suffix)) {
        logger.info("Skipping file matching excluded suffix '" + suffix + "': " + fileName)
        return false
      }
    }
    return true
  }

  private def getManifestSize(manifest: Manifest): Long = {
    var baos: ByteArrayOutputStream = new ByteArrayOutputStream
    manifest.write(baos)
    var l: Long = baos.size
    baos.close
    return l
  }

  private def newJarOutputStream(manifest: Manifest): JarOutputStream = {
    if ((manifest == null) || (!this.replicateManifests)) {
      return new JarOutputStream(createOutFile(({
        this.nextFileIndex += 1;
        this.nextFileIndex
      })))
    }
    return new JarOutputStream(createOutFile(({
      this.nextFileIndex += 1;
      this.nextFileIndex
    })), manifest)
  }

  private def beginNewOutputStream(manifest: Manifest, manifestSize: Long): Unit = {
    this.currentStream.close
    this.currentStream = newJarOutputStream(manifest)
    this.currentSize = manifestSize
  }

  private def readIntoBuffer(inputStream: InputStream, readBuffer: Array[Byte], out: ByteArrayOutputStream): Unit = {
    var count: Int = 0
    while ((({
      count = inputStream.read(readBuffer);
      count
    })) != -1) out.write(readBuffer, 0, count)
  }

  private def createOutFile(index: Int): OutputStream = {
    var name: String = this.inputJar.getName
    if (name.endsWith(".jar")) {
      name = name.substring(0, name.length - ".jar".length)
    }
    var formatString: String = "%s-%0" + this.outputDigits + "%s"
    var newName: String = "%s-%s.split.jar" format(name, index)
    var newFile: File = new File(this.outputDirectory, newName)
    logger.info("Opening new file: " + newFile)
    return new BufferedOutputStream(new FileOutputStream(newFile))
  }

  private final var inputJar: File = null
  private final var outputDirectory: File = null
  private final var maximumSize: Int = 0
  private final var replicateManifests: Boolean = false
  private final var excludes: Set[String] = null
  private var nextFileIndex: Int = 0
  private var currentSize: Long = 0L
  private var currentStream: JarOutputStream = null
  private var outputDigits: Int = 0
  private final var logger: sbt.Logger = null
}
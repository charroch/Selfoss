package novoda.sbt

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Set
import java.util.jar.JarEntry
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

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
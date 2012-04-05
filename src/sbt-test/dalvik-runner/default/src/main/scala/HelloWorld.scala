
object HelloWorld {
  def main(args: Array[String]) {
    val c = android.net.Uri.parse("content://no.stub.execption")
    println("I rather be a class but dex will do for now.")
    println("Oh and no stub exception either %s" format c)
  }
}
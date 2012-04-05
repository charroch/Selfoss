import novoda.sbt.DalvikPlugin.DalvikKeys._

seq(novoda.sbt.DalvikPlugin.dalvikSettings: _*)

androidSource := file("/media/server/FOSS/android/head/")

libraryDependencies += "com.google.android" % "android" % "4.0.1.2" % "provided"

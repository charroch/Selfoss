import novoda.sbt.DalvikPlugin.DalvikKeys._

seq(novoda.sbt.DalvikPlugin.dalvikSettings: _*)

androidSource := file("/var/android/")

dalvikvm := file("/var/android/bin/dalvikvm")
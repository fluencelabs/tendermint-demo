name := "tmdemoapp"

version := "0.1"

scalaVersion := "2.12.6"

organizationName := "Fluence Labs Limited"

startYear := Some(2018)

licenses += ("Apache-2.0", new URL("https://www.apache.org/licenses/LICENSE-2.0.txt"))

libraryDependencies += "com.github.jtendermint" % "jabci" % "0.17.1"

libraryDependencies += "org.bouncycastle" % "bcpkix-jdk15on" % "1.56"

libraryDependencies += "com.google.code.gson" % "gson" % "2.8.5"

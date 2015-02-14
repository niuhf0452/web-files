name := "web-files"

version := "0.1"

scalaVersion := "2.11.5"

resolvers += Resolver.sonatypeRepo("releases")

libraryDependencies += "org.scala-lang" % "scala-reflect" % scalaVersion.value withSources()

libraryDependencies += "com.chuusai" %% "shapeless" % "2.0.0" withSources()

libraryDependencies += "org.scalaz" %% "scalaz-core" % "7.1.1" withSources()



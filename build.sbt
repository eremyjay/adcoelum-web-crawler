name := """adcoelum-alpha"""

// val runSearchBot = TaskKey[Unit]("run-adcoelum", "Builds crawler and indexer and core Search Bot, performs assembly of Search Bot and then runs program.")

lazy val commonSettings = Seq(
    organization := "com.adcoelum",
    version := "0.8.0-alpha",
    scalaVersion := "2.11.8",

    libraryDependencies ++= Seq(
        "com.typesafe.akka" %% "akka-actor" % "2.3.15",
        "com.typesafe.akka" %% "akka-remote" % "2.3.15",

        // HTTP connection
        "com.sparkjava" % "spark-core" % "2.6.0",
        "org.scalaj" %% "scalaj-http" % "2.3.0",
        "org.apache.httpcomponents" % "httpclient" % "4.5.2",

        // XML Parsing
        "net.sourceforge.htmlcleaner" % "htmlcleaner" % "2.6.1",
        "org.scala-lang.modules" %% "scala-xml" % "1.0.3",
        "org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.3",

        // Databases and IO support
        // "com.amazonaws" % "aws-java-sdk" % "1.11.10",
        "com.aerospike" % "aerospike-client" % "3.3.0",
        "org.reactivemongo" %% "reactivemongo" % "0.12.0",
        "com.typesafe.play" %% "play-json" % "2.5.0",
        // "com.github.tototoshi" %% "scala-csv" % "1.3.1",

        // Scheduler
        "com.enragedginger" %% "akka-quartz-scheduler" % "1.5.0-akka-2.4.x",  // https://github.com/enragedginger/akka-quartz-scheduler

        // Allow logging
        "ch.qos.logback" % "logback-classic" % "1.1.8",
        "org.codehaus.janino" % "janino" % "2.6.1",
        "com.github.alexvictoor" % "web-logback" % "0.2",

        "com.google.code.findbugs" % "jsr305" % "3.0.1" % "compile"

        /*
        // Monitoring Tools
        "io.kamon" %% "kamon-core" % "0.6.0",
        "io.kamon" %% "kamon-statsd" % "0.6.0",
        "io.kamon" %% "kamon-akka" % "0.6.0",
        "io.kamon" %% "kamon-scala" % "0.6.0"
        // "io.kamon" %% "aspectj-runner" % "0.1.3"
        */
    ),

    resolvers ++= Seq(
        "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases",
        "opennlp sourceforge repo" at "http://opennlp.sourceforge.net/maven2",
        "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/releases/",
        "Kamon Repository Snapshots" at "http://snapshots.kamon.io"
    ),

    scalacOptions += "-feature",

    assemblyMergeStrategy in assembly := {
        case PathList("io", "netty", xs @ _*) => MergeStrategy.last
        case "META-INF/io.netty.versions.properties" => MergeStrategy.last
        case x =>
            val oldStrategy = (assemblyMergeStrategy in assembly).value
            oldStrategy(x)
    }
)

lazy val adcoelum = (project in file("."))
    .settings(commonSettings: _*)
    .settings(
        /*
        runSearchBot := {
            //(assembly in crawler).value
            //(assembly in indexer).value
            val r = (runner in Compile).value
            val cp = (fullClasspath in Compile).value
            toError(r.run("SearchBot", sbt.Attributed.data(cp), Seq(), streams.value.log))
        },
        */
        // excludeFilter in scalaSource := "HtmlUnit*"
    )

lazy val searchBot = (project in file("searchBot"))
    .settings(commonSettings: _*)
    .settings(
        assemblyJarName in assembly := "searchBot.jar",
        libraryDependencies ++= Seq(
        )
    )
    .dependsOn(adcoelum)

lazy val crawler = (project in file("crawler"))
    .settings(commonSettings: _*)
    .settings(
        assemblyJarName in assembly := "crawler.jar",
        libraryDependencies ++= Seq()
    )
    .dependsOn(adcoelum)


lazy val downloader = (project in file("downloader"))
    .settings(commonSettings: _*)
    .settings(
        assemblyJarName in assembly := "downloader.jar",
        libraryDependencies ++= Seq(
            // Headless browsers and scrapers
            "org.seleniumhq.selenium" % "selenium-java" % "3.4.0",
            "org.seleniumhq.selenium" % "selenium-support" % "3.4.0",
            "org.apache.httpcomponents" % "httpclient" % "4.5.2",
            "net.lightbody.bmp" % "browsermob-core" % "2.1.4" exclude("org.slf4j", "jcl-over-slf4j") exclude("org.apache.httpcomponents", "httpclient")
            // "com.machinepublishers" % "jbrowserdriver" % "0.17.3" exclude("com.sun.jna", "jna") exclude("org.seleniumhq.selenium", "selenium-server"),
        )
    )
    .dependsOn(adcoelum)

lazy val indexer = (project in file("indexer"))
    .settings(commonSettings: _*)
    .settings (
        assemblyJarName in assembly := "indexer.jar",
        libraryDependencies ++= Seq(
            // Natural language support
            "org.apache.opennlp" % "opennlp-tools" % "1.8.0"
        )
    )
    .dependsOn(adcoelum)


fork in run := true
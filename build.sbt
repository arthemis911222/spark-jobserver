
import Dependencies._
import JobServerRelease._

transitiveClassifiers in Global := Seq(Artifact.SourceClassifier)
lazy val dirSettings = Seq()

lazy val akkaApp = Project(id = "akka-app", base = file("akka-app"))
  .settings(description := "Common Akka application stack: metrics, tracing, logging, and more.")
  .settings(commonSettings)
  .settings(libraryDependencies ++= coreTestDeps ++ akkaDeps)
  .settings(publishSettings)
  .disablePlugins(SbtScalariform)

lazy val jobServer = Project(id = "job-server", base = file("job-server"))
  .settings(commonSettings)
  .settings(revolverSettings)
  .settings(assembly := null.asInstanceOf[File])
  .settings(
    description := "Spark as a Service: a RESTful job server for Apache Spark",
    libraryDependencies ++= sparkDeps ++ slickDeps ++ cassandraDeps ++ securityDeps ++ coreTestDeps,
    test in Test <<= (test in Test).dependsOn(packageBin in Compile in jobServerTestJar)
      .dependsOn(clean in Compile in jobServerTestJar)
      .dependsOn(buildPython in jobServerPython)
      .dependsOn(clean in Compile in jobServerPython),
    testOnly in Test <<= (testOnly in Test).dependsOn(packageBin in Compile in jobServerTestJar)
      .dependsOn(clean in Compile in jobServerTestJar)
      .dependsOn(buildPython in jobServerPython)
      .dependsOn(clean in Compile in jobServerPython),
    console in Compile <<= Defaults.consoleTask(fullClasspath in Compile, console in Compile),
    fullClasspath in Compile <<= (fullClasspath in Compile).map { classpath =>
      extraJarPaths ++ classpath
    },
    fork in Test := true
  )
  .settings(publishSettings)
  .dependsOn(akkaApp, jobServerApi)
  .disablePlugins(SbtScalariform)

lazy val jobServerTestJar = Project(id = "job-server-tests", base = file("job-server-tests"))
  .settings(commonSettings)
  .settings(jobServerTestJarSettings)
  .settings(noPublishSettings)
  .dependsOn(jobServerApi)
  .disablePlugins(SbtScalariform)
  .disablePlugins(ScoverageSbtPlugin) // do not include in coverage report

lazy val jobServerApi = Project(id = "job-server-api", base = file("job-server-api"))
  .settings(commonSettings)
  .settings(jobServerApiSettings)
  .settings(publishSettings)
  .disablePlugins(SbtScalariform)

lazy val jobServerExtras = Project(id = "job-server-extras", base = file("job-server-extras"))
  .settings(commonSettings)
  .settings(jobServerExtrasSettings)
  .settings(
    test in Test <<= (test in Test)
      .dependsOn(packageBin in Compile in jobServerTestJar)
      .dependsOn(clean in Compile in jobServerTestJar)
      .dependsOn(buildPython in jobServerPython)
      .dependsOn(buildPyExamples in jobServerPython)
      .dependsOn(clean in Compile in jobServerPython),
    testOnly in Test <<= (testOnly in Test)
      .dependsOn(packageBin in Compile in jobServerTestJar)
      .dependsOn(clean in Compile in jobServerTestJar)
      .dependsOn(buildPython in jobServerPython)
      .dependsOn(buildPyExamples in jobServerPython)
      .dependsOn(clean in Compile in jobServerPython)
  )
  .dependsOn(jobServerApi, jobServer % "compile->compile; test->test")
  .disablePlugins(SbtScalariform)

lazy val jobServerPython = Project(id = "job-server-python", base = file("job-server-python"))
  .settings(commonSettings)
  .settings(jobServerPythonSettings)
  .dependsOn(jobServerApi, akkaApp % "test")
  .disablePlugins(SbtScalariform)

lazy val root = Project(id = "root", base = file("."))
  .settings(commonSettings)
  .settings(ourReleaseSettings)
  .settings(noPublishSettings)
  .settings(rootSettings)
  .settings(dockerSettings)
  .aggregate(jobServer, jobServerApi, jobServerTestJar, akkaApp, jobServerExtras, jobServerPython)
  .dependsOn(jobServer, jobServerExtras)
  .disablePlugins(SbtScalariform).enablePlugins(DockerPlugin)

lazy val jobServerExtrasSettings = revolverSettings ++ Assembly.settings ++ publishSettings ++ Seq(
  libraryDependencies ++= sparkExtraDeps,
  // Extras packages up its own jar for testing itself
  test in Test <<= (test in Test).dependsOn(packageBin in Compile),
  fork in Test := true,
  parallelExecution in Test := false,
  // Temporarily disable test for assembly builds so folks can package and get started.  Some tests
  // are flaky in extras esp involving paths.
  test in assembly := {},
  exportJars := true
)

lazy val jobServerApiSettings = Seq(libraryDependencies ++= sparkDeps ++ sparkExtraDeps)

lazy val testPython = taskKey[Unit]("Launch a sub process to run the Python tests")
lazy val buildPython = taskKey[Unit]("Build the python side of python support into an egg")
lazy val buildPyExamples = taskKey[Unit]("Build the examples of python jobs into an egg")

lazy val jobServerPythonSettings = revolverSettings ++ Assembly.settings ++ publishSettings ++ Seq(
  libraryDependencies ++= sparkPythonDeps,
  fork in Test := true,
  cancelable in Test := true,
  testPython := PythonTasks.testPythonTask(baseDirectory.value),
  buildPython := PythonTasks.buildPythonTask(baseDirectory.value, version.value),
  buildPyExamples := PythonTasks.buildExamplesTask(baseDirectory.value, version.value),
  assembly <<= assembly.dependsOn(buildPython)
)

lazy val jobServerTestJarSettings = Seq(
  libraryDependencies ++= sparkDeps ++ apiDeps,
  description := "Test jar for Spark Job Server",
  exportJars := true // use the jar instead of target/classes
)

lazy val noPublishSettings = Seq(
  publishTo := Some(Resolver.file("Unused repo", file("target/unusedrepo"))),
  publishArtifact := false,
  publish := {}
)

lazy val dockerSettings = Seq(
  // Make the docker task depend on the assembly task, which generates a fat JAR file
  docker <<= (docker dependsOn (assembly in jobServerExtras)),
  dockerfile in docker := {
    val artifact = (assemblyOutputPath in assembly in jobServerExtras).value
    val artifactTargetPath = s"/app/${artifact.name}"

    //val sparkBuild = s"spark-${Versions.spark}-bin-hadoop2.6"
    val sparkBuild = s"SPARK2-2.1.0.cloudera1-1.cdh5.7.0.p0.120904"

    val sparkBuildCmd = scalaBinaryVersion.value match {
      case "2.11" =>
        Versions.spark match {
          case s if s.startsWith("1") => {"./make-distribution.sh -Dscala-2.11 -Phadoop-2.6 -Dhadoop.version=2.6.0 -Phive -DskipTests"}
          case _ => {"./dev/make-distribution.sh -Dscala-2.11 -Phadoop-2.6 -Phive -Dhadoop.version=2.6.0 -DskipTests"}
        }
      case other => throw new RuntimeException(s"Scala version $other is not supported!")
    }

    new sbtdocker.mutable.Dockerfile {
      from(s"openjdk:${Versions.java}")
      // Dockerfile best practices: https://docs.docker.com/articles/dockerfile_best-practices/
      expose(8090)
      expose(9999) // for JMX
      env("MESOS_VERSION", Versions.mesos)
      runRaw(
        """     apt-key adv --keyserver keyserver.ubuntu.com --recv E56151BF && \
                apt-get -y update && \
                apt-get clean
        """)
      env("MAVEN_VERSION","3.3.9")
      runRaw(
        """mkdir -p /usr/share/maven /usr/share/maven/ref \
          && curl -fsSL http://apache.osuosl.org/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.tar.gz \
          | tar -xzC /usr/share/maven --strip-components=1 \
          && ln -s /usr/share/maven/bin/mvn /usr/bin/mvn
        """)
      env("MAVEN_HOME","/usr/share/maven")
      env("MAVEN_CONFIG", "/.m2")

      copy(artifact, artifactTargetPath)
      copy(baseDirectory(_ / "bin" / "server_start.sh").value, file("app/server_start.sh"))
      copy(baseDirectory(_ / "bin" / "server_stop.sh").value, file("app/server_stop.sh"))
      copy(baseDirectory(_ / "bin" / "manager_start.sh").value, file("app/manager_start.sh"))
      copy(baseDirectory(_ / "bin" / "setenv.sh").value, file("app/setenv.sh"))
      copy(baseDirectory(_ / "config" / "log4j-stdout.properties").value, file("app/log4j-server.properties"))
      copy(baseDirectory(_ / "config" / "docker.conf").value, file("app/docker.conf"))
      copy(baseDirectory(_ / "config" / "docker.sh").value, file("app/settings.sh"))
      // Including envs in Dockerfile makes it easy to override from docker command
      env("JOBSERVER_MEMORY", "1G")
      env("SPARK_HOME", "/spark")
      // Use a volume to persist database between container invocations
      run("mkdir", "-p", "/database")
      runRaw(
        s"""
           |wget http://archive.cloudera.com/spark2/parcels/2.1.0/$sparkBuild-trusty.parcel && \\
           |mv $sparkBuild-trusty.parcel $sparkBuild-trusty.gz && \\
           |gunzip  $sparkBuild-trusty.gz && \\
           |tar -xvf $sparkBuild-trusty && \\
           |mv $sparkBuild/lib/spark2 /spark && \\
           |rm -Rf /spark/conf && \\
           |mkdir /spark/conf && \\
           |rm -Rf /spark/work && \\
           |rm $sparkBuild-trusty && \\
           |rm -Rf $sparkBuild && \\
           |cd  /spark/jars && \\
           |wget https://repository.cloudera.com/artifactory/cloudera-repos/org/apache/hadoop/hadoop-common/2.6.0-cdh5.10.1/hadoop-common-2.6.0-cdh5.10.1.jar && \\
           |wget http://central.maven.org/maven2/org/slf4j/slf4j-api/1.7.16/slf4j-api-1.7.16.jar && \\
           |wget http://central.maven.org/maven2/org/slf4j/slf4j-log4j12/1.7.16/slf4j-log4j12-1.7.16.jar && \\
           |wget http://central.maven.org/maven2/log4j/log4j/1.2.17/log4j-1.2.17.jar && \\
           |wget http://central.maven.org/maven2/commons-configuration/commons-configuration/1.6/commons-configuration-1.6.jar && \\
           |wget http://central.maven.org/maven2/commons-beanutils/commons-beanutils/1.7.0/commons-beanutils-1.7.0.jar && \\
           |wget http://central.maven.org/maven2/commons-beanutils/commons-beanutils-core/1.8.0/commons-beanutils-core-1.8.0.jar && \\
           |wget http://central.maven.org/maven2/com/google/guava/guava/14.0.1/guava-14.0.1.jar && \\
           |wget http://central.maven.org/maven2/com/google/protobuf/protobuf-java/2.5.0/protobuf-java-2.5.0.jar && \\
           |wget http://central.maven.org/maven2/org/apache/htrace/htrace-core4/4.0.1-incubating/htrace-core4-4.0.1-incubating.jar && \\
           |wget https://repository.cloudera.com/artifactory/cloudera-repos/org/apache/hadoop/hadoop-auth/2.6.0-cdh5.10.1/hadoop-auth-2.6.0-cdh5.10.1.jar && \\
           |wget https://repository.cloudera.com/artifactory/cloudera-repos/org/apache/hadoop/hadoop-annotations/2.6.0-cdh5.10.1/hadoop-annotations-2.6.0-cdh5.10.1.jar && \\
           |wget https://repository.cloudera.com/artifactory/cloudera-repos/org/apache/hadoop/hadoop-client/2.6.0-cdh5.10.1/hadoop-client-2.6.0-cdh5.10.1.jar && \\
           |wget https://repository.cloudera.com/artifactory/cloudera-repos/org/apache/hadoop/hadoop-hdfs/2.6.0-cdh5.10.1/hadoop-hdfs-2.6.0-cdh5.10.1.jar && \\
           |wget https://repository.cloudera.com/artifactory/cloudera-repos/org/apache/hadoop/hadoop-mapreduce-client-app/2.6.0-cdh5.10.1/hadoop-mapreduce-client-app-2.6.0-cdh5.10.1.jar && \\
           |wget https://repository.cloudera.com/artifactory/cloudera-repos/org/apache/hadoop/hadoop-mapreduce-client-common/2.6.0-cdh5.10.1/hadoop-mapreduce-client-common-2.6.0-cdh5.10.1.jar && \\
           |wget https://repository.cloudera.com/artifactory/cloudera-repos/org/apache/hadoop/hadoop-mapreduce-client-core/2.6.0-cdh5.10.1/hadoop-mapreduce-client-core-2.6.0-cdh5.10.1.jar && \\
           |wget https://repository.cloudera.com/artifactory/cloudera-repos/org/apache/hadoop/hadoop-mapreduce-client-jobclient/2.6.0-cdh5.10.1/hadoop-mapreduce-client-jobclient-2.6.0-cdh5.10.1.jar && \\
           |wget https://repository.cloudera.com/artifactory/cloudera-repos/org/apache/hadoop/hadoop-mapreduce-client-shuffle/2.6.0-cdh5.10.1/hadoop-mapreduce-client-shuffle-2.6.0-cdh5.10.1.jar && \\
           |wget https://repository.cloudera.com/artifactory/cloudera-repos/org/apache/hadoop/hadoop-yarn-api/2.6.0-cdh5.10.1/hadoop-yarn-api-2.6.0-cdh5.10.1.jar && \\
           |wget https://repository.cloudera.com/artifactory/cloudera-repos/org/apache/hadoop/hadoop-yarn-client/2.6.0-cdh5.10.1/hadoop-yarn-client-2.6.0-cdh5.10.1.jar && \\
           |wget https://repository.cloudera.com/artifactory/cloudera-repos/org/apache/hadoop/hadoop-yarn-common/2.6.0-cdh5.10.1/hadoop-yarn-common-2.6.0-cdh5.10.1.jar && \\
           |wget https://repository.cloudera.com/artifactory/cloudera-repos/org/apache/hadoop/hadoop-yarn-server-web-proxy/2.6.0-cdh5.10.1/hadoop-yarn-server-web-proxy-2.6.0-cdh5.10.1.jar && \\
           |wget https://repository.cloudera.com/artifactory/cloudera-repos/org/apache/hadoop/hadoop-yarn-server-common/2.6.0-cdh5.10.1/hadoop-yarn-server-common-2.6.0-cdh5.10.1.jar && \\
           |wget http://central.maven.org/maven2/org/codehaus/jackson/jackson-core-asl/1.9.13/jackson-core-asl-1.9.13.jar && \\
           |wget http://central.maven.org/maven2/org/codehaus/jackson/jackson-mapper-asl/1.9.13/jackson-mapper-asl-1.9.13.jar
        """.stripMargin.trim
      )
      volume("/database")
      entryPoint("app/server_start.sh")
    }
  },
  imageNames in docker := Seq(
    sbtdocker.ImageName(namespace = Some("codegerm"),
                        repository = "spark-jobserver",
                        tag = Some(
                          s"${version.value}" +
                          s".cdh-5.x" +
                          s".spark-${Versions.spark}" +
                          s".scala-${scalaBinaryVersion.value}" +
                          s".jdk-${Versions.java}")
                        )
  )
)

lazy val rootSettings = Seq(
  // Must run Spark tests sequentially because they compete for port 4040!
  parallelExecution in Test := false,
  publishArtifact := false,
  concurrentRestrictions := Seq(
    Tags.limit(Tags.CPU, java.lang.Runtime.getRuntime.availableProcessors()),
    // limit to 1 concurrent test task, even across sub-projects
    // Note: some components of tests seem to have the "Untagged" tag rather than "Test" tag.
    // So, we limit the sum of "Test", "Untagged" tags to 1 concurrent
    Tags.limitSum(1, Tags.Test, Tags.Untagged))
)

lazy val revolverSettings = Seq(
  javaOptions in reStart += jobServerLogging,
  // Give job server a bit more PermGen since it does classloading
  //javaOptions in reStart += "-XX:MaxPermSize=256m",
  javaOptions in reStart += "-Djava.security.krb5.realm= -Djava.security.krb5.kdc=",
  // This lets us add Spark back to the classpath without assembly barfing
  fullClasspath in reStart := (fullClasspath in Compile).value,
  mainClass in reStart := Some("spark.jobserver.JobServer")
)

// To add an extra jar to the classpath when doing "re-start" for quick development, set the
// env var EXTRA_JAR to the absolute full path to the jar
lazy val extraJarPaths = Option(System.getenv("EXTRA_JAR"))
  .map(jarpath => Seq(Attributed.blank(file(jarpath))))
  .getOrElse(Nil)

// Create a default Scala style task to run with compiles
lazy val runScalaStyle = taskKey[Unit]("testScalaStyle")

lazy val commonSettings = Defaults.coreDefaultSettings ++ dirSettings ++ implicitlySettings ++ Seq(
  organization := "spark.jobserver",
  crossPaths   := true,
  scalaVersion := sys.env.getOrElse("SCALA_VERSION", "2.11.8"),
  dependencyOverrides += "org.scala-lang" % "scala-compiler" % scalaVersion.value,
  // scalastyleFailOnError := true,
  runScalaStyle := {
    org.scalastyle.sbt.ScalastylePlugin.scalastyle.in(Compile).toTask("").value
  },
  (compile in Compile) <<= (compile in Compile) dependsOn runScalaStyle,

  // In Scala 2.10, certain language features are disabled by default, such as implicit conversions.
  // Need to pass in language options or import scala.language.* to enable them.
  // See SIP-18 (https://docs.google.com/document/d/1nlkvpoIRkx7at1qJEZafJwthZ3GeIklTFhqmXMvTX9Q/edit)
  scalacOptions := Seq(
    "-deprecation", "-feature",
    "-language:implicitConversions",
    "-language:postfixOps",
    "-language:existentials"
  ),
  // For Building on Encrypted File Systems...
  scalacOptions ++= Seq("-Xmax-classfile-name", "128"),
  resolvers ++= Dependencies.repos,
  libraryDependencies ++= apiDeps,
  parallelExecution in Test := false,
  testOptions in Test += Tests.Argument(TestFrameworks.ScalaTest, "-oDF"),
  // We need to exclude jms/jmxtools/etc because it causes undecipherable SBT errors  :(
  ivyXML :=
    <dependencies>
      <exclude module="jms"/>
      <exclude module="jmxtools"/>
      <exclude module="jmxri"/>
    </dependencies>
) ++ scoverageSettings

lazy val scoverageSettings = {
  // Semicolon-separated list of regexs matching classes to exclude
  coverageExcludedPackages := ".+Benchmark.*;.+Example.*;.+TestJob"
}

lazy val publishSettings = Seq(
  licenses += ("Apache-2.0", url("http://choosealicense.com/licenses/apache/")),
  bintrayOrganization := Some("spark-jobserver")
)

// This is here so we can easily switch back to Logback when Spark fixes its log4j dependency.
lazy val jobServerLogbackLogging = "-Dlogback.configurationFile=config/logback-local.xml"
lazy val jobServerLogging = "-Dlog4j.configuration=file:config/log4j-local.properties"

package org.pitest.sbt

import org.pitest.aggregate.ReportAggregator
import org.pitest.mutationtest.config.{DirectoryResultOutputStrategy, PluginServices, ReportOptions, UndatedReportDirCreationStrategy}
import org.pitest.mutationtest.tooling.{AnalysisResult, EntryPoint}
import org.pitest.testapi.TestGroupConfig
import org.pitest.util.Verbosity
import sbt.Keys.*
import sbt.{Def, inProjects, *}

import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.Properties
import scala.collection.JavaConverters.*
import scala.xml.{Node, XML}

object PitPlugin extends AutoPlugin {
  object autoImport extends PitKeys
  override def trigger = allRequirements

  import autoImport.*

  override def projectSettings: Seq[Def.Setting[_]] =
    unscopedSettings ++ inConfig(Test)(scopedSettings)

  private val mutationsFilename = "mutations.xml"
  private val lineCoverageFilename = "linecoverage.xml"

  def unscopedSettings: Seq[Def.Setting[_]] = Seq(
    pitThreads := 1,
    pitMaxMutationsPerClass := 0,
    pitVerbose := false,
    pitMutationUnitSize := 0,
    pitTimeoutFactor := 1.25f,
    pitTimeoutConst := 4000,
    pitDetectInlinedCode := true,
    pitFailWhenNoMutation := true,
    pitFullMutationMatrix := false,
    pitTimestampedReports := false,
    pitExportLineCoverage := true,

    pitMutators := Seq(),
    pitFeatures := Seq(),
    pitJvmArgs := Seq(),
    pitArgLine := "",
    pitOutputFormats := Seq("HTML"),
    pitIncludedGroups := Seq(),
    pitExcludedGroups := Seq(),
    pitPluginConfiguration := Map(),
    pitIncludedTestMethods := Seq(),

    pitReportPath := target.value / "pit-reports",
    pitAggregateReportPath := target.value / "pit-reports-aggregate",
    pitTargetClasses := Seq(),
    pitTargetTests := Seq(),
    pitExcludedMethods := Seq(),
    pitAvoidCallsTo := Seq(),
    pitExcludedClasses := Seq(),
    pitExcludedTestClasses := Seq(),
    pitExcludedRunners := Seq(),
    pitEngine := "gregor",

    pitHistoryInputLocation := Option.empty,
    pitHistoryOutputLocation := Option.empty,

    pitConfiguration := Def.task{
      Configuration(
        pitEngine.value,
        pitMutators.value,
        pitFeatures.value,
        pitOutputFormats.value,
        pitJvmArgs.value,
        pitArgLine.value,
        pitIncludedGroups.value,
        pitExcludedGroups.value,
        pitIncludedTestMethods.value,
        pitPluginConfiguration.value
      )
    }.value,
    pitMutableCodePaths := Def.task{
      Seq((Compile / classDirectory).value)
    }.value,
    pitPathSettings := (Def.task {
      PathSettings(
        baseDirectory.value,
        pitReportPath.value,
        pitMutableCodePaths.value,
        (Test / fullClasspath).value,
        (Compile / sourceDirectories).value,
        pitHistoryInputLocation.value.orNull,
        pitHistoryOutputLocation.value.orNull
      )
    } dependsOn (Compile / compile)).value,
    pitFilterSettings := Def.task{
      FilterSettings(pitTargetClasses.value, pitTargetTests.value)
    }.value,
    pitExcludes := Def.task{
      Excludes(
        pitExcludedClasses.value,
        pitExcludedMethods.value,
        pitExcludedTestClasses.value,
        pitExcludedRunners.value,
        pitAvoidCallsTo.value)
    }.value,
    pitOptions := Def.task{
      Options(
        pitDetectInlinedCode.value,
        pitThreads.value,
        pitMaxMutationsPerClass.value,
        pitVerbose.value,
        pitMutationUnitSize.value,
        pitTimeoutFactor.value,
        pitTimeoutConst.value,
        pitFailWhenNoMutation.value,
        pitFullMutationMatrix.value,
        pitTimestampedReports.value,
        pitExportLineCoverage.value
      )
    }.value
  )

  def scopedSettings: Seq[Def.Setting[_]] = Seq(
    pitest := (Def.task{
      runPitest(
        pitOptions.value,
        pitConfiguration.value,
        pitPathSettings.value,
        pitFilterSettings.value,
        pitExcludes.value,
        streams.value.log
      )
    } dependsOn (Test/compile)).value,
    pitestAggregate := pitestAggregateTask.value,
    pitestAggregate / aggregate := false
  )

  def makeReportOptions(
    options: Options,
    config: Configuration,
    paths: PathSettings,
    filters: FilterSettings,
    excludes: Excludes,
    ps: PluginServices
  ): ReportOptions = {
    val data = new ReportOptions

    data.setCodePaths(paths.mutatablePath.map(_.getPath).asJavaCollection)

    data.setClassPathElements(makeClasspath(paths, ps).asJavaCollection)

    data.setFailWhenNoMutations(options.failWhenNoMutation)

    data.setTargetClasses(filters.targetClasses.asJavaCollection)
    data.setTargetTests(org.pitest.util.Glob.toGlobPredicates(filters.targetTests.asJavaCollection))

    data.setExcludedClasses(excludes.excludedClasses.asJavaCollection)
    data.setExcludedMethods(excludes.excludedMethods.asJavaCollection)
    data.setExcludedTestClasses(
      excludes.excludeTestClasses.map(
        org.pitest.util.Glob.toGlobPredicate.apply(_)).asJavaCollection);
    data.setNumberOfThreads(options.threads)
    data.setExcludedRunners(excludes.excludedRunners.asJavaCollection)

    data.setReportDir(paths.targetPath.getAbsolutePath)
    data.setVerbosity(if (options.verbose) Verbosity.VERBOSE else Verbosity.DEFAULT)

    data.addChildJVMArgs(java.util.List.copyOf(config.jvmArgs.asJavaCollection))
    data.setArgLine(config.argline)

    data.setMutators(config.mutators.asJavaCollection)
    data.setFeatures(config.features.asJavaCollection)
    data.setTimeoutConstant(options.timeoutConst)
    data.setTimeoutFactor(options.timeoutFactor)
    data.setLoggingClasses(excludes.excludedMethods.asJavaCollection)

    data.setSourceDirs(paths.sources.map(_.toPath).asJavaCollection)

    data.addOutputFormats(config.outputFormats.asJavaCollection)

    data.setGroupConfig(new TestGroupConfig(
      java.util.List.copyOf(config.excludedGroups.asJavaCollection),
      java.util.List.copyOf(config.includedGroups.asJavaCollection)))

    data.setFullMutationMatrix(options.fullMutationMatrix)

    data.setMutationUnitSize(options.mutationUnitSize)
    data.setShouldCreateTimestampedReports(options.timestampedReports)
    data.setDetectInlinedCode(options.detectInlinedCode)

    data.setHistoryInputLocation(paths.historyInput)
    data.setHistoryOutputLocation(paths.historyOutput)

    data.setExportLineCoverage(options.exportLineCoverage)
    data.setMutationEngine(config.engine)
//    data.setJavaExecutable() // defaults to JAVA_HOME
    data.setFreeFormProperties(createPluginProperties(config.pluginConfiguration))
    data.setIncludedTestMethods(config.includedTestMethods.asJavaCollection)

    data.setInputEncoding(Charset.defaultCharset())
    data.setOutputEncoding(Charset.defaultCharset())

    data.setProjectBase(paths.baseDir.toPath)

    data
  }

  private def createPluginProperties(
    pluginConfiguration: Map[String, String]
  ): Properties = {
    val properties = new Properties()

    if (pluginConfiguration.nonEmpty) {
      pluginConfiguration.foreach(entry => properties.put(entry._1, entry._2))
    }

    properties
  }

  def runPitest(
    options : Options,
    conf : Configuration,
    paths: PathSettings,
    filters: FilterSettings,
    excludes: Excludes,
    logger: Logger
  ): AnalysisResult = {
    logger.info("Starting PITest...")
    val originalCL = Thread.currentThread.getContextClassLoader
    Thread.currentThread().setContextClassLoader(PitPlugin.getClass.getClassLoader)

    try {
      val ps = PluginServices.makeForContextLoader
      val pit = new EntryPoint
      val data = makeReportOptions(options, conf, paths, filters, excludes, ps)
      val result = pit.execute(paths.baseDir, data, ps, System.getenv)

      if (result.getError.isPresent) {
        result.getError.get.printStackTrace()
      }

      logger.info("PITest Report generated at " + paths.targetPath + ".")
      result
    } finally {
      Thread.currentThread().setContextClassLoader(originalCL)
    }
  }

  private def makeClasspath(paths: PathSettings, ps : PluginServices) : Seq[String] = {
    val cp = paths.classPath.files.map(_.getPath.trim)
    val services = ps.findClientClasspathPlugins.asScala
    val pluginPaths = services.map(c => pathTo(c.getClass))
    cp ++ pluginPaths.toSet
  }

  private def pathTo(c : Class[_]) = {
    val p = c.getProtectionDomain.getCodeSource.getLocation.getPath
    URLDecoder.decode(p, "UTF-8")
  }

  private def allProjectsSetting[K](setting: SettingKey[K]) = Def.settingDyn {
    setting.all(
      ScopeFilter.apply(
        inProjects(
          (thisProject.value.dependencies.map(_.project) ++ Seq(thisProjectRef.value)): _*)))
  }

  private def allProjectsTask[K](task: TaskKey[K]) = Def.taskDyn {
    task.all(
      ScopeFilter.apply(
        inProjects(
          (thisProject.value.dependencies.map(_.project) ++ Seq(thisProjectRef.value)): _*)))
  }

  def pitestAggregateTask: Def.Initialize[Task[Unit]] = Def.task {
    val pitestResults = allProjectsTask(Test / pitest).value

    streams.value.log.info("Aggregating Pitest reports for " + name.value + "...")

    val reportPaths = allProjectsSetting(pitReportPath).value
    val aggregateReportPath = pitAggregateReportPath.value
    val sourceDirs =
      allProjectsSetting(Compile / sourceDirectories).value.flatten ++
        allProjectsSetting(Test / sourceDirectories).value.flatten
    val classDirs =
      allProjectsSetting(Compile / classDirectory).value ++
        allProjectsSetting(Test / classDirectory).value

    if (pitOutputFormats.value.contains("XML")) {
      aggregateReportXmls(
        reportPaths.map(_ / mutationsFilename),
        aggregateReportPath / mutationsFilename)
    }

    if (pitOutputFormats.value.contains("HTML")) {
      aggregateReportHtmls(reportPaths, sourceDirs, classDirs, aggregateReportPath)
    }

    streams.value.log.info("Generated Aggregated Pitest Report for " + name.value + " at " + aggregateReportPath.getAbsoluteFile + ".")
  }

  private def aggregateReportHtmls(
    reportPaths: Seq[File],
    sourceDirs: Seq[File],
    classDirs: Seq[File],
    aggregatePath: File
  ): Unit = {
    val originalCL = Thread.currentThread.getContextClassLoader
    Thread.currentThread().setContextClassLoader(PitPlugin.getClass.getClassLoader)
    try {
      val raBuilder = ReportAggregator.builder
      reportPaths.foreach(
        reportPath =>
          raBuilder
            .addMutationResultsFile(reportPath / mutationsFilename)
            .addLineCoverageFile(reportPath / lineCoverageFilename))
      sourceDirs.filter(_.exists)
        .foreach(sourcePath => raBuilder.addSourceCodeDirectory(sourcePath))
      classDirs.filter(_.exists)
        .foreach(classfilePath => raBuilder.addCompiledCodeDirectory(classfilePath))
      raBuilder.resultOutputStrategy(
        new DirectoryResultOutputStrategy(
          aggregatePath.getAbsolutePath,
          new UndatedReportDirCreationStrategy))
      raBuilder.build.aggregateReport
    } finally {
      Thread.currentThread().setContextClassLoader(originalCL)
    }
  }

  private def aggregateReportXmls(xmlFiles: Seq[File], outputFile: File): Unit = {
    val newXml = xmlFiles.map(XML.loadFile)
      .reduceLeft((xml, otherXml) => xml.copy(child = xml.child.toSeq ++ otherXml.child.toSeq))
    if (!outputFile.getParentFile.exists) {
      outputFile.getParentFile.mkdirs
    }
    XML.save(
      outputFile.getAbsolutePath,
      newXml.copy(child = compressConsecutiveNewlines(newXml.child.toSeq)))
  }

  private def compressConsecutiveNewlines(nodes: Seq[Node]): Seq[Node] = {
    nodes.foldLeft(Seq.empty[Node]) {
      case (seq: Seq[Node], node: Node) if (seq.nonEmpty && seq.last.toString.equals("\n") && node.toString.equals("\n")) => seq
      case (seq: Seq[Node], node: Node) => seq ++ node
    }
  }
}

package org.pitest.sbt

import org.pitest.aggregate.ReportAggregator
import org.pitest.mutationtest.config.{DirectoryResultOutputStrategy, PluginServices, ReportOptions, UndatedReportDirCreationStrategy}
import org.pitest.mutationtest.tooling.{AnalysisResult, EntryPoint}
import org.pitest.testapi.TestGroupConfig
import sbt.{Def, inProjects, _}
import sbt.Keys._

import java.net.URLDecoder
import scala.collection.JavaConverters._
import scala.xml.{Text, XML, Node}

object PitPlugin extends AutoPlugin {
  object autoImport extends PitKeys
  override def trigger = allRequirements

  import autoImport._

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

    pitMutators := Seq(),
    pitOutputFormats := Seq("HTML"),
    pitIncludedGroups := Seq(),
    pitExcludedGroups := Seq(),

    pitDependencyDistance := -1,
    pitReportPath := target.value / "pit-reports",
    pitAggregateReportPath := target.value / "pit-reports-aggregate",
    pitTargetClasses := Seq(),
    pitTargetTests := Seq(),
    pitExcludedMethods := Seq(),
    pitAvoidCallsTo := Seq(),
    pitExcludedClasses := Seq(),
    pitEngine := "gregor",
    pitTestPlugin := "junit",

    pitHistoryInputLocation := Option.empty,
    pitHistoryOutputLocation := Option.empty,

    pitConfiguration := Def.task{
      Configuration(
        pitEngine.value,
        pitMutators.value,
        pitOutputFormats.value,
        (Test / javacOptions).value,
        pitIncludedGroups.value,
        pitExcludedGroups.value,
        pitTestPlugin.value
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
      FilterSettings(pitTargetClasses.value, pitTargetTests.value, pitDependencyDistance.value)
    }.value,
    pitExcludes := Def.task{
      Excludes(pitExcludedClasses.value, pitExcludedMethods.value, pitAvoidCallsTo.value)
    }.value,
    pitOptions := Def.task{
      Options(
        pitDetectInlinedCode.value,
        pitThreads.value,
        pitMaxMutationsPerClass.value,
        pitVerbose.value,
        pitMutationUnitSize.value,
        pitTimeoutFactor.value,
        pitTimeoutConst.value
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
    data.setReportDir(paths.targetPath.getAbsolutePath)
    data.setClassPathElements(makeClasspath(paths, ps).asJavaCollection)
    data.setCodePaths(paths.mutatablePath.map(_.getPath).asJavaCollection)
    data.setHistoryInputLocation(paths.historyInput)
    data.setHistoryOutputLocation(paths.historyOutput)
    data.setTargetClasses(filters.targetClasses.asJavaCollection)
    data.setTargetTests(org.pitest.util.Glob.toGlobPredicates(filters.targetTests.asJavaCollection))
    data.setDependencyAnalysisMaxDistance(filters.dependencyDistance)
    data.setSourceDirs(paths.sources.asJavaCollection)
    data.setVerbose(options.verbose)
    data.setDetectInlinedCode(options.detectInlinedCode)
    data.setNumberOfThreads(options.threads)
    data.setVerbose(options.verbose)
    data.setMutationUnitSize(options.mutationUnitSize)
    data.setTimeoutFactor(options.timeoutFactor)
    data.setTimeoutConstant(options.timeoutConst)
    data.setExcludedClasses(excludes.excludedClasses.asJavaCollection)
    data.setExcludedMethods(excludes.excludedMethods.asJavaCollection)
    data.setLoggingClasses(excludes.excludedMethods.asJavaCollection)

    data.setMutationEngine(config.engine)
    data.setMutators(config.mutators.asJavaCollection)
    data.addOutputFormats(config.outputFormats.asJavaCollection)
    data.setTestPlugin(config.testPlugin)

    data.setShouldCreateTimestampedReports(false)
    data.setExportLineCoverage(true)

    val conf = new TestGroupConfig(config.excludedGroups.asJava, config.includedGroups.asJava)
    data.setGroupConfig(conf)

    data
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

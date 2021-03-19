package org.pitest.sbt

import sbt._
import Keys._
import org.pitest.mutationtest.tooling.AnalysisResult

private[sbt] case class Configuration(
    engine : String,
    mutators : Seq[String],
    outputFormats : Seq[String],
    jvmArgs : Seq[String],
    includedGroups : Seq[String],
    excludedGroups : Seq[String],
    testPlugin: String
)
private[sbt] case class Options(
    detectInlinedCode : Boolean,
    threads : Int,
    maxMutationsPerClass : Int,
    verbose : Boolean,
    mutationUnitSize : Int,
    timeoutFactor : Float,
    timeoutConst : Long
)
private[sbt] case class PathSettings(
    baseDir : File,
    targetPath: File,
    mutatablePath: Seq[File],
    classPath: Classpath,
    sources : Seq[File],
    historyInput: File,
    historyOutput: File
)
private[sbt] case class FilterSettings(
    targetClasses : Seq[String],
    targetTests : Seq[String],
    dependencyDistance : Int
)
private[sbt] case class Excludes(
    excludedClasses : Seq[String],
    excludedMethods : Seq[String],
    avoidCallsTo : Seq[String]
)

trait PitKeys {
    val pitest = TaskKey[AnalysisResult]("pitest")
    val pitestAggregate = TaskKey[Unit]("pitest-aggregate")

    val pitEngine = SettingKey[String]("pit-engine")
    val pitMutators = SettingKey[Seq[String]]("pit-mutators")
    val pitOutputFormats = SettingKey[Seq[String]]("pit-outputFormats")
    val pitIncludedGroups = SettingKey[Seq[String]]("pit-included-groups")
    val pitExcludedGroups = SettingKey[Seq[String]]("pit-excluded-groups")

    val pitTargetClasses = TaskKey[Seq[String]]("pit-target-classes")
    val pitTargetTests = TaskKey[Seq[String]]("pit-target-tests")
    val pitDependencyDistance = SettingKey[Int]("pit-max-dependency-distance")
    val pitExcludedMethods = SettingKey[Seq[String]]("pit-excluded-methods")
    val pitExcludedClasses = SettingKey[Seq[String]]("pit-excluded-classes")
    val pitAvoidCallsTo = SettingKey[Seq[String]]("pit-avoid-calls-to")
    val pitThreads = SettingKey[Int]("pit-threads")
    val pitMaxMutationsPerClass = SettingKey[Int]("pit-max-mutation-per-class")
    val pitVerbose = SettingKey[Boolean]("pit-verbose")
    val pitMutationUnitSize = SettingKey[Int]("pit-mutationUnitSize")
    val pitTimeoutFactor = SettingKey[Float]("pit-timeoutFactor")
    val pitTimeoutConst = SettingKey[Long]("pit-timeoutConst")
    val pitDetectInlinedCode =  SettingKey[Boolean]("pit-detect-inlined-code")
    val pitTestPlugin = SettingKey[String]("pit-test-plugin")
    val pitHistoryInputLocation = SettingKey[Option[File]]("pit-history-input-location")
    val pitHistoryOutputLocation = SettingKey[Option[File]]("pit-history-output-location")
    
    /** Output path for reports. Defaults to <code>target / "pit-reports"</code>. */
    val pitReportPath = SettingKey[File]("pit-target-path")
    val pitAggregateReportPath = SettingKey[File]("pit-aggregate-target-path")
    
    private[sbt] val pitMutableCodePaths = TaskKey[Seq[File]]("mutable-code-path")
    private[sbt] val pitPathSettings = TaskKey[PathSettings]("pit-path-settings")
    private[sbt] val pitFilterSettings = TaskKey[FilterSettings]("pit-filter-settings")
    private[sbt] val pitExcludes = TaskKey[Excludes]("pit-excludes")
    private[sbt] val pitOptions = TaskKey[Options]("pit-options")
    private[sbt] val pitConfiguration = TaskKey[Configuration]("pit-configuration")
}

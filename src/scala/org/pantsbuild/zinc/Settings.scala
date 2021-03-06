/**
 * Copyright (C) 2012 Typesafe, Inc. <http://www.typesafe.com>
 */

package org.pantsbuild.zinc

import java.io.File
import java.util.{ List => JList }
import org.pantsbuild.zinc.Main.MainLog
import sbt.inc.ClassfileManager
import sbt.inc.IncOptions.{ Default => DefaultIncOptions }
import sbt.Level
import sbt.Path._
import scala.collection.JavaConverters._
import xsbti.compile.CompileOrder

/**
 * All parsed command-line options.
 */
case class Settings(
  help: Boolean              = false,
  version: Boolean           = false,
  quiet: Boolean             = false,
  logLevel: Level.Value      = Level.Info,
  color: Boolean             = true,
  sources: Seq[File]         = Seq.empty,
  classpath: Seq[File]       = Seq.empty,
  classesDirectory: File     = new File("."),
  scala: ScalaLocation       = ScalaLocation(),
  scalacOptions: Seq[String] = Seq.empty,
  javaHome: Option[File]     = None,
  forkJava: Boolean          = false,
  javaOnly: Boolean          = false,
  javacOptions: Seq[String]  = Seq.empty,
  compileOrder: CompileOrder = CompileOrder.Mixed,
  sbt: SbtJars               = SbtJars(),
  incOptions: IncOptions     = IncOptions(),
  analysis: AnalysisOptions  = AnalysisOptions(),
  analysisUtil: AnalysisUtil = AnalysisUtil(),
  properties: Seq[String]    = Seq.empty
)

/**
 * Alternative ways to locate the scala jars.
 */
case class ScalaLocation(
  home: Option[File]     = None,
  path: Seq[File]        = Seq.empty,
  compiler: Option[File] = None,
  library: Option[File]  = None,
  extra: Seq[File]       = Seq.empty
)

object ScalaLocation {
  /**
   * Java API for creating ScalaLocation.
   */
  def create(
    home: File,
    path: JList[File],
    compiler: File,
    library: File,
    extra: JList[File]): ScalaLocation =
  ScalaLocation(
    Option(home),
    path.asScala,
    Option(compiler),
    Option(library),
    extra.asScala
  )

  /**
   * Java API for creating ScalaLocation with scala home.
   */
  def fromHome(home: File) = ScalaLocation(home = Option(home))

  /**
   * Java API for creating ScalaLocation with scala path.
   */
  def fromPath(path: JList[File]) = ScalaLocation(path = path.asScala)
}

/**
 * Locating the sbt jars needed for zinc compile.
 */
case class SbtJars(
  sbtInterface: Option[File]         = None,
  compilerInterfaceSrc: Option[File] = None
)

object SbtJars {
  /**
   * Select the sbt jars from a path.
   */
  def fromPath(path: Seq[File]): SbtJars = {
    val sbtInterface = path find (_.getName matches Setup.SbtInterface.pattern)
    val compilerInterfaceSrc = path find (_.getName matches Setup.CompilerInterfaceSources.pattern)
    SbtJars(sbtInterface, compilerInterfaceSrc)
  }

  /**
   * Java API for selecting sbt jars from a path.
   */
  def fromPath(path: JList[File]): SbtJars = fromPath(path.asScala)
}

/**
 * Wrapper around incremental compiler options.
 */
case class IncOptions(
  transitiveStep: Int            = DefaultIncOptions.transitiveStep,
  recompileAllFraction: Double   = DefaultIncOptions.recompileAllFraction,
  relationsDebug: Boolean        = DefaultIncOptions.relationsDebug,
  apiDebug: Boolean              = DefaultIncOptions.apiDebug,
  apiDiffContextSize: Int        = DefaultIncOptions.apiDiffContextSize,
  apiDumpDirectory: Option[File] = DefaultIncOptions.apiDumpDirectory,
  transactional: Boolean         = false,
  backup: Option[File]           = None,
  recompileOnMacroDef: Boolean   = DefaultIncOptions.recompileOnMacroDef,
  nameHashing: Boolean           = DefaultIncOptions.nameHashing
) {
  @deprecated("Use the primary constructor instead.", "0.3.5.2")
  def this(
    transitiveStep: Int,
    recompileAllFraction: Double,
    relationsDebug: Boolean,
    apiDebug: Boolean,
    apiDiffContextSize: Int,
    apiDumpDirectory: Option[File],
    transactional: Boolean,
    backup: Option[File]
  ) = {
    this(transitiveStep, recompileAllFraction, relationsDebug, apiDebug, apiDiffContextSize,
      apiDumpDirectory, transactional, backup, DefaultIncOptions.recompileOnMacroDef,
      DefaultIncOptions.nameHashing)
  }
  def options: sbt.inc.IncOptions = {
    sbt.inc.IncOptions(
      transitiveStep,
      recompileAllFraction,
      relationsDebug,
      apiDebug,
      apiDiffContextSize,
      apiDumpDirectory,
      classfileManager,
      recompileOnMacroDef,
      nameHashing
    )
  }

  def classfileManager: () => ClassfileManager = {
    if (transactional && backup.isDefined)
      ClassfileManager.transactional(backup.get, MainLog.log)
    else
      DefaultIncOptions.newClassfileManager
  }
}

/**
 * Configuration for sbt analysis and analysis output options.
 */
case class AnalysisOptions(
  cache: Option[File]           = None,
  cacheMap: Map[File, File]     = Map.empty,
  forceClean: Boolean           = false,
  outputRelations: Option[File] = None,
  outputProducts: Option[File]  = None,
  mirrorAnalysis: Boolean       = false
)

/**
 * Configuration for analysis manipulation utilities.
 */
case class AnalysisUtil(
  run: Boolean                = false,
  cache: Option[File]         = None,
  merge: Seq[File]            = Seq.empty,
  rebase: Map[File, File]     = Map.empty,
  split: Map[Seq[File], File] = Map.empty,
  reload: Seq[File]           = Seq.empty
)

object Settings {
  /**
   * All available command-line options.
   */
  val options = Seq(
    header("Output options:"),
    boolean(  ("-help", "-h"),                 "Print this usage message",                   (s: Settings) => s.copy(help = true)),
    boolean(   "-version",                     "Print version",                              (s: Settings) => s.copy(version = true)),
    boolean(  ("-quiet", "-q"),                "Silence all logging",                        (s: Settings) => s.copy(quiet = true)),
    boolean(   "-debug",                       "Set log level to debug",                     (s: Settings) => s.copy(logLevel = Level.Debug)),
    string(    "-log-level", "level",          "Set log level (debug|info|warn|error)",      (s: Settings, l: String) => s.copy(logLevel = Level.withName(l))),
    boolean(   "-no-color",                    "No color in logging",                        (s: Settings) => s.copy(color = false)),

    header("Compile options:"),
    path(     ("-classpath", "-cp"), "path",   "Specify the classpath",                      (s: Settings, cp: Seq[File]) => s.copy(classpath = cp)),
    file(      "-d", "directory",              "Destination for compiled classes",           (s: Settings, f: File) => s.copy(classesDirectory = f)),

    header("Scala options:"),
    file(      "-scala-home", "directory",     "Scala home directory (for locating jars)",   (s: Settings, f: File) => s.copy(scala = s.scala.copy(home = Some(f)))),
    path(      "-scala-path", "path",          "Specify all Scala jars directly",            (s: Settings, sp: Seq[File]) => s.copy(scala = s.scala.copy(path = sp))),
    file(      "-scala-compiler", "file",      "Specify Scala compiler jar directly" ,       (s: Settings, f: File) => s.copy(scala = s.scala.copy(compiler = Some(f)))),
    file(      "-scala-library", "file",       "Specify Scala library jar directly" ,        (s: Settings, f: File) => s.copy(scala = s.scala.copy(library = Some(f)))),
    path(      "-scala-extra", "path",         "Specify extra Scala jars directly",          (s: Settings, e: Seq[File]) => s.copy(scala = s.scala.copy(extra = e))),
    prefix(    "-S", "<scalac-option>",        "Pass option to scalac",                      (s: Settings, o: String) => s.copy(scalacOptions = s.scalacOptions :+ o)),

    header("Java options:"),
    file(      "-java-home", "directory",      "Select javac home directory (and fork)",     (s: Settings, f: File) => s.copy(javaHome = Some(f))),
    boolean(   "-fork-java",                   "Run java compiler in separate process",      (s: Settings) => s.copy(forkJava = true)),
    string(    "-compile-order", "order",      "Compile order for Scala and Java sources",   (s: Settings, o: String) => s.copy(compileOrder = compileOrder(o))),
    boolean(   "-java-only",                   "Don't add scala library to classpath",       (s: Settings) => s.copy(javaOnly = true)),
    prefix(    "-C", "<javac-option>",         "Pass option to javac",                       (s: Settings, o: String) => s.copy(javacOptions = s.javacOptions :+ o)),

    header("sbt options:"),
    file(      "-sbt-interface", "file",       "Specify sbt interface jar",                  (s: Settings, f: File) => s.copy(sbt = s.sbt.copy(sbtInterface = Some(f)))),
    file(      "-compiler-interface", "file",  "Specify compiler interface sources jar",     (s: Settings, f: File) => s.copy(sbt = s.sbt.copy(compilerInterfaceSrc = Some(f)))),

    header("Incremental compiler options:"),
    int(       "-transitive-step", "n",        "Steps before transitive closure",            (s: Settings, i: Int) => s.copy(incOptions = s.incOptions.copy(transitiveStep = i))),
    fraction(  "-recompile-all-fraction", "x", "Limit before recompiling all sources",       (s: Settings, d: Double) => s.copy(incOptions = s.incOptions.copy(recompileAllFraction = d))),
    boolean(   "-debug-relations",             "Enable debug logging of analysis relations", (s: Settings) => s.copy(incOptions = s.incOptions.copy(relationsDebug = true))),
    boolean(   "-debug-api",                   "Enable analysis API debugging",              (s: Settings) => s.copy(incOptions = s.incOptions.copy(apiDebug = true))),
    file(      "-api-dump", "directory",       "Destination for analysis API dump",          (s: Settings, f: File) => s.copy(incOptions = s.incOptions.copy(apiDumpDirectory = Some(f)))),
    int(       "-api-diff-context-size", "n",  "Diff context size (in lines) for API debug", (s: Settings, i: Int) => s.copy(incOptions = s.incOptions.copy(apiDiffContextSize = i))),
    boolean(   "-transactional",               "Restore previous class files on failure",    (s: Settings) => s.copy(incOptions = s.incOptions.copy(transactional = true))),
    file(      "-backup", "directory",         "Backup location (if transactional)",         (s: Settings, f: File) => s.copy(incOptions = s.incOptions.copy(backup = Some(f)))),
    boolean(   "-recompileOnMacroDefDisabled", "Disable recompilation of all dependencies of a macro def",
      (s: Settings) => s.copy(incOptions = s.incOptions.copy(recompileOnMacroDef = false))),
    boolean(   "-no-name-hashing",             "Disable improved incremental compilation algorithm",
      (s: Settings) => s.copy(incOptions = s.incOptions.copy(nameHashing = false))),

    header("Analysis options:"),
    file(      "-analysis-cache", "file",      "Cache file for compile analysis",            (s: Settings, f: File) => s.copy(analysis = s.analysis.copy(cache = Some(f)))),
    fileMap(   "-analysis-map",                "Upstream analysis mapping (file:file,...)",  (s: Settings, m: Map[File, File]) => s.copy(analysis = s.analysis.copy(cacheMap = m))),
    boolean(   "-force-clean",                 "Force clean classes on empty analysis",      (s: Settings) => s.copy(analysis = s.analysis.copy(forceClean = true))),
    boolean(   "-mirror-analysis",             "Store a readable text version of analysis",  (s: Settings) => s.copy(analysis = s.analysis.copy(mirrorAnalysis = true))),
    file(      "-output-relations", "file",    "Print readable analysis relations to file",  (s: Settings, f: File) => s.copy(analysis = s.analysis.copy(outputRelations = Some(f)))),
    file(      "-output-products", "file",     "Print readable source products to file",     (s: Settings, f: File) => s.copy(analysis = s.analysis.copy(outputProducts = Some(f)))),

    header("JVM options:"),
    prefix(    "-D", "property=value",         "Pass property to runtime system",            (s: Settings, o: String) => s.copy(properties = s.properties :+ o)),
    dummy(     "-J<flag>",                     "Set JVM flag directly for this process"),

    header("Nailgun options:"),
    dummy(     "-nailed",                      "Run as daemon with nailgun server"),
    dummy(     "-port",                        "Set nailgun port (if nailed)"),
    dummy(     "-start",                       "Ensure nailgun server is running (if nailed)"),
    dummy(     "-status",                      "Report nailgun server status (if nailed)"),
    dummy(     "-shutdown",                    "Shutdown nailgun server (if nailed)"),
    dummy(     "-idle-timeout <duration>",     "Set idle timeout (Nh|Nm|Ns) (if nailed)"),

    header("Analysis manipulation utilities:"),
    boolean(   "-analysis",                    "Run analysis manipulation utilities",        (s: Settings) => s.copy(analysisUtil = s.analysisUtil.copy(run = true))),
    file(      "-cache", "file",               "Analysis cache file to alter",               (s: Settings, f: File) => s.copy(analysisUtil = s.analysisUtil.copy(cache = Some(f)))),
    path(      "-merge", "path",               "Merge analyses, overwrite cached analysis",  (s: Settings, ap: Seq[File]) => s.copy(analysisUtil = s.analysisUtil.copy(merge = ap))),
    fileMap(   "-rebase",                      "Rebase all analysis paths (from:to,...)",    (s: Settings, m: Map[File, File]) => s.copy(analysisUtil = s.analysisUtil.copy(rebase = m))),
    fileSeqMap("-split",                       "Split analysis by source directory",         (s: Settings, m: Map[Seq[File], File]) => s.copy(analysisUtil = s.analysisUtil.copy(split = m))),
    file(      "-reload", "cache-file",        "Reload analysis from cache file",            (s: Settings, f: File) => s.copy(analysisUtil = s.analysisUtil.copy(reload = s.analysisUtil.reload :+ f)))
  )

  val allOptions: Set[OptionDef[Settings]] = options.toSet

  /**
   * Print out the usage message.
   */
  def printUsage(): Unit = {
    val column = options.map(_.length).max + 2
    println("Usage: %s <options> <sources>" format Setup.Command)
    options foreach { opt => if (opt.extraline) println(); println(opt.usage(column)) }
    println()
  }

  /**
   * Anything starting with '-' is considered an option, not a source file.
   */
  def isOpt(s: String) = s startsWith "-"

  /**
   * Parse all args into a Settings object.
   * Residual args are either unknown options or source files.
   */
  def parse(args: Seq[String]): Parsed[Settings] = {
    val Parsed(settings, remaining, errors) = Options.parse(Settings(), allOptions, args, stopOnError = false)
    val (unknown, residual) = remaining partition isOpt
    val sources = residual map (new File(_))
    val unknownErrors = unknown map ("Unknown option: " + _)
    Parsed(settings.copy(sources = sources), Seq.empty, errors ++ unknownErrors)
  }

  /**
   * Create a CompileOrder value based on string input.
   */
  def compileOrder(order: String): CompileOrder = {
    order.toLowerCase match {
      case "mixed"                                       => CompileOrder.Mixed
      case "java"  | "java-then-scala" | "javathenscala" => CompileOrder.JavaThenScala
      case "scala" | "scala-then-java" | "scalathenjava" => CompileOrder.ScalaThenJava
    }
  }

  /**
   * Normalise all relative paths to the actual current working directory, if provided.
   */
  def normalise(settings: Settings, cwd: Option[File]): Settings = {
    if (cwd.isEmpty) settings
    else {
      settings.copy(
        sources = Util.normaliseSeq(cwd)(settings.sources),
        classpath = Util.normaliseSeq(cwd)(settings.classpath),
        classesDirectory = Util.normalise(cwd)(settings.classesDirectory),
        scala = settings.scala.copy(
          home = Util.normaliseOpt(cwd)(settings.scala.home),
          path = Util.normaliseSeq(cwd)(settings.scala.path),
          compiler = Util.normaliseOpt(cwd)(settings.scala.compiler),
          library = Util.normaliseOpt(cwd)(settings.scala.library),
          extra = Util.normaliseSeq(cwd)(settings.scala.extra)
        ),
        javaHome = Util.normaliseOpt(cwd)(settings.javaHome),
        sbt = settings.sbt.copy(
          sbtInterface = Util.normaliseOpt(cwd)(settings.sbt.sbtInterface),
          compilerInterfaceSrc = Util.normaliseOpt(cwd)(settings.sbt.compilerInterfaceSrc)
        ),
        incOptions = settings.incOptions.copy(
          apiDumpDirectory = Util.normaliseOpt(cwd)(settings.incOptions.apiDumpDirectory),
          backup = Util.normaliseOpt(cwd)(settings.incOptions.backup)
        ),
        analysis = settings.analysis.copy(
          cache = Util.normaliseOpt(cwd)(settings.analysis.cache),
          cacheMap = Util.normaliseMap(cwd)(settings.analysis.cacheMap),
          outputRelations = Util.normaliseOpt(cwd)(settings.analysis.outputRelations),
          outputProducts = Util.normaliseOpt(cwd)(settings.analysis.outputProducts)
        ),
        analysisUtil = settings.analysisUtil.copy(
          cache = Util.normaliseOpt(cwd)(settings.analysisUtil.cache),
          merge = Util.normaliseSeq(cwd)(settings.analysisUtil.merge),
          rebase = settings.analysisUtil.rebase map Util.normalisePair(cwd),
          split = Util.normaliseSeqMap(cwd)(settings.analysisUtil.split),
          reload = Util.normaliseSeq(cwd)(settings.analysisUtil.reload)
        )
      )
    }
  }

  // helpers for creating options

  def boolean(opt: String, desc: String, action: Settings => Settings) = new BooleanOption[Settings](Seq(opt), desc, action)
  def boolean(opts: (String, String), desc: String, action: Settings => Settings) = new BooleanOption[Settings](Seq(opts._1, opts._2), desc, action)
  def string(opt: String, arg: String, desc: String, action: (Settings, String) => Settings) = new StringOption[Settings](Seq(opt), arg, desc, action)
  def int(opt: String, arg: String, desc: String, action: (Settings, Int) => Settings) = new IntOption[Settings](Seq(opt), arg, desc, action)
  def double(opt: String, arg: String, desc: String, action: (Settings, Double) => Settings) = new DoubleOption[Settings](Seq(opt), arg, desc, action)
  def fraction(opt: String, arg: String, desc: String, action: (Settings, Double) => Settings) = new FractionOption[Settings](Seq(opt), arg, desc, action)
  def file(opt: String, arg: String, desc: String, action: (Settings, File) => Settings) = new FileOption[Settings](Seq(opt), arg, desc, action)
  def path(opt: String, arg: String, desc: String, action: (Settings, Seq[File]) => Settings) = new PathOption[Settings](Seq(opt), arg, desc, action)
  def path(opts: (String, String), arg: String, desc: String, action: (Settings, Seq[File]) => Settings) = new PathOption[Settings](Seq(opts._1, opts._2), arg, desc, action)
  def prefix(pre: String, arg: String, desc: String, action: (Settings, String) => Settings) = new PrefixOption[Settings](pre, arg, desc, action)
  def filePair(opt: String, arg: String, desc: String, action: (Settings, (File, File)) => Settings) = new FilePairOption[Settings](Seq(opt), arg, desc, action)
  def fileMap(opt: String, desc: String, action: (Settings, Map[File, File]) => Settings) = new FileMapOption[Settings](Seq(opt), desc, action)
  def fileSeqMap(opt: String, desc: String, action: (Settings, Map[Seq[File], File]) => Settings) = new FileSeqMapOption[Settings](Seq(opt), desc, action)
  def header(label: String) = new HeaderOption[Settings](label)
  def dummy(opt: String, desc: String) = new DummyOption[Settings](opt, desc)
}

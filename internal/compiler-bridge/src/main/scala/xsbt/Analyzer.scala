/*
 * Zinc - The incremental compiler for Scala.
 * Copyright 2011 - 2017, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * This software is released under the terms written in LICENSE.
 */

package xsbt

import java.io.File

import scala.tools.nsc.Phase
import scala.collection.JavaConverters._

object Analyzer {
  def name = "xsbt-analyzer"
}

final class Analyzer(val global: CallbackGlobal) extends LocateClassFile {
  import global._

  def newPhase(prev: Phase): Phase = new AnalyzerPhase(prev)
  private class AnalyzerPhase(prev: Phase) extends GlobalPhase(prev) {
    override def description =
      "Finds concrete instances of provided superclasses, and application entry points."
    def name = Analyzer.name

    /**
     * When straight-to-jar compilation is enabled, returns the classes
     * that are found in the jar of the last compilation. This method
     * gets the existing classes from the analysis callback and adapts
     * it for consumption in the compiler bridge.
     *
     * It's lazy because it triggers a read of the zip, which may be
     * unnecessary if there are no local classes in a compilation unit.
     */
    private lazy val classesWrittenByGenbcode: Set[String] = {
      JarUtils.outputJar match {
        case Some(jar) =>
          val classes = global.callback.classesInOutputJar().asScala
          classes.map(JarUtils.classNameInJar(jar, _)).toSet
        case None => Set.empty
      }
    }

    def apply(unit: CompilationUnit): Unit = {
      if (!unit.isJava) {
        val sourceFile = unit.source.file.file
        for (iclass <- unit.icode) {
          val sym = iclass.symbol
          lazy val outputDir = settings.outputDirs.outputDirFor(sym.sourceFile).file
          def addGenerated(separatorRequired: Boolean): Unit = {
            val locatedClass = {
              JarUtils.outputJar match {
                case Some(outputJar) => locateClassInJar(sym, outputJar, separatorRequired)
                case None            => locatePlainClassFile(sym, separatorRequired)
              }
            }

            locatedClass.foreach { classFile =>
              assert(sym.isClass, s"${sym.fullName} is not a class")
              // Use own map of local classes computed before lambdalift to ascertain class locality
              if (localToNonLocalClass.isLocal(sym).getOrElse(true)) {
                // Inform callback about local classes, non-local classes have been reported in API
                callback.generatedLocalClass(sourceFile, classFile)
              }
            }
          }

          if (sym.isModuleClass && !sym.isImplClass) {
            if (isTopLevelModule(sym) && sym.companionClass == NoSymbol)
              addGenerated(false)
            addGenerated(true)
          } else
            addGenerated(false)
        }
      }
    }

    private def locatePlainClassFile(sym: Symbol, separatorRequired: Boolean): Option[File] = {
      val outputDir = settings.outputDirs.outputDirFor(sym.sourceFile).file
      val classFile = fileForClass(outputDir, sym, separatorRequired)
      if (classFile.exists()) Some(classFile) else None
    }

    private def locateClassInJar(sym: Symbol, jar: File, sepRequired: Boolean): Option[File] = {
      val classFile = pathToClassFile(sym, sepRequired)
      val classInJar = JarUtils.classNameInJar(jar, classFile)
      if (!classesWrittenByGenbcode.contains(classInJar)) None
      else Some(new File(classInJar))
    }
  }
}

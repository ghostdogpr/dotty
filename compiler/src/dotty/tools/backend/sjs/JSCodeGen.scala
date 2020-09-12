package dotty.tools.backend.sjs

import scala.annotation.switch

import scala.collection.mutable

import dotty.tools.FatalError

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Phases.Phase

import dotty.tools.dotc.core._
import Periods._
import SymDenotations._
import Contexts._
import Decorators._
import Flags._
import dotty.tools.dotc.ast.Trees._
import Names._
import Types._
import Symbols._
import Denotations._
import Phases._
import StdNames._
import TypeErasure.ErasedValueType

import dotty.tools.dotc.transform.{Erasure, ValueClasses}
import dotty.tools.dotc.transform.SymUtils._
import dotty.tools.dotc.util.SourcePosition
import dotty.tools.dotc.util.Spans.Span
import dotty.tools.dotc.report

import org.scalajs.ir
import org.scalajs.ir.{ClassKind, Position, Names => jsNames, Trees => js, Types => jstpe}
import org.scalajs.ir.Names.{ClassName, MethodName, SimpleMethodName}
import org.scalajs.ir.OriginalName
import org.scalajs.ir.OriginalName.NoOriginalName
import org.scalajs.ir.Trees.OptimizerHints

import dotty.tools.dotc.transform.sjs.JSSymUtils._

import JSEncoding._
import JSInterop._
import ScopedVar.withScopedVars

/** Main codegen for Scala.js IR.
 *
 *  [[GenSJSIR]] creates one instance of `JSCodeGen` per compilation unit.
 *  The `run()` method processes the whole compilation unit and generates
 *  `.sjsir` files for it.
 *
 *  There are 4 main levels of translation:
 *
 *  - `genCompilationUnit()` iterates through all the type definitions in the
 *    compilation unit. Each generated `js.ClassDef` is serialized to an
 *    `.sjsir` file.
 *  - `genScalaClass()` and other similar methods generate the skeleton of
 *    classes.
 *  - `genMethod()` and similar methods generate the declarations of methods.
 *  - `genStatOrExpr()` and everything else generate the bodies of methods.
 */
class JSCodeGen()(using genCtx: Context) {
  import JSCodeGen._
  import tpd._

  private val sjsPlatform = dotty.tools.dotc.config.SJSPlatform.sjsPlatform
  private val jsdefn = JSDefinitions.jsdefn
  private val primitives = new JSPrimitives(genCtx)

  private val positionConversions = new JSPositions()(using genCtx)
  import positionConversions._

  // Some state --------------------------------------------------------------

  private val generatedClasses = mutable.ListBuffer.empty[js.ClassDef]
  private val generatedStaticForwarderClasses = mutable.ListBuffer.empty[(Symbol, js.ClassDef)]

  private val currentClassSym = new ScopedVar[Symbol]
  private val currentMethodSym = new ScopedVar[Symbol]
  private val localNames = new ScopedVar[LocalNameGenerator]
  private val thisLocalVarIdent = new ScopedVar[Option[js.LocalIdent]]
  private val undefinedDefaultParams = new ScopedVar[mutable.Set[Symbol]]

  private def withNewLocalNameScope[A](body: => A): A = {
    withScopedVars(localNames := new LocalNameGenerator) {
      body
    }
  }

  /** Implicitly materializes the current local name generator. */
  private implicit def implicitLocalNames: LocalNameGenerator = localNames.get

  /* See genSuperCall()
   * TODO Can we avoid this unscoped var?
   */
  private var isModuleInitialized: Boolean = false

  private def currentClassType = encodeClassType(currentClassSym)

  /** Returns a new fresh local identifier. */
  private def freshLocalIdent()(implicit pos: Position): js.LocalIdent =
    localNames.get.freshLocalIdent()

  /** Returns a new fresh local identifier. */
  private def freshLocalIdent(base: String)(implicit pos: Position): js.LocalIdent =
    localNames.get.freshLocalIdent(base)

  /** Returns a new fresh local identifier. */
  private def freshLocalIdent(base: TermName)(implicit pos: Position): js.LocalIdent =
    localNames.get.freshLocalIdent(base)

  // Compilation unit --------------------------------------------------------

  def run(): Unit = {
    try {
      genCompilationUnit(ctx.compilationUnit)
    } finally {
      generatedClasses.clear()
      generatedStaticForwarderClasses.clear()
    }
  }

  /** Generates the Scala.js IR for a compilation unit
   *  This method iterates over all the class and interface definitions
   *  found in the compilation unit and emits their IR (.sjsir).
   *
   *  Some classes are never actually emitted:
   *  - Classes representing primitive types
   *  - The scala.Array class
   *
   *  TODO Some classes representing anonymous functions are not actually emitted.
   *  Instead, a temporary representation of their `apply` method is built
   *  and recorded, so that it can be inlined as a JavaScript anonymous
   *  function in the method that instantiates it.
   *
   *  Other ClassDefs are emitted according to their nature:
   *  * Scala.js-defined JS class     -> `genScalaJSDefinedJSClass()`
   *  * Other raw JS type (<: js.Any) -> `genRawJSClassData()`
   *  * Interface                     -> `genInterface()`
   *  * Normal class                  -> `genClass()`
   */
  private def genCompilationUnit(cunit: CompilationUnit): Unit = {
    def collectTypeDefs(tree: Tree): List[TypeDef] = {
      tree match {
        case EmptyTree            => Nil
        case PackageDef(_, stats) => stats.flatMap(collectTypeDefs)
        case cd: TypeDef          => cd :: Nil
        case _: ValDef            => Nil // module instance
      }
    }
    val allTypeDefs = collectTypeDefs(cunit.tpdTree)

    // TODO Record anonymous JS function classes

    /* Finally, we emit true code for the remaining class defs. */
    for (td <- allTypeDefs) {
      val sym = td.symbol
      implicit val pos: Position = sym.span

      /* Do not actually emit code for primitive types nor scala.Array. */
      val isPrimitive =
        sym.isPrimitiveValueClass || sym == defn.ArrayClass

      if (!isPrimitive) {
        withScopedVars(
            currentClassSym := sym
        ) {
          val tree = if (isJSType(sym)) {
            /*assert(!isRawJSFunctionDef(sym),
                s"Raw JS function def should have been recorded: $cd")*/
            if (!sym.is(Trait) && isScalaJSDefinedJSClass(sym))
              genScalaJSDefinedJSClass(td)
            else
              genRawJSClassData(td)
          } else if (sym.is(Trait)) {
            genInterface(td)
          } else {
            genScalaClass(td)
          }

          generatedClasses += tree
        }
      }
    }

    for (tree <- generatedClasses)
      genIRFile(cunit, tree)

    if (generatedStaticForwarderClasses.nonEmpty) {
      /* #4148 Add generated static forwarder classes, except those that
       * would collide with regular classes on case insensitive file systems.
       */

      /* I could not find any reference anywhere about what locale is used
       * by case insensitive file systems to compare case-insensitively.
       * In doubt, force the English locale, which is probably going to do
       * the right thing in virtually all cases (especially if users stick
       * to ASCII class names), and it has the merit of being deterministic,
       * as opposed to using the OS' default locale.
       * The JVM backend performs a similar test to emit a warning for
       * conflicting top-level classes. However, it uses `toLowerCase()`
       * without argument, which is not deterministic.
       */
      def caseInsensitiveNameOf(classDef: js.ClassDef): String =
        classDef.name.name.nameString.toLowerCase(java.util.Locale.ENGLISH)

      val generatedCaseInsensitiveNames =
        generatedClasses.map(caseInsensitiveNameOf).toSet

      for ((site, classDef) <- generatedStaticForwarderClasses) {
        if (!generatedCaseInsensitiveNames.contains(caseInsensitiveNameOf(classDef))) {
          genIRFile(cunit, classDef)
        } else {
          report.warning(
              s"Not generating the static forwarders of ${classDef.name.name.nameString} " +
              "because its name differs only in case from the name of another class or trait in this compilation unit.",
              site.srcPos)
        }
      }
    }
  }

  private def genIRFile(cunit: CompilationUnit, tree: ir.Trees.ClassDef): Unit = {
    val outfile = getFileFor(cunit, tree.name.name, ".sjsir")
    val output = outfile.bufferedOutput
    try {
      ir.Serializers.serialize(output, tree)
    } finally {
      output.close()
    }
  }

  private def getFileFor(cunit: CompilationUnit, className: ClassName,
      suffix: String): dotty.tools.io.AbstractFile = {
    val outputDirectory = ctx.settings.outputDir.value
    val pathParts = className.nameString.split('.')
    val dir = pathParts.init.foldLeft(outputDirectory)(_.subdirectoryNamed(_))
    val filename = pathParts.last
    dir.fileNamed(filename + suffix)
  }

  // Generate a class --------------------------------------------------------

  /** Gen the IR ClassDef for a Scala class definition (maybe a module class).
   */
  private def genScalaClass(td: TypeDef): js.ClassDef = {
    val sym = td.symbol.asClass
    implicit val pos: SourcePosition = sym.sourcePos

    assert(!sym.is(Trait),
        "genScalaClass() must be called only for normal classes: "+sym)
    assert(sym.superClass != NoSymbol, sym)

    /*if (hasDefaultCtorArgsAndRawJSModule(sym)) {
      reporter.error(pos,
          "Implementation restriction: constructors of " +
          "Scala classes cannot have default parameters " +
          "if their companion module is JS native.")
    }*/

    val classIdent = encodeClassNameIdent(sym)
    val originalName = originalNameOfClass(sym)
    val isHijacked = false //isHijackedBoxedClass(sym)

    // Optimizer hints

    def isStdLibClassWithAdHocInlineAnnot(sym: Symbol): Boolean = {
      val fullName = sym.fullName.toString
      (fullName.startsWith("scala.Tuple") && !fullName.endsWith("$")) ||
      (fullName.startsWith("scala.collection.mutable.ArrayOps$of"))
    }

    val shouldMarkInline = (
        sym.hasAnnotation(jsdefn.InlineAnnot) ||
        (sym.isAnonymousFunction && !sym.isSubClass(defn.PartialFunctionClass)) ||
        isStdLibClassWithAdHocInlineAnnot(sym))

    val optimizerHints = {
      OptimizerHints.empty
        .withInline(shouldMarkInline)
        .withNoinline(sym.hasAnnotation(jsdefn.NoinlineAnnot))
    }

    // Generate members (constructor + methods)

    val generatedNonFieldMembers = new mutable.ListBuffer[js.MemberDef]
    val exportedSymbols = new mutable.ListBuffer[Symbol]

    val tpl = td.rhs.asInstanceOf[Template]
    for (tree <- tpl.constr :: tpl.body) {
      tree match {
        case EmptyTree => ()

        case _: ValDef =>
          () // fields are added via genClassFields()

        case dd: DefDef =>
          val sym = dd.symbol

          val isExport = false //jsInterop.isExport(sym)

          if (sym.hasAnnotation(jsdefn.JSNativeAnnot))
            generatedNonFieldMembers += genJSNativeMemberDef(dd)
          else
            generatedNonFieldMembers ++= genMethod(dd)

          if (isExport) {
            // We add symbols that we have to export here. This way we also
            // get inherited stuff that is implemented in this class.
            exportedSymbols += sym
          }

        case _ =>
          throw new FatalError("Illegal tree in body of genScalaClass(): " + tree)
      }
    }

    // Generate fields and add to methods + ctors
    val generatedMembers = genClassFields(td) ++ generatedNonFieldMembers.toList

    // Generate the exported members, constructors and accessors
    val exports = {
      /*
      // Generate the exported members
      val memberExports = genMemberExports(sym, exportedSymbols.toList)

      // Generate exported constructors or accessors
      val exportedConstructorsOrAccessors =
        if (isStaticModule(sym)) genModuleAccessorExports(sym)
        else genConstructorExports(sym)

      memberExports ++ exportedConstructorsOrAccessors
      */
      Nil
    }

    // Static initializer
    val optStaticInitializer = {
      // Initialization of reflection data, if required
      val reflectInit = {
        val enableReflectiveInstantiation = {
          sym.baseClasses.exists { ancestor =>
            ancestor.hasAnnotation(jsdefn.EnableReflectiveInstantiationAnnot)
          }
        }
        if (enableReflectiveInstantiation)
          genRegisterReflectiveInstantiation(sym)
        else
          None
      }

      val staticInitializerStats = reflectInit.toList
      if (staticInitializerStats.nonEmpty)
        List(genStaticInitializerWithStats(js.Block(staticInitializerStats)))
      else
        Nil
    }

    val allMemberDefsExceptStaticForwarders =
      generatedMembers ::: exports ::: optStaticInitializer

    // Add static forwarders
    val allMemberDefs = if (!isCandidateForForwarders(sym)) {
      allMemberDefsExceptStaticForwarders
    } else {
      if (isStaticModule(sym)) {
        /* If the module class has no linked class, we must create one to
         * hold the static forwarders. Otherwise, this is going to be handled
         * when generating the companion class.
         */
        if (!sym.linkedClass.exists) {
          val forwarders = genStaticForwardersFromModuleClass(Nil, sym)
          if (forwarders.nonEmpty) {
            val forwardersClassDef = js.ClassDef(
                js.ClassIdent(ClassName(classIdent.name.nameString.stripSuffix("$"))),
                originalName,
                ClassKind.Class,
                None,
                Some(js.ClassIdent(ir.Names.ObjectClass)),
                Nil,
                None,
                None,
                forwarders,
                Nil
            )(js.OptimizerHints.empty)
            generatedStaticForwarderClasses += sym -> forwardersClassDef
          }
        }
        allMemberDefsExceptStaticForwarders
      } else {
        val forwarders = genStaticForwardersForClassOrInterface(
            allMemberDefsExceptStaticForwarders, sym)
        allMemberDefsExceptStaticForwarders ::: forwarders
      }
    }

    // Hashed definitions of the class
    val hashedDefs = ir.Hashers.hashMemberDefs(allMemberDefs)

    // The complete class definition
    val kind =
      if (isStaticModule(sym)) ClassKind.ModuleClass
      else if (isHijacked) ClassKind.HijackedClass
      else ClassKind.Class

    val classDefinition = js.ClassDef(
        classIdent,
        originalName,
        kind,
        None,
        Some(encodeClassNameIdent(sym.superClass)),
        genClassInterfaces(sym),
        None,
        None,
        hashedDefs,
        Nil)(
        optimizerHints)

    classDefinition
  }

  /** Gen the IR ClassDef for a Scala.js-defined JS class. */
  private def genScalaJSDefinedJSClass(td: TypeDef): js.ClassDef = {
    val sym = td.symbol.asClass
    implicit val pos: SourcePosition = sym.sourcePos

    assert(!sym.is(Trait),
        "genScalaJSDefinedJSClass() must be called only for normal classes: "+sym)
    assert(sym.superClass != NoSymbol, sym)

    val classIdent = encodeClassNameIdent(sym)
    val originalName = originalNameOfClass(sym)

    report.error("cannot emit non-native JS classes yet", td.sourcePos)

    // Dummy result
    js.ClassDef(
        classIdent,
        originalName,
        ClassKind.JSClass,
        None,
        Some(encodeClassNameIdent(sym.superClass)),
        genClassInterfaces(sym),
        None,
        None,
        Nil,
        Nil)(
        OptimizerHints.empty)
  }

  /** Gen the IR ClassDef for a raw JS class or trait.
   */
  private def genRawJSClassData(td: TypeDef): js.ClassDef = {
    val sym = td.symbol.asClass
    implicit val pos: Position = sym.span

    val classIdent = encodeClassNameIdent(sym)
    val kind = {
      if (sym.is(Trait)) ClassKind.AbstractJSType
      else if (sym.is(ModuleClass)) ClassKind.NativeJSModuleClass
      else ClassKind.NativeJSClass
    }
    val superClass =
      if (sym.is(Trait)) None
      else Some(encodeClassNameIdent(sym.superClass))
    val jsNativeLoadSpec = computeJSNativeLoadSpecOfClass(sym)

    js.ClassDef(
        classIdent,
        originalNameOfClass(sym),
        kind,
        None,
        superClass,
        genClassInterfaces(sym),
        None,
        jsNativeLoadSpec,
        Nil,
        Nil)(
        OptimizerHints.empty)
  }

  /** Gen the IR ClassDef for an interface definition.
   */
  private def genInterface(td: TypeDef): js.ClassDef = {
    val sym = td.symbol.asClass
    implicit val pos: SourcePosition = sym.sourcePos

    val classIdent = encodeClassNameIdent(sym)

    val generatedMethods = new mutable.ListBuffer[js.MethodDef]

    val tpl = td.rhs.asInstanceOf[Template]
    for (tree <- tpl.constr :: tpl.body) {
      tree match {
        case EmptyTree  => ()
        case dd: DefDef => generatedMethods ++= genMethod(dd)
        case _ =>
          throw new FatalError(
            i"""Illegal tree in gen of genInterface(): $tree
               |class = $td
               |in ${ctx.compilationUnit}""")
      }
    }

    val superInterfaces = genClassInterfaces(sym)

    val genMethodsList = generatedMethods.toList
    val allMemberDefs =
      if (!isCandidateForForwarders(sym)) genMethodsList
      else genMethodsList ::: genStaticForwardersForClassOrInterface(genMethodsList, sym)

    // Hashed definitions of the interface
    val hashedDefs = ir.Hashers.hashMemberDefs(allMemberDefs)

    js.ClassDef(
        classIdent,
        originalNameOfClass(sym),
        ClassKind.Interface,
        None,
        None,
        superInterfaces,
        None,
        None,
        hashedDefs,
        Nil)(
        OptimizerHints.empty)
  }

  private def genClassInterfaces(sym: ClassSymbol)(
      implicit pos: Position): List[js.ClassIdent] = {
    for {
      intf <- sym.directlyInheritedTraits
    } yield {
      encodeClassNameIdent(intf)
    }
  }

  // Static forwarders -------------------------------------------------------

  /* This mimics the logic in BCodeHelpers.addForwarders and the code that
   * calls it, except that we never have collisions with existing methods in
   * the companion class. This is because in the IR, only methods with the
   * same `MethodName` (including signature) and that are also
   * `PublicStatic` would collide. There should never be an actual collision
   * because the only `PublicStatic` methods that are otherwise generated are
   * the bodies of SAMs, which have mangled names. If that assumption is
   * broken, an error message is emitted asking the user to report a bug.
   *
   * It is important that we always emit forwarders, because some Java APIs
   * actually have a public static method and a public instance method with
   * the same name. For example the class `Integer` has a
   * `def hashCode(): Int` and a `static def hashCode(Int): Int`. The JVM
   * back-end considers them as colliding because they have the same name,
   * but we must not.
   *
   * By default, we only emit forwarders for top-level objects, like the JVM
   * back-end. However, if requested via a compiler option, we enable them
   * for all static objects. This is important so we can implement static
   * methods of nested static classes of JDK APIs (see scala-js/#3950).
   */

  /** Is the given Scala class, interface or module class a candidate for
   *  static forwarders?
   *
   *  - the flag `-XnoForwarders` is not set to true, and
   *  - the symbol is static, and
   *  - either of both of the following is true:
   *    - the flag `-scalajsGenStaticForwardersForNonTopLevelObjects` is set to true, or
   *    - the symbol was originally at the package level
   *
   *  Other than the Scala.js-specific flag, and the fact that we also consider
   *  interfaces, this performs the same tests as the JVM back-end.
   */
  def isCandidateForForwarders(sym: Symbol): Boolean = {
    !ctx.settings.XnoForwarders.value && sym.isStatic && {
      ctx.settings.scalajsGenStaticForwardersForNonTopLevelObjects.value || {
        atPhase(flattenPhase) {
          toDenot(sym).owner.is(PackageClass)
        }
      }
    }
  }

  /** Gen the static forwarders to the members of a class or interface for
   *  methods of its companion object.
   *
   *  This is only done if there exists a companion object and it is not a JS
   *  type.
   *
   *  Precondition: `isCandidateForForwarders(sym)` is true
   */
  def genStaticForwardersForClassOrInterface(
      existingMembers: List[js.MemberDef], sym: Symbol)(
      implicit pos: SourcePosition): List[js.MemberDef] = {
    val module = sym.companionModule
    if (!module.exists) {
      Nil
    } else {
      val moduleClass = module.moduleClass
      if (!isJSType(moduleClass))
        genStaticForwardersFromModuleClass(existingMembers, moduleClass)
      else
        Nil
    }
  }

  /** Gen the static forwarders for the methods of a module class.
   *
   *  Precondition: `isCandidateForForwarders(moduleClass)` is true
   */
  def genStaticForwardersFromModuleClass(existingMembers: List[js.MemberDef],
      moduleClass: Symbol)(
      implicit pos: SourcePosition): List[js.MemberDef] = {

    assert(moduleClass.is(ModuleClass), moduleClass)

    val existingPublicStaticMethodNames = existingMembers.collect {
      case js.MethodDef(flags, name, _, _, _, _)
          if flags.namespace == js.MemberNamespace.PublicStatic =>
        name.name
    }.toSet

    val members = {
      moduleClass.info.membersBasedOnFlags(required = Flags.Method,
          excluded = Flags.ExcludedForwarder).map(_.symbol)
    }

    def isExcluded(m: Symbol): Boolean = {
      def hasAccessBoundary = m.accessBoundary(defn.RootClass) ne defn.RootClass
      m.is(Deferred) || m.isConstructor || hasAccessBoundary || (m.owner eq defn.ObjectClass)
    }

    val forwarders = for {
      m <- members
      if !isExcluded(m)
    } yield {
      withNewLocalNameScope {
        val flags = js.MemberFlags.empty.withNamespace(js.MemberNamespace.PublicStatic)
        val methodIdent = encodeMethodSym(m)
        val originalName = originalNameOfMethod(m)
        val jsParams = for {
          (paramName, paramInfo) <- m.info.paramNamess.flatten.zip(m.info.paramInfoss.flatten)
        } yield {
          js.ParamDef(freshLocalIdent(paramName), NoOriginalName,
              toIRType(paramInfo), mutable = false, rest = false)
        }
        val resultType = toIRType(m.info.resultType)

        if (existingPublicStaticMethodNames.contains(methodIdent.name)) {
          report.error(
              "Unexpected situation: found existing public static method " +
              s"${methodIdent.name.nameString} in the companion class of " +
              s"${moduleClass.fullName}; cannot generate a static forwarder " +
              "the method of the same name in the object." +
              "Please report this as a bug in the Scala.js support in dotty.",
              pos)
        }

        js.MethodDef(flags, methodIdent, originalName, jsParams, resultType, Some {
          genApplyMethod(genLoadModule(moduleClass), m, jsParams.map(_.ref))
        })(OptimizerHints.empty, None)
      }
    }

    forwarders.toList
  }

  // Generate the fields of a class ------------------------------------------

  /** Gen definitions for the fields of a class.
   */
  private def genClassFields(td: TypeDef): List[js.FieldDef] = {
    val classSym = td.symbol.asClass
    assert(currentClassSym.get == classSym,
        "genClassFields called with a ClassDef other than the current one")

    // Term members that are neither methods nor modules are fields
    classSym.info.decls.filter { f =>
      !f.isOneOf(Method | Module) && f.isTerm
        && !f.hasAnnotation(jsdefn.JSNativeAnnot)
    }.map({ f =>
      implicit val pos = f.span

      val name =
        /*if (isExposed(f)) js.StringLiteral(jsNameOf(f))
        else*/ encodeFieldSym(f)

      val irTpe = //if (!isScalaJSDefinedJSClass(classSym)) {
        toIRType(f.info)
      /*} else {
        val tpeEnteringPosterasure =
          enteringPhase(currentRun.posterasurePhase)(f.tpe)
        tpeEnteringPosterasure match {
          case tpe: ErasedValueType =>
            /* Here, we must store the field as the boxed representation of
             * the value class. The default value of that field, as
             * initialized at the time the instance is created, will
             * therefore be null. This will not match the behavior we would
             * get in a Scala class. To match the behavior, we would need to
             * initialized to an instance of the boxed representation, with
             * an underlying value set to the zero of its type. However we
             * cannot implement that, so we live with the discrepancy.
             * Anyway, scalac also has problems with uninitialized value
             * class values, if they come from a generic context.
             *
             * TODO Evaluate how much of this needs to be adapted for dotc,
             * which unboxes `null` to the zero of their underlying.
             */
            jstpe.ClassType(encodeClassFullName(tpe.valueClazz))

          case _ if f.tpe.typeSymbol == CharClass =>
            /* Will be initialized to null, which will unbox to '\0' when
             * read.
             */
            jstpe.ClassType(ir.Definitions.BoxedCharacterClass)

          case _ =>
            /* Other types are not boxed, so we can initialized them to
             * their true zero.
             */
            toIRType(f.tpe)
        }
      }*/

      val flags = js.MemberFlags.empty.withMutable(f.is(Mutable))
      js.FieldDef(flags, name, originalNameOfField(f), irTpe)
    }).toList
  }

  // Static initializers -----------------------------------------------------

  private def genStaticInitializerWithStats(stats: js.Tree)(
      implicit pos: Position): js.MethodDef = {
    js.MethodDef(
        js.MemberFlags.empty.withNamespace(js.MemberNamespace.StaticConstructor),
        js.MethodIdent(ir.Names.StaticInitializerName),
        NoOriginalName,
        Nil,
        jstpe.NoType,
        Some(stats))(
        OptimizerHints.empty, None)
  }

  private def genRegisterReflectiveInstantiation(sym: Symbol)(
      implicit pos: SourcePosition): Option[js.Tree] = {
    if (isStaticModule(sym))
      genRegisterReflectiveInstantiationForModuleClass(sym)
    else if (sym.is(ModuleClass))
      None // scala-js#3228
    else if (sym.is(Lifted) && !sym.originalOwner.isClass)
      None // scala-js#3227
    else
      genRegisterReflectiveInstantiationForNormalClass(sym)
  }

  private def genRegisterReflectiveInstantiationForModuleClass(sym: Symbol)(
      implicit pos: SourcePosition): Option[js.Tree] = {
    val fqcnArg = js.StringLiteral(sym.fullName.toString)
    val runtimeClassArg = js.ClassOf(toTypeRef(sym.info))
    val loadModuleFunArg =
      js.Closure(arrow = true, Nil, Nil, genLoadModule(sym), Nil)

    val stat = genApplyMethod(
        genLoadModule(jsdefn.ReflectModule),
        jsdefn.Reflect_registerLoadableModuleClass,
        List(fqcnArg, runtimeClassArg, loadModuleFunArg))

    Some(stat)
  }

  private def genRegisterReflectiveInstantiationForNormalClass(sym: Symbol)(
      implicit pos: SourcePosition): Option[js.Tree] = {
    val ctors =
      if (sym.is(Abstract)) Nil
      else sym.info.member(nme.CONSTRUCTOR).alternatives.map(_.symbol).filter(m => !m.isOneOf(Private | Protected))

    if (ctors.isEmpty) {
      None
    } else {
      val constructorsInfos = for {
        ctor <- ctors
      } yield {
        withNewLocalNameScope {
          val (parameterTypes, formalParams, actualParams) = (for {
            (paramName, paramInfo) <- ctor.info.paramNamess.flatten.zip(ctor.info.paramInfoss.flatten)
          } yield {
            val paramType = js.ClassOf(toTypeRef(paramInfo))
            val paramDef = js.ParamDef(freshLocalIdent(paramName),
                NoOriginalName, jstpe.AnyType, mutable = false, rest = false)
            val actualParam = unbox(paramDef.ref, paramInfo)
            (paramType, paramDef, actualParam)
          }).unzip3

          val paramTypesArray = js.JSArrayConstr(parameterTypes)

          val newInstanceFun = js.Closure(arrow = true, Nil, formalParams, {
            js.New(encodeClassName(sym), encodeMethodSym(ctor), actualParams)
          }, Nil)

          js.JSArrayConstr(List(paramTypesArray, newInstanceFun))
        }
      }

      val fqcnArg = js.StringLiteral(sym.fullName.toString)
      val runtimeClassArg = js.ClassOf(toTypeRef(sym.info))
      val ctorsInfosArg = js.JSArrayConstr(constructorsInfos)

      val stat = genApplyMethod(
          genLoadModule(jsdefn.ReflectModule),
          jsdefn.Reflect_registerInstantiatableClass,
          List(fqcnArg, runtimeClassArg, ctorsInfosArg))

      Some(stat)
    }
  }

  // Generate a method -------------------------------------------------------

  /** Generates the JSNativeMemberDef. */
  def genJSNativeMemberDef(tree: DefDef): js.JSNativeMemberDef = {
    implicit val pos = tree.span

    val sym = tree.symbol
    val flags = js.MemberFlags.empty.withNamespace(js.MemberNamespace.PublicStatic)
    val methodName = encodeMethodSym(sym)
    val jsNativeLoadSpec = computeJSNativeLoadSpecOfValDef(sym)
    js.JSNativeMemberDef(flags, methodName, jsNativeLoadSpec)
  }

  private def genMethod(dd: DefDef): Option[js.MethodDef] = {
    withScopedVars(
        localNames := new LocalNameGenerator
    ) {
      genMethodWithCurrentLocalNameScope(dd)
    }
  }

  /** Gen JS code for a method definition in a class or in an impl class.
   *  On the JS side, method names are mangled to encode the full signature
   *  of the Scala method, as described in `JSEncoding`, to support
   *  overloading.
   *
   *  Some methods are not emitted at all:
   *  - Primitives, since they are never actually called
   *  - Constructors of hijacked classes
   *
   *  Constructors are emitted by generating their body as a statement.
   *
   *  Other (normal) methods are emitted with `genMethodBody()`.
   */
  private def genMethodWithCurrentLocalNameScope(dd: DefDef): Option[js.MethodDef] = {
    implicit val pos = dd.span
    val sym = dd.symbol
    val vparamss = dd.vparamss
    val rhs = dd.rhs

    isModuleInitialized = false

    withScopedVars(
        currentMethodSym       := sym,
        undefinedDefaultParams := mutable.Set.empty,
        thisLocalVarIdent      := None
    ) {
      assert(vparamss.isEmpty || vparamss.tail.isEmpty,
          "Malformed parameter list: " + vparamss)
      val params = if (vparamss.isEmpty) Nil else vparamss.head.map(_.symbol)

      val isJSClassConstructor =
        sym.isClassConstructor && isScalaJSDefinedJSClass(currentClassSym)

      val methodName = encodeMethodSym(sym)
      val originalName = originalNameOfMethod(sym)

      def jsParams = for (param <- params) yield {
        implicit val pos = param.span
        js.ParamDef(encodeLocalSym(param), originalNameOfLocal(param),
            toIRType(param.info), mutable = false, rest = false)
      }

      if (primitives.isPrimitive(sym)) {
        None
      } else if (sym.is(Deferred)) {
        Some(js.MethodDef(js.MemberFlags.empty, methodName, originalName,
            jsParams, toIRType(patchedResultType(sym)), None)(
            OptimizerHints.empty, None))
      } else /*if (isJSNativeCtorDefaultParam(sym)) {
        None
      } else if (sym.isClassConstructor && isHijackedBoxedClass(sym.owner)) {
        None
      } else*/ {
        /*def isTraitImplForwarder = dd.rhs match {
          case app: Apply => foreignIsImplClass(app.symbol.owner)
          case _          => false
        }*/

        val shouldMarkInline = {
          sym.hasAnnotation(jsdefn.InlineAnnot) ||
          sym.isAnonymousFunction
        }

        val shouldMarkNoinline = {
          sym.hasAnnotation(jsdefn.NoinlineAnnot) /*&&
          !isTraitImplForwarder*/
        }

        val optimizerHints = {
          OptimizerHints.empty
            .withInline(shouldMarkInline)
            .withNoinline(shouldMarkNoinline)
        }

        val methodDef = {
          /*if (isJSClassConstructor) {
            val body0 = genStat(rhs)
            val body1 =
              if (!sym.isPrimaryConstructor) body0
              else moveAllStatementsAfterSuperConstructorCall(body0)
            js.MethodDef(js.MemberFlags.empty, methodName,
                jsParams, jstpe.NoType, body1)(optimizerHints, None)
          } else*/ if (sym.isClassConstructor) {
            val namespace = js.MemberNamespace.Constructor
            js.MethodDef(js.MemberFlags.empty.withNamespace(namespace),
                methodName, originalName, jsParams, jstpe.NoType,
                Some(genStat(rhs)))(optimizerHints, None)
          } else {
            val namespace = if (isMethodStaticInIR(sym)) {
              if (sym.isPrivate) js.MemberNamespace.PrivateStatic
              else js.MemberNamespace.PublicStatic
            } else {
              if (sym.isPrivate) js.MemberNamespace.Private
              else js.MemberNamespace.Public
            }
            val resultIRType = toIRType(patchedResultType(sym))
            genMethodDef(namespace, methodName, originalName,
                params, resultIRType, rhs, optimizerHints)
          }
        }

        Some(methodDef)
      }
    }
  }

  /** Generates the MethodDef of a (non-constructor) method
   *
   *  Most normal methods are emitted straightforwardly. If the result
   *  type is Unit, then the body is emitted as a statement. Otherwise, it is
   *  emitted as an expression.
   *
   *  Methods Scala.js-defined JS classes are compiled as static methods taking
   *  an explicit parameter for their `this` value.
   */
  private def genMethodDef(namespace: js.MemberNamespace, methodName: js.MethodIdent,
      originalName: OriginalName,paramsSyms: List[Symbol], resultIRType: jstpe.Type,
      tree: Tree, optimizerHints: OptimizerHints): js.MethodDef = {
    implicit val pos = tree.span

    val jsParams = for (param <- paramsSyms) yield {
      implicit val pos = param.span
      js.ParamDef(encodeLocalSym(param), originalNameOfLocal(param),
          toIRType(param.info), mutable = false, rest = false)
    }

    def genBody() = localNames.makeLabeledIfRequiresEnclosingReturn(resultIRType) {
      if (resultIRType == jstpe.NoType) genStat(tree)
      else genExpr(tree)
    }

    //if (!isScalaJSDefinedJSClass(currentClassSym)) {
    val flags = js.MemberFlags.empty.withNamespace(namespace)
    js.MethodDef(flags, methodName, originalName, jsParams, resultIRType, Some(genBody()))(
          optimizerHints, None)
    /*} else {
      assert(!namespace.isStatic, tree.span)

      withScopedVars(
        thisLocalVarIdent := Some(freshLocalIdent("this"))
      ) {
        val thisParamDef = js.ParamDef(thisLocalVarIdent.get.get,
            jstpe.AnyType, mutable = false, rest = false)

        js.MethodDef(static = true, methodName, thisParamDef :: jsParams,
            resultIRType, genBody())(
            optimizerHints, None)
      }
    }*/
  }

  // Generate statements and expressions -------------------------------------

  /** Gen JS code for a tree in statement position (in the IR).
   */
  private def genStat(tree: Tree): js.Tree = {
    exprToStat(genStatOrExpr(tree, isStat = true))
  }

  /** Turn a JavaScript expression of type Unit into a statement */
  private def exprToStat(tree: js.Tree): js.Tree = {
    /* Any JavaScript expression is also a statement, but at least we get rid
     * of some pure expressions that come from our own codegen.
     */
    implicit val pos = tree.pos
    tree match {
      case js.Block(stats :+ expr)  => js.Block(stats :+ exprToStat(expr))
      case _:js.Literal | js.This() => js.Skip()
      case _                        => tree
    }
  }

  /** Gen JS code for a tree in expression position (in the IR).
   */
  private def genExpr(tree: Tree): js.Tree = {
    val result = genStatOrExpr(tree, isStat = false)
    assert(result.tpe != jstpe.NoType,
        s"genExpr($tree) returned a tree with type NoType at pos ${tree.span}")
    result
  }

  private def genExpr(name: JSName)(implicit pos: SourcePosition): js.Tree = name match {
    case JSName.Literal(name) => js.StringLiteral(name)
    case JSName.Computed(sym) => genComputedJSName(sym)
  }

  private def genComputedJSName(sym: Symbol)(implicit pos: SourcePosition): js.Tree = {
    /* By construction (i.e. restriction in PrepJSInterop), we know that sym
     * must be a static method.
     * Therefore, at this point, we can invoke it by loading its owner and
     * calling it.
     */
    def moduleOrGlobalScope = genLoadModuleOrGlobalScope(sym.owner)
    def module = genLoadModule(sym.owner)

    if (sym.owner.isJSType) {
      if (!sym.owner.isNonNativeJSClass || sym.isJSExposed)
        genApplyJSMethodGeneric(sym, moduleOrGlobalScope, args = Nil, isStat = false)
      else
        genApplyJSClassMethod(module, sym, arguments = Nil)
    } else {
      genApplyMethod(module, sym, arguments = Nil)
    }
  }

  /** Gen JS code for a tree in expression position (in the IR) or the
   *  global scope.
   */
  def genExprOrGlobalScope(tree: Tree): MaybeGlobalScope = {
    implicit def pos: SourcePosition = tree.sourcePos

    tree match {
      case _: This =>
        val sym = tree.symbol
        if (sym != currentClassSym.get && sym.is(Module))
          genLoadModuleOrGlobalScope(sym)
        else
          MaybeGlobalScope.NotGlobalScope(genExpr(tree))

      case _:Ident | _:Select =>
        val sym = tree.symbol
        if (sym.is(Module)) {
          assert(!sym.is(PackageClass), "Cannot use package as value: " + tree)
          genLoadModuleOrGlobalScope(sym)
        } else {
          MaybeGlobalScope.NotGlobalScope(genExpr(tree))
        }

      case Apply(fun, _) =>
        if (fun.symbol == jsdefn.JSDynamic_global)
          MaybeGlobalScope.GlobalScope(pos)
        else
          MaybeGlobalScope.NotGlobalScope(genExpr(tree))

      case _ =>
        MaybeGlobalScope.NotGlobalScope(genExpr(tree))
    }
  }

  /** Gen JS code for a tree in statement or expression position (in the IR).
   *
   *  This is the main transformation method. Each node of the Scala AST
   *  is transformed into an equivalent portion of the JS AST.
   */
  private def genStatOrExpr(tree: Tree, isStat: Boolean): js.Tree = {
    implicit val pos: SourcePosition = tree.sourcePos

    report.debuglog("  " + tree)
    report.debuglog("")

    tree match {
      /** Local val or var declaration */
      case tree @ ValDef(name, _, _) =>
        /* Must have been eliminated by the tail call transform performed
         * by genMethodBody(). */
        assert(name != nme.THIS,
            s"ValDef(_, nme.THIS, _, _) found at ${tree.span}")

        val sym = tree.symbol
        val rhs = tree.rhs
        val rhsTree = genExpr(rhs)

        rhsTree match {
          case js.Transient(UndefinedParam) =>
            /* This is an intermediate assignment for default params on a
             * js.Any. Add the symbol to the corresponding set to inform
             * the Ident resolver how to replace it and don't emit the symbol.
             */
            undefinedDefaultParams += sym
            js.Skip()
          case _ =>
            js.VarDef(encodeLocalSym(sym), originalNameOfLocal(sym),
                toIRType(sym.info), sym.is(Mutable), rhsTree)
        }

      case If(cond, thenp, elsep) =>
        js.If(genExpr(cond), genStatOrExpr(thenp, isStat),
            genStatOrExpr(elsep, isStat))(toIRType(tree.tpe))

      case Labeled(bind, expr) =>
        js.Labeled(encodeLabelSym(bind.symbol), toIRType(tree.tpe), genStatOrExpr(expr, isStat))

      case Return(expr, from) =>
        val fromSym = from.symbol
        val label =
          if (fromSym.is(Label)) encodeLabelSym(fromSym)
          else localNames.get.getEnclosingReturnLabel()
        js.Return(toIRType(expr.tpe) match {
          case jstpe.NoType => js.Block(genStat(expr), js.Undefined())
          case _            => genExpr(expr)
        }, label)

      case WhileDo(cond, body) =>
        val genCond =
          if (cond == EmptyTree) js.BooleanLiteral(true)
          else genExpr(cond)
        js.While(genCond, genStat(body))

      case t: Try =>
        genTry(t, isStat)

      case app: Apply =>
        genApply(app, isStat)

      case app: TypeApply =>
        genTypeApply(app)

      /*case app: ApplyDynamic =>
        genApplyDynamic(app)*/

      case tree: This =>
        val currentClass = currentClassSym.get
        val symIsModuleClass = tree.symbol.is(ModuleClass)
        assert(tree.symbol == currentClass || symIsModuleClass,
            s"Trying to access the this of another class: tree.symbol = ${tree.symbol}, class symbol = $currentClass")
        if (symIsModuleClass && tree.symbol != currentClass)
          genLoadModule(tree.symbol)
        else
          genThis()

      case Select(qualifier, _) =>
        val sym = tree.symbol
        if (sym.is(Module)) {
          assert(!sym.is(Package), "Cannot use package as value: " + tree)
          genLoadModule(sym)
        } else if (sym.is(JavaStatic)) {
          genLoadStaticField(sym)
        } else /*if (paramAccessorLocals contains sym) {
          paramAccessorLocals(sym).ref
        } else if (isScalaJSDefinedJSClass(sym.owner)) {
          val genQual = genExpr(qualifier)
          val boxed = if (isExposed(sym))
            js.JSBracketSelect(genQual, js.StringLiteral(jsNameOf(sym)))
          else
            js.JSDotSelect(genQual, encodeFieldSym(sym))
          fromAny(boxed,
              enteringPhase(currentRun.posterasurePhase)(sym.tpe))
        } else*/ {
          js.Select(genExpr(qualifier), encodeClassName(sym.owner),
              encodeFieldSym(sym))(toIRType(sym.info))
        }

      case tree: Ident =>
        desugarIdent(tree).fold[js.Tree] {
          val sym = tree.symbol
          assert(!sym.is(Package), "Cannot use package as value: " + tree)
          if (sym.is(Module)) {
            genLoadModule(sym)
          } else if (undefinedDefaultParams.contains(sym)) {
            /* This is a default parameter whose assignment was moved to
             * a local variable. Put an undefined param instead.
             */
            js.Transient(UndefinedParam)(toIRType(sym.info))
          } else {
            js.VarRef(encodeLocalSym(sym))(toIRType(sym.info))
          }
        } { select =>
          genStatOrExpr(select, isStat)
        }

      case Literal(value) =>
        import Constants._
        value.tag match {
          case UnitTag =>
            js.Skip()
          case BooleanTag =>
            js.BooleanLiteral(value.booleanValue)
          case ByteTag =>
            js.ByteLiteral(value.byteValue)
          case ShortTag =>
            js.ShortLiteral(value.shortValue)
          case CharTag =>
            js.CharLiteral(value.charValue)
          case IntTag =>
            js.IntLiteral(value.intValue)
          case LongTag =>
            js.LongLiteral(value.longValue)
          case FloatTag =>
            js.FloatLiteral(value.floatValue)
          case DoubleTag =>
            js.DoubleLiteral(value.doubleValue)
          case StringTag =>
            js.StringLiteral(value.stringValue)
          case NullTag =>
            js.Null()
          case ClazzTag =>
            genClassConstant(value.typeValue)
          case EnumTag =>
            genLoadStaticField(value.symbolValue)
        }

      case Block(stats, expr) =>
        js.Block(stats.map(genStat) :+ genStatOrExpr(expr, isStat))

      case Typed(expr, _) =>
        expr match {
          case _: Super => genThis()
          case _        => genExpr(expr)
        }

      case Assign(lhs0, rhs) =>
        val sym = lhs0.symbol
        if (sym.is(JavaStaticTerm))
          throw new FatalError(s"Assignment to static member ${sym.fullName} not supported")
        def genRhs = genExpr(rhs)
        val lhs = lhs0 match {
          case lhs: Ident => desugarIdent(lhs).getOrElse(lhs)
          case lhs => lhs
        }
        lhs match {
          case lhs: Select =>
            val qualifier = lhs.qualifier

            def ctorAssignment = (
                currentMethodSym.get.name == nme.CONSTRUCTOR &&
                currentMethodSym.get.owner == qualifier.symbol &&
                qualifier.isInstanceOf[This]
            )
            // TODO This fails for OFFSET$x fields. Re-enable when we can.
            /*if (!sym.is(Mutable) && !ctorAssignment)
              throw new FatalError(s"Assigning to immutable field ${sym.fullName} at $pos")*/

            val genQual = genExpr(qualifier)

            if (sym.hasAnnotation(jsdefn.JSNativeAnnot)) {
              /* This is an assignment to a @js.native field. Since we reject
               * `@js.native var`s as compile errors, this can only happen in
               * the constructor of the enclosing object.
               * We simply ignore the assignment, since the field will not be
               * emitted at all.
               */
              js.Skip()
            } else /*if (isScalaJSDefinedJSClass(sym.owner)) {
              val genLhs = if (isExposed(sym))
                js.JSBracketSelect(genQual, js.StringLiteral(jsNameOf(sym)))
              else
                js.JSDotSelect(genQual, encodeFieldSym(sym))
              val boxedRhs =
                ensureBoxed(genRhs,
                    enteringPhase(currentRun.posterasurePhase)(rhs.tpe))
              js.Assign(genLhs, boxedRhs)
            } else*/ {
              js.Assign(
                  js.Select(genQual, encodeClassName(sym.owner),
                      encodeFieldSym(sym))(toIRType(sym.info)),
                  genRhs)
            }
          case _ =>
            js.Assign(
                js.VarRef(encodeLocalSym(sym))(toIRType(sym.info)),
                genRhs)
        }

      /** Array constructor */
      case javaSeqLiteral: JavaSeqLiteral =>
        genJavaSeqLiteral(javaSeqLiteral)

      /** A Match reaching the backend is supposed to be optimized as a switch */
      case mtch: Match =>
        genMatch(mtch, isStat)

      case tree: Closure =>
        genClosure(tree)

      case EmptyTree =>
        js.Skip()

      case _ =>
        throw new FatalError("Unexpected tree in genExpr: " +
            tree + "/" + tree.getClass + " at: " + (tree.span: Position))
    }
  } // end of genStatOrExpr()

  private def qualifierOf(fun: Tree): Tree = fun match {
    case fun: Ident =>
      fun.tpe match {
        case TermRef(prefix: TermRef, _) => tpd.ref(prefix)
        case TermRef(prefix: ThisType, _) => tpd.This(prefix.cls)
      }
    case Select(qualifier, _) =>
      qualifier
    case TypeApply(fun, _) =>
      qualifierOf(fun)
  }

  /** Gen JS this of the current class.
   *  Normally encoded straightforwardly as a JS this.
   *  But must be replaced by the `thisLocalVarIdent` local variable if there
   *  is one.
   */
  private def genThis()(implicit pos: Position): js.Tree = {
    /*if (tryingToGenMethodAsJSFunction) {
      throw new CancelGenMethodAsJSFunction(
          "Trying to generate `this` inside the body")
    }*/

    thisLocalVarIdent.fold[js.Tree] {
      js.This()(currentClassType)
    } { thisLocalIdent =>
      js.VarRef(thisLocalIdent)(currentClassType)
    }
  }

  /** Gen IR code for a `try..catch` or `try..finally` block.
   *
   *  `try..finally` blocks are compiled straightforwardly to `try..finally`
   *  blocks of the IR.
   *
   *  `try..catch` blocks are a bit more subtle, as the IR does not have
   *  type-based selection of exceptions to catch. We thus encode explicitly
   *  the type tests, like in:
   *
   *  ```
   *  try { ... }
   *  catch (e) {
   *    if (e.isInstanceOf[IOException]) { ... }
   *    else if (e.isInstanceOf[Exception]) { ... }
   *    else {
   *      throw e; // default, re-throw
   *    }
   *  }
   *  ```
   *
   *  In addition, there are provisions to handle catching JavaScript
   *  exceptions (which do not extend `Throwable`) as wrapped in a
   *  `js.JavaScriptException`.
   */
  private def genTry(tree: Try, isStat: Boolean): js.Tree = {
    implicit val pos: SourcePosition = tree.sourcePos
    val Try(block, catches, finalizer) = tree

    val blockAST = genStatOrExpr(block, isStat)
    val resultType = toIRType(tree.tpe)

    val handled =
      if (catches.isEmpty) blockAST
      else genTryCatch(blockAST, catches, resultType, isStat)

    genStat(finalizer) match {
      case js.Skip() => handled
      case ast       => js.TryFinally(handled, ast)
    }
  }

  private def genTryCatch(body: js.Tree, catches: List[CaseDef],
      resultType: jstpe.Type,
      isStat: Boolean)(implicit pos: SourcePosition): js.Tree = {
    val exceptIdent = freshLocalIdent("e")
    val origExceptVar = js.VarRef(exceptIdent)(jstpe.AnyType)

    val mightCatchJavaScriptException = catches.exists { caseDef =>
      caseDef.pat match {
        case Typed(Ident(nme.WILDCARD), tpt) =>
          isMaybeJavaScriptException(tpt.tpe)
        case Ident(nme.WILDCARD) =>
          true
        case pat @ Bind(_, _) =>
          isMaybeJavaScriptException(pat.symbol.info)
      }
    }

    val (exceptValDef, exceptVar) = if (mightCatchJavaScriptException) {
      val valDef = js.VarDef(freshLocalIdent("e"), NoOriginalName,
          encodeClassType(defn.ThrowableClass), mutable = false, {
        genModuleApplyMethod(jsdefn.Runtime_wrapJavaScriptException, origExceptVar :: Nil)
      })
      (valDef, valDef.ref)
    } else {
      (js.Skip(), origExceptVar)
    }

    val elseHandler: js.Tree = js.Throw(origExceptVar)

    val handler = catches.foldRight(elseHandler) { (caseDef, elsep) =>
      implicit val pos: SourcePosition = caseDef.sourcePos
      val CaseDef(pat, _, body) = caseDef

      // Extract exception type and variable
      val (tpe, boundVar) = (pat match {
        case Typed(Ident(nme.WILDCARD), tpt) =>
          (tpt.tpe, None)
        case Ident(nme.WILDCARD) =>
          (defn.ThrowableType, None)
        case Bind(_, _) =>
          val ident = encodeLocalSym(pat.symbol)
          val origName = originalNameOfLocal(pat.symbol)
          (pat.symbol.info, Some(ident, origName))
      })

      // Generate the body that must be executed if the exception matches
      val bodyWithBoundVar = (boundVar match {
        case None =>
          genStatOrExpr(body, isStat)
        case Some((boundVarIdent, boundVarOriginalName)) =>
          val castException = genAsInstanceOf(exceptVar, tpe)
          js.Block(
              js.VarDef(boundVarIdent, boundVarOriginalName, toIRType(tpe),
                  mutable = false, castException),
              genStatOrExpr(body, isStat))
      })

      // Generate the test
      if (tpe =:= defn.ThrowableType) {
        bodyWithBoundVar
      } else {
        val cond = genIsInstanceOf(exceptVar, tpe)
        js.If(cond, bodyWithBoundVar, elsep)(resultType)
      }
    }

    js.TryCatch(body, exceptIdent, NoOriginalName,
        js.Block(exceptValDef, handler))(resultType)
  }

  /** Gen JS code for an Apply node (method call)
   *
   *  There's a whole bunch of varieties of Apply nodes: regular method
   *  calls, super calls, constructor calls, isInstanceOf/asInstanceOf,
   *  primitives, JS calls, etc. They are further dispatched in here.
   */
  private def genApply(tree: Apply, isStat: Boolean): js.Tree = {
    implicit val pos = tree.span
    val args = tree.args
    val sym = tree.fun.symbol

    val fun = tree.fun match {
      case fun: Ident => desugarIdent(fun)
      case fun => fun
    }

    fun match {
      case _ if isJSDefaultParam(sym) =>
        js.Transient(UndefinedParam)(toIRType(sym.info.finalResultType))

      case Select(Super(_, _), _) =>
        genSuperCall(tree, isStat)

      case Select(New(_), nme.CONSTRUCTOR) =>
        genApplyNew(tree)

      case _ =>
        if (primitives.isPrimitive(tree)) {
          genPrimitiveOp(tree, isStat)
        } else if (Erasure.Boxing.isBox(sym)) {
          // Box a primitive value (cannot be Unit)
          val arg = args.head
          makePrimitiveBox(genExpr(arg), arg.tpe)
        } else if (Erasure.Boxing.isUnbox(sym)) {
          // Unbox a primitive value (cannot be Unit)
          val arg = args.head
          makePrimitiveUnbox(genExpr(arg), tree.tpe)
        } else {
          genNormalApply(tree, isStat)
        }
    }
  }

  /** Gen JS code for a super call, of the form Class.super[mix].fun(args).
   *
   *  This does not include calls defined in mixin traits, as these are
   *  already desugared by the 'mixin' phase. Only calls to super classes
   *  remain.
   *
   *  Since a class has exactly one direct superclass, and calling a method
   *  two classes above the current one is invalid in Scala, the `mix` item is
   *  irrelevant.
   */
  private def genSuperCall(tree: Apply, isStat: Boolean): js.Tree = {
    implicit val pos = tree.span
    val Apply(fun @ Select(sup @ Super(qual, _), _), args) = tree
    val sym = fun.symbol

    if (sym == defn.Any_getClass) {
      // The only primitive that is also callable as super call
      js.GetClass(genThis())
    } else /*if (isScalaJSDefinedJSClass(currentClassSym)) {
      genJSSuperCall(tree, isStat)
    } else*/ {
      /* #3013 `qual` can be `this.$outer()` in some cases since Scala 2.12,
       * so we call `genExpr(qual)`, not just `genThis()`.
       */
      val superCall = genApplyMethodStatically(
          genExpr(qual), sym, genActualArgs(sym, args))

      // Initialize the module instance just after the super constructor call.
      if (isStaticModule(currentClassSym) && !isModuleInitialized &&
          currentMethodSym.get.isClassConstructor) {
        isModuleInitialized = true
        val className = encodeClassName(currentClassSym)
        val thisType = jstpe.ClassType(className)
        val initModule = js.StoreModule(className, js.This()(thisType))
        js.Block(superCall, initModule)
      } else {
        superCall
      }
    }
  }

  /** Gen JS code for a constructor call (new).
   *  Further refined into:
   *  * new String(...)
   *  * new of a hijacked boxed class
   *  * new of an anonymous function class that was recorded as JS function
   *  * new of a raw JS class
   *  * new Array
   *  * regular new
   */
  private def genApplyNew(tree: Apply): js.Tree = {
    implicit val pos: SourcePosition = tree.sourcePos

    val Apply(fun @ Select(New(tpt), nme.CONSTRUCTOR), args) = tree
    val ctor = fun.symbol
    val tpe = tpt.tpe

    assert(ctor.isClassConstructor,
        "'new' call to non-constructor: " + ctor.name)

    val clsSym = tpe.widenDealias.typeSymbol

    if (isHijackedClass(clsSym)) {
      genNewHijackedClass(clsSym, ctor, args.map(genExpr))
    } else /*if (translatedAnonFunctions contains tpe.typeSymbol) {
      val functionMaker = translatedAnonFunctions(tpe.typeSymbol)
      functionMaker(args map genExpr)
    } else*/ if (isJSType(clsSym)) {
      if (clsSym == jsdefn.JSObjectClass && args.isEmpty) js.JSObjectConstr(Nil)
      else if (clsSym == jsdefn.JSArrayClass && args.isEmpty) js.JSArrayConstr(Nil)
      else js.JSNew(genLoadJSConstructor(clsSym), genActualJSArgs(ctor, args))
    } else {
      toTypeRef(tpe) match {
        case jstpe.ClassRef(className) =>
          js.New(className, encodeMethodSym(ctor), genActualArgs(ctor, args))

        case other =>
          throw new FatalError(s"Non ClassRef cannot be instantiated: $other")
      }
    }
  }

  /** Gen JS code for a call to a constructor of a hijacked class.
   *  Reroute them to the `new` method with the same signature in the
   *  companion object.
   */
  private def genNewHijackedClass(clazz: Symbol, ctor: Symbol,
      args: List[js.Tree])(implicit pos: SourcePosition): js.Tree = {

    val className = encodeClassName(clazz)
    val initName = encodeMethodSym(ctor).name
    val newName = MethodName(newSimpleMethodName, initName.paramTypeRefs,
        jstpe.ClassRef(className))
    val newMethodIdent = js.MethodIdent(newName)

    js.ApplyStatic(js.ApplyFlags.empty, className, newMethodIdent, args)(
        jstpe.ClassType(className))
  }

  /** Gen JS code for a primitive method call. */
  private def genPrimitiveOp(tree: Apply, isStat: Boolean): js.Tree = {
    import dotty.tools.backend.ScalaPrimitivesOps._

    implicit val pos = tree.span

    val Apply(fun, args) = tree
    val receiver = qualifierOf(fun)

    val code = primitives.getPrimitive(tree, receiver.tpe)

    if (isArithmeticOp(code) || isLogicalOp(code) || isComparisonOp(code))
      genSimpleOp(tree, receiver :: args, code)
    else if (code == CONCAT)
      genStringConcat(tree, receiver, args)
    else if (code == HASH)
      genScalaHash(tree, receiver)
    else if (isArrayOp(code))
      genArrayOp(tree, code)
    else if (code == SYNCHRONIZED)
      genSynchronized(tree, isStat)
    else if (isCoercion(code))
      genCoercion(tree, receiver, code)
    else if (code == JSPrimitives.THROW)
      genThrow(tree, args)
    else if (JSPrimitives.isJSPrimitive(code))
      genJSPrimitive(tree, args, code, isStat)
    else
      throw new FatalError(s"Unknown primitive: ${tree.symbol.fullName} at: $pos")
  }

  /** Gen JS code for a simple operation (arithmetic, logical, or comparison) */
  private def genSimpleOp(tree: Apply, args: List[Tree], code: Int): js.Tree = {
    args match {
      case List(arg)      => genSimpleUnaryOp(tree, arg, code)
      case List(lhs, rhs) => genSimpleBinaryOp(tree, lhs, rhs, code)
      case _              => throw new FatalError("Incorrect arity for primitive")
    }
  }

  /** Gen JS code for a simple unary operation. */
  private def genSimpleUnaryOp(tree: Apply, arg: Tree, code: Int): js.Tree = {
    import dotty.tools.backend.ScalaPrimitivesOps._

    implicit val pos = tree.span

    val resultIRType = toIRType(tree.tpe)
    val genArg = adaptPrimitive(genExpr(arg), resultIRType)

    (code: @switch) match {
      case POS =>
        genArg

      case NEG =>
        (resultIRType: @unchecked) match {
          case jstpe.IntType =>
            js.BinaryOp(js.BinaryOp.Int_-, js.IntLiteral(0), genArg)
          case jstpe.LongType =>
            js.BinaryOp(js.BinaryOp.Long_-, js.LongLiteral(0), genArg)
          case jstpe.FloatType =>
            js.BinaryOp(js.BinaryOp.Float_*, js.FloatLiteral(-1.0f), genArg)
          case jstpe.DoubleType =>
            js.BinaryOp(js.BinaryOp.Double_*, js.DoubleLiteral(-1.0), genArg)
        }

      case NOT =>
        (resultIRType: @unchecked) match {
          case jstpe.IntType =>
            js.BinaryOp(js.BinaryOp.Int_^, js.IntLiteral(-1), genArg)
          case jstpe.LongType =>
            js.BinaryOp(js.BinaryOp.Long_^, js.LongLiteral(-1), genArg)
        }

      case ZNOT =>
        js.UnaryOp(js.UnaryOp.Boolean_!, genArg)

      case _ =>
        throw new FatalError("Unknown unary operation code: " + code)
    }
  }

  /** Gen JS code for a simple binary operation. */
  private def genSimpleBinaryOp(tree: Apply, lhs: Tree, rhs: Tree, code: Int): js.Tree = {
    import dotty.tools.backend.ScalaPrimitivesOps._
    import js.UnaryOp._

    implicit val pos: SourcePosition = tree.sourcePos

    val lhsIRType = toIRType(lhs.tpe)
    val rhsIRType = toIRType(rhs.tpe)

    val isShift = isShiftOp(code)

    val opType = {
      if (isShift) {
        if (lhsIRType == jstpe.LongType) jstpe.LongType
        else jstpe.IntType
      } else {
        (lhsIRType, rhsIRType) match {
          case (jstpe.DoubleType, _) | (_, jstpe.DoubleType)                          => jstpe.DoubleType
          case (jstpe.FloatType, _) | (_, jstpe.FloatType)                            => jstpe.FloatType
          case (jstpe.LongType, _) | (_, jstpe.LongType)                              => jstpe.LongType
          case (jstpe.IntType | jstpe.ByteType | jstpe.ShortType | jstpe.CharType, _) => jstpe.IntType
          case (_, jstpe.IntType | jstpe.ByteType | jstpe.ShortType | jstpe.CharType) => jstpe.IntType
          case (jstpe.BooleanType, _) | (_, jstpe.BooleanType)                        => jstpe.BooleanType
          case _                                                                      => jstpe.AnyType
        }
      }
    }

    val lsrc =
      if (opType == jstpe.AnyType) genExpr(lhs)
      else adaptPrimitive(genExpr(lhs), opType)
    val rsrc =
      if (opType == jstpe.AnyType) genExpr(rhs)
      else adaptPrimitive(genExpr(rhs), if (isShift) jstpe.IntType else opType)

    if (opType == jstpe.AnyType && isUniversalEqualityOp(code)) {
      genUniversalEqualityOp(lhs.tpe, rhs.tpe, lsrc, rsrc, code)
    } else if (code == ZOR) {
      js.If(lsrc, js.BooleanLiteral(true), rsrc)(jstpe.BooleanType)
    } else if (code == ZAND) {
      js.If(lsrc, rsrc, js.BooleanLiteral(false))(jstpe.BooleanType)
    } else {
      import js.BinaryOp._

      (opType: @unchecked) match {
        case jstpe.IntType =>
          val op = (code: @switch) match {
            case ADD => Int_+
            case SUB => Int_-
            case MUL => Int_*
            case DIV => Int_/
            case MOD => Int_%
            case OR  => Int_|
            case AND => Int_&
            case XOR => Int_^
            case LSL => Int_<<
            case LSR => Int_>>>
            case ASR => Int_>>

            case EQ => Int_==
            case NE => Int_!=
            case LT => Int_<
            case LE => Int_<=
            case GT => Int_>
            case GE => Int_>=
          }
          js.BinaryOp(op, lsrc, rsrc)

        case jstpe.FloatType =>
          def withFloats(op: Int): js.Tree =
            js.BinaryOp(op, lsrc, rsrc)

          def toDouble(value: js.Tree): js.Tree =
            js.UnaryOp(js.UnaryOp.FloatToDouble, value)

          def withDoubles(op: Int): js.Tree =
            js.BinaryOp(op, toDouble(lsrc), toDouble(rsrc))

          (code: @switch) match {
            case ADD => withFloats(Float_+)
            case SUB => withFloats(Float_-)
            case MUL => withFloats(Float_*)
            case DIV => withFloats(Float_/)
            case MOD => withFloats(Float_%)

            case EQ => withDoubles(Double_==)
            case NE => withDoubles(Double_!=)
            case LT => withDoubles(Double_<)
            case LE => withDoubles(Double_<=)
            case GT => withDoubles(Double_>)
            case GE => withDoubles(Double_>=)
          }

        case jstpe.DoubleType =>
          val op = (code: @switch) match {
            case ADD => Double_+
            case SUB => Double_-
            case MUL => Double_*
            case DIV => Double_/
            case MOD => Double_%

            case EQ => Double_==
            case NE => Double_!=
            case LT => Double_<
            case LE => Double_<=
            case GT => Double_>
            case GE => Double_>=
          }
          js.BinaryOp(op, lsrc, rsrc)

        case jstpe.LongType =>
          val op = (code: @switch) match {
            case ADD => Long_+
            case SUB => Long_-
            case MUL => Long_*
            case DIV => Long_/
            case MOD => Long_%
            case OR  => Long_|
            case XOR => Long_^
            case AND => Long_&
            case LSL => Long_<<
            case LSR => Long_>>>
            case ASR => Long_>>

            case EQ => Long_==
            case NE => Long_!=
            case LT => Long_<
            case LE => Long_<=
            case GT => Long_>
            case GE => Long_>=
          }
          js.BinaryOp(op, lsrc, rsrc)

        case jstpe.BooleanType =>
          val op = (code: @switch) match {
            case EQ  => Boolean_==
            case NE  => Boolean_!=
            case OR  => Boolean_|
            case AND => Boolean_&
            case XOR => Boolean_!=
          }
          js.BinaryOp(op, lsrc, rsrc)

        case jstpe.AnyType =>
          val op = code match {
            case ID => ===
            case NI => !==
          }
          js.BinaryOp(op, lsrc, rsrc)
      }
    }
  }

  private def adaptPrimitive(value: js.Tree, to: jstpe.Type)(
      implicit pos: Position): js.Tree = {
    genConversion(value.tpe, to, value)
  }

  /* This method corresponds to the method of the same name in
   * BCodeBodyBuilder of the JVM back-end. It ends up calling the method
   * BCodeIdiomatic.emitT2T, whose logic we replicate here.
    */
  private def genConversion(from: jstpe.Type, to: jstpe.Type, value: js.Tree)(
      implicit pos: Position): js.Tree = {
    import js.UnaryOp._

    if (from == to || from == jstpe.NothingType) {
      value
    } else if (from == jstpe.BooleanType || to == jstpe.BooleanType) {
      throw new AssertionError(s"Invalid genConversion from $from to $to")
    } else {
      def intValue = (from: @unchecked) match {
        case jstpe.IntType    => value
        case jstpe.CharType   => js.UnaryOp(CharToInt, value)
        case jstpe.ByteType   => js.UnaryOp(ByteToInt, value)
        case jstpe.ShortType  => js.UnaryOp(ShortToInt, value)
        case jstpe.LongType   => js.UnaryOp(LongToInt, value)
        case jstpe.FloatType  => js.UnaryOp(DoubleToInt, js.UnaryOp(FloatToDouble, value))
        case jstpe.DoubleType => js.UnaryOp(DoubleToInt, value)
      }

      def doubleValue = from match {
        case jstpe.DoubleType => value
        case jstpe.FloatType  => js.UnaryOp(FloatToDouble, value)
        case jstpe.LongType   => js.UnaryOp(LongToDouble, value)
        case _                => js.UnaryOp(IntToDouble, intValue)
      }

      (to: @unchecked) match {
        case jstpe.CharType =>
          js.UnaryOp(IntToChar, intValue)
        case jstpe.ByteType =>
          js.UnaryOp(IntToByte, intValue)
        case jstpe.ShortType =>
          js.UnaryOp(IntToShort, intValue)
        case jstpe.IntType =>
          intValue
        case jstpe.LongType =>
          from match {
            case jstpe.FloatType | jstpe.DoubleType =>
              js.UnaryOp(DoubleToLong, doubleValue)
            case _ =>
              js.UnaryOp(IntToLong, intValue)
          }
        case jstpe.FloatType =>
          js.UnaryOp(js.UnaryOp.DoubleToFloat, doubleValue)
        case jstpe.DoubleType =>
          doubleValue
      }
    }
  }

  /** Gen JS code for a universal equality test. */
  private def genUniversalEqualityOp(ltpe: Type, rtpe: Type, lhs: js.Tree, rhs: js.Tree, code: Int)(
      implicit pos: SourcePosition): js.Tree = {

    import dotty.tools.backend.ScalaPrimitivesOps._

    val bypassEqEq = {
      // Do not call equals if we have a literal null at either side.
      lhs.isInstanceOf[js.Null] ||
      rhs.isInstanceOf[js.Null]
    }

    if (bypassEqEq) {
      js.BinaryOp(
          if (code == EQ) js.BinaryOp.=== else js.BinaryOp.!==,
          lhs, rhs)
    } else {
      val body = genEqEqPrimitive(ltpe, rtpe, lhs, rhs)
      if (code == EQ) body
      else js.UnaryOp(js.UnaryOp.Boolean_!, body)
    }
  }

  private lazy val externalEqualsNumNum: Symbol =
    defn.BoxesRunTimeModule.requiredMethod(nme.equalsNumNum)
  private lazy val externalEqualsNumChar: Symbol =
    NoSymbol // requiredMethod(BoxesRunTimeTypeRef, nme.equalsNumChar) // this method is private
  private lazy val externalEqualsNumObject: Symbol =
    defn.BoxesRunTimeModule.requiredMethod(nme.equalsNumObject)
  private lazy val externalEquals: Symbol =
    defn.BoxesRunTimeModule.info.decl(nme.equals_).suchThat(toDenot(_).info.firstParamTypes.size == 2).symbol

  /** Gen JS code for a call to Any.== */
  private def genEqEqPrimitive(ltpe: Type, rtpe: Type, lsrc: js.Tree, rsrc: js.Tree)(
      implicit pos: SourcePosition): js.Tree = {
    report.debuglog(s"$ltpe == $rtpe")
    val lsym = ltpe.widenDealias.typeSymbol.asClass
    val rsym = rtpe.widenDealias.typeSymbol.asClass

    /* True if the equality comparison is between values that require the
     * use of the rich equality comparator
     * (scala.runtime.BoxesRunTime.equals).
     * This is the case when either side of the comparison might have a
     * run-time type subtype of java.lang.Number or java.lang.Character,
     * **which includes when either is a JS type**.
     * When it is statically known that both sides are equal and subtypes of
     * Number or Character, not using the rich equality is possible (their
     * own equals method will do ok), except for java.lang.Float and
     * java.lang.Double: their `equals` have different behavior around `NaN`
     * and `-0.0`, see Javadoc (scala-dev#329, scala-js#2799).
     */
    val mustUseAnyComparator: Boolean = {
      isJSType(lsym) || isJSType(rsym) || {
        val p = ctx.platform
        p.isMaybeBoxed(lsym) && p.isMaybeBoxed(rsym) && {
          val areSameFinals = lsym.is(Final) && rsym.is(Final) && (ltpe =:= rtpe)
          !areSameFinals || lsym == defn.BoxedFloatClass || lsym == defn.BoxedDoubleClass
        }
      }
    }

    if (mustUseAnyComparator) {
      val equalsMethod: Symbol = {
        // scalastyle:off line.size.limit
        val ptfm = ctx.platform
        if (lsym.derivesFrom(defn.BoxedNumberClass)) {
          if (rsym.derivesFrom(defn.BoxedNumberClass)) externalEqualsNumNum
          else if (rsym.derivesFrom(defn.BoxedCharClass)) externalEqualsNumObject // will be externalEqualsNumChar in 2.12, SI-9030
          else externalEqualsNumObject
        } else externalEquals
        // scalastyle:on line.size.limit
      }
      genApplyStatic(equalsMethod, List(lsrc, rsrc))
    } else {
      // if (lsrc eq null) rsrc eq null else lsrc.equals(rsrc)
      if (lsym == defn.StringClass) {
        // String.equals(that) === (this eq that)
        js.BinaryOp(js.BinaryOp.===, lsrc, rsrc)
      } else {
        /* This requires to evaluate both operands in local values first.
         * The optimizer will eliminate them if possible.
         */
        val ltemp = js.VarDef(freshLocalIdent(), NoOriginalName, lsrc.tpe, mutable = false, lsrc)
        val rtemp = js.VarDef(freshLocalIdent(), NoOriginalName, rsrc.tpe, mutable = false, rsrc)
        js.Block(
            ltemp,
            rtemp,
            js.If(js.BinaryOp(js.BinaryOp.===, ltemp.ref, js.Null()),
                js.BinaryOp(js.BinaryOp.===, rtemp.ref, js.Null()),
                genApplyMethod(ltemp.ref, defn.Any_equals, List(rtemp.ref)))(
                jstpe.BooleanType))
      }
    }
  }

  /** Gen JS code for string concatenation.
   */
  private def genStringConcat(tree: Apply, receiver: Tree,
      args: List[Tree]): js.Tree = {
    implicit val pos = tree.span

    val arg = args.head

    /* Primitive number types such as scala.Int have a
     *   def +(s: String): String
     * method, which is why we have to box the lhs sometimes.
     * Otherwise, both lhs and rhs are already reference types (Any or String)
     * so boxing is not necessary (in particular, rhs is never a primitive).
     */
    assert(!isPrimitiveValueType(receiver.tpe) || arg.tpe.isRef(defn.StringClass))
    assert(!isPrimitiveValueType(arg.tpe))

    val genLhs = {
      val genLhs0 = genExpr(receiver)
      // Box the receiver if it is a primitive value
      if (!isPrimitiveValueType(receiver.tpe)) genLhs0
      else makePrimitiveBox(genLhs0, receiver.tpe)
    }

    val genRhs = genExpr(arg)

    js.BinaryOp(js.BinaryOp.String_+, genLhs, genRhs)
  }

  /** Gen JS code for a call to Any.## */
  private def genScalaHash(tree: Apply, receiver: Tree): js.Tree = {
    implicit val pos: SourcePosition = tree.sourcePos

    genModuleApplyMethod(defn.ScalaRuntimeModule.requiredMethod(nme.hash_),
        List(genExpr(receiver)))
  }

  /** Gen JS code for an array operation (get, set or length) */
  private def genArrayOp(tree: Tree, code: Int): js.Tree = {
    import dotty.tools.backend.ScalaPrimitivesOps._

    implicit val pos = tree.span

    val Apply(fun, args) = tree
    val arrayObj = qualifierOf(fun)

    val genArray = genExpr(arrayObj)
    val genArgs = args.map(genExpr)

    def elementType: Type = arrayObj.tpe.widenDealias match {
      case defn.ArrayOf(el)  => el
      case JavaArrayType(el) => el
      case tpe =>
        val msg = ex"expected Array $tpe"
        report.error(msg)
        ErrorType(msg)
    }

    def genSelect(): js.Tree =
      js.ArraySelect(genArray, genArgs(0))(toIRType(elementType))

    if (isArrayGet(code)) {
      // get an item of the array
      assert(args.length == 1,
          s"Array get requires 1 argument, found ${args.length} in $tree")
      genSelect()
    } else if (isArraySet(code)) {
      // set an item of the array
      assert(args.length == 2,
          s"Array set requires 2 arguments, found ${args.length} in $tree")
      js.Assign(genSelect(), genArgs(1))
    } else {
      // length of the array
      js.ArrayLength(genArray)
    }
  }

  /** Gen JS code for a call to AnyRef.synchronized */
  private def genSynchronized(tree: Apply, isStat: Boolean): js.Tree = {
    /* JavaScript is single-threaded, so we can drop the
     * synchronization altogether.
     */
    val Apply(fun, List(arg)) = tree
    val receiver = qualifierOf(fun)

    val genReceiver = genExpr(receiver)
    val genArg = genStatOrExpr(arg, isStat)

    genReceiver match {
      case js.This() =>
        // common case for which there is no side-effect nor NPE
        genArg
      case _ =>
        implicit val pos = tree.span
        js.Block(
            js.If(js.BinaryOp(js.BinaryOp.===, genReceiver, js.Null()),
                js.Throw(js.New(NullPointerExceptionClass, js.MethodIdent(jsNames.NoArgConstructorName), Nil)),
                js.Skip())(jstpe.NoType),
            genArg)
    }
  }

  /** Gen JS code for a coercion */
  private def genCoercion(tree: Apply, receiver: Tree, code: Int): js.Tree = {
    implicit val pos = tree.span

    val source = genExpr(receiver)
    val resultType = toIRType(tree.tpe)
    adaptPrimitive(source, resultType)
  }

  /** Gen a call to the special `throw` method. */
  private def genThrow(tree: Apply, args: List[Tree]): js.Tree = {
    implicit val pos: SourcePosition = tree.sourcePos
    val exception = args.head
    val genException = genExpr(exception)
    js.Throw {
      if (exception.tpe.widenDealias.typeSymbol.derivesFrom(jsdefn.JavaScriptExceptionClass)) {
        genModuleApplyMethod(
            jsdefn.Runtime_unwrapJavaScriptException,
            List(genException))
      } else {
        genException
      }
    }
  }

  /** Gen a "normal" apply (to a true method).
   *
   *  But even these are further refined into:
   *  * Methods of java.lang.String, which are redirected to the
   *    RuntimeString trait implementation.
   *  * Calls to methods of raw JS types (Scala.js -> JS interop)
   *  * Calls to methods in impl classes of Scala2 traits.
   *  * Regular method call
   */
  private def genNormalApply(tree: Apply, isStat: Boolean): js.Tree = {
    implicit val pos = tree.span

    val fun = tree.fun match {
      case fun: Ident => desugarIdent(fun).get
      case fun: Select => fun
    }
    val receiver = fun.qualifier
    val args = tree.args
    val sym = fun.symbol

    def isStringMethodFromObject: Boolean = sym.name match {
      case nme.toString_ | nme.equals_ | nme.hashCode_ => true
      case _                                           => false
    }

    if (isMethodStaticInIR(sym)) {
      genApplyStatic(sym, genActualArgs(sym, args))
    } else if (isJSType(sym.owner)) {
      //if (!isScalaJSDefinedJSClass(sym.owner) || isExposed(sym))
        genApplyJSMethodGeneric(sym, genExprOrGlobalScope(receiver), genActualJSArgs(sym, args), isStat)(tree.sourcePos)
      /*else
        genApplyJSClassMethod(genExpr(receiver), sym, genActualArgs(sym, args))*/
    } else if (sym.hasAnnotation(jsdefn.JSNativeAnnot)) {
      genJSNativeMemberCall(tree, isStat)
    } else {
      genApplyMethodMaybeStatically(genExpr(receiver), sym, genActualArgs(sym, args))
    }
  }

  /** Gen JS code for a call to a JS method (of a subclass of `js.Any`).
   *
   *  Basically it boils down to calling the method as a `JSBracketSelect`,
   *  without name mangling. But other aspects come into play:
   *
   *  - Operator methods are translated to JS operators (not method calls)
   *  - `apply` is translated as a function call, i.e., `o()` instead of `o.apply()`
   *  - Scala varargs are turned into JS varargs (see `genPrimitiveJSArgs()`)
   *  - Getters and parameterless methods are translated as `JSBracketSelect`
   *  - Setters are translated to `Assign` to `JSBracketSelect`
   */
  private def genApplyJSMethodGeneric(sym: Symbol,
      receiver: MaybeGlobalScope, args: List[js.TreeOrJSSpread], isStat: Boolean,
      jsSuperClassValue: Option[js.Tree] = None)(
      implicit pos: SourcePosition): js.Tree = {

    def noSpread = !args.exists(_.isInstanceOf[js.JSSpread])
    val argc = args.size // meaningful only for methods that don't have varargs

    def requireNotSuper(): Unit = {
      if (jsSuperClassValue.isDefined)
        report.error("Illegal super call in Scala.js-defined JS class", pos)
    }

    def requireNotSpread(arg: js.TreeOrJSSpread): js.Tree =
      arg.asInstanceOf[js.Tree]

    def hasExplicitJSEncoding = {
      sym.hasAnnotation(jsdefn.JSNameAnnot) ||
      sym.hasAnnotation(jsdefn.JSBracketAccessAnnot) ||
      sym.hasAnnotation(jsdefn.JSBracketCallAnnot)
    }

    val boxedResult = sym.name match {
      case JSUnaryOpMethodName(code) if argc == 0 =>
        requireNotSuper()
        js.JSUnaryOp(code, ruleOutGlobalScope(receiver))

      case JSBinaryOpMethodName(code) if argc == 1 =>
        requireNotSuper()
        js.JSBinaryOp(code, ruleOutGlobalScope(receiver), requireNotSpread(args.head))

      case nme.apply if !hasExplicitJSEncoding =>
        requireNotSuper()
        if (jsdefn.isJSThisFunctionClass(sym.owner))
          js.JSMethodApply(ruleOutGlobalScope(receiver), js.StringLiteral("call"), args)
        else
          js.JSFunctionApply(ruleOutGlobalScope(receiver), args)

      case _ =>
        def jsFunName = genExpr(jsNameOf(sym))

        def genSuperReference(propName: js.Tree): js.Tree = {
          jsSuperClassValue.fold[js.Tree] {
            genJSSelectOrGlobalRef(receiver, propName)
          } { superClassValue =>
            js.JSSuperSelect(superClassValue, ruleOutGlobalScope(receiver), propName)
          }
        }

        def genSelectGet(propName: js.Tree): js.Tree =
          genSuperReference(propName)

        def genSelectSet(propName: js.Tree, value: js.Tree): js.Tree =
          js.Assign(genSuperReference(propName), value)

        def genCall(methodName: js.Tree, args: List[js.TreeOrJSSpread]): js.Tree = {
          jsSuperClassValue.fold[js.Tree] {
            genJSMethodApplyOrGlobalRefApply(receiver, methodName, args)
          } { superClassValue =>
            js.JSSuperMethodCall(superClassValue, ruleOutGlobalScope(receiver), methodName, args)
          }
        }

        if (isJSGetter(sym)) {
          assert(noSpread && argc == 0)
          genSelectGet(jsFunName)
        } else if (isJSSetter(sym)) {
          assert(noSpread && argc == 1)
          genSelectSet(jsFunName, requireNotSpread(args.head))
        } else if (isJSBracketAccess(sym)) {
          assert(noSpread && (argc == 1 || argc == 2),
              s"@JSBracketAccess methods should have 1 or 2 non-varargs arguments")
          (args: @unchecked) match {
            case List(keyArg) =>
              genSelectGet(requireNotSpread(keyArg))
            case List(keyArg, valueArg) =>
              genSelectSet(requireNotSpread(keyArg), requireNotSpread(valueArg))
          }
        } else if (isJSBracketCall(sym)) {
          val (methodName, actualArgs) = extractFirstArg(args)
          genCall(methodName, actualArgs)
        } else {
          genCall(jsFunName, args)
        }
    }

    if (isStat) {
      boxedResult
    } else {
      val tpe = atPhase(elimErasedValueTypePhase) {
        sym.info.finalResultType
      }
      unbox(boxedResult, tpe)
    }
  }

  private object JSUnaryOpMethodName {
    private val map = Map(
      nme.UNARY_+ -> js.JSUnaryOp.+,
      nme.UNARY_- -> js.JSUnaryOp.-,
      nme.UNARY_~ -> js.JSUnaryOp.~,
      nme.UNARY_! -> js.JSUnaryOp.!
    )

    def unapply(name: TermName): Option[js.JSUnaryOp.Code] =
      map.get(name)
  }

  private object JSBinaryOpMethodName {
    private val map = Map(
      nme.ADD -> js.JSBinaryOp.+,
      nme.SUB -> js.JSBinaryOp.-,
      nme.MUL -> js.JSBinaryOp.*,
      nme.DIV -> js.JSBinaryOp./,
      nme.MOD -> js.JSBinaryOp.%,

      nme.LSL -> js.JSBinaryOp.<<,
      nme.ASR -> js.JSBinaryOp.>>,
      nme.LSR -> js.JSBinaryOp.>>>,
      nme.OR  -> js.JSBinaryOp.|,
      nme.AND -> js.JSBinaryOp.&,
      nme.XOR -> js.JSBinaryOp.^,

      nme.LT -> js.JSBinaryOp.<,
      nme.LE -> js.JSBinaryOp.<=,
      nme.GT -> js.JSBinaryOp.>,
      nme.GE -> js.JSBinaryOp.>=,

      nme.ZAND -> js.JSBinaryOp.&&,
      nme.ZOR  -> js.JSBinaryOp.||
    )

    def unapply(name: TermName): Option[js.JSBinaryOp.Code] =
      map.get(name)
  }

  /** Extract the first argument in a list of actual arguments.
   *
   *  This is nothing else than decomposing into head and tail, except that
   *  we assert that the first element is not a JSSpread.
   */
  private def extractFirstArg(args: List[js.TreeOrJSSpread]): (js.Tree, List[js.TreeOrJSSpread]) = {
    assert(args.nonEmpty,
        "Trying to extract the first argument of an empty argument list")
    val firstArg = args.head
    assert(!firstArg.isInstanceOf[js.JSSpread],
        "Trying to extract the first argument of an argument list starting " +
        "with a Spread argument: " + firstArg)
    (firstArg.asInstanceOf[js.Tree], args.tail)
  }

  /** Gen JS code for a call to a native JS def or val. */
  private def genJSNativeMemberCall(tree: Apply, isStat: Boolean): js.Tree = {
    val sym = tree.symbol
    val Apply(_, args) = tree

    implicit val pos = tree.span

    val jsNativeMemberValue =
      js.SelectJSNativeMember(encodeClassName(sym.owner), encodeMethodSym(sym))

    val boxedResult =
      if (sym.isJSGetter) jsNativeMemberValue
      else js.JSFunctionApply(jsNativeMemberValue, genActualJSArgs(sym, args))

    unbox(boxedResult, atPhase(elimErasedValueTypePhase) {
      sym.info.resultType
    })
  }


  /** Gen JS code for a call to a polymorphic method.
   *
   *  The only methods that reach the back-end as polymorphic are
   *  `isInstanceOf` and `asInstanceOf`.
   *
   *  (Well, in fact `DottyRunTime.newRefArray` too, but it is handled as a
   *  primitive instead.)
   */
  private def genTypeApply(tree: TypeApply): js.Tree = {
    implicit val pos: SourcePosition = tree.sourcePos

    val TypeApply(fun, targs) = tree

    val sym = fun.symbol
    val receiver = qualifierOf(fun)

    val to = targs.head.tpe

    assert(!isPrimitiveValueType(receiver.tpe),
        s"Found receiver of type test with primitive type ${receiver.tpe} at $pos")
    assert(!isPrimitiveValueType(to),
        s"Found target type of type test with primitive type ${receiver.tpe} at $pos")

    val genReceiver = genExpr(receiver)

    if (sym == defn.Any_asInstanceOf) {
      genAsInstanceOf(genReceiver, to)
    } else if (sym == defn.Any_isInstanceOf) {
      genIsInstanceOf(genReceiver, to)
    } else {
      throw new FatalError(
          s"Unexpected type application $fun with symbol ${sym.fullName}")
    }
  }

  /** Gen JS code for a Java Seq literal. */
  private def genJavaSeqLiteral(tree: JavaSeqLiteral): js.Tree = {
    implicit val pos = tree.span

    val genElems = tree.elems.map(genExpr)
    val arrayTypeRef = toTypeRef(tree.tpe).asInstanceOf[jstpe.ArrayTypeRef]
    js.ArrayValue(arrayTypeRef, genElems)
  }

  /** Gen JS code for a switch-`Match`, which is translated into an IR `js.Match`. */
  def genMatch(tree: Tree, isStat: Boolean): js.Tree = {
    implicit val pos = tree.span
    val Match(selector, cases) = tree

    def abortMatch(msg: String): Nothing =
      throw new FatalError(s"$msg in switch-like pattern match at ${tree.span}: $tree")

    /* Although GenBCode adapts the scrutinee and the cases to `int`, only
     * true `int`s can reach the back-end, as asserted by the String-switch
     * transformation in `cleanup`. Therefore, we do not adapt, preserving
     * the `string`s and `null`s that come out of the pattern matching in
     * Scala 2.13.2+.
     */
    val genSelector = genExpr(selector)

    // Sanity check: we can handle Ints and Strings (including `null`s), but nothing else
    genSelector.tpe match {
      case jstpe.IntType | jstpe.ClassType(jsNames.BoxedStringClass) | jstpe.NullType | jstpe.NothingType =>
        // ok
      case _ =>
        abortMatch(s"Invalid selector type ${genSelector.tpe}")
    }

    val resultType = toIRType(tree.tpe) match {
      case jstpe.NothingType => jstpe.NothingType // must take priority over NoType below
      case _ if isStat       => jstpe.NoType
      case resType           => resType
    }

    var clauses: List[(List[js.Tree], js.Tree)] = Nil
    var optDefaultClause: Option[js.Tree] = None

    for (caze @ CaseDef(pat, guard, body) <- cases) {
      if (guard != EmptyTree)
        abortMatch("Found a case guard")

      val genBody = genStatOrExpr(body, isStat)

      pat match {
        case lit: Literal =>
          clauses = (List(genExpr(lit)), genBody) :: clauses
        case Ident(nme.WILDCARD) =>
          optDefaultClause = Some(genBody)
        case Alternative(alts) =>
          val genAlts = alts.map {
            case lit: Literal => genExpr(lit)
            case _            => abortMatch("Invalid case in alternative")
          }
          clauses = (genAlts, genBody) :: clauses
        case _ =>
          abortMatch("Invalid case pattern")
      }
    }

    clauses = clauses.reverse
    val defaultClause = optDefaultClause.getOrElse {
      throw new AssertionError("No elseClause in pattern match")
    }

    /* Builds a `js.Match`, but simplifies it to a `js.If` if there is only
     * one case with one alternative, and to a `js.Block` if there is no case
     * at all. This happens in practice in the standard library. Having no
     * case is a typical product of `match`es that are full of
     * `case n if ... =>`, which are used instead of `if` chains for
     * convenience and/or readability.
     *
     * When no optimization applies, and any of the case values is not a
     * literal int, we emit a series of `if..else` instead of a `js.Match`.
     * This became necessary in 2.13.2 with strings and nulls.
     *
     * Note that dotc has not adopted String-switch-Matches yet, so these code
     * paths are dead code at the moment. However, they already existed in the
     * scalac, so were ported, to be immediately available and working when
     * dotc starts emitting switch-Matches on Strings.
     */
    def isInt(tree: js.Tree): Boolean = tree.tpe == jstpe.IntType

    clauses match {
      case Nil =>
        // Completely remove the Match. Preserve the side-effects of `genSelector`.
        js.Block(exprToStat(genSelector), defaultClause)

      case (uniqueAlt :: Nil, caseRhs) :: Nil =>
        /* Simplify the `match` as an `if`, so that the optimizer has less
         * work to do, and we emit less code at the end of the day.
         * Use `Int_==` instead of `===` if possible, since it is a common case.
         */
        val op =
          if (isInt(genSelector) && isInt(uniqueAlt)) js.BinaryOp.Int_==
          else js.BinaryOp.===
        js.If(js.BinaryOp(op, genSelector, uniqueAlt), caseRhs, defaultClause)(resultType)

      case _ =>
        if (isInt(genSelector) &&
            clauses.forall(_._1.forall(_.isInstanceOf[js.IntLiteral]))) {
          // We have int literals only: use a js.Match
          val intClauses = clauses.asInstanceOf[List[(List[js.IntLiteral], js.Tree)]]
          js.Match(genSelector, intClauses, defaultClause)(resultType)
        } else {
          // We have other stuff: generate an if..else chain
          val (tempSelectorDef, tempSelectorRef) = genSelector match {
            case varRef: js.VarRef =>
              (js.Skip(), varRef)
            case _ =>
              val varDef = js.VarDef(freshLocalIdent(), NoOriginalName,
                  genSelector.tpe, mutable = false, genSelector)
              (varDef, varDef.ref)
          }
          val ifElseChain = clauses.foldRight(defaultClause) { (caze, elsep) =>
            val conds = caze._1.map { caseValue =>
              js.BinaryOp(js.BinaryOp.===, tempSelectorRef, caseValue)
            }
            val cond = conds.reduceRight[js.Tree] { (left, right) =>
              js.If(left, js.BooleanLiteral(true), right)(jstpe.BooleanType)
            }
            js.If(cond, caze._2, elsep)(resultType)
          }
          js.Block(tempSelectorDef, ifElseChain)
        }
    }
  }

  /** Gen JS code for a closure.
   *
   *  Input: a `Closure` tree of the form
   *  {{{
   *  Closure(env, call, functionalInterface)
   *  }}}
   *  representing the pseudo-syntax
   *  {{{
   *  { (p1, ..., pm) => call(env1, ..., envn, p1, ..., pm) }: functionInterface
   *  }}}
   *  where `envi` are identifiers in the local scope. The qualifier of `call`
   *  is also implicitly captured.
   *
   *  Output: a `js.Closure` tree of the form
   *  {{{
   *  js.Closure(formalCaptures, formalParams, body, actualCaptures)
   *  }}}
   *  representing the pseudo-syntax
   *  {{{
   *  lambda<formalCapture1 = actualCapture1, ..., formalCaptureN = actualCaptureN>(
   *      formalParam1, ..., formalParamM) = body
   *  }}}
   *  where the `actualCaptures` and `body` are, in general, arbitrary
   *  expressions. But in this case, `actualCaptures` will be identifiers from
   *  `env`, and the `body` will be of the form
   *  {{{
   *  call(formalCapture1.ref, ..., formalCaptureN.ref,
   *      formalParam1.ref, ...formalParamM.ref)
   *  }}}
   *
   *  When the `js.Closure` node is evaluated, i.e., when the closure value is
   *  created, the expressions of the `actualCaptures` are evaluated, and the
   *  results of those evaluations is "stored" in the environment of the
   *  closure as the corresponding `formalCapture`.
   *
   *  When we later *call* the closure, the `formalCaptures` already have their
   *  values from the environment, and they are available in the `body`. The
   *  `formalParams` of the created closure receive their values from the
   *  actual arguments at the call-site of the closure, and they are also
   *  available in the `body`.
   */
  private def genClosure(tree: Closure): js.Tree = {
    implicit val pos = tree.span
    val Closure(env, call, functionalInterface) = tree

    val envSize = env.size

    val (fun, args) = call match {
      // case Apply(fun, args) => (fun, args) // Conjectured not to happen
      case t @ Select(_, _) => (t, Nil)
      case t @ Ident(_) => (t, Nil)
    }
    val sym = fun.symbol
    val isStaticCall = isMethodStaticInIR(sym)

    val qualifier = qualifierOf(fun)
    val allCaptureValues =
      if (isStaticCall) env
      else qualifier :: env

    val formalAndActualCaptures = allCaptureValues.map { value =>
      implicit val pos = value.span
      val (formalIdent, originalName) = value match {
        case Ident(name) => (freshLocalIdent(name.toString), OriginalName(name.toString))
        case This(_)     => (freshLocalIdent("this"), thisOriginalName)
        case _           => (freshLocalIdent(), NoOriginalName)
      }
      val formalCapture = js.ParamDef(formalIdent, originalName,
          toIRType(value.tpe), mutable = false, rest = false)
      val actualCapture = genExpr(value)
      (formalCapture, actualCapture)
    }
    val (formalCaptures, actualCaptures) = formalAndActualCaptures.unzip

    val formalParamNames = sym.info.paramNamess.flatten.drop(envSize)
    val formalParamTypes = sym.info.paramInfoss.flatten.drop(envSize)
    val formalParamNamesAndTypes = formalParamNames.zip(formalParamTypes)
    val formalAndActualParams = formalParamNamesAndTypes.map {
      case (name, tpe) =>
        val formalParam = js.ParamDef(freshLocalIdent(name.toString),
            OriginalName(name.toString), jstpe.AnyType, mutable = false,
            rest = false)
        val actualParam = unbox(formalParam.ref, tpe)
        (formalParam, actualParam)
    }
    val (formalParams, actualParams) = formalAndActualParams.unzip

    val genBody = {
      val call = if (isStaticCall) {
        genApplyStatic(sym, formalCaptures.map(_.ref) ::: actualParams)
      } else {
        val thisCaptureRef :: argCaptureRefs = formalCaptures.map(_.ref)
        genApplyMethodMaybeStatically(thisCaptureRef, sym,
            argCaptureRefs ::: actualParams)
      }
      box(call, sym.info.finalResultType)
    }

    val funInterfaceSym = functionalInterface.tpe.widenDealias.typeSymbol

    if (jsdefn.isJSThisFunctionClass(funInterfaceSym)) {
      val thisParam :: otherParams = formalParams
      js.Closure(
          arrow = false,
          formalCaptures,
          otherParams,
          js.Block(
              js.VarDef(thisParam.name, thisParam.originalName,
                  thisParam.ptpe, mutable = false,
                  js.This()(thisParam.ptpe)(thisParam.pos))(thisParam.pos),
              genBody),
          actualCaptures)
    } else {
      val closure = js.Closure(arrow = true, formalCaptures, formalParams, genBody, actualCaptures)

      if (jsdefn.isJSFunctionClass(funInterfaceSym)) {
        closure
      } else {
        assert(!funInterfaceSym.exists || defn.isFunctionClass(funInterfaceSym),
            s"Invalid functional interface $funInterfaceSym reached the back-end")
        val formalCount = formalParams.size
        val cls = ClassName("scala.scalajs.runtime.AnonFunction" + formalCount)
        val ctorName = MethodName.constructor(
            jstpe.ClassRef(ClassName("scala.scalajs.js.Function" + formalCount)) :: Nil)
        js.New(cls, js.MethodIdent(ctorName), List(closure))
      }
    }
  }

  /** Boxes a value of the given type before `elimErasedValueType`.
   *
   *  This should be used when sending values to a JavaScript context, which
   *  is erased/boxed at the IR level, although it is not erased at the
   *  dotty/JVM level.
   *
   *  @param expr Tree to be boxed if needed.
   *  @param tpeEnteringElimErasedValueType The type of `expr` as it was
   *    entering the `elimErasedValueType` phase.
   */
  private def box(expr: js.Tree, tpeEnteringElimErasedValueType: Type)(
      implicit pos: Position): js.Tree = {

    tpeEnteringElimErasedValueType match {
      case tpe if isPrimitiveValueType(tpe) =>
        makePrimitiveBox(expr, tpe)

      case tpe: ErasedValueType =>
        val boxedClass = tpe.tycon.typeSymbol
        val ctor = boxedClass.primaryConstructor
        js.New(encodeClassName(boxedClass), encodeMethodSym(ctor), List(expr))

      case _ =>
        expr
    }
  }

  /** Unboxes a value typed as Any to the given type before `elimErasedValueType`.
   *
   *  This should be used when receiving values from a JavaScript context,
   *  which is erased/boxed at the IR level, although it is not erased at the
   *  dotty/JVM level.
   *
   *  @param expr Tree to be extracted.
   *  @param tpeEnteringElimErasedValueType The type of `expr` as it was
   *    entering the `elimErasedValueType` phase.
   */
  private def unbox(expr: js.Tree, tpeEnteringElimErasedValueType: Type)(
      implicit pos: Position): js.Tree = {

    tpeEnteringElimErasedValueType match {
      case tpe if isPrimitiveValueType(tpe) =>
        makePrimitiveUnbox(expr, tpe)

      case tpe: ErasedValueType =>
        val boxedClass = tpe.tycon.typeSymbol.asClass
        val unboxMethod = ValueClasses.valueClassUnbox(boxedClass)
        val content = genApplyMethod(
            js.AsInstanceOf(expr, encodeClassType(boxedClass)), unboxMethod, Nil)
        if (unboxMethod.info.resultType <:< tpe.erasedUnderlying)
          content
        else
          unbox(content, tpe.erasedUnderlying)

      case tpe =>
        genAsInstanceOf(expr, tpe)
    }
  }

  /** Gen JS code for an asInstanceOf cast (for reference types only) */
  private def genAsInstanceOf(value: js.Tree, to: Type)(
      implicit pos: Position): js.Tree = {

    val sym = to.widenDealias.typeSymbol

    if (sym == defn.ObjectClass || isJSType(sym)) {
      /* asInstanceOf[Object] always succeeds, and
       * asInstanceOf to a raw JS type is completely erased.
       */
      value
    } else if (sym == defn.NullClass) {
      js.If(
          js.BinaryOp(js.BinaryOp.===, value, js.Null()),
          js.Null(),
          genThrowClassCastException())(
          jstpe.NullType)
    } else if (sym == defn.NothingClass) {
      js.Block(value, genThrowClassCastException())
    } else {
      js.AsInstanceOf(value, toIRType(to))
    }
  }

  private def genThrowClassCastException()(implicit pos: Position): js.Tree = {
    js.Throw(js.New(jsNames.ClassCastExceptionClass,
        js.MethodIdent(jsNames.NoArgConstructorName), Nil))
  }

  /** Gen JS code for an isInstanceOf test (for reference types only) */
  private def genIsInstanceOf(value: js.Tree, to: Type)(
      implicit pos: SourcePosition): js.Tree = {
    val sym = to.widenDealias.typeSymbol

    if (sym == defn.ObjectClass) {
      js.BinaryOp(js.BinaryOp.!==, value, js.Null())
    } else if (isJSType(sym)) {
      if (sym.is(Trait)) {
        report.error(
            s"isInstanceOf[${sym.fullName}] not supported because it is a JS trait",
            pos)
        js.BooleanLiteral(true)
      } else {
        js.AsInstanceOf(js.JSBinaryOp(
            js.JSBinaryOp.instanceof, value, genLoadJSConstructor(sym)),
            jstpe.BooleanType)
      }
    } else {
      // The Scala type system prevents x.isInstanceOf[Null] and ...[Nothing]
      assert(sym != defn.NullClass && sym != defn.NothingClass,
          s"Found a .isInstanceOf[$sym] at $pos")
      js.IsInstanceOf(value, toIRType(to))
    }
  }

  /** Gen a statically linked call to an instance method. */
  private def genApplyMethodMaybeStatically(receiver: js.Tree, method: Symbol,
      arguments: List[js.Tree])(implicit pos: Position): js.Tree = {
    if (method.isPrivate || method.isClassConstructor)
      genApplyMethodStatically(receiver, method, arguments)
    else
      genApplyMethod(receiver, method, arguments)
  }

  /** Gen a dynamically linked call to a Scala method. */
  private def genApplyMethod(receiver: js.Tree, method: Symbol,
      arguments: List[js.Tree])(
      implicit pos: Position): js.Tree = {
    assert(!method.isPrivate,
        s"Cannot generate a dynamic call to private method $method at $pos")
    js.Apply(js.ApplyFlags.empty, receiver, encodeMethodSym(method), arguments)(
        toIRType(patchedResultType(method)))
  }

  /** Gen a statically linked call to an instance method. */
  private def genApplyMethodStatically(receiver: js.Tree, method: Symbol,
      arguments: List[js.Tree])(implicit pos: Position): js.Tree = {
    val flags = js.ApplyFlags.empty
      .withPrivate(method.isPrivate && !method.isClassConstructor)
      .withConstructor(method.isClassConstructor)
    js.ApplyStatically(flags, receiver, encodeClassName(method.owner),
        encodeMethodSym(method), arguments)(
        toIRType(patchedResultType(method)))
  }

  /** Gen a call to a static method. */
  private def genApplyStatic(method: Symbol, arguments: List[js.Tree])(
      implicit pos: Position): js.Tree = {
    js.ApplyStatic(js.ApplyFlags.empty.withPrivate(method.isPrivate),
        encodeClassName(method.owner), encodeMethodSym(method), arguments)(
        toIRType(patchedResultType(method)))
  }

  /** Gen a call to a non-exposed method of a non-native JS class. */
  private def genApplyJSClassMethod(receiver: js.Tree, method: Symbol,
      arguments: List[js.Tree])(implicit pos: Position): js.Tree = {
    genApplyStatic(method, receiver :: arguments)
  }

  /** Gen a call to a method of a Scala top-level module. */
  private def genModuleApplyMethod(methodSym: Symbol, arguments: List[js.Tree])(
      implicit pos: SourcePosition): js.Tree = {
    genApplyMethod(genLoadModule(methodSym.owner), methodSym, arguments)
  }

  /** Gen a boxing operation (tpe is the primitive type) */
  private def makePrimitiveBox(expr: js.Tree, tpe: Type)(
      implicit pos: Position): js.Tree = {
    toIRType(tpe) match {
      case jstpe.NoType => // for JS interop cases
        js.Block(expr, js.Undefined())
      case jstpe.BooleanType | jstpe.CharType | jstpe.ByteType |
          jstpe.ShortType | jstpe.IntType | jstpe.LongType | jstpe.FloatType |
          jstpe.DoubleType =>
        expr // box is identity for all those primitive types
      case typeRef =>
        throw new FatalError(
            s"makePrimitiveBox requires a primitive type, found $typeRef for $tpe at $pos")
    }
  }

  /** Gen an unboxing operation (tpe is the primitive type) */
  private def makePrimitiveUnbox(expr: js.Tree, tpe: Type)(
      implicit pos: Position): js.Tree = {
    toIRType(tpe) match {
      case jstpe.NoType => expr // for JS interop cases
      case irTpe        => js.AsInstanceOf(expr, irTpe)
    }
  }

  /** Gen JS code for a Scala.js-specific primitive method */
  private def genJSPrimitive(tree: Apply, args: List[Tree], code: Int,
      isStat: Boolean): js.Tree = {

    import JSPrimitives._

    implicit val pos = tree.span

    def genArgs1: js.Tree = {
      assert(args.size == 1,
          s"Expected exactly 1 argument for JS primitive $code but got " +
          s"${args.size} at $pos")
      genExpr(args.head)
    }

    def genArgs2: (js.Tree, js.Tree) = {
      assert(args.size == 2,
          s"Expected exactly 2 arguments for JS primitive $code but got " +
          s"${args.size} at $pos")
      (genExpr(args.head), genExpr(args.tail.head))
    }

    def genArgsVarLength: List[js.TreeOrJSSpread] =
      genActualJSArgs(tree.symbol, args)

    def resolveReifiedJSClassSym(arg: Tree): Symbol = {
      def fail(): Symbol = {
        report.error(
            tree.symbol.name.toString + " must be called with a constant " +
            "classOf[T] representing a class extending js.Any " +
            "(not a trait nor an object)",
            tree.sourcePos)
        NoSymbol
      }
      arg match {
        case Literal(value) if value.tag == Constants.ClazzTag =>
          val classSym = value.typeValue.typeSymbol
          if (isJSType(classSym) && !classSym.is(Trait) && !classSym.is(ModuleClass))
            classSym
          else
            fail()
        case _ =>
          fail()
      }
    }

    (code: @switch) match {
      case DYNNEW =>
        // js.Dynamic.newInstance(clazz)(actualArgs: _*)
        val (jsClass, actualArgs) = extractFirstArg(genArgsVarLength)
        js.JSNew(jsClass, actualArgs)

      case ARR_CREATE =>
        // js.Array(elements: _*)
        js.JSArrayConstr(genArgsVarLength)

      case CONSTRUCTOROF =>
        // runtime.constructorOf(clazz)
        val classSym = resolveReifiedJSClassSym(args.head)
        if (classSym == NoSymbol)
          js.Undefined() // compile error emitted by resolveReifiedJSClassSym
        else
          genLoadJSConstructor(classSym)

      /*
      case CREATE_INNER_JS_CLASS | CREATE_LOCAL_JS_CLASS =>
        // runtime.createInnerJSClass(clazz, superClass)
        // runtime.createLocalJSClass(clazz, superClass, fakeNewInstances)
        val classSym = resolveReifiedJSClassSym(args(0))
        val superClassValue = genExpr(args(1))
        if (classSym == NoSymbol) {
          js.Undefined() // compile error emitted by resolveReifiedJSClassSym
        } else {
          val captureValues = {
            if (code == CREATE_INNER_JS_CLASS) {
              val outer = genThis()
              List.fill(classSym.info.decls.count(_.isClassConstructor))(outer)
            } else {
              val ArrayValue(_, fakeNewInstances) = args(2)
              fakeNewInstances.flatMap(genCaptureValuesFromFakeNewInstance(_))
            }
          }
          js.CreateJSClass(encodeClassRef(classSym),
              superClassValue :: captureValues)
        }

      case WITH_CONTEXTUAL_JS_CLASS_VALUE =>
        // withContextualJSClassValue(jsclass, inner)
        val jsClassValue = genExpr(args(0))
        withScopedVars(
            contextualJSClassValue := Some(jsClassValue)
        ) {
          genStatOrExpr(args(1), isStat)
        }
      */

      case LINKING_INFO =>
        // runtime.linkingInfo
        js.JSLinkingInfo()

      case DEBUGGER =>
        // js.special.debugger()
        js.Debugger()

      case UNITVAL =>
        // BoxedUnit.UNIT, which is the boxed version of ()
        js.Undefined()

      case JS_NATIVE =>
        // js.native
        report.error(
            "js.native may only be used as stub implementation in facade types",
            tree.sourcePos)
        js.Undefined()

      case TYPEOF =>
        // js.typeOf(arg)
        val arg = genArgs1
        val typeofExpr = arg match {
          case arg: js.JSGlobalRef => js.JSTypeOfGlobalRef(arg)
          case _                   => js.JSUnaryOp(js.JSUnaryOp.typeof, arg)
        }
        js.AsInstanceOf(typeofExpr, jstpe.ClassType(jsNames.BoxedStringClass))

      case STRICT_EQ =>
        // js.special.strictEquals(arg1, arg2)
        val (arg1, arg2) = genArgs2
        js.JSBinaryOp(js.JSBinaryOp.===, arg1, arg2)

      case IN =>
        // js.special.in(arg1, arg2)
        val (arg1, arg2) = genArgs2
        js.AsInstanceOf(js.JSBinaryOp(js.JSBinaryOp.in, arg1, arg2),
            jstpe.BooleanType)

      case INSTANCEOF =>
        // js.special.instanceof(arg1, arg2)
        val (arg1, arg2) = genArgs2
        js.AsInstanceOf(js.JSBinaryOp(js.JSBinaryOp.instanceof, arg1, arg2),
            jstpe.BooleanType)

      case DELETE =>
        // js.special.delete(arg1, arg2)
        val (arg1, arg2) = genArgs2
        js.JSDelete(arg1, arg2)

      case FORIN =>
        /* js.special.forin(arg1, arg2)
         *
         * We must generate:
         *
         * val obj = arg1
         * val f = arg2
         * for (val key in obj) {
         *   f(key)
         * }
         *
         * with temporary vals, because `arg2` must be evaluated only
         * once, and after `arg1`.
         */
        val (arg1, arg2) = genArgs2
        val objVarDef = js.VarDef(freshLocalIdent("obj"), NoOriginalName,
            jstpe.AnyType, mutable = false, arg1)
        val fVarDef = js.VarDef(freshLocalIdent("f"), NoOriginalName,
            jstpe.AnyType, mutable = false, arg2)
        val keyVarIdent = freshLocalIdent("key")
        val keyVarRef = js.VarRef(keyVarIdent)(jstpe.AnyType)
        js.Block(
            objVarDef,
            fVarDef,
            js.ForIn(objVarDef.ref, keyVarIdent, NoOriginalName, {
              js.JSFunctionApply(fVarDef.ref, List(keyVarRef))
            }))

      case REFLECT_SELECTABLE_SELECTDYN =>
        // scala.reflect.Selectable.selectDynamic
        genReflectiveCall(tree, isSelectDynamic = true)
      case REFLECT_SELECTABLE_APPLYDYN =>
        // scala.reflect.Selectable.applyDynamic
        genReflectiveCall(tree, isSelectDynamic = false)
    }
  }

  /** Gen the SJSIR for a reflective call.
   *
   *  Reflective calls are calls to a structural type field or method that
   *  involve a reflective Selectable. They look like the following in source
   *  code:
   *  {{{
   *  import scala.reflect.Selectable.reflectiveSelectable
   *
   *  type Structural = {
   *    val foo: Int
   *    def bar(x: Int, y: String): String
   *  }
   *
   *  val structural: Structural = new {
   *    val foo: Int = 5
   *    def bar(x: Int, y: String): String = x.toString + y
   *  }
   *
   *  structural.foo
   *  structural.bar(6, "hello")
   *  }}}
   *
   *  After expansion by the Scala 3 rules for structural member selections and
   *  calls, they look like
   *
   *  {{{
   *  reflectiveSelectable(structural).selectDynamic("foo")
   *  reflectiveSelectable(structural).applyDynamic("bar",
   *    ClassTag(classOf[Int]), ClassTag(classOf[String])
   *  )(
   *    6, "hello"
   *  )
   *  }}}
   *
   *  and eventually reach the back-end as
   *
   *  {{{
   *  reflectiveSelectable(structural).selectDynamic("foo") // same as above
   *  reflectiveSelectable(structural).applyDynamic("bar",
   *    wrapRefArray([ ClassTag(classOf[Int]), ClassTag(classOf[String]) : ClassTag ]
   *  )(
   *    genericWrapArray([ Int.box(6), "hello" : Object ])
   *  )
   *  }}}
   *
   *  If we use the deprecated `import scala.language.reflectiveCalls`, the
   *  wrapper for the receiver `structural` are the following instead:
   *
   *  {{{
   *  reflectiveSelectableFromLangReflectiveCalls(structural)(
   *    using scala.languageFeature.reflectiveCalls)
   *  }}}
   *
   *  (in which case we don't care about the contextual argument).
   *
   *  In SJSIR, they must be encoded as follows:
   *
   *  {{{
   *  structural.foo;R()
   *  structural.bar;I;Ljava.lang.String;R(
   *    Int.box(6).asInstanceOf[int],
   *    "hello".asInstanceOf[java.lang.String]
   *  )
   *  }}}
   *
   *  This means that we must deconstruct the elaborated calls to recover:
   *
   *  - the original receiver `structural`
   *  - the method name as a compile-time string `foo` or `bar`
   *  - the `tp: Type`s that have been wrapped in `ClassTag(classOf[tp])`, as a
   *    compile-time List[Type], from which we'll derive `jstpe.Type`s for the
   *    `asInstanceOf`s and `jstpe.TypeRef`s for the `MethodName.reflectiveProxy`
   *  - the actual arguments as a compile-time `List[Tree]`
   *
   *  Virtually all of the code in `genReflectiveCall` deals with recovering
   *  those elements. Constructing the IR Tree is the easy part after that.
   */
  private def genReflectiveCall(tree: Apply, isSelectDynamic: Boolean): js.Tree = {
    implicit val pos = tree.span
    val Apply(fun @ Select(receiver0, _), args) = tree

    /* Extract the real receiver, which is the first argument to one of the
     * implicit conversions scala.reflect.Selectable.reflectiveSelectable or
     * scala.Selectable.reflectiveSelectableFromLangReflectiveCalls.
     */
    val receiver = receiver0 match {
      case Apply(fun1, receiver :: _)
          if fun1.symbol == jsdefn.ReflectSelectable_reflectiveSelectable ||
              fun1.symbol == jsdefn.Selectable_reflectiveSelectableFromLangReflectiveCalls =>
        genExpr(receiver)

      case _ =>
        report.error(
            "The receiver of Selectable.selectDynamic or Selectable.applyDynamic " +
            "must be a call to the (implicit) method scala.reflect.Selectable.reflectiveSelectable. " +
            "Other uses are not supported in Scala.js.",
            tree.sourcePos)
        js.Undefined()
    }

    // Extract the method name as a String
    val methodNameStr = args.head match {
      case Literal(Constants.Constant(name: String)) =>
        name
      case _ =>
        report.error(
            "The method name given to Selectable.selectDynamic or Selectable.applyDynamic " +
            "must be a literal string. " +
            "Other uses are not supported in Scala.js.",
            args.head.sourcePos)
        "erroneous"
    }

    val (formalParamTypeRefs, actualArgs) = if (isSelectDynamic) {
      (Nil, Nil)
    } else {
      // Extract the param type refs and actual args from the 2nd and 3rd argument to applyDynamic
      args.tail match {
        case WrapArray(classTagsArray: JavaSeqLiteral) :: WrapArray(actualArgsAnyArray: JavaSeqLiteral) :: Nil =>
          // Extract jstpe.Type's and jstpe.TypeRef's from the ClassTag.apply(_) trees
          val formalParamTypesAndTypeRefs = classTagsArray.elems.map {
            // ClassTag.apply(classOf[tp]) -> tp
            case Apply(fun, Literal(const) :: Nil)
                if fun.symbol == defn.ClassTagModule_apply && const.tag == Constants.ClazzTag =>
              toIRTypeAndTypeRef(const.typeValue)
            // ClassTag.SpecialType -> erasure(SepecialType.typeRef) (e.g., ClassTag.Any -> Object)
            case Apply(Select(classTagModule, name), Nil)
                if classTagModule.symbol == defn.ClassTagModule &&
                    defn.SpecialClassTagClasses.exists(_.name == name.toTypeName) =>
              toIRTypeAndTypeRef(TypeErasure.erasure(
                  defn.SpecialClassTagClasses.find(_.name == name.toTypeName).get.typeRef))
            // Anything else is invalid
            case classTag =>
              report.error(
                  "The ClassTags passed to Selectable.applyDynamic must be " +
                  "literal ClassTag(classOf[T]) expressions " +
                  "(typically compiler-generated). " +
                  "Other uses are not supported in Scala.js.",
                  classTag.sourcePos)
              (jstpe.AnyType, jstpe.ClassRef(jsNames.ObjectClass))
          }

          // Gen the actual args, downcasting them to the formal param types
          val actualArgs = actualArgsAnyArray.elems.zip(formalParamTypesAndTypeRefs).map {
            (actualArgAny, formalParamTypeAndTypeRef) =>
              val genActualArgAny = genExpr(actualArgAny)
              js.AsInstanceOf(genActualArgAny, formalParamTypeAndTypeRef._1)(genActualArgAny.pos)
          }

          (formalParamTypesAndTypeRefs.map(_._2), actualArgs)

        case _ =>
          report.error(
              "Passing the varargs of Selectable.applyDynamic with `: _*` " +
              "is not supported in Scala.js.",
              tree.sourcePos)
          (Nil, Nil)
      }
    }

    val methodName = MethodName.reflectiveProxy(methodNameStr, formalParamTypeRefs)

    js.Apply(js.ApplyFlags.empty, receiver, js.MethodIdent(methodName), actualArgs)(jstpe.AnyType)
  }

  /** Gen actual actual arguments to Scala method call.
   *  Returns a list of the transformed arguments.
   *
   *  This tries to optimize repeated arguments (varargs) by turning them
   *  into js.WrappedArray instead of Scala wrapped arrays.
   */
  private def genActualArgs(sym: Symbol, args: List[Tree])(
      implicit pos: Position): List[js.Tree] = {
    args.map(genExpr)
    /*val wereRepeated = exitingPhase(currentRun.typerPhase) {
      sym.tpe.params.map(p => isScalaRepeatedParamType(p.tpe))
    }

    if (wereRepeated.size > args.size) {
      // Should not happen, but let's not crash
      args.map(genExpr)
    } else {
      /* Arguments that are in excess compared to the type signature after
       * erasure are lambda-lifted arguments. They cannot be repeated, hence
       * the extension to `false`.
       */
      for ((arg, wasRepeated) <- args.zipAll(wereRepeated, EmptyTree, false)) yield {
        if (wasRepeated) {
          tryGenRepeatedParamAsJSArray(arg, handleNil = false).fold {
            genExpr(arg)
          } { genArgs =>
            genNew(WrappedArrayClass, WrappedArray_ctor,
                List(js.JSArrayConstr(genArgs)))
          }
        } else {
          genExpr(arg)
        }
      }
    }*/
  }

  /** Gen actual actual arguments to a JS method call.
   *  Returns a list of the transformed arguments.
   *
   *  - TODO Repeated arguments (varargs) are expanded
   *  - Default arguments are omitted or replaced by undefined
   *  - All arguments are boxed
   *
   *  Repeated arguments that cannot be expanded at compile time (i.e., if a
   *  Seq is passed to a varargs parameter with the syntax `seq: _*`) will be
   *  wrapped in a [[js.JSSpread]] node to be expanded at runtime.
   */
  private def genActualJSArgs(sym: Symbol, args: List[Tree])(
      implicit pos: Position): List[js.TreeOrJSSpread] = {

    def paramNamesAndTypes(using Context): List[(Names.TermName, Type)] =
      sym.info.paramNamess.flatten.zip(sym.info.paramInfoss.flatten)

    val wereRepeated = atPhase(elimRepeatedPhase) {
      val list =
        for ((name, tpe) <- paramNamesAndTypes)
        yield (name -> tpe.isRepeatedParam)
      list.toMap
    }

    val paramTypes = atPhase(elimErasedValueTypePhase) {
      paramNamesAndTypes.toMap
    }

    var reversedArgs: List[js.TreeOrJSSpread] = Nil

    val argsParamNamesAndTypes = args.zip(paramNamesAndTypes)
    for ((arg, (paramName, paramType)) <- argsParamNamesAndTypes) {
      val wasRepeated = wereRepeated.getOrElse(paramName, false)
      if (wasRepeated) {
        reversedArgs =
          genJSRepeatedParam(arg) reverse_::: reversedArgs
      } else {
        val unboxedArg = genExpr(arg)
        val boxedArg = unboxedArg match {
          case js.Transient(UndefinedParam) =>
            unboxedArg
          case _ =>
            val tpe = paramTypes.getOrElse(paramName, paramType)
            box(unboxedArg, tpe)
        }
        reversedArgs ::= boxedArg
      }
    }

    /* Remove all consecutive UndefinedParam's at the end of the argument
     * list. No check is performed whether they may be there, since they will
     * only be placed where default arguments can be anyway.
     */
    reversedArgs = reversedArgs.dropWhile(_.isInstanceOf[js.Transient])

    /* Find remaining UndefinedParam and replace by js.Undefined. This can
     * happen with named arguments or with multiple argument lists.
     */
    reversedArgs = reversedArgs map {
      case js.Transient(UndefinedParam) => js.Undefined()
      case arg => arg
    }

    reversedArgs.reverse
  }

  /** Gen JS code for a repeated param of a JS method.
   *
   *  In this case `arg` has type `Seq[T]` for some `T`, but the result should
   *  be an expanded list of the elements in the sequence. So this method
   *  takes care of the conversion.
   *
   *  It is specialized for the shapes of tree generated by the desugaring
   *  of repeated params in Scala, so that these are actually expanded at
   *  compile-time.
   *
   *  Otherwise, it returns a `JSSpread` with the `Seq` converted to a
   *  `js.Array`.
   */
  private def genJSRepeatedParam(arg: Tree): List[js.TreeOrJSSpread] = {
    tryGenRepeatedParamAsJSArray(arg, handleNil = true).getOrElse {
      /* Fall back to calling runtime.genTraversableOnce2jsArray
       * to perform the conversion to js.Array, then wrap in a Spread
       * operator.
       */
      implicit val pos: SourcePosition = arg.sourcePos
      val jsArrayArg = genModuleApplyMethod(
          jsdefn.Runtime_toJSVarArgs,
          List(genExpr(arg)))
      List(js.JSSpread(jsArrayArg))
    }
  }

  /** Try and expand an actual argument to a repeated param `(xs: T*)`.
   *
   *  This method recognizes the shapes of tree generated by the desugaring
   *  of repeated params in Scala, and expands them.
   *  If `arg` does not have the shape of a generated repeated param, this
   *  method returns `None`.
   */
  private def tryGenRepeatedParamAsJSArray(arg: Tree,
      handleNil: Boolean): Option[List[js.Tree]] = {
    implicit val pos = arg.span

    // Given a method `def foo(args: T*)`
    arg match {
      // foo(arg1, arg2, ..., argN) where N > 0
      case MaybeAsInstanceOf(WrapArray(MaybeAsInstanceOf(array: JavaSeqLiteral))) =>
        /* Value classes in arrays are already boxed, so no need to use
         * the type before erasure.
         * TODO Is this true in dotty?
         */
        Some(array.elems.map(e => box(genExpr(e), e.tpe)))

      // foo()
      case Ident(_) if handleNil && arg.symbol == defn.NilModule =>
        Some(Nil)

      // foo(argSeq: _*) - cannot be optimized
      case _ =>
        None
    }
  }

  private object MaybeAsInstanceOf {
    def unapply(tree: Tree): Some[Tree] = tree match {
      case TypeApply(asInstanceOf_? @ Select(base, _), _)
          if asInstanceOf_?.symbol == defn.Any_asInstanceOf =>
        Some(base)
      case _ =>
        Some(tree)
    }
  }

  private object WrapArray {
    lazy val isWrapArray: Set[Symbol] = {
      val names0 = defn.ScalaValueClasses().map(sym => nme.wrapXArray(sym.name))
      val names1 = names0 ++ Set(nme.wrapRefArray, nme.genericWrapArray)
      val symsInPredef = names1.map(defn.ScalaPredefModule.requiredMethod(_))
      val symsInScalaRunTime = names1.map(defn.ScalaRuntimeModule.requiredMethod(_))
      (symsInPredef ++ symsInScalaRunTime).toSet
    }

    def unapply(tree: Apply): Option[Tree] = tree match {
      case Apply(wrapArray_?, List(wrapped)) if isWrapArray(wrapArray_?.symbol) =>
        Some(wrapped)
      case _ =>
        None
    }
  }

  /** Gen JS code for loading a Java static field.
   */
  private def genLoadStaticField(sym: Symbol)(implicit pos: SourcePosition): js.Tree = {
    /* Actually, there is no static member in Scala.js. If we come here, that
     * is because we found the symbol in a Java-emitted .class in the
     * classpath. But the corresponding implementation in Scala.js will
     * actually be a val in the companion module.
     */

    if (sym == defn.BoxedUnit_UNIT) {
      js.Undefined()
    } else if (sym == defn.BoxedUnit_TYPE) {
      js.ClassOf(jstpe.VoidRef)
    } else {
      val className = encodeClassName(sym.owner)
      val method = encodeStaticMemberSym(sym)
      js.ApplyStatic(js.ApplyFlags.empty, className, method, Nil)(toIRType(sym.info))
    }
  }

  /** Generate loading of a module value.
   *
   *  Can be given either the module symbol or its module class symbol.
   *
   *  If the module we load refers to the global scope (i.e., it is
   *  annotated with `@JSGlobalScope`), report a compile error specifying
   *  that a global scope object should only be used as the qualifier of a
   *  `.`-selection.
   */
  private def genLoadModule(sym: Symbol)(implicit pos: SourcePosition): js.Tree =
    ruleOutGlobalScope(genLoadModuleOrGlobalScope(sym))

  /** Generate loading of a module value or the global scope.
   *
   *  Can be given either the module symbol of its module class symbol.
   *
   *  Unlike `genLoadModule`, this method does not fail if the module we load
   *  refers to the global scope.
   */
  def genLoadModuleOrGlobalScope(sym0: Symbol)(
      implicit pos: SourcePosition): MaybeGlobalScope = {

    require(sym0.is(Module),
        "genLoadModule called with non-module symbol: " + sym0)
    val sym = if (sym0.isTerm) sym0.moduleClass else sym0

    // Does that module refer to the global scope?
    if (sym.hasAnnotation(jsdefn.JSGlobalScopeAnnot)) {
      MaybeGlobalScope.GlobalScope(pos)
    } else {
      val cls = encodeClassName(sym)
      val tree =
        if (isJSType(sym)) js.LoadJSModule(cls)
        else js.LoadModule(cls)
      MaybeGlobalScope.NotGlobalScope(tree)
    }
  }

  /** Gen JS code representing the constructor of a JS class. */
  private def genLoadJSConstructor(sym: Symbol)(
      implicit pos: Position): js.Tree = {
    assert(!isStaticModule(sym) && !sym.is(Trait),
        s"genPrimitiveJSClass called with non-class $sym")
    js.LoadJSConstructor(encodeClassName(sym))
  }

  private final val GenericGlobalObjectInformationMsg = {
    "\n  " +
    "See https://www.scala-js.org/doc/interoperability/global-scope.html " +
    "for further information."
  }

  /** Rule out the `GlobalScope` case of a `MaybeGlobalScope` and extract the
   *  value tree.
   *
   *  If `tree` represents the global scope, report a compile error.
   */
  private def ruleOutGlobalScope(tree: MaybeGlobalScope): js.Tree = {
    tree match {
      case MaybeGlobalScope.NotGlobalScope(t) =>
        t
      case MaybeGlobalScope.GlobalScope(pos) =>
        reportErrorLoadGlobalScope()(pos)
    }
  }

  /** Report a compile error specifying that the global scope cannot be
   *  loaded as a value.
   */
  private def reportErrorLoadGlobalScope()(implicit pos: SourcePosition): js.Tree = {
    report.error(
        "Loading the global scope as a value (anywhere but as the " +
        "left-hand-side of a `.`-selection) is not allowed." +
        GenericGlobalObjectInformationMsg,
        pos)
    js.Undefined()
  }

  /** Gen a JS bracket select or a `JSGlobalRef`.
   *
   *  If the receiver is a normal value, i.e., not the global scope, then
   *  emit a `JSSelect`.
   *
   *  Otherwise, if the `item` is a constant string that is a valid
   *  JavaScript identifier, emit a `JSGlobalRef`.
   *
   *  Otherwise, report a compile error.
   */
  private def genJSSelectOrGlobalRef(qual: MaybeGlobalScope, item: js.Tree)(
      implicit pos: SourcePosition): js.Tree = {
    qual match {
      case MaybeGlobalScope.NotGlobalScope(qualTree) =>
        js.JSSelect(qualTree, item)

      case MaybeGlobalScope.GlobalScope(_) =>
        item match {
          case js.StringLiteral(value) =>
            if (js.JSGlobalRef.isValidJSGlobalRefName(value)) {
              js.JSGlobalRef(value)
            } else if (js.JSGlobalRef.ReservedJSIdentifierNames.contains(value)) {
              report.error(
                  "Invalid selection in the global scope of the reserved " +
                  s"identifier name `$value`." +
                  GenericGlobalObjectInformationMsg,
                  pos)
              js.JSGlobalRef("erroneous")
            } else {
              report.error(
                  "Selecting a field of the global scope whose name is " +
                  "not a valid JavaScript identifier is not allowed." +
                  GenericGlobalObjectInformationMsg,
                  pos)
              js.JSGlobalRef("erroneous")
            }

          case _ =>
            report.error(
                "Selecting a field of the global scope with a dynamic " +
                "name is not allowed." +
                GenericGlobalObjectInformationMsg,
                pos)
            js.JSGlobalRef("erroneous")
        }
    }
  }

  /** Gen a JS bracket method apply or an apply of a `GlobalRef`.
   *
   *  If the receiver is a normal value, i.e., not the global scope, then
   *  emit a `JSMethodApply`.
   *
   *  Otherwise, if the `method` is a constant string that is a valid
   *  JavaScript identifier, emit a `JSFunctionApply(JSGlobalRef(...), ...)`.
   *
   *  Otherwise, report a compile error.
   */
  private def genJSMethodApplyOrGlobalRefApply(
      receiver: MaybeGlobalScope, method: js.Tree, args: List[js.TreeOrJSSpread])(
      implicit pos: SourcePosition): js.Tree = {
    receiver match {
      case MaybeGlobalScope.NotGlobalScope(receiverTree) =>
        js.JSMethodApply(receiverTree, method, args)

      case MaybeGlobalScope.GlobalScope(_) =>
        method match {
          case js.StringLiteral(value) =>
            if (js.JSGlobalRef.isValidJSGlobalRefName(value)) {
              js.JSFunctionApply(js.JSGlobalRef(value), args)
            } else if (js.JSGlobalRef.ReservedJSIdentifierNames.contains(value)) {
              report.error(
                  "Invalid call in the global scope of the reserved " +
                  s"identifier name `$value`." +
                  GenericGlobalObjectInformationMsg,
                  pos)
              js.Undefined()
            } else {
              report.error(
                  "Calling a method of the global scope whose name is not " +
                  "a valid JavaScript identifier is not allowed." +
                  GenericGlobalObjectInformationMsg,
                  pos)
              js.Undefined()
            }

          case _ =>
            report.error(
                "Calling a method of the global scope with a dynamic " +
                "name is not allowed." +
                GenericGlobalObjectInformationMsg,
                pos)
            js.Undefined()
        }
    }
  }

  private def computeJSNativeLoadSpecOfValDef(sym: Symbol): js.JSNativeLoadSpec = {
    atPhase(picklerPhase.next) {
      computeJSNativeLoadSpecOfInPhase(sym)
    }
  }

  private def computeJSNativeLoadSpecOfClass(sym: Symbol): Option[js.JSNativeLoadSpec] = {
    if (sym.is(Trait) || sym.hasAnnotation(jsdefn.JSGlobalScopeAnnot)) {
      None
    } else {
      atPhase(picklerPhase.next) {
        if (sym.owner.isStaticOwner)
          Some(computeJSNativeLoadSpecOfInPhase(sym))
        else
          None
      }
    }
  }

  private def computeJSNativeLoadSpecOfInPhase(sym: Symbol)(using Context): js.JSNativeLoadSpec = {
    import js.JSNativeLoadSpec._

    val symOwner = sym.owner

    // Marks a code path as unexpected because it should have been reported as an error in `PrepJSInterop`.
    def unexpected(msg: String): Nothing =
      throw new FatalError(i"$msg for ${sym.fullName} at ${sym.srcPos}")

    if (symOwner.hasAnnotation(jsdefn.JSNativeAnnot)) {
      val jsName = sym.jsName match {
        case JSName.Literal(jsName) => jsName
        case JSName.Computed(_)     => unexpected("could not read the simple JS name as a string literal")
      }

      if (symOwner.hasAnnotation(jsdefn.JSGlobalScopeAnnot)) {
        Global(jsName, Nil)
      } else {
        val ownerLoadSpec = computeJSNativeLoadSpecOfInPhase(symOwner)
        ownerLoadSpec match {
          case Global(globalRef, path) =>
            Global(globalRef, path :+ jsName)
          case Import(module, path) =>
            Import(module, path :+ jsName)
          case ImportWithGlobalFallback(Import(module, modulePath), Global(globalRef, globalPath)) =>
            ImportWithGlobalFallback(
                Import(module, modulePath :+ jsName),
                Global(globalRef, globalPath :+ jsName))
        }
      }
    } else {
      def parsePath(pathName: String): List[String] =
        pathName.split('.').toList

      def parseGlobalPath(pathName: String): Global = {
        val globalRef :: path = parsePath(pathName)
        Global(globalRef, path)
      }

      val annot = sym.annotations.find { annot =>
        annot.symbol == jsdefn.JSGlobalAnnot || annot.symbol == jsdefn.JSImportAnnot
      }.getOrElse {
        unexpected("could not find the JS native load spec annotation")
      }

      if (annot.symbol == jsdefn.JSGlobalAnnot) {
        val pathName = annot.argumentConstantString(0).getOrElse {
          sym.defaultJSName
        }
        parseGlobalPath(pathName)
      } else { // annot.symbol == jsdefn.JSImportAnnot
        val module = annot.argumentConstantString(0).getOrElse {
          unexpected("could not read the module argument as a string literal")
        }
        val path = annot.argumentConstantString(1).fold[List[String]](Nil)(parsePath)
        val importSpec = Import(module, path)
        annot.argumentConstantString(2).fold[js.JSNativeLoadSpec] {
          importSpec
        } { globalPathName =>
          ImportWithGlobalFallback(importSpec, parseGlobalPath(globalPathName))
        }
      }
    }
  }

  private def isMethodStaticInIR(sym: Symbol): Boolean =
    sym.is(JavaStatic)

  /** Generate a Class[_] value (e.g. coming from classOf[T]) */
  private def genClassConstant(tpe: Type)(implicit pos: Position): js.Tree =
    js.ClassOf(toTypeRef(tpe))

  private def isStaticModule(sym: Symbol): Boolean =
    sym.is(Module) && sym.isStatic

  private def isPrimitiveValueType(tpe: Type): Boolean = {
    tpe.widenDealias match {
      case JavaArrayType(_)   => false
      case _: ErasedValueType => false
      case t                  => t.typeSymbol.asClass.isPrimitiveValueClass
    }
  }

  protected lazy val isHijackedClass: Set[Symbol] = {
    /* This list is a duplicate of ir.Definitions.HijackedClasses, but
     * with global.Symbol's instead of IR encoded names as Strings.
     * We also add java.lang.Void, which BoxedUnit "erases" to.
     */
    Set[Symbol](
        defn.BoxedUnitClass, defn.BoxedBooleanClass, defn.BoxedCharClass, defn.BoxedByteClass,
        defn.BoxedShortClass, defn.BoxedIntClass, defn.BoxedLongClass, defn.BoxedFloatClass,
        defn.BoxedDoubleClass, defn.StringClass, jsdefn.JavaLangVoidClass
    )
  }

  private def isMaybeJavaScriptException(tpe: Type): Boolean =
    jsdefn.JavaScriptExceptionClass.isSubClass(tpe.typeSymbol)

  // Copied from DottyBackendInterface

  private val desugared = new java.util.IdentityHashMap[Type, tpd.Select]

  def desugarIdent(i: Ident): Option[tpd.Select] = {
    var found = desugared.get(i.tpe)
    if (found == null) {
      tpd.desugarIdent(i) match {
        case sel: tpd.Select =>
          desugared.put(i.tpe, sel)
          found = sel
        case _ =>
      }
    }
    if (found == null) None else Some(found)
  }
}

object JSCodeGen {

  private val NullPointerExceptionClass = ClassName("java.lang.NullPointerException")
  private val JSObjectClassName = ClassName("scala.scalajs.js.Object")

  private val newSimpleMethodName = SimpleMethodName("new")

  private val ObjectArgConstructorName =
    MethodName.constructor(List(jstpe.ClassRef(ir.Names.ObjectClass)))

  private val thisOriginalName = OriginalName("this")

  sealed abstract class MaybeGlobalScope

  object MaybeGlobalScope {
    final case class NotGlobalScope(tree: js.Tree) extends MaybeGlobalScope

    final case class GlobalScope(pos: SourcePosition) extends MaybeGlobalScope
  }

  /** Marker object for undefined parameters in JavaScript semantic calls.
   *
   *  To be used inside a `js.Transient` node.
   */
  case object UndefinedParam extends js.Transient.Value {
    def printIR(out: ir.Printers.IRTreePrinter): Unit =
      out.print("<undefined-param>")
  }

}

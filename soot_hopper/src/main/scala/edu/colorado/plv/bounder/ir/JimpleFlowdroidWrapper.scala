package edu.colorado.plv.bounder.ir

import java.util

import edu.colorado.plv.bounder.BounderSetupApplication
import edu.colorado.plv.bounder.solver.PersistantConstraints
import edu.colorado.plv.bounder.symbolicexecutor._
import edu.colorado.plv.fixedsoot.EnhancedUnitGraphFixed
import soot.jimple.infoflow.entryPointCreators.SimulatedCodeElementTag
import soot.jimple.internal._
import soot.jimple.spark.SparkTransformer
import soot.jimple.toolkits.callgraph.{CHATransformer, CallGraph}
import soot.jimple._
import soot.util.Chain
import soot.{ArrayType, Body, BooleanType, ByteType, CharType, DoubleType, FloatType, Hierarchy, IntType, LongType, RefType, Scene, ShortType, SootClass, SootMethod, SootMethodRef, Type, Value}

import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scala.collection.{MapView, mutable}
import scala.jdk.CollectionConverters._
import scala.util.matching.Regex

object JimpleFlowdroidWrapper{
  def stringNameOfClass(m : SootClass): String = {
    val name = m.getName
//    s"${m.getPackageName}.${name}"
    name
  }
  def stringNameOfType(t : Type) : String = t match {
    case t:RefType =>
      val str = t.toString
      str
    case _:IntType => PersistantConstraints.intType
    case _:ShortType => PersistantConstraints.shortType
    case _:ByteType => PersistantConstraints.byteType
    case _:LongType => PersistantConstraints.longType
    case _:DoubleType => PersistantConstraints.doubleType
    case _:CharType => PersistantConstraints.charType
    case _:BooleanType => PersistantConstraints.booleanType
    case _:FloatType => PersistantConstraints.floatType
    case t => t.toString
  }

  /**
   * Use instead of soot version because soot version crashes on interface.
   * @param sootClass
   * @return
   */
  def subThingsOf(sootClass: SootClass):Set[SootClass] =
    if(sootClass.isInterface)
      Scene.v.getActiveHierarchy.getImplementersOf(sootClass).asScala.toSet
    else
      Scene.v.getActiveHierarchy.getSubclassesOfIncluding(sootClass).asScala.toSet

}

trait CallGraphProvider{
  def edgesInto(method:SootMethod):Set[(SootMethod,soot.Unit)]
  def edgesOutOf(unit:soot.Unit):Set[SootMethod]
  def edgesOutOfMethod(method: SootMethod):Set[SootMethod]
}

/**
 * Compute an application only call graph
 * see Application-only Call Graph Construction, Karim Ali
 * @param cg
 */
class AppOnlyCallGraph(cg: CallGraph,
                       callbacks: Set[SootMethod],
                       wrapper: JimpleFlowdroidWrapper,
                       resolver: AppCodeResolver) extends CallGraphProvider {
  sealed trait PointLoc
  case class FieldPoint(clazz: SootClass, fname: String) extends PointLoc
  case class LocalPoint(method: SootMethod, locName:String) extends PointLoc
  // TODO: finish implementing this
  var pointsTo = mutable.Map[PointLoc, Set[SootClass]]()
  var icg = mutable.Map[soot.Unit, Set[SootMethod]]()
  var workList = callbacks.toList
  case class PTState(pointsTo: Map[PointLoc, Set[SootClass]],
                     callGraph : Map[soot.Unit, Set[SootMethod]],
                     registered: Set[SootClass]){
    def updateLocal(method: SootMethod, name:String, clazz : SootClass): PTState = {
      val ptKey = LocalPoint(method,name)
      val updatedClasses = pointsTo.getOrElse(ptKey, Set()) + clazz
      this.copy(pointsTo=pointsTo+(ptKey-> updatedClasses))
    }

  }
//  val hierarchy = Scene.v().getActiveHierarchy
  def initialParamForCallback(method:SootMethod, state:PTState):PTState = {
    ???
  }
  def processMethod(method:SootMethod, state:PTState) : (PTState, List[SootMethod]) = {

    ???
  }
  @tailrec
  private def iComputePt(workList: Queue[SootMethod], state: PTState): PTState = {
    if(workList.isEmpty) state else {
      val head = workList.front
      val (state1,nextWL) = processMethod(head, state)
      iComputePt(workList.tail ++ nextWL, state1)
    }
  }
  val allFwkClasses = Scene.v().getClasses.asScala.filter(c =>
    resolver.isFrameworkClass(JimpleFlowdroidWrapper.stringNameOfClass(c))).toSet
  val ptState = iComputePt(Queue.from(callbacks),PTState(Map(), Map(), allFwkClasses))

  override def edgesInto(method : SootMethod): Set[(SootMethod,soot.Unit)] = {
    ???
  }

  override def edgesOutOf(unit: soot.Unit): Set[SootMethod] = {
    ptState.callGraph(unit)
  }

  override def edgesOutOfMethod(method: SootMethod): Set[SootMethod] = ???
}

/**
 * A call graph wrapper that patches in missing edges from CHA
 * missing edges are detected by call sites with no outgoing edges
 * @param cg
 */
class PatchingCallGraphWrapper(cg:CallGraph, appMethods: Set[SootMethod]) extends CallGraphProvider{
  val unitToTargets: MapView[SootMethod, Set[(SootMethod,soot.Unit)]] =
    appMethods.flatMap{ outerMethod =>
      if(outerMethod.hasActiveBody){
        outerMethod.getActiveBody.getUnits.asScala.flatMap{unit =>
          val methods = edgesOutOf(unit)
          methods.map(m => (m,(outerMethod,unit)))
        }
      }else{Set()}
    }.groupBy(_._1).view.mapValues(l => l.map(_._2))

  def edgesInto(method : SootMethod): Set[(SootMethod,soot.Unit)] = {
    unitToTargets.getOrElse(method, Set())
  }

  def findMethodInvoke(clazz : SootClass, method: SootMethodRef) : Option[SootMethod] =
    if(clazz.declaresMethod(method.getSubSignature))
      Some(clazz.getMethod(method.getSubSignature))
    else{
      if(clazz.hasSuperclass){
        val superClass = clazz.getSuperclass
        findMethodInvoke(superClass, method)
      }else None
    }

  private def baseType(sType: Value): SootClass = sType match{
    case l : JimpleLocal if l.getType.isInstanceOf[RefType] =>
      l.getType.asInstanceOf[RefType].getSootClass
    case v : JimpleLocal if v.getType.isInstanceOf[ArrayType]=>
      // Arrays inherit Object methods such as clone and toString
      // We consider both as callins when invoked on arrays
      Scene.v().getSootClass("java.lang.Object")
    case v =>
      println(v)
      ???
  }
  val subThingsOf : SootClass => Set[SootClass] = JimpleFlowdroidWrapper.subThingsOf
  private def fallbackOutEdgesInvoke(v : Value):Set[SootMethod] = v match{
    case v : JVirtualInvokeExpr =>
      // TODO: is base ever not a local?
      val base = v.getBase
      val reachingObjects = subThingsOf(baseType(base))
      val ref: SootMethodRef = v.getMethodRef
      val out = reachingObjects.flatMap(findMethodInvoke(_, ref))
      Set(out.toList:_*).filter(m => !m.isAbstract)
    case i : JInterfaceInvokeExpr =>
      val base = i.getBase.asInstanceOf[JimpleLocal]
      val reachingObjects =
        subThingsOf(base.getType.asInstanceOf[RefType].getSootClass)
      val ref: SootMethodRef = i.getMethodRef
      val out = reachingObjects.flatMap(findMethodInvoke(_, ref)).filter(m => !m.isAbstract)
      Set(out.toList:_*)
    case i : JSpecialInvokeExpr =>
      val m = i.getMethod
      assert(!m.isAbstract, "Special invoke of abstract method?")
      Set(m)
    case i : JStaticInvokeExpr =>
      val method = i.getMethod
      Set(method)
    case v => Set() //Non invoke methods have no edges
  }
  private def fallbackOutEdges(unit: soot.Unit): Set[SootMethod] = unit match{
    case j: JAssignStmt => fallbackOutEdgesInvoke(j.getRightOp)
    case j: JInvokeStmt => fallbackOutEdgesInvoke(j.getInvokeExpr)
    case _ => Set()
  }
  def edgesOutOf(unit: soot.Unit): Set[SootMethod] = {
    val out = cg.edgesOutOf(unit).asScala.map(e => e.tgt())
    if(out.isEmpty) {
      fallbackOutEdges(unit)
    } else out.toSet
  }
  def edgesOutOfMethod(method: SootMethod):Set[SootMethod] = {
    val cgOut = cg.edgesOutOf(method).asScala.map(e => e.tgt()).toSet
    if(method.hasActiveBody) {
      method.getActiveBody.getUnits.asScala.foldLeft(cgOut) {
        case (ccg, unit) if !cg.edgesOutOf(unit).hasNext => ccg ++ edgesOutOf(unit)
        case (ccg, _) => ccg
      }
    }else cgOut
  }
}

class CallGraphWrapper(cg: CallGraph) extends CallGraphProvider{
  def edgesInto(method : SootMethod): Set[(SootMethod,soot.Unit)] = {
    val out = cg.edgesInto(method).asScala.map(e => (e.src(),e.srcUnit()))
    out.toSet
  }

  def edgesOutOf(unit: soot.Unit): Set[SootMethod] = {
    val out = cg.edgesOutOf(unit).asScala.map(e => e.tgt())
    out.toSet
  }
  def edgesOutOfMethod(method: SootMethod):Set[SootMethod] = {
    cg.edgesOutOf(method).asScala.map(e => e.tgt()).toSet
  }
}

class JimpleFlowdroidWrapper(apkPath : String,
                             callGraphSource: CallGraphSource) extends IRWrapper[SootMethod, soot.Unit] {

  BounderSetupApplication.loadApk(apkPath, callGraphSource)


  private var unitGraphCache : scala.collection.mutable.Map[Body, EnhancedUnitGraphFixed] = scala.collection.mutable.Map()
  private var appMethodCache : scala.collection.mutable.Set[SootMethod] = scala.collection.mutable.Set()

  val resolver = new DefaultAppCodeResolver[SootMethod, soot.Unit](this)

  val callbacks: Set[SootMethod] = resolver.getCallbacks.flatMap{
    case JimpleMethodLoc(method) => Some(method)
  }

  val cg: CallGraphProvider = callGraphSource match{
    case SparkCallGraph =>
      Scene.v().setEntryPoints(callbacks.toList.asJava)
      val appClasses: Set[SootClass] = getAppMethods(resolver).flatMap{ a =>
        val cname = JimpleFlowdroidWrapper.stringNameOfClass(a.getDeclaringClass)
        if (!resolver.isFrameworkClass(cname))
          Some(a.getDeclaringClass)
        else None
      }
      appClasses.foreach{(c:SootClass) =>
        c.setApplicationClass()
      }

      val opt = Map(
        ("verbose", "true"),
        ("propagator", "worklist"),
        ("simple-edges-bidirectional", "true"),
        ("on-fly-cg", "true"),
        ("set-impl", "double"),
        ("double-set-old", "hybrid"),
        ("double-set-new", "hybrid")
      )
      SparkTransformer.v().transform("", opt.asJava)
      val pta = Scene.v().getPointsToAnalysis

      new CallGraphWrapper(Scene.v().getCallGraph)
    case CHACallGraph =>
      Scene.v().setEntryPoints(callbacks.toList.asJava)
      CHATransformer.v().transform()
      new CallGraphWrapper(Scene.v().getCallGraph)
    case AppOnlyCallGraph =>
      val chacg: CallGraph = Scene.v().getCallGraph
      new AppOnlyCallGraph(chacg, callbacks, this, resolver)
    case FlowdroidCallGraph => new CallGraphWrapper(Scene.v().getCallGraph)
    case PatchedFlowdroidCallGraph =>
      new PatchingCallGraphWrapper(Scene.v().getCallGraph, getAppMethods(resolver))
  }


  def addClassFile(path: String): Unit = {
    ???
  }

  private def cmdToLoc(u : soot.Unit, containingMethod:SootMethod): AppLoc = {
    AppLoc(JimpleMethodLoc(containingMethod),JimpleLineLoc(u,containingMethod),false)
  }

  protected def getUnitGraph(body:Body):EnhancedUnitGraphFixed = {
    if(unitGraphCache.contains(body)){
      unitGraphCache.getOrElse(body, ???)
    }else{
      val cache = new EnhancedUnitGraphFixed(body)
      unitGraphCache.put(body, cache)
      cache
    }
  }
  protected def getAppMethods(resolver: AppCodeResolver):Set[SootMethod] = {
    if(appMethodCache.isEmpty) {
      val classes = Scene.v().getApplicationClasses
      classes.forEach(c =>
        if (resolver.isAppClass(c.getName))
           c.methodIterator().forEachRemaining(m => {
             var simulated:Boolean = false
             // simulated code tags added to flowdroid additions
             m.getTags.forEach(t =>
               simulated = simulated || t.isInstanceOf[SimulatedCodeElementTag])
             if(!simulated)
              appMethodCache.add(m)
           })
      )
    }
    appMethodCache.toSet
  }


  def getClassByName(className:String):SootClass = {
    Scene.v().getSootClass(className)
  }
  override def findMethodLoc(className: String, methodName: String):Option[JimpleMethodLoc] = {
    val methodRegex: Regex = methodName.r
    val clazzFound = getClassByName(className)
    val clazz = if(clazzFound.isPhantom){None} else {Some(clazzFound)}
    val method: Option[SootMethod] = clazz.flatMap(a => try{
      Some(a.getMethod(methodName))
    }catch{
      case t:RuntimeException if t.getMessage.startsWith("No method") =>
        None
      case t:Throwable => throw t
    })
    method.map(a=> JimpleMethodLoc(a))
  }
  def findMethodLocRegex(className: String, methodName: Regex):Option[JimpleMethodLoc] = {
    val methodRegex: Regex = methodName
    val clazzFound = getClassByName(className)
    val clazz = if(clazzFound.isPhantom){None} else {Some(clazzFound)}
    val method: Option[SootMethod] = clazz.flatMap(a => try{
      //      Some(a.getMethod(methodName))
      var methodsFound : List[SootMethod] = Nil
      a.getMethods.forEach{ m =>
        if (methodRegex.matches(m.getSubSignature))
          methodsFound = m::methodsFound
      }
      assert(methodsFound.size > 0, s"findMethodLoc found no methods matching regex ${methodName}")
      assert(methodsFound.size <2, s"findMethodLoc found multiple methods matching " +
        s"regex ${methodName} \n   methods ${methodsFound.mkString(",")}")
      Some(methodsFound.head)
    }catch{
      case t:RuntimeException if t.getMessage.startsWith("No method") =>
        None
      case t:Throwable => throw t
    })
    method.map(a=> JimpleMethodLoc(a))
  }
  override def getAllMethods: Iterable[MethodLoc] = {
    Scene.v().getApplicationClasses.asScala.flatMap(clazz => {
      clazz.getMethods.asScala
        .filter(!_.hasTag("SimulatedCodeElementTag")) // Remove simulated code elements from Flowdroid
        .map(JimpleMethodLoc)
    })
  }


  override def makeCmd(cmd: soot.Unit, method: SootMethod,
                       locOpt:Option[AppLoc] = None): CmdWrapper = {
    val loc:AppLoc = locOpt.getOrElse(???)
    cmd match{
      case cmd: AbstractDefinitionStmt if cmd.rightBox.isInstanceOf[JCaughtExceptionRef] =>
        val leftBox = makeVal(cmd.leftBox.getValue).asInstanceOf[LVal]
        var exceptionName:String = ""
        method.getActiveBody.getTraps.forEach{trap =>
          if(trap.getHandlerUnit == cmd) exceptionName = JimpleFlowdroidWrapper.stringNameOfClass(trap.getException)
        }
        val rightBox = CaughtException(exceptionName)
        AssignCmd(leftBox, rightBox, loc)
      case cmd: AbstractDefinitionStmt => {
        val leftBox = makeVal(cmd.leftBox.getValue).asInstanceOf[LVal]
        val rightBox = makeVal(cmd.rightBox.getValue)
        AssignCmd(leftBox, rightBox,loc)
      }
      case cmd: JReturnStmt => {
        val box = makeVal(cmd.getOpBox.getValue)
        ReturnCmd(Some(box), loc)
      }
      case cmd:JInvokeStmt => {
        val invokeval = makeVal(cmd.getInvokeExpr).asInstanceOf[Invoke]
        InvokeCmd(invokeval, loc)
      }
      case _ : JReturnVoidStmt => {
        ReturnCmd(None, loc)
      }
      case cmd: JIfStmt =>
        If(makeVal(cmd.getCondition),loc)
      case _ : JNopStmt =>
        NopCmd(loc)
      case _: JThrowStmt =>
        // TODO: exception being thrown
        ThrowCmd(loc)
      case _:JGotoStmt => NopCmd(loc) // control flow handled elsewhere
      case _:JExitMonitorStmt => NopCmd(loc) // ignore concurrency
      case _:JEnterMonitorStmt => NopCmd(loc) // ignore concurrency
      case sw:JLookupSwitchStmt =>
        val key = makeRVal(sw.getKey).asInstanceOf[LocalWrapper]
        val targets = sw.getTargets.asScala.map(u => makeCmd(u,method, locOpt))
        SwitchCmd(key,targets.toList,loc)
      case v =>
        throw CmdNotImplemented(s"Unimplemented command: ${v}")
    }
  }

  override def degreeOut(cmd : AppLoc) = {
    val ll = cmd.line.asInstanceOf[JimpleLineLoc]
    val unitGraph = getUnitGraph(ll.method.retrieveActiveBody())
    unitGraph.getSuccsOf(ll.cmd).size()
  }
  override def commandPredecessors(cmdWrapper : CmdWrapper):List[AppLoc] =
    cmdWrapper.getLoc match{
    case AppLoc(methodWrapper @ JimpleMethodLoc(_),JimpleLineLoc(cmd,sootMethod), true) => {
      val unitGraph = getUnitGraph(sootMethod.retrieveActiveBody())
      val predCommands = unitGraph.getPredsOf(cmd).asScala
      predCommands.map(cmd => AppLoc(methodWrapper,JimpleLineLoc(cmd,sootMethod), false)).toList
    }
    case v =>
        throw new IllegalStateException(s"Bad argument for command predecessor ${v}")
  }
  override def commandNext(cmdWrapper: CmdWrapper):List[AppLoc] =
    cmdWrapper.getLoc match{
      case AppLoc(method, line, _) => List(AppLoc(method,line,true))
      case _ =>
        throw new IllegalStateException("command after pre location doesn't exist")
    }

  override def cmdAtLocation(loc: AppLoc):CmdWrapper = loc match {
    case AppLoc(_, JimpleLineLoc(cmd,method),_) => makeCmd(cmd,method,Some(loc))
    case loc => throw new IllegalStateException(s"No command associated with location: ${loc}")
  }

  protected def makeRVal(box:Value):RVal = box match{
    case a: AbstractInstanceInvokeExpr =>{
      val target = makeVal(a.getBase) match{
        case jl@LocalWrapper(_,_)=>jl
        case _ => ???
      }
      val targetClass = a.getMethodRef.getDeclaringClass.getName
      val targetMethod = a.getMethodRef.getSignature
      val params: List[RVal] = (0 until a.getArgCount()).map(argPos =>
        makeVal(a.getArg(argPos))
      ).toList
      a match{
        case _:JVirtualInvokeExpr => VirtualInvoke(target, targetClass, targetMethod, params)
        case _:JSpecialInvokeExpr => SpecialInvoke(target,targetClass, targetMethod, params)
        case _:JInterfaceInvokeExpr => VirtualInvoke(target, targetClass, targetMethod, params)
        case v =>
          //println(v)
          ???
      }
    }
    case a : AbstractStaticInvokeExpr => {
      val params: List[RVal] = (0 until a.getArgCount()).map(argPos =>
        makeVal(a.getArg(argPos))
      ).toList
      val targetClass = a.getMethodRef.getDeclaringClass.getName
      val targetMethod = a.getMethodRef.getSignature
      StaticInvoke(targetClass, targetMethod, params)
    }
    case n : AbstractNewExpr => {
      val className = n.getType.toString
      NewCommand(className)
    }
    case t:ThisRef => ThisWrapper(t.getType.toString)
    case _:NullConstant => NullConst
    case v:IntConstant => IntConst(v.value)
    case v:LongConstant => IntConst(v.value.toInt)
    case v:StringConstant => StringConst(v.value)
    case p:ParameterRef =>
      val name = s"@parameter${p.getIndex}"
      val tname = p.getType.toString
      LocalWrapper(name, tname)
    case ne: JNeExpr => Binop(makeRVal(ne.getOp1),Ne, makeRVal(ne.getOp2))
    case eq: JEqExpr => Binop(makeRVal(eq.getOp1),Eq, makeRVal(eq.getOp2))
    case local: JimpleLocal =>
      LocalWrapper(local.getName, JimpleFlowdroidWrapper.stringNameOfType(local.getType))
    case cast: JCastExpr =>
      val castType = JimpleFlowdroidWrapper.stringNameOfType(cast.getCastType)
      val v = makeRVal(cast.getOp).asInstanceOf[LocalWrapper]
      Cast(castType, v)
    case mult: JMulExpr =>
      val op1 = makeRVal(mult.getOp1)
      val op2 = makeRVal(mult.getOp2)
      Binop(op1, Mult, op2)
    case div : JDivExpr =>
      val op1 = makeRVal(div.getOp1)
      val op2 = makeRVal(div.getOp2)
      Binop(op1, Div, op2)
    case div : JAddExpr =>
      val op1 = makeRVal(div.getOp1)
      val op2 = makeRVal(div.getOp2)
      Binop(op1, Add, op2)
    case div : JSubExpr =>
      val op1 = makeRVal(div.getOp1)
      val op2 = makeRVal(div.getOp2)
      Binop(op1, Sub, op2)
    case lt : JLeExpr =>
      val op1 = makeRVal(lt.getOp1)
      val op2 = makeRVal(lt.getOp2)
      Binop(op1, Le, op2)
    case lt : JLtExpr =>
      val op1 = makeRVal(lt.getOp1)
      val op2 = makeRVal(lt.getOp2)
      Binop(op1, Lt, op2)
    case gt: JGtExpr =>
      val op1 = makeRVal(gt.getOp1)
      val op2 = makeRVal(gt.getOp2)
      Binop(op2, Lt, op1)
    case ge: JGeExpr =>
      val op1 = makeRVal(ge.getOp1)
      val op2 = makeRVal(ge.getOp2)
      Binop(op1, Ge, op2)
    case staticRef : StaticFieldRef =>
      val declaringClass = JimpleFlowdroidWrapper.stringNameOfClass(staticRef.getFieldRef.declaringClass())
      val fieldName = staticRef.getFieldRef.name()
      val containedType = JimpleFlowdroidWrapper.stringNameOfType(staticRef.getFieldRef.`type`())
      StaticFieldReference(declaringClass, fieldName, containedType)

    case const: RealConstant=>
      ConstVal(const) // Not doing anything special with real values for now
    case caught: JCaughtExceptionRef =>
      CaughtException("")
    case jcomp: JCmpExpr =>
      val op1 = makeRVal(jcomp.getOp1)
      val op2 = makeRVal(jcomp.getOp2)
      Binop(op1,Eq, op2)
    case i : JInstanceOfExpr =>
      val targetClassType = JimpleFlowdroidWrapper.stringNameOfType(i.getCheckType)
      val target = makeRVal(i.getOp).asInstanceOf[LocalWrapper]
      InstanceOf(targetClassType, target)
    case a : ArrayRef =>
      val baseVar = makeRVal(a.getBase)
      val index = makeRVal(a.getIndex)
      ArrayReference(baseVar, makeRVal(a.getIndex))
    case a : NewArrayExpr =>
      NewCommand(JimpleFlowdroidWrapper.stringNameOfType(a.getType))
    case a : ClassConstant =>
      ClassConst(JimpleFlowdroidWrapper.stringNameOfType(a.getType))
    case v =>
      //println(v)
      throw CmdNotImplemented(s"Command not implemented: $v  type: ${v.getType}")
  }

  protected def makeVal(box: Value):RVal = box match{
    case a : JimpleLocal=>
      LocalWrapper(a.getName,a.getType.toString)
    case f: AbstractInstanceFieldRef => {
      val fieldType = f.getType.toString
      val base = makeVal(f.getBase).asInstanceOf[LocalWrapper]
      val fieldname = f.getField.getName
      val fieldDeclType = f.getField.getDeclaringClass.toString
      FieldReference(base,fieldType, fieldDeclType, fieldname)
    }
    case a => makeRVal(a)
  }

  override def isMethodEntry(cmdWrapper: CmdWrapper): Boolean = cmdWrapper.getLoc match {
    case AppLoc(_, JimpleLineLoc(cmd,method),true) => {
      val unitBoxes = method.retrieveActiveBody().getUnits
      if(unitBoxes.size > 0){
        cmd.equals(unitBoxes.getFirst)
      }else {false}
    }
    case AppLoc(_, _,false) => false // exit of command is not entry let transfer one more time
    case _ => ???
  }

  override def findLineInMethod(className: String, methodName: String, line: Int): Iterable[AppLoc] = {
    val loc: Option[JimpleMethodLoc] = findMethodLoc(className, methodName)
    loc.map(loc => {
      val activeBody = loc.method.retrieveActiveBody()
      val units: Iterable[soot.Unit] = activeBody.getUnits.asScala
      units.filter(a => a.getJavaSourceStartLineNumber == line).map((a:soot.Unit) => AppLoc(loc, JimpleLineLoc(a,loc.method),true))
    }).getOrElse(List())
  }

  def makeMethodTargets(source: MethodLoc): Set[MethodLoc] =
    cg.edgesOutOfMethod(source.asInstanceOf[JimpleMethodLoc].method).map(JimpleMethodLoc(_))

  override def makeInvokeTargets(appLoc: AppLoc): UnresolvedMethodTarget = {
    val line = appLoc.line.asInstanceOf[JimpleLineLoc]
    val edgesOut = cg.edgesOutOf(line.cmd)

    val mref = appLoc.line match {
      case JimpleLineLoc(cmd: JInvokeStmt, _) => cmd.getInvokeExpr.getMethodRef
      case JimpleLineLoc(cmd: JAssignStmt, _) if cmd.getRightOp.isInstanceOf[JVirtualInvokeExpr] =>
        cmd.getRightOp.asInstanceOf[JVirtualInvokeExpr].getMethodRef
      case JimpleLineLoc(cmd: JAssignStmt, _) if cmd.getRightOp.isInstanceOf[JInterfaceInvokeExpr] =>
        cmd.getRightOp.asInstanceOf[JInterfaceInvokeExpr].getMethodRef
      case JimpleLineLoc(cmd: JAssignStmt, _) if cmd.getRightOp.isInstanceOf[JSpecialInvokeExpr] =>
        cmd.getRightOp.asInstanceOf[JSpecialInvokeExpr].getMethodRef
      case JimpleLineLoc(cmd: JAssignStmt, _) if cmd.getRightOp.isInstanceOf[JStaticInvokeExpr] =>
        cmd.getRightOp.asInstanceOf[JStaticInvokeExpr].getMethodRef
      case t =>
        throw new IllegalArgumentException(s"Bad Location Type $t")
    }
    val declClass = mref.getDeclaringClass
    val clazzName = declClass.getName
    val name = mref.getSubSignature

    UnresolvedMethodTarget(clazzName, name.toString,edgesOut.map(f => JimpleMethodLoc(f)))
  }

  def canAlias(type1: String, type2: String): Boolean = {
    val oneIsPrimitive = List(type1,type2).exists{
      case PersistantConstraints.Primitive() => true
      case _ => false
    }
    if(oneIsPrimitive)
      return false
    if(type1 == type2) true else {
      val hierarchy: Hierarchy = Scene.v().getActiveHierarchy
      assert(Scene.v().containsClass(type1), s"Type: $type1 not in soot scene")
      assert(Scene.v().containsClass(type2), s"Type: $type2 not in soot scene")
      val type1Soot = Scene.v().getSootClass(type1)
      val type2Soot = Scene.v().getSootClass(type2)
      val sub1 = JimpleFlowdroidWrapper.subThingsOf(type1Soot)
      val sub2 = JimpleFlowdroidWrapper.subThingsOf(type2Soot)
      sub1.exists(a => sub2.contains(a))
    }
  }

  override def getOverrideChain(method: MethodLoc): Seq[MethodLoc] = {
    val m = method.asInstanceOf[JimpleMethodLoc]
    val methodDeclClass = m.method.getDeclaringClass
    val methodSignature = m.method.getSubSignature
    val superclasses: util.List[SootClass] = Scene.v().getActiveHierarchy.getSuperclassesOf(methodDeclClass)
    val interfaces: Chain[SootClass] = methodDeclClass.getInterfaces
    val methods = (superclasses.iterator.asScala ++ interfaces.iterator.asScala)
      .filter(sootClass => sootClass.declaresMethod(methodSignature))
      .map( sootClass=> JimpleMethodLoc(sootClass.getMethod(methodSignature)))
    methods.toList
  }

  //TODO: check that this function covers all cases
  private val callSiteCache = mutable.HashMap[MethodLoc, Seq[AppLoc]]()
  override def appCallSites(method_in: MethodLoc, resolver:AppCodeResolver): Seq[AppLoc] = {
    val method = method_in.asInstanceOf[JimpleMethodLoc]
    callSiteCache.getOrElse(method, {
      val m = method.asInstanceOf[JimpleMethodLoc]
      val sootMethod = m.method
      val incomingEdges = cg.edgesInto(sootMethod)
      val appLocations: Seq[AppLoc] = incomingEdges.flatMap{
        case (method,unit) =>
          val className = JimpleFlowdroidWrapper.stringNameOfClass(method.getDeclaringClass)
          if (!resolver.isFrameworkClass(className)){
            Some(cmdToLoc(unit, method))
          }else None
      }.toSeq
      callSiteCache.put(method_in, appLocations)
      appLocations
    })
  }


  override def makeMethodRetuns(method: MethodLoc): List[AppLoc] = {
    val smethod = method.asInstanceOf[JimpleMethodLoc]
    val rets = mutable.ListBuffer[AppLoc]()
    try{
      smethod.method.getActiveBody()
    }catch{
      case t: Throwable =>
        //println(t)
    }
    if (smethod.method.hasActiveBody) {
      smethod.method.getActiveBody.getUnits.forEach((u: soot.Unit) => {
        if (u.isInstanceOf[JReturnStmt] || u.isInstanceOf[JReturnVoidStmt]) {
          val lineloc = JimpleLineLoc(u, smethod.method)
          rets.addOne(AppLoc(smethod, lineloc, false))
        }
      })
      rets.toList
    }else{
      // TODO: for some reason no active bodies for R or BuildConfig generated classes?
      // note: these typically don't have any meaningful implementation anyway
      val classString = smethod.method.getDeclaringClass.toString
      if (! (classString.contains(".R$") || classString.contains("BuildConfig") || classString.endsWith(".R"))){
        //TODO: figure out why some app methods don't have active bodies
//        println(s"Method $method has no active body, consider adding it to FrameworkExtensions.txt")
      }
      List()
    }
  }

  override def getClassHierarchy: Map[String, Set[String]] = {
    val hierarchy: Hierarchy = Scene.v().getActiveHierarchy
    Scene.v().getClasses().asScala.foldLeft(Map[String, Set[String]]()){ (acc,v) =>
      val cname = JimpleFlowdroidWrapper.stringNameOfClass(v)
      val subclasses = if(v.isInterface()) {
        hierarchy.getImplementersOf(v)
      }else {
        try {
          hierarchy.getSubclassesOf(v)
        }catch {
          case _: NullPointerException =>
            assert(v.toString.contains("$$Lambda"))
            List[SootClass]().asJava // Soot bug with lambdas
        }
      }
      val strSubClasses = subclasses.asScala.map(c => JimpleFlowdroidWrapper.stringNameOfClass(c)).toSet + cname
      acc  + (cname -> strSubClasses)
    }
  }

  /**
   * NOTE: DO NOT USE Scene.v.getActiveHierarchy.{isSuperClassOf...,isSubClassOf...}
   *      Above methods always return true if a parent is a phantom class
   * Check if one class is a subtype of another
   * Also returns true if they are equal
   * @param type1 possible supertype
   * @param type2 possible subtype
   * @return if type2 is subtype or equal to type2
   */
  override def isSuperClass(type1: String, type2: String): Boolean = {
    val type1Soot = Scene.v().getSootClass(type1)
    val type2Soot = Scene.v().getSootClass(type2)
    val subclasses = Scene.v.getActiveHierarchy.getSubclassesOfIncluding(type1Soot)
    val res = subclasses.contains(type2Soot)
    res
  }
}

case class JimpleMethodLoc(method: SootMethod) extends MethodLoc {
  def string(clazz: SootClass):String = JimpleFlowdroidWrapper.stringNameOfClass(clazz)
  def string(t:Type) :String = JimpleFlowdroidWrapper.stringNameOfType(t)
  override def simpleName: String = {
//    val n = method.getName
    method.getSubSignature
  }

  override def classType: String = string(method.getDeclaringClass)

  // return type, receiver type, arg1, arg2 ...
  override def argTypes: List[String] = string(method.getReturnType)::
    classType::
    method.getParameterTypes.asScala.map(string).toList

  /**
   * None for reciever if static
   * @return list of args, [reciever, arg1,arg2 ...]
   */
  override def getArgs: List[Option[LocalWrapper]] = {
    val clazz = string(method.getDeclaringClass)
    val params =
      (0 until method.getParameterCount).map(ind =>
        Some(LocalWrapper("@parameter" + s"$ind", string(method.getParameterType(ind)))))
    val out = (if (method.isStatic) None else Some(LocalWrapper("@this",clazz)) ):: params.toList
    //TODO: this method is probably totally wrong, figure out arg names and how to convert type to string
    out
  }

  override def isStatic: Boolean = method.isStatic

  override def isInterface: Boolean = method.getDeclaringClass.isInterface
}
case class JimpleLineLoc(cmd: soot.Unit, method: SootMethod) extends LineLoc{
  override def toString: String = "line: " + cmd.getJavaSourceStartLineNumber() + " " + cmd.toString()
  def returnTypeIfReturn :Option[String] = cmd match{
    case cmd :JReturnVoidStmt => Some("void")
    case _ =>
      ???
  }
}


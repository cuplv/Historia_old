package edu.colorado.plv.bounder.ir

import edu.colorado.plv.bounder.BounderSetupApplication
import edu.colorado.plv.bounder.solver.PersistantConstraints
import edu.colorado.plv.bounder.symbolicexecutor.AppCodeResolver
import edu.colorado.plv.fixedsoot.EnhancedUnitGraphFixed
import soot.jimple.infoflow.entryPointCreators.SimulatedCodeElementTag
import soot.jimple.{DynamicInvokeExpr, InstanceInvokeExpr, IntConstant, InvokeExpr, NullConstant, ParameterRef, StringConstant, ThisRef}
import soot.jimple.internal.{AbstractDefinitionStmt, AbstractInstanceFieldRef, AbstractInstanceInvokeExpr, AbstractInvokeExpr, AbstractNewExpr, AbstractStaticInvokeExpr, InvokeExprBox, JAssignStmt, JCastExpr, JEqExpr, JIdentityStmt, JIfStmt, JInstanceFieldRef, JInvokeStmt, JNeExpr, JNewExpr, JReturnStmt, JReturnVoidStmt, JSpecialInvokeExpr, JVirtualInvokeExpr, JimpleLocal, VariableBox}
import soot.{Body, BooleanType, ByteType, CharType, DoubleType, FloatType, Hierarchy, IntType, LongType, RefType, Scene, ShortType, SootClass, SootMethod, Type, Value, VoidType}

import scala.collection.mutable
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
      val className = t.getClassName
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
    case t =>
      println(t)
      ???
  }
}

class JimpleFlowdroidWrapper(apkPath : String) extends IRWrapper[SootMethod, soot.Unit] {

  BounderSetupApplication.loadApk(apkPath)

  private var unitGraphCache : scala.collection.mutable.Map[Body, EnhancedUnitGraphFixed] = scala.collection.mutable.Map()
  private var appMethodCache : scala.collection.mutable.Set[SootMethod] = scala.collection.mutable.Set()

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
  protected def getAppMethods(resolver: AppCodeResolver):mutable.Set[SootMethod] = {
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
    appMethodCache
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
      case cmd: AbstractDefinitionStmt => {
        val leftBox = makeVal(cmd.leftBox.getValue).asInstanceOf[LVal]
        val rightBox = makeVal(cmd.rightBox.getValue)
        AssignCmd(leftBox, rightBox,loc)
      }
      case cmd: JReturnStmt => {
        val box = makeVal(cmd.getOpBox.getValue).asInstanceOf[LocalWrapper]
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

      case v =>
        println(s"unimplemented command: ${v}")
        ???

    }
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
        case _ => ???
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
    case v:StringConstant => StringConst(v.value)
    case p:ParameterRef =>
      val name = s"@parameter${p.getIndex}"
      val tname = p.getType.toString
      LocalWrapper(name, tname)
    case ne: JNeExpr => Ne(makeRVal(ne.getOp1), makeRVal(ne.getOp2))
    case eq: JEqExpr => Eq(makeRVal(eq.getOp1), makeRVal(eq.getOp2))
    case local: JimpleLocal =>
      LocalWrapper(local.getName, JimpleFlowdroidWrapper.stringNameOfType(local.getType))
    case cast: JCastExpr =>
      val castType = JimpleFlowdroidWrapper.stringNameOfType(cast.getCastType)
      val v = makeRVal(cast.getOp).asInstanceOf[LocalWrapper]
      Cast(castType, v)
    case v =>
      println(v)
      ???
  }

  protected def makeVal(box: Value):RVal = box match{
    case a : JimpleLocal=>
      LocalWrapper(a.getName,a.getType.toString)
    case f: AbstractInstanceFieldRef => {
      val fieldType = f.getType.toString
      val base = makeVal(f.getBase).asInstanceOf[LocalWrapper]
      val fieldname = f.getField.getName
      val fieldDeclType = f.getField.getDeclaringClass.toString
      FieldRef(base,fieldType, fieldDeclType, fieldname)
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

  override def makeInvokeTargets(appLoc: AppLoc, upperTypeBound: Option[String]): UnresolvedMethodTarget = {
    val mref = appLoc.line match {
      case JimpleLineLoc(cmd :JInvokeStmt, _) => cmd.getInvokeExpr.getMethodRef
      case JimpleLineLoc(cmd :JAssignStmt, _) if cmd.getRightOp.isInstanceOf[JVirtualInvokeExpr] =>
        cmd.getRightOp.asInstanceOf[JVirtualInvokeExpr].getMethodRef
      case _ =>
        throw new IllegalArgumentException("Bad Location Type")
    }
//    val mref = cmd.getMethodRef
    val declClass = mref.getDeclaringClass
    val clazzName = declClass.getName
    val name = mref.getSubSignature

    val hierarchy: Hierarchy = Scene.v().getActiveHierarchy
    val out = mutable.Set[JimpleMethodLoc]()
    val subClasses = hierarchy.getSuperclassesOfIncluding(declClass)
    val boundClass = upperTypeBound.map(getClassByName).getOrElse(getClassByName("java.lang.Object"))
    var found:Boolean = false
    subClasses.forEach{ c =>
      if(!found && c.declaresMethod(name)){
        out.add( JimpleMethodLoc(c.getMethod(name)))
        if(hierarchy.isClassSuperclassOf(c,boundClass)){
          found = true
        }
      }
    }
    UnresolvedMethodTarget(clazzName, name.toString,out.toSet)
  }

  def canAlias(type1: String, type2: String): Boolean = {
    if(type1 == type2) true else {
      val hierarchy: Hierarchy = Scene.v().getActiveHierarchy
      val type1Soot = Scene.v().getSootClass(type1)
      val type2Soot = Scene.v().getSootClass(type2)
      val sub1 = hierarchy.getSubclassesOfIncluding(type1Soot).asScala
      val sub2 = hierarchy.getSubclassesOfIncluding(type2Soot).asScala.toSet
      sub1.exists(a => sub2.contains(a))
    }
  }

  override def getOverrideChain(method: MethodLoc): Seq[MethodLoc] = {
    val m = method.asInstanceOf[JimpleMethodLoc]
    val methodDeclClass = m.method.getDeclaringClass
    val methodSignature = m.method.getSubSignature
    val superclasses = Scene.v().getActiveHierarchy.getSuperclassesOf(methodDeclClass)
    val methods = superclasses.asScala.filter(sootClass => sootClass.declaresMethod(methodSignature))
      .map( sootClass=> JimpleMethodLoc(sootClass.getMethod(methodSignature)))
    methods.toList
  }

  private def canCall(invokeExpr:InvokeExpr, method:JimpleMethodLoc):Boolean = {
    val targetName = method.method.getName
    val callName = invokeExpr.getMethod.getName
    if (targetName != callName ) {
      return false
    }
    val paramCount = method.method.getParameterCount
    if (invokeExpr.getArgCount != paramCount){
      return false
    }
    // Check if types are remotely compatible, abstract domain figures things out more precisely later
    val callRefType = invokeExpr.getMethodRef.getDeclaringClass
    val targetType = method.method.getDeclaringClass
    val canCall = Scene.v().getActiveHierarchy.isClassSubclassOfIncluding(callRefType, targetType)
    canCall
  }

  //TODO: check that this function covers all cases
  private val callSiteCache = mutable.HashMap[MethodLoc, Seq[AppLoc]]()
  override def appCallSites(method_in: MethodLoc, resolver:AppCodeResolver): Seq[AppLoc] = {
    val method = method_in.asInstanceOf[JimpleMethodLoc]
    callSiteCache.getOrElse(method, {
      val m = method.asInstanceOf[JimpleMethodLoc]
      val sootMethod = m.method
      val appMethods = getAppMethods(resolver)
      val callSites:Seq[AppLoc] = appMethods.flatMap(mContainingCall => {
        var locs = mutable.Set[AppLoc]()
        if (mContainingCall.hasActiveBody) {
          mContainingCall.getActiveBody.getUnits.forEach {
            case unit : JInvokeStmt =>
              if (canCall(unit.getInvokeExpr, method)) {
                val loc = cmdToLoc(unit, mContainingCall)
                locs.add(loc)
              }

            case unit : JAssignStmt => // if unit.getRightOpBox.getValue.isInstanceOf[InvokeExprBox] =>
              unit.getRightOpBox.getValue match {
                case v : InvokeExpr =>
                  if(canCall(v, method)) {
                    val loc = cmdToLoc(unit, mContainingCall)
                    locs.add(loc)
                  }
                case _ =>
              }
            case r: JReturnStmt =>
              assert(!r.getOpBox.isInstanceOf[InvokeExpr]) //TODO: don't think this can happen
            case unit =>
          }
        }
        locs
      }).toSeq
      callSiteCache.put(method, callSites)
      callSites
    })
  }

  override def makeMethodRetuns(method: MethodLoc): List[AppLoc] = {
    val smethod = method.asInstanceOf[JimpleMethodLoc]
    val rets = mutable.ListBuffer[AppLoc]()
    try{
      smethod.method.getActiveBody()
    }catch{
      case t: Throwable =>
        println(t)
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
        assert(false)
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
//  private def receiverOfInvoke(i: InvokeExpr) = {
//    case _:StaticInvoke => None
//    case i:InstanceInvokeExpr => Some(i.getBase)
//    case i:DynamicInvokeExpr =>
//      println(i)
//      ???
//    case i =>
//      println(i)
//      ???
//  }
  override def getDirectMethodCalls(method: MethodLoc): Set[MethodLoc] = {
    val methodLoc = method.asInstanceOf[JimpleMethodLoc]
    val units = methodLoc.method.getActiveBody.getUnits
    var out:List[MethodLoc] = List()
    units.forEach{ (u:soot.Unit) =>
      val loc = AppLoc(method,JimpleLineLoc(u,methodLoc.method), false)
      val upper = u match{
        case i : JInvokeStmt =>
//          receiverOfInvoke(i.getInvokeExpr)
          println(i)
          println(i)
        case i : JAssignStmt if i.getRightOp.isInstanceOf[InvokeExpr] =>
          println(i)
          println(i)
        case i =>
          println(i)
          println(i)
      }
//      val targets = makeInvokeTargets(loc, upper)

    }
    ???
    out.toSet
  }

  override def getDirectHeapOps(method: MethodLoc): Set[CmdWrapper] = ???
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
}
case class JimpleLineLoc(cmd: soot.Unit, method: SootMethod) extends LineLoc{
  override def toString: String = "line: " + cmd.getJavaSourceStartLineNumber() + " " + cmd.toString()
  def returnTypeIfReturn :Option[String] = cmd match{
    case cmd :JReturnVoidStmt => Some("void")
    case _ =>
      ???
  }
}


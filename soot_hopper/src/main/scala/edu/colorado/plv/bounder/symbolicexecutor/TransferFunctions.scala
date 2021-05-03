package edu.colorado.plv.bounder.symbolicexecutor

import better.files.Resource
import edu.colorado.plv.bounder.BounderUtil
import edu.colorado.plv.bounder.ir.{NullConst, _}
import edu.colorado.plv.bounder.lifestate.LifeState._
import edu.colorado.plv.bounder.lifestate.{LifeState, SpecSpace}
import edu.colorado.plv.bounder.solver.ClassHierarchyConstraints
import edu.colorado.plv.bounder.symbolicexecutor.FrameworkExtensions.urlPos
import edu.colorado.plv.bounder.symbolicexecutor.TransferFunctions.{inVarsForCall, nonNullCallins, relevantAliases, relevantAliases2}
import edu.colorado.plv.bounder.symbolicexecutor.state._
import upickle.default._
import edu.colorado.plv.bounder.symbolicexecutor.state.PrettyPrinting

object TransferFunctions{
  private val nonNullDefUrl = List(
    "NonNullReturnCallins.txt",
    "NonNullReturnCallins",
    "/resources/NonNullReturnCallins.txt",
    "resources/NonNullReturnCallins.txt",
  )
  lazy val nonNullCallins: Seq[I] =
    nonNullDefUrl.flatMap{ (path:String) =>
      //    val source = Source.fromFile(frameworkExtPath)
      try {
        val source = Resource.getAsString(path)
        Some(LifeState.parseIFile(source))
      }catch{
        case t:IllegalArgumentException => throw t //exception thrown when parsing fails
        case _:Throwable => None // deal with instability of java jar file resource resolution
      }
    }.head

  /**
   * Get set of things that if aliased, change the trace abstraction state
   * TODO: this is over approx
   * @param pre state before cmd that emits an observed message
   * @param dir callback/callin entry/exit
   * @param signature class and name of method
   * @return
   */
  def relevantAliases(pre: State,
                      dir: MessageType,
                      signature: (String, String))(implicit
                                                   ch:ClassHierarchyConstraints) :Set[List[LSParamConstraint]]  = {
    val relevantI: Set[(I, List[LSParamConstraint])] = pre.findIFromCurrent(dir, signature)
    relevantI.map{
      case (I(_, _, vars),p)=> p
    }
  }
  //TODO: replace relevantAliases with this
  // transfer should simply define any variables that aren't seen in the state but read
  // alias considerations are done later by the trace abstraction or by separation logic
  def relevantAliases2(pre:State,
                       dir:MessageType,
                       signature: (String,String),
                       lst : List[Option[RVal]])(implicit ch:ClassHierarchyConstraints):List[Option[RVal]] = {
    val relevantI = pre.findIFromCurrent(dir,signature)
    lst.zipWithIndex.map{ case (rval,ind) =>
      val existsNAtInd = relevantI.exists{i =>
        val vars: Seq[String] = i._1.lsVars
        val out = (ind < vars.length) && !LSAnyVal.matches(vars(ind))
        out
      }
      if(existsNAtInd) rval else None
    }
  }
  private def inVarsForCall(i:Invoke):List[Option[RVal]] = i match{
    case i@VirtualInvoke(tgt, _, _, _) =>
      Some(tgt) :: i.params.map(Some(_))
    case i@SpecialInvoke(tgt, _, _, _) =>
      Some(tgt) :: i.params.map(Some(_))
    case i@StaticInvoke(_, _, _) =>
      None :: i.params.map(Some(_))
  }
  def inVarsForCall[M,C](source: AppLoc, w:IRWrapper[M,C]):List[Option[RVal]] = {
    w.cmdAtLocation(source) match {
      case AssignCmd(local : LocalWrapper, i:Invoke, _) =>
        Some(local) :: inVarsForCall(i)
      case InvokeCmd(i: Invoke, _) =>
        None :: inVarsForCall(i)
      case v =>
        //Note: jimple should restrict so that assign only occurs to locals from invoke
        throw new IllegalStateException(s"$v is not a call to a method")
    }
  }
}

class TransferFunctions[M,C](w:IRWrapper[M,C], specSpace: SpecSpace,
                             classHierarchyConstraints: ClassHierarchyConstraints) {
  private val resolver = new DefaultAppCodeResolver(w)
  private implicit val ch = classHierarchyConstraints
  private implicit val irWrapper = w
  def defineVarsAs(state: State, comb: List[(Option[RVal], Option[PureExpr])]):State =
    comb.foldLeft(state){
      case (stateNext, (None,_)) => stateNext
      case (stateNext, (_,None)) => stateNext
      case (stateNext, (Some(rval), Some(pexp))) => stateNext.defineAs(rval, pexp)
    }

  /**
   *
   * @param postState state after current location in control flow
   * @param target predecessor of current location
   * @param source current location
   * @return set of states that may reach the target state by stepping from source to target
   */
  def transfer(postState: State, target: Loc, source: Loc): Set[State] = (source, target) match {
    case (source@AppLoc(m, _, false), cmret@CallinMethodReturn(_, _)) =>
      // traverse back over the retun of a callin
//      val (pkg, name) = msgCmdToMsg(cmret)
//      val inVars: List[Option[RVal]] = inVarsForCall(source,w)
//      val relAliases = relevantAliases2(postState, CIExit, (pkg,name),inVars)
//      val frame = CallStackFrame(target, Some(source.copy(isPre = true)), Map())
//      val (rvals, state0) = getOrDefineRVals(m,relAliases, postState)
//      val state1 = traceAllPredTransfer(CIExit, (pkg,name),rvals, state0)
//      val outState = newSpecInstanceTransfer(source.method, CIExit, (pkg, name), inVars, cmret, state1)
//      val outState1: Set[State] = inVars match{
//        case Some(revar:LocalWrapper)::_ => outState.map(s3 => s3.clearLVal(revar))
//        case _ => outState
//      }
//      val outState2 = outState1.map(s2 => s2.copy(callStack = frame::s2.callStack, nextCmd = List(target),
//        alternateCmd = Nil))
//
//      val out = outState2.map{ oState =>
//        //clear assigned var from stack if exists
//        val statesWithClearedReturn = inVars.head match{
//          case Some(v:LocalWrapper) => oState.clearLVal(v)
//          case None => oState
//          case v => throw new IllegalStateException(s"Malformed IR. Callin result assigned to non-local: $v")
//        }
//        statesWithClearedReturn
//      }
//      out
      // TODO: remove CallinMethodReturn and replace with grouped
      val g = GroupedCallinMethodReturn(Set(cmret.fmwClazz), cmret.fmwName)
      transfer(postState,g,source)
    case (source@AppLoc(m, _, false), cmret@GroupedCallinMethodReturn(_, _)) =>
      // traverse back over the retun of a callin

      // if post state has materialized value for receiver, assume non-null
      val cmd = w.cmdAtLocation(source)
      val inv = cmd match{
        case InvokeCmd(inv : Invoke, _) => inv
        case AssignCmd(_, inv: Invoke, _) => inv
        case c => throw new IllegalStateException(s"Malformed invoke command $c")
      }
      val receiverOption: Option[LocalWrapper] = inv match{
        case v:VirtualInvoke => Some(v.target)
        case s:SpecialInvoke => Some(s.target)
        case _:StaticInvoke => None
      }
      val postState2 = if(receiverOption.exists{postState.containsLocal}) {
        val localV = postState.get(receiverOption.get).get
//        postState.copy(pureFormula = postState.pureFormula + PureConstraint(localV, NotEquals, NullVal))
        postState.addPureConstraint(PureConstraint(localV, NotEquals, NullVal))
      } else postState

      val (pkg, name) = msgCmdToMsg(cmret)
      val inVars: List[Option[RVal]] = inVarsForCall(source,w)
      val relAliases = relevantAliases2(postState2, CIExit, (pkg,name),inVars)
      val frame = CallStackFrame(target, Some(source.copy(isPre = true)), Map())
      val (rvals, state0) = getOrDefineRVals(m,relAliases, postState2)
      val state1 = traceAllPredTransfer(CIExit, (pkg,name),rvals, state0)
      val outState = newSpecInstanceTransfer(source.method, CIExit, (pkg, name), inVars, cmret, state1)
      // if retVar is materialized and assigned, clear it from the state
      val outState1: Set[State] = inVars match{
        case Some(retVar:LocalWrapper)::_ =>
          val outState11 = if (nonNullCallins.exists(i => i.contains(CIExit, (pkg,name))))
            // if non-null return callins defines this method, assume that the materialized return value is non null
            outState.map{s =>
              if(s.containsLocal(retVar))
//                s.copy(pureFormula = s.pureFormula + PureConstraint(s.get(retVar).get, NotEquals, NullVal))
                s.addPureConstraint(PureConstraint(s.get(retVar).get, NotEquals, NullVal))
              else s
            }
          else outState
          outState11.map(s3 => s3.clearLVal(retVar))
        case _ => outState
      }
      val outState2 = outState1.map(s2 => s2.copy(sf = s2.sf.copy(callStack = frame::s2.callStack),
        nextCmd = List(target),
        alternateCmd = Nil))
      val out = outState2.map{ oState =>
        //clear assigned var from stack if exists
        val statesWithClearedReturn = inVars.head match{
          case Some(v:LocalWrapper) => oState.clearLVal(v)
          case None => oState
          case v => throw new IllegalStateException(s"Malformed IR. Callin result assigned to non-local: $v")
        }
        statesWithClearedReturn
      }
      out
    case (cminv@GroupedCallinMethodInvoke(targets, _), tgt@AppLoc(m,_,true)) =>
      assert(postState.callStack.nonEmpty, "Bad control flow, abstract stack must be non-empty.")
      val invars = inVarsForCall(tgt,w)
      val (pkg,name) = msgCmdToMsg(cminv)
      val relAliases = relevantAliases2(postState, CIEnter, (pkg,name),invars)
      val ostates:Set[State] = {
        val (rvals, state0) = getOrDefineRVals(m,relAliases, postState)
        val state1 = traceAllPredTransfer(CIEnter, (pkg, name), rvals, state0)
        Set(state1)
      }
      //Only add receiver if this or callin return is in abstract trace
      val traceNeedRec = List(CIEnter, CIExit).exists( dir => postState.findIFromCurrent(dir, (pkg,name)).nonEmpty)
      val cfNeedRec = postState.alternateCmd.exists(other => !postState.nextCmd.contains(other))
      //TODO: why does onDestroy exit have a bunch of alternate locations of pre-line: -1 r0:= @this:MyActivity$1/2...
      ostates.map{s =>
        // Pop stack and set command just processed
        val s2 = s.copy(sf = s.sf.copy(callStack = s.callStack.tail))
        // If dynamic invoke, restrict receiver type by the callin we just came from
        invars match{
          case _::Some(rec)::_ if traceNeedRec || cfNeedRec =>
            val (recV,stateWithRec) = s2.getOrDefine(rec, Some(tgt.method))
//            val pureFormulaConstrainingReceiver = stateWithRec.pureFormula +
//              PureConstraint(recV, NotEquals, NullVal)
            stateWithRec.addPureConstraint(PureConstraint(recV, NotEquals, NullVal))
              .constrainOneOfType(recV, targets, ch)
          case _ => s2
        }
      }.map(_.copy(nextCmd = List(target), alternateCmd = Nil))

    case (GroupedCallinMethodReturn(_,_), GroupedCallinMethodInvoke(_,_)) =>
      Set(postState).map(_.copy(nextCmd = List(target), alternateCmd = Nil))
    case (CallinMethodReturn(_, _), CallinMethodInvoke(_, _)) =>
      Set(postState).map(_.copy(nextCmd = List(target), alternateCmd = Nil))
    case (cminv@CallinMethodInvoke(invokeType, _), tgt@AppLoc(m, _, true)) =>
      assert(postState.callStack.nonEmpty, "Bad control flow, abstract stack must be non-empty.")
      val invars = inVarsForCall(tgt,w)
      val (pkg,name) = msgCmdToMsg(cminv)
      val relAliases = relevantAliases2(postState, CIEnter, (pkg,name),invars)
      val ostates:Set[State] = {
        val (rvals, state0) = getOrDefineRVals(m,relAliases, postState)
        val state1 = traceAllPredTransfer(CIEnter, (pkg, name), rvals, state0)
        Set(state1)
      }
      //Only add receiver if this or callin return is in abstract trace
      val traceNeedRec = List(CIEnter, CIExit).exists( dir => postState.findIFromCurrent(dir, (pkg,name)).nonEmpty)
      val cfNeedRec = postState.alternateCmd.exists(other => !postState.nextCmd.contains(other))

      ostates.map{s =>
        // Pop stack and set command just processed
        val s2 = s.copy(sf = s.sf.copy(callStack = s.callStack.tail), nextCmd = List(tgt))
        // If dynamic invoke, restrict receiver type by the callin we just came from
        val out = invars match{
          case _::Some(rec)::_ if traceNeedRec || cfNeedRec =>
            val (recV,stateWithRec) = s2.getOrDefine(rec,Some(tgt.method))
            stateWithRec.addPureConstraint(PureConstraint(recV, NotEquals, NullVal))
              .constrainUpperType(recV, invokeType, ch)
          case _ =>
            s2
        }
        out
      }.map(_.copy(nextCmd = List(target), alternateCmd = Nil))
    case (AppLoc(_, _, true), AppLoc(_, _, false)) => Set(postState)
    case (appLoc@AppLoc(c1, m1, false), postLoc@AppLoc(c2, m2, true)) if c1 == c2 && m1 == m2 =>
      cmdTransfer(w.cmdAtLocation(appLoc), postState).map(_.setNextCmd(List(postLoc))).map(_.copy(alternateCmd = Nil))
    case (AppLoc(containingMethod, _, true), cmInv@CallbackMethodInvoke(fc1, fn1, l1)) =>
      // If call doesn't match return on stack, return bottom
      // Target loc of CallbackMethodInvoke means just before callback is invoked
      if(postState.callStack.nonEmpty){
        postState.callStack.head match {
          case CallStackFrame(CallbackMethodReturn(fc2,fn2,l2,_),_,_) if fc1 != fc2 || fn1 != fn2 || l1 != l2 =>
            throw new IllegalStateException("ControlFlowResolver should enforce stack matching")
          case _ =>
        }
      }

      val invars: List[Option[LocalWrapper]] = None :: containingMethod.getArgs
      val (pkg, name) = msgCmdToMsg(cmInv)
      val relAliases = relevantAliases2(postState, CBEnter, (pkg,name),invars)
      val (inVals, state0) = getOrDefineRVals(containingMethod, relAliases,postState)
      val state1 = traceAllPredTransfer(CBEnter, (pkg,name), inVals, state0)
      val b = newSpecInstanceTransfer(containingMethod, CBEnter, (pkg, name), invars, cmInv, state1)
      b.map(s => s.copy(sf = s.sf.copy(callStack = if(s.callStack.isEmpty) Nil else s.callStack.tail),
        nextCmd = List(target),
        alternateCmd = Nil))
    case (CallbackMethodInvoke(_, _, _), targetLoc@CallbackMethodReturn(_,_,mloc, _)) =>
      // Case where execution goes to the exit of another callback
      // TODO: nested callbacks not supported yet, assuming we can't go back to callin entry
      // TODO: note that the callback method invoke is to be ignored here.
      // Control flow resolver is responsible for the
      val appLoc = AppLoc(targetLoc.loc, targetLoc.line.get,isPre = false)
      val rvar = w.cmdAtLocation(appLoc) match{
        case ReturnCmd(v,_) =>v
        case c => throw new IllegalStateException(s"return from non return command $c ")
      }
      val newFrame = CallStackFrame(targetLoc, None, Map())
      val (pkg,name) = msgCmdToMsg(target)
      // Push frame regardless of relevance
      val pre_push = postState.copy(sf = postState.sf.copy(callStack = newFrame::postState.callStack))
      val localVarOrVal: List[Option[RVal]] = rvar::mloc.getArgs
      val relAliases = relevantAliases2(postState, CBExit, (pkg,name),localVarOrVal)
      // Note: no newSpecInstanceTransfer since this is an in-message
      val (rVals, state0) = getOrDefineRVals(mloc, relAliases, pre_push)
      val state1 = traceAllPredTransfer(CBExit, (pkg, name), rVals, state0)
      Set(state1).map(_.copy(nextCmd = List(target), alternateCmd = Nil))
    case (CallbackMethodReturn(_,_,mloc1,_), AppLoc(mloc2,_,false)) =>
      assert(mloc1 == mloc2)
      Set(postState).map(_.copy(nextCmd = List(source), alternateCmd = Nil))
    case (InternalMethodInvoke(invokeType, _,_), al@AppLoc(_, _, true)) =>
      val cmd = w.cmdAtLocation(al) match{
        case InvokeCmd(inv : Invoke, _) => inv
        case AssignCmd(_, inv: Invoke, _) => inv
        case c =>
          throw new IllegalStateException(s"Malformed invoke command $c")
      }
      val receiverOption: Option[RVal] = cmd match{
        case v:VirtualInvoke => Some(v.target)
        case s:SpecialInvoke => Some(s.target)
        case _:StaticInvoke => None
      }
      val argOptions: List[Option[RVal]] = cmd.params.map(Some(_))
      val state0 = postState.setNextCmd(List(source))

      // Always define receiver to reduce dynamic dispatch imprecision
      // Value is discarded if static call
      //TODO: check if skipped internal method and don't materialize receiver or other args
      //TODO: implemented this, check that it works
      val cfNeedRec = postState.alternateCmd.exists(other => !postState.nextCmd.contains(other))
      val (receiverValue,stateWithRec) = if(cfNeedRec){
        val (recV,st) = state0.getOrDefine(LocalWrapper("@this","_"), None)
        (Some(recV),st)
      }else (state0.get(LocalWrapper("@this","_")), state0)
      val stateWithRecPf = stateWithRec.copy(sf = stateWithRec.sf.copy(pureFormula = stateWithRec.pureFormula ++
//        PureConstraint(receiverValue, TypeComp, SubclassOf(invokeType)) +
        receiverValue.map(PureConstraint(_, NotEquals, NullVal))
      ))
      val stateWithRecTypeCst = if(receiverValue.isDefined)
        stateWithRecPf.constrainUpperType(receiverValue.get.asInstanceOf[PureVar], invokeType,ch)
      else stateWithRecPf

      // Get values associated with arguments in state
      val frameArgVals: List[Option[PureExpr]] =
        cmd.params.indices.map(i => stateWithRecTypeCst.get(LocalWrapper(s"@parameter$i", "_"))).toList

      // Combine args and params into list of tuples
      val allArgs = receiverOption :: argOptions
      val allParams: Seq[Option[PureExpr]] = (receiverValue::frameArgVals)
      val argsAndVals: List[(Option[RVal], Option[PureExpr])] = allArgs zip allParams

      // Possible stack frames for source of call being a callback or internal method call
      val out = if (stateWithRecTypeCst.callStack.size == 1) {
        val newStackFrames: List[CallStackFrame] =
          BounderUtil.resolveMethodReturnForAppLoc(resolver, al).map(mr => CallStackFrame(mr, None, Map()))
        val newStacks = newStackFrames.map{frame =>
          frame :: (if (stateWithRecTypeCst.callStack.isEmpty) Nil else stateWithRecTypeCst.callStack.tail)}
        val nextStates = newStacks.map(newStack =>
          stateWithRecTypeCst.copy(sf = stateWithRecTypeCst.sf.copy(callStack = newStack)))
        nextStates.map(nextState => defineVarsAs(nextState, argsAndVals)).toSet
      }else if (stateWithRecTypeCst.callStack.size > 1){
        val state1 = stateWithRecTypeCst
          .copy(sf = stateWithRecTypeCst.sf.copy(callStack = stateWithRecTypeCst.callStack.tail))
        Set(defineVarsAs(state1, argsAndVals))
      }else{
        throw new IllegalStateException("Abstract state should always have a " +
          "stack when returning from internal method.")
      }
      out.map(_.copy(nextCmd = List(target), alternateCmd = Nil))
    case (SkippedInternalMethodInvoke(invokeType, _,_), al@AppLoc(_, _, true)) =>
      val cmd = w.cmdAtLocation(al) match{
        case InvokeCmd(inv : Invoke, _) => inv
        case AssignCmd(_, inv: Invoke, _) => inv
        case c => throw new IllegalStateException(s"Malformed invoke command $c")
      }
      val receiverOption: Option[LocalWrapper] = cmd match{
        case v:VirtualInvoke => Some(v.target)
        case s:SpecialInvoke => Some(s.target)
        case _:StaticInvoke => None
      }

      // receiver must be non-null for invoke to be possible
      val postState2 = if(receiverOption.exists{postState.containsLocal}) {
        val localV = postState.get(receiverOption.get).get
//        postState.copy(pureFormula = postState.pureFormula + PureConstraint(localV, NotEquals, NullVal))
        postState.addPureConstraint(PureConstraint(localV, NotEquals, NullVal))
      } else postState

      val argOptions: List[Option[RVal]] = cmd.params.map(Some(_))
      val state0 = postState2.setNextCmd(List(source))

      // Always define receiver to reduce dynamic dispatch imprecision
      // Value is discarded if static call
      val (receiverValue,stateWithRec) = state0.getOrDefine(LocalWrapper("@this",invokeType),None)
      val stateWithRecTypeCst = stateWithRec.addPureConstraint(PureConstraint(receiverValue, NotEquals, NullVal))
        .constrainUpperType(receiverValue, invokeType,ch)

      // Get values associated with arguments in state
      val frameArgVals: List[Option[PureExpr]] =
        (0 until cmd.params.length).map(i => stateWithRecTypeCst.get(LocalWrapper(s"@parameter$i", "_"))).toList

      // Combine args and params into list of tuples
      val allArgs = receiverOption :: argOptions
      val allParams: Seq[Option[PureExpr]] = (Some(receiverValue)::frameArgVals)
      val argsAndVals: List[(Option[RVal], Option[PureExpr])] = allArgs zip allParams

      // Possible stack frames for source of call being a callback or internal method call
      val out = if (stateWithRecTypeCst.callStack.size == 1) {
        val newStackFrames: List[CallStackFrame] =
          BounderUtil.resolveMethodReturnForAppLoc(resolver, al).map(mr => CallStackFrame(mr, None, Map()))
        val newStacks = newStackFrames.map{frame =>
          frame :: (if (stateWithRecTypeCst.callStack.isEmpty) Nil else stateWithRecTypeCst.callStack.tail)}
        val nextStates = newStacks.map(newStack => stateWithRecTypeCst.setCallStack(newStack))
        nextStates.map(nextState => defineVarsAs(nextState, argsAndVals)).toSet
      }else if (stateWithRecTypeCst.callStack.size > 1){
        val state1 = stateWithRecTypeCst.setCallStack(stateWithRecTypeCst.callStack.tail)
        Set(defineVarsAs(state1, argsAndVals))
      }else{
        throw new IllegalStateException("Abstract state should always have a " +
          "stack when returning from internal method.")
      }
      out.map(_.copy(nextCmd = List(target), alternateCmd = Nil))
    case (retloc@AppLoc(mloc, line, false), mRet: InternalMethodReturn) =>
      val cmd = w.cmdAtLocation(retloc)
      val retVal:Map[StackVar, PureExpr] = cmd match{
        case AssignCmd(tgt, _:Invoke, _) =>
          postState.get(tgt).map(v => Map(StackVar("@ret") -> v)).getOrElse(Map())
        case InvokeCmd(_,_) => Map()
        case _ => throw new IllegalStateException(s"malformed bytecode, source: $retloc  target: $mRet")
      }
      val inv = cmd match{
        case InvokeCmd(inv : Invoke, _) => inv
        case AssignCmd(_, inv: Invoke, _) => inv
        case c => throw new IllegalStateException(s"Malformed invoke command $c")
      }
      val receiverOption: Option[LocalWrapper] = inv match{
        case v:VirtualInvoke => Some(v.target)
        case s:SpecialInvoke => Some(s.target)
        case _:StaticInvoke => None
      }

      val postState2 = if(receiverOption.exists{postState.containsLocal}) {
        val localV = postState.get(receiverOption.get).get
//        postState.copy(pureFormula = postState.pureFormula + PureConstraint(localV, NotEquals, NullVal))
        postState.addPureConstraint(PureConstraint(localV, NotEquals, NullVal))
      } else postState


      // If receiver variable is materialized, use that,
      // otherwise check if it is the "@this" var and use materialized "@this" if it exists
      val materializedReceiver = receiverOption.flatMap(recVar =>
        if(postState2.get(recVar).isDefined){
          postState2.get(recVar)
        }else if(w.getThisVar(retloc).contains(recVar)){
          // invoke on variable representing @this
          val r = postState2.get(LocalWrapper("@this", "_"))
          r
        }else None
      )
      // Create call stack frame with return value
      val receiverTypesFromPT: Option[TypeSet] = receiverOption.map{
        case rec@LocalWrapper(_,_) =>
          w.pointsToSet(mloc, rec)
        case _ => throw new IllegalStateException()
      }
      val newFrame = CallStackFrame(mRet, Some(AppLoc(mloc, line, true)), retVal)
      val clearedLVal = cmd match {
        case AssignCmd(target, _, _) => postState2.clearLVal(target)
        case _ => postState2
      }
      val stateWithFrame =
        clearedLVal.setCallStack(newFrame :: postState2.callStack).copy(nextCmd = List(target), alternateCmd = Nil)
      // Constraint receiver by current points to set  TODO: apply this to other method transfers ====
      if(receiverTypesFromPT.isDefined) {
        val (thisV, stateWThis) = if(materializedReceiver.isEmpty) {
          stateWithFrame.getOrDefine(LocalWrapper("@this", "_"),None)
        } else {
          val v:PureVar = materializedReceiver.get.asInstanceOf[PureVar]
          (v,stateWithFrame.defineAs(LocalWrapper("@this","_"), v))
        }
        val pts = stateWThis.typeConstraints.get(thisV).map(_.intersect(receiverTypesFromPT.get))
          .getOrElse(receiverTypesFromPT.get)
        Set(stateWThis.addTypeConstraint(thisV,pts))
      } else {
        Set(stateWithFrame)
      }
    case (retLoc@AppLoc(mloc, line, false), mRet@SkippedInternalMethodReturn(_, _, rel, _)) =>
      // Create call stack frame with return value
      val newFrame = CallStackFrame(mRet, Some(AppLoc(mloc,line,true)), Map())
      // Remove assigned variable from the abstract state
      val clearedLval = w.cmdAtLocation(retLoc) match{
        case AssignCmd(target, _:Invoke, _) =>
          postState.clearLVal(target)
        case _ => postState
      }
      val withStackFrame = clearedLval.setCallStack(newFrame :: clearedLval.callStack)
        .copy(nextCmd = List(target), alternateCmd = Nil)
      val withPrecisionLoss = rel.applyPrecisionLossForSkip(withStackFrame)
      Set(withPrecisionLoss)
    case (SkippedInternalMethodReturn(_,_,_,_), SkippedInternalMethodInvoke(_,_,_)) =>
      Set(postState).map(_.copy(nextCmd = List(source), alternateCmd = Nil))
    case (mr@InternalMethodReturn(_,_,_), cmd@AppLoc(m,_,false)) =>
      // if @this is defined, constrain to be subtype of receiver class
      val postStateWithThisTC = postState.get(LocalWrapper("@this","_")) match{
        case Some(thisPv:PureVar) if postState.typeConstraints.contains(thisPv)=>
          val oldTc = postState.typeConstraints(thisPv)
          val newTc = oldTc.filterSubTypeOf(Set(m.classType))
          postState.addTypeConstraint(thisPv, newTc)
        case _ => postState
      }
      val out = w.cmdAtLocation(cmd) match{
        case ReturnCmd(_,_) => Set(postStateWithThisTC)
        case _ => throw new IllegalStateException(s"malformed bytecode, source: $mr  target: ${cmd}")
      }
      out.map(_.copy(nextCmd = List(source), alternateCmd = Nil))
    case (_:AppLoc, _:InternalMethodInvoke) =>
      Set(postState).map(_.copy(nextCmd = List(source), alternateCmd = Nil))
    case t =>
      println(t)
      ???
  }

  /**
   * For a back message with a given package and name, instantiate each rule as a new trace abstraction
   *
   * @param loc callback invoke or callin return
   * @param postState state at point in app just before back message
   * @return a new trace abstraction for each possible rule
   */
  def newSpecInstanceTransfer(targetMethod:MethodLoc, mt: MessageType,
                              sig:(String,String), allVar:List[Option[RVal]],
                              loc: Loc, postState: State): Set[State] = {
    val specsBySignature: Set[LSSpec] = specSpace.specsBySig(mt, sig._1, sig._2)


    val postStatesByConstAssume: Set[(LSSpec,State)] = specsBySignature.flatMap{ (s:LSSpec) =>
      val cv = s.target.constVals(s.rhsConstraints) zip allVar
      val definedCv: Seq[(PureExpr,CmpOp ,RVal)] = cv.flatMap{
        case (None,_) => None
        case (_,None) => None
        case (Some((op,cv)), Some(stateVar)) => Some((cv,op,stateVar))
      }
      if(definedCv.isEmpty) {
        // Spec does not assume any constants
        Set((s,postState))
      } else {
        //  Negation of RHS of spec requires False unless defined
        val posState: State = definedCv.foldLeft(postState) {
          case (st, (pureExpr, op,stateVar)) =>
            val (vv, st1) = st.getOrDefine(stateVar, Some(targetMethod))
            st1.addPureConstraint(PureConstraint(vv, op, pureExpr))
        }
        val out = Set((s,posState))
        out
      }
    }

    // If no lifestate rules match, no new specs are instantiated
    if(postStatesByConstAssume.isEmpty)
      return Set(postState)

    // For each applicable state and spec,
    //  instantiate ls variables in both the trace abstraction and abstract state
    postStatesByConstAssume.map {
      case (LSSpec(pred, target,_), newPostState) =>
        val parameterPairing: Seq[(String, Option[RVal])] = target.lsVars zip allVar

        // Define variables in rule in the state
        val state2 = parameterPairing.foldLeft(newPostState) {
          case (cstate, (LSAnyVal(), _)) => cstate
          case (cstate, (LSConst(_), _)) => cstate
          case (cstate, (_, Some(rval))) => cstate.getOrDefine(rval,Some(targetMethod))._2
          case (cstate, _) => cstate
        }
        val lsVarConstraints = parameterPairing.flatMap {
          case (LSAnyVal(), _) => None
          case (LSVar(k), Some(l: LocalWrapper)) =>
            Some((k, state2.get(l).get))
          case (_, None) => None
          case (LSConst(_), Some(_: LocalWrapper)) => None
          case (k, v) =>
            println(k)
            println(v)
            ??? //TODO: handle primitives e.g. true "string" 1 2 etc
        }
        // Match each lsvar to absvar if both exist
        val newLsAbstraction = AbstractTrace(pred, Nil, lsVarConstraints.toMap)
        state2.copy(sf = state2.sf.copy(traceAbstraction = state2.traceAbstraction + newLsAbstraction))
    }
  }

  /**
   * Get input and output vars of executing part of the app responsible for an observed message
   * Note: all vars used in invoke or assign/invoke can be in post state
   * @param loc
   * @return (pkg, function name)
   */
  private def msgCmdToMsg(loc: Loc): (String, String) =

    loc match {
      case CallbackMethodReturn(pkg, name, _,_) => (pkg, name)
      case CallbackMethodInvoke(pkg, name, _) => (pkg,name)
      case CallinMethodInvoke(clazz, name) => (clazz,name)
      case CallinMethodReturn(clazz,name) => (clazz,name)
      case GroupedCallinMethodInvoke(targetClasses, fmwName) => (targetClasses.head, fmwName)
      case GroupedCallinMethodReturn(targetClasses,fmwName) => (targetClasses.head, fmwName)
      case v =>
        throw new IllegalStateException(s"No command message for $v")
    }

  /**
   * Assume state is updated with appropriate vars
   *
   * @return
   */
  def predTransferTrace(pred:AbstractTrace, mt:MessageType,
                        sig:(String,String),
                        vals: List[Option[PureExpr]]):AbstractTrace = {
    if (pred.a.contains(mt,sig)) {
      specSpace.getIWithFreshVars(mt, sig) match {
        case Some(i@I(_, _, lsVars)) =>
          val modelVarConstraints: Map[String, PureExpr] = (lsVars zip vals).flatMap {
            case (LSVar(lsVar), Some(stateVal)) => Some((lsVar, stateVal))
            case _ => None //TODO: cases where transfer needs const values (e.g. setEnabled(true))
          }.toMap
          assert(!modelVarConstraints.isEmpty) //TODO: can this ever happen?
          assert(pred.modelVars.keySet.intersect(modelVarConstraints.keySet).isEmpty,
            "Previous substitutions must be made so that comflicting model " +
              "var constraints aren't added to trace abstraction")
          AbstractTrace(pred.a,
            i :: pred.rightOfArrow, pred.modelVars ++ modelVarConstraints)
        case None => pred
      }
    } else pred
  }

  /**
   * Update each trace abstraction in an abstract state
   * @param allVal values to apply transfer with
   * @return
   */
  def traceAllPredTransfer(mt: MessageType,
                           sig:(String,String), allVal:List[Option[PureExpr]],
                           postState: State):State = {
    // values we want to track should already be added to the state
    val newTraceAbs: Set[AbstractTrace] = postState.traceAbstraction.map {
      traceAbs => predTransferTrace(traceAbs, mt, sig, allVal)
    }
    postState.copy(sf = postState.sf.copy(traceAbstraction = newTraceAbs))
  }

//  def pureCanAlias(pv:PureVar, otherType:String, state:State):Boolean =
//    classHierarchyConstraints.typeSetForPureVar(pv,state).contains(otherType)

  private def exprContainsV(value: PureVar, expr:PureExpr):Boolean = expr match{
    case p:PureVar => value == p
    case _:PureVal => false
  }
  private def heapCellReferencesVAndIsNonNull(value:PureVar, state: State): Boolean = state.heapConstraints.exists{
    case (FieldPtEdge(base, _), ptVal) =>
      if(value == base || exprContainsV(value,ptVal)) {
        ptVal != NullVal &&
          (!state.pureFormula.exists{
            case PureConstraint(lhs, Equals, NullVal) if lhs == ptVal => true
            case PureConstraint(NullVal, Equals, rhs) if rhs == ptVal => true
            case _ => false
          })
      } else false
    case (StaticPtEdge(_,_),ptVal) => exprContainsV(value,ptVal)
    case (ArrayPtEdge(base,index),ptVal) =>
      exprContainsV(value,base) || exprContainsV(value,index) || exprContainsV(value,ptVal)
  }
  private def localReferencesV(pureVar: PureVar, state: State): Boolean ={
    state.callStack.exists{sf =>
      sf.locals.exists{
        case (_,v) =>
          v == pureVar
      }
    }
  }

  def cmdTransfer(cmd:CmdWrapper, state:State):Set[State] = cmd match {
    case AssignCmd(lhs: LocalWrapper, TopExpr(_), _) => Set(state.clearLVal(lhs))
    case AssignCmd(lhs@LocalWrapper(_, _), NewCommand(className), _) =>
      // x = new T
      state.get(lhs) match {
        case Some(v: PureVar) =>
          if (heapCellReferencesVAndIsNonNull(v, state) || localReferencesV(v,state.clearLVal(lhs))) {
            // If x->v^ and some heap cell references v^, the state is not possible
            // new command does not call constructor, it just creates an instance with all null vals
            // <init>(...) is the constructor and is called in the instruction after the new instruction
            Set()
          } else {
            // x is assigned here so remove it from the pre-state
            val sWithoutLVal = state.clearLVal(lhs)
            val sWithoutNullHeapCells = sWithoutLVal.copy(sf = sWithoutLVal.sf.copy(heapConstraints =
              sWithoutLVal.heapConstraints.filter{
                case (FieldPtEdge(base, _),_) if base == v =>
                  // Previously, we checked for non-null heap cells that contain the value v
                  // and would have refuted before now
                  false
                case _ => true
              }
            ))
            // If x = new T and x->v^ then v^<:T
            // v^ != null since new instruction never returns null
            Set(sWithoutNullHeapCells.addPureConstraint(PureConstraint(v, NotEquals, NullVal)
            ).constrainIsType(v, className, ch))
          }
        case Some(_: PureVal) => Set() // new cannot return anything but a pointer
        case None => Set(state) // Do nothing if variable x is not in state
      }
    case AssignCmd(lw: LocalWrapper, ThisWrapper(thisTypename), a) =>
      val out = cmdTransfer(AssignCmd(lw, LocalWrapper("@this", thisTypename), a), state)
      out.map { s =>
        s.get(LocalWrapper("@this", thisTypename)) match {
          case Some(v) =>
            s.addPureConstraint(PureConstraint(v, NotEquals, NullVal))
          case None => s
        }
      }
    case AssignCmd(lhs: LocalWrapper, rhs: LocalWrapper, _) => //
      // x = y
      val lhsv = state.get(lhs) // Find what lhs pointed to if anything
      lhsv.map(pexpr => {
        // remove lhs from abstract state (since it is assigned here)
        val state2 = state.clearLVal(lhs)
        if (state2.containsLocal(rhs)) {
          // rhs constrained by refutation state, lhs should be equivalent
          Set(state2.addPureConstraint(PureConstraint(pexpr, Equals, state2.get(rhs).get)))
        } else {
          // rhs unconstrained by refutation state, should now be same as lhs
          val state3 = state2.defineAs(rhs, pexpr)
          Set(state3)
        }
      }).getOrElse {
        Set(state) // if lhs points to nothing, no change in state
      }
    case ReturnCmd(Some(v), _) =>
      val fakeRetLocal = LocalWrapper("@ret", "_")
      val retv = state.get(fakeRetLocal)
      val state1 = state.clearLVal(fakeRetLocal)
      Set(retv.map(state1.defineAs(v, _)).getOrElse(state))
    case ReturnCmd(None, _) => Set(state)
    case AssignCmd(lhs: LocalWrapper, FieldReference(base, fieldType, _, fieldName), l) =>
      // x = y.f
      state.get(lhs) match { //TODO: some kind of imprecision here or in the simplification shown by "Test dynamic dispatch 2"
        case Some(lhsV) => {
          val (basev, state1) = state.getOrDefine(base,Some(l.method))
          // get all heap cells with correct field name that can alias
          val possibleHeapCells = state1.heapConstraints.filter {
            case (FieldPtEdge(pv, heapFieldName), materializedTgt) =>
              val fieldEq = fieldName == heapFieldName
              //TODO: === check that contianing method works here
              val canAlias = state1.canAlias(pv,l.containingMethod.get, base,w)
              fieldEq && canAlias
            case _ =>
              false
          }
          val statesWhereBaseAliasesExisting: Set[State] = possibleHeapCells.map {
            case (FieldPtEdge(p, _), heapV) =>
              state1.addPureConstraint(PureConstraint(basev, Equals, p))
                .addPureConstraint(PureConstraint(lhsV, Equals, heapV))
            case _ => throw new IllegalStateException()
          }.toSet
          val heapCell = FieldPtEdge(basev, fieldName)
          val stateWhereNoHeapCellIsAliased = state1.copy(sf = state1.sf.copy(
            heapConstraints = state1.heapConstraints + (heapCell -> lhsV),
            pureFormula = state1.pureFormula ++ possibleHeapCells.map {
              case (FieldPtEdge(p, _), _) => PureConstraint(p, NotEquals, basev)
              case _ => throw new IllegalStateException()
            }
          ))
          val res = statesWhereBaseAliasesExisting + stateWhereNoHeapCellIsAliased
          res.map(s => s.clearLVal(lhs))
        }
        case None => Set(state)
      }
    case AssignCmd(FieldReference(base, fieldType, _, fieldName), rhs, l) =>
      // x.f = y
      val (basev, state2) = state.getOrDefine(base, Some(l.method))

      // get all heap cells with correct field name that can alias
      val possibleHeapCells = state2.heapConstraints.filter {
        case (FieldPtEdge(pv, heapFieldName), _) =>
          val fieldEq = fieldName == heapFieldName
          val canAlias = state2.canAlias(pv,l.containingMethod.get, base,w)
          fieldEq && canAlias
        case _ =>
          false
      }

      // Get or define right hand side
      val possibleRhs = Set(state2.getOrDefine2(rhs, l.method))
      // get or define base of assignment
      // Enumerate over existing base values that could alias assignment
      // Enumerate permutations of heap cell and rhs
      // TODO: remove repeatingPerm here since only one possible rhs
      val perm = BounderUtil.repeatingPerm(a => if (a == 0) possibleHeapCells else possibleRhs, 2)
      val casesWithHeapCellAlias: Set[State] = perm.map {
        case (pte@FieldPtEdge(heapPv, _), tgtVal: PureExpr) :: (rhsPureExpr: PureExpr, state3: State) :: Nil =>
          val withPureConstraint = state3.addPureConstraint(PureConstraint(basev, Equals, heapPv))
          val swapped = withPureConstraint.copy(sf = withPureConstraint.sf.copy(
            heapConstraints = withPureConstraint.heapConstraints - pte,
            pureFormula = withPureConstraint.pureFormula +
              PureConstraint(tgtVal, Equals, rhsPureExpr) +
              PureConstraint(heapPv, NotEquals, NullVal) // Base must be non null for normal control flow
          ))
          swapped
        case v =>
          println(v)
          ???
      }.toSet
      val caseWithNoAlias = state2.copy(sf = state2.sf.copy(pureFormula = state2.pureFormula ++ possibleHeapCells.flatMap {
        case (FieldPtEdge(pv, _), _) => Some(PureConstraint(basev, NotEquals, pv))
        case _ => None
      }))
      casesWithHeapCellAlias + caseWithNoAlias
    case AssignCmd(target: LocalWrapper, source, l) if source.isConst =>
      state.get(target) match {
        case Some(v) =>
          val src = Set(state.getOrDefine2(source, l.method))
          src.map {
            case (pexp, s2) => s2.addPureConstraint(PureConstraint(v, Equals, pexp)).clearLVal(target)
          }
        case None => Set(state)
      }
    case _: InvokeCmd => Set(state) // Invoke not relevant and skipped
    case AssignCmd(_, _: Invoke, _) => Set(state)
    case If(b, trueLoc, l) =>
      if (state.nextCmd.toSet.size == 1) {
        val stateLocationFrom: Loc = state.nextCmd.head
        if (stateLocationFrom == trueLoc)
          assumeInState(l.method, b, state, negate = false).toSet
        else
          assumeInState(l.method, b, state, negate = true).toSet
      } else if (state.nextCmd.toSet.size == 2){
        Set(state) // When both true and false branch is represented by current state, do not materialize anything
      }else{
        throw new IllegalStateException(s"If should only have 1 or 2 next commands, next commands: ${state.nextCmd}")
      }
    case AssignCmd(l,Cast(castT, local),cmdloc) =>
      val state1 = state.get(local) match{
        case Some(v:PureVar) => state.constrainUpperType(v, castT, ch)
        case Some(v) =>
          println(v)
          ???
//          .copy(pureFormula = state.pureFormula + PureConstraint(v, TypeComp, SubclassOf(castT)))
        case None => state
      }
      cmdTransfer(AssignCmd(l,local,cmdloc),state1)
    case AssignCmd(l:LocalWrapper, StaticFieldReference(declaringClass, fname, containedType), _) =>
      if(state.containsLocal(l)){
        val v = state.get(l).get.asInstanceOf[PureVar]
        val state1 = state.clearLVal(l)
        Set(state1.copy(sf =
          state1.sf.copy(heapConstraints = state1.heapConstraints + (StaticPtEdge(declaringClass,fname) -> v),
//          pureFormula = state1.pureFormula + PureConstraint(v, TypeComp, SubclassOf(containedType))
        )).constrainUpperType(v,containedType,ch))
      }else Set(state)
    case AssignCmd(StaticFieldReference(declaringClass,fieldName,_), l,_) =>
      val edge = StaticPtEdge(declaringClass, fieldName)
      if(state.heapConstraints.contains(edge)){
        val v = state.heapConstraints(edge)
        val state1 = state.defineAs(l, v)
        Set(state1.copy(sf = state1.sf.copy(heapConstraints = state1.heapConstraints - edge)))
      }else Set(state)
    case NopCmd(_) => Set(state)
    case ThrowCmd(v) => Set() //TODO: implement exceptional control flow
    case SwitchCmd(_,targets,_) => //TODO: precise implementation of switch
      targets.foldLeft(Set[State]()){
        case (acc, cmd) => acc ++ cmdTransfer(cmd, state)
      }
    case AssignCmd(lhs:LocalWrapper, ArrayReference(base, index),_) =>
      state.get(lhs) match{
        case Some(v) =>
          //TODO: We currently do not precisely track array references
          // Dropping the constraint should be sound but not precise
//          val (basev,state1) = state.getOrDefine(base)
//          val (indexv,state2) = state1.getOrDefine(index)
//          val arrayRef = ArrayPtEdge(basev, indexv)
//          Set(state2.copy(heapConstraints = state2.heapConstraints + (arrayRef -> v)).clearLVal(lhs))
          Set(state.clearLVal(lhs))
        case None => Set(state)
      }
    case AssignCmd(ArrayReference(base,index), lhs,_) =>
      val possibleAliases: Map[HeapPtEdge, PureExpr] = state.heapConstraints.filter{
        case (ArrayPtEdge(_,_),_) => true
        case _ => false
      }
      if (possibleAliases.isEmpty)
        Set(state)
      else {
        //TODO: handle arrays more precisely
        Set(state.copy(sf = state.sf.copy(heapConstraints = state.heapConstraints -- possibleAliases.keySet)))
      }

    case AssignCmd(lhs:LocalWrapper, ArrayLength(l),_) =>
      state.get(lhs) match{
        case Some(v) =>
          //TODO: handle arrays more precisely
          val state1 = state.clearLVal(lhs)
          Set(state1.copy(sf = state1.sf.copy(pureFormula = state.pureFormula + PureConstraint(v, NotEquals, NullVal))))
        case None => Set(state)
      }
    case AssignCmd(_:LocalWrapper, CaughtException(_), _) =>
      Set[State]() //TODO: handle exceptional control flow
    case AssignCmd(lhs:LocalWrapper, Binop(_,_,_),_) if state.get(lhs).isEmpty =>
      Set(state) // lhs of binop is not defined
    case AssignCmd(lhs:LocalWrapper, Binop(_,_,_), _) =>
      // TODO: sound to drop constraint, add precision when necessary
      Set(state.clearLVal(lhs))
    case AssignCmd(lhs:LocalWrapper, InstanceOf(clazz, target),_) =>
      // TODO: sound to drop constraint, handle instanceof when needed
      state.get(lhs) match{
        case Some(v) => Set(state.clearLVal(lhs))
        case None => Set(state)
      }
    case c =>
      println(c)
      ???
  }

  private def getOrDefineRVals(method: MethodLoc, rhs:List[Option[RVal]], state:State): (List[Option[PureExpr]], State) ={
    rhs.foldRight((List[Option[PureExpr]](),state)){
      case (Some(rval),(vlist,cstate)) =>
        val (pexp,nstate) = cstate.getOrDefine2(rval,method)//enumerateAliasesForRVal(rval,cstate)
        (Some(pexp)::vlist, nstate)
      case (None,(vlist,cstate)) => (None::vlist, cstate)
    }
  }


//  def localCanAliasPV(v:RVal, state:State) = v match{
//    case lw@LocalWrapper(_,localType) =>
//      state.pureVars.exists{ p =>
//        state.typeConstraints.get(p) match{
//          case Some(tc) => tc.subtypeOfCanAlias(localType,ch)
//          case _ => true
//        }
//      }
//    case _ => false
//  }
  def assumeInState(method:MethodLoc, bExp:RVal, state:State, negate: Boolean): Option[State] = bExp match{
    case BoolConst(b) if b != negate => Some(state)
    case BoolConst(b) if b == negate => None
    case Binop(v1, op, v2) =>
      val (v1Val, state0) = state.getOrDefine2(v1, method)
      val (v2Val, state1) = state0.getOrDefine2(v2, method)
      //TODO: Handle boolean expressions, integer expressions, etc
      // it is sound, but not precise, to drop constraints
      Some((op, negate) match {
        case (Eq, false) => state1.addPureConstraint(PureConstraint(v1Val, Equals, v2Val))
        case (Ne, false) => state1.addPureConstraint(PureConstraint(v1Val, NotEquals, v2Val))
        case (Eq, true) => state1.addPureConstraint(PureConstraint(v1Val, NotEquals, v2Val))
        case (Ne, true) => state1.addPureConstraint(PureConstraint(v1Val, Equals, v2Val))
        case _ => state
      })
    case v =>
      throw new IllegalStateException(s"Invalid rval for assumeInState: $v")
  }
}

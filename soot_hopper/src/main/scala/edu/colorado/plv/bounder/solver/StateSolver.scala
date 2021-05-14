package edu.colorado.plv.bounder.solver

import com.microsoft.z3.AST
import edu.colorado.plv.bounder.BounderUtil
import edu.colorado.plv.bounder.ir.{TAddr, TMessage, TNullVal, TypeSet}
import edu.colorado.plv.bounder.lifestate.LifeState
import edu.colorado.plv.bounder.lifestate.LifeState._
import edu.colorado.plv.bounder.symbolicexecutor.state.{HeapPtEdge, _}
import org.slf4j.LoggerFactory
import scalaz.Memo

import scala.collection.parallel.CollectionConverters._
import upickle.default._

import scala.collection.immutable

trait Assumptions

class UnknownSMTResult(msg : String) extends Exception(msg)
trait SolverCtx

/** SMT solver parameterized by its AST or expression type */
trait StateSolver[T, C <: SolverCtx] {
  private val logger = LoggerFactory.getLogger(classOf[StateSolver[T, C]])

  // checking
  def getSolverCtx: C

  def checkSAT()(implicit zctx: C): Boolean

  def push()(implicit zctx: C): Unit

  def pop()(implicit zctx: C): Unit


  // quantifiers
  /**
   * forall int condition is true
   *
   * @param cond condition inside the forall statement
   */
  protected def mkForallInt(min: T, max: T, cond: T => T)(implicit zctx: C): T

  protected def mkForallAddr(name: String, cond: T => T)(implicit zctx: C): T

  protected def mkExistsAddr(name: String, cond: AST => AST)(implicit zctx: Z3SolverCtx): AST

  protected def mkExistsInt(min: T, max: T, cond: T => T)(implicit zctx: C): T

  // comparison operations
  protected def mkEq(lhs: T, rhs: T)(implicit zctx: C): T

  protected def mkNe(lhs: T, rhs: T)(implicit zctx: C): T

  protected def mkGt(lhs: T, rhs: T)(implicit zctx: C): T

  protected def mkLt(lhs: T, rhs: T)(implicit zctx: C): T

  protected def mkGe(lhs: T, rhs: T)(implicit zctx: C): T

  protected def mkLe(lhs: T, rhs: T)(implicit zctx: C): T

  // logical and arithmetic operations
  protected def mkImplies(t: T, t1: T)(implicit zctx: C): T

  protected def mkNot(o: T)(implicit zctx: C): T

  protected def mkAdd(lhs: T, rhs: T)(implicit zctx: C): T

  protected def mkSub(lhs: T, rhs: T)(implicit zctx: C): T

  protected def mkMul(lhs: T, rhs: T)(implicit zctx: C): T

  protected def mkDiv(lhs: T, rhs: T)(implicit zctx: C): T

  protected def mkRem(lhs: T, rhs: T)(implicit zctx: C): T

  protected def mkAnd(lhs: T, rhs: T)(implicit zctx: C): T

  protected def mkAnd(t: List[T])(implicit zctx: C): T

  protected def mkOr(lhs: T, rhs: T)(implicit zctx: C): T

  protected def mkOr(t: List[T])(implicit zctx: C): T

  protected def mkExactlyOneOf(l: List[T])(implicit zctx: C): T

  // creation of variables, constants, assertions
  protected def mkIntVal(i: Int)(implicit zctx: C): T

  protected def mkBoolVal(b: Boolean)(implicit zctx: C): T

  protected def mkIntVar(s: String)(implicit zctx: C): T

  protected def mkFreshIntVar(s: String)(implicit zctx: C): T

  protected def mkBoolVar(s: String)(implicit zctx: C): T

  protected def mkObjVar(s: PureVar)(implicit zctx: C): T //Symbolic variable
  protected def mkModelVar(s: String, predUniqueID: String)(implicit zctx: C): T // model vars are scoped to trace abstraction
  protected def mkAssert(t: T)(implicit zctx: C): Unit

  protected def mkFieldFun(n: String)(implicit zctx: C): T

  protected def fieldEquals(fieldFun: T, t1: T, t2: T)(implicit zctx: C): T

  protected def solverSimplify(t: T,
                               state: State,
                               messageTranslator: MessageTranslator, logDbg: Boolean = false)(implicit zctx: C): Option[T]

  protected def mkTypeConstraints(types: Set[Int])(implicit zctx: C): (T, Map[Int, T])
  protected def mkConstConstraintsMap(pvs: Set[PureVal])(implicit zctx: C): (T, Map[PureVal, T])

  protected def mkTypeConstraintForAddrExpr(typeFun: T, typeToSolverConst: Map[Int, T],
                                            addr: T, tc: Set[Int])(implicit zctx: C): T

  protected def createTypeFun()(implicit zctx: C): T

  // TODO: swap enum with uninterpreted type
  protected def mkEnum(name: String, types: List[String])(implicit zctx: C): T

  protected def getEnumElement(enum: T, i: Int)(implicit zctx: C): T

  // function traceIndex -> msg
  protected def mkTraceFn(uid: String)(implicit zctx: C): T

  protected def mkFreshTraceFn(uid: String)(implicit zctx: C): T

  // function msg -> iname
  protected def mkINameFn(enum: T)(implicit zctx: C): T

  // function for argument i -> msg -> addr
  protected def mkArgFun()(implicit zctx: C): T

//  // function to test if addr is null
//  // Uses uninterpreted function isNullFn : addr -> bool
//  protected def mkIsNull(addr: T)(implicit zctx: C): T
//
//  protected def mkIntValueConstraint(addr:T)(implicit zctx: C): T
//
  protected def mkConstValueConstraint(addr:T)(implicit zctx:C):T

  // Get enum value for I based on index
  protected def mkIName(enum: T, enumNum: Int)(implicit zctx: C): T

  // function from index to message (message is at index in trace)
  protected def mkTraceConstraint(traceFun: T, index: T)(implicit zctx: C): T

  // function msg -> funname
  protected def mkNameConstraint(nameFun: T, msg: T)(implicit zctx: C): T

  // function argumentindex -> msg -> argvalue
  protected def mkArgConstraint(argFun: T, argIndex: T, msg: T)(implicit zctx: C): T

  protected def mkAddrConst(i: Int)(implicit zctx: C): T

  def printDbgModel(messageTranslator: MessageTranslator, traceabst: Set[AbstractTrace],
                    lenUID: String)(implicit zctx: C): Unit

  def compareConstValueOf(rhs: T, op: CmpOp, pureVal: PureVal, constMap: Map[PureVal, T])(implicit zctx: C): T = {
    (pureVal, op) match {
      case (TopVal, _) => mkBoolVal(b = true)
      case (ClassVal(_), _) => mkBoolVal(b = true) //TODO: add class vals if necessary for precision
      case (v:PureVal, Equals) => mkEq(constMap(v), mkConstValueConstraint(rhs))
      case (v:PureVal, NotEquals) => mkNot(mkEq(constMap(v), mkConstValueConstraint(rhs)))
      case (_:PureVal, _) => mkBoolVal(b = true)
//      case (NullVal, Equals) => mkIsNull(rhs)
//      case (NullVal, NotEquals) => mkNot(mkIsNull(rhs))
////      case (v, Equals) if v == pureVal =>
////        mkBoolVal(true)
////        ??? // TODO:  make sure this case didn't do anything bad
//      case (IntVal(v), Equals) =>
//        // Covers boolean case as well
//        mkAnd(mkEq(mkIntVal(v), mkIntValueConstraint(rhs)),
//          mkNot(mkIsNull(rhs)))
//      case (IntVal(v), NotEquals) =>
//        mkNot(mkEq(mkIntVal(v), mkIntValueConstraint(rhs))) // Covers boolean case as well
//      case (_:PureVal, Equals) =>
//        mkNot(mkIsNull(rhs)) // any constant other than null is non-null
      case v =>
        println(v)
        ???
    }
  }

  def toAST(p: PureConstraint, constMap: Map[PureVal, T])(implicit zctx: C): T = p match {
    // TODO: field constraints based on containing object constraints
    case PureConstraint(v: PureVal, op, rhs) => compareConstValueOf(toAST(rhs), op, v,constMap)
    case PureConstraint(lhs, op, v: PureVal) => compareConstValueOf(toAST(lhs), op, v,constMap)
    case PureConstraint(lhs, op, rhs) =>
      toAST(toAST(lhs), op, toAST(rhs))
    case _ => ???
  }

  def toAST(p: PureExpr)(implicit zctx: C): T = p match {
    case p: PureVar => mkObjVar(p)
    case _ => throw new IllegalStateException("Values should be handled at a higher level")
  }

  def toAST(lhs: T, op: CmpOp, rhs: T)(implicit zctx: C): T = op match {
    case Equals => mkEq(lhs, rhs)
    case NotEquals =>
      mkNe(lhs, rhs)
    case _ =>
      ???
  }

  /**
   * Formula representing truth of "m is at position index in traceFn"
   *
   * @param index   index of the message (ArithExpr)
   * @param m observed message
   * @param messageTranslator mapping from observed messages to z3 representation
   * @param traceFn : Int->Msg
   * @return
   */
  private def assertIAt(index: T, m: I,
                        messageTranslator: MessageTranslator,
                        traceFn: T, // Int -> Msg
                        negated: Boolean = false,
                        modelVarMap: String => T)(implicit zctx: C): T = {
    val msgExpr = mkTraceConstraint(traceFn, index)
    val nameFun = messageTranslator.nameFun
    val nameConstraint = mkEq(mkNameConstraint(nameFun, msgExpr), messageTranslator.enumFromI(m))
    val argConstraints = m.lsVars.zipWithIndex.flatMap {
      case (LSAnyVal(), _) => None //TODO: primitive value cases
      case (msgVar, ind) =>
        //        val modelVar = modelVarMap(msgVar)
        val modelExpr = encodeModelVarOrConst(msgVar, modelVarMap)
        Some(mkEq(mkArgConstraint(mkArgFun(), mkIntVal(ind), msgExpr), modelExpr))
    }

    // w[i] = cb foo(x,y)
    // If we are asserting that a message is not at a location, the arg function cannot be negated due to skolemization
    // We only negate the name function
    if (negated)
      mkOr(mkNot(nameConstraint), mkOr(argConstraints.map(mkNot)))
    else
      mkAnd(nameConstraint, mkAnd(argConstraints))
  }

  private def encodeModelVarOrConst(lsExpr: String, modelVarMap: String => T)(implicit zctx: C): T = lsExpr match {
    case LifeState.LSVar(v) => modelVarMap(v)
    case LifeState.LSConst(const) => toAST(const)
    case LifeState.LSAnyVal() =>
      throw new IllegalStateException("AnyVal shouldn't reach here")
  }

  private def encodePred(combinedPred: LifeState.LSPred, traceFn: T, len: T,
                         messageTranslator: MessageTranslator
                         , modelVarMap: Map[String, T], negate: Boolean = false)(implicit zctx: C): T = combinedPred match {
    case And(l1, l2) if !negate => mkAnd(encodePred(l1, traceFn, len, messageTranslator, modelVarMap),
      encodePred(l2, traceFn, len, messageTranslator, modelVarMap))
    case And(l1, l2) if negate => mkOr(
      encodePred(l1, traceFn, len, messageTranslator, modelVarMap, negate = true),
      encodePred(l2, traceFn, len, messageTranslator, modelVarMap, negate = true)
    )
    case Or(l1, l2) if !negate => mkOr(encodePred(l1, traceFn, len, messageTranslator, modelVarMap),
      encodePred(l2, traceFn, len, messageTranslator, modelVarMap))
    case Or(l1, l2) if negate => mkAnd(encodePred(l1, traceFn, len, messageTranslator, modelVarMap, negate = true),
      encodePred(l2, traceFn, len, messageTranslator, modelVarMap, negate = true))
    //    case Not(m:I) if !negate =>
    //      mkForallInt(mkIntVal(-1),len, i => assertIAt(i,m,messageTranslator,traceFn,absUID,true))
    case Not(l) =>
      encodePred(l, traceFn, len, messageTranslator, modelVarMap, !negate)
    case m@I(_, _, _) if !negate =>
      mkExistsInt(mkIntVal(-1), len,
        i => assertIAt(i, m, messageTranslator, traceFn, negated = false, modelVarMap))
    case m: I if negate =>
      mkForallInt(mkIntVal(-1), len, i => assertIAt(i, m, messageTranslator, traceFn, negated = true, modelVarMap))
    case NI(m1, m2) if !negate =>
      // exists i such that omega[i] = m1 and forall j > i omega[j] != m2
      mkExistsInt(mkIntVal(-1), len, i => mkAnd(List(
        assertIAt(i, m1, messageTranslator, traceFn, negated = false, modelVarMap),
        mkForallInt(i, len, j => assertIAt(j, m2, messageTranslator, traceFn, negated = true, modelVarMap))
      )))
    case NI(m1, m2) if negate =>
      // not NI(m1,m2) def= (not I(m1)) or NI(m2,m1)
      // encode with no negation
      encodePred(Or(Not(m1), NI(m2, m1)), traceFn, len, messageTranslator, modelVarMap)
    case LSFalse =>
      mkBoolVal(negate)
    case LSTrue =>
      mkBoolVal(!negate)
  }


  private def allITraceAbs(traceAbstractionSet: Set[AbstractTrace]): Set[I] =
    traceAbstractionSet.flatMap(a => allI(a, includeArrow = true))

  private def allI(pred: LSPred): Set[I] = pred match {
    case i@I(_, _, _) => Set(i)
    case NI(i1, i2) => Set(i1, i2)
    case And(l1, l2) => allI(l1).union(allI(l2))
    case Or(l1, l2) => allI(l1).union(allI(l2))
    case Not(l) => allI(l)
    case LSTrue => Set()
    case LSFalse => Set()
  }

  private def allI(abs: AbstractTrace, includeArrow: Boolean): Set[I] = abs match {
    case AbstractTrace(pred, i2, mapping) =>
      if (includeArrow)
        allI(pred) ++ i2
      else
        allI(pred)
  }

  /**
   *
   * @param abs               abstraction of trace to encode for the solver
   * @param messageTranslator mapping from I preds to enum elements
   * @param traceFn           solver function from indices to trace messages
   * @param traceLen          total length of trace including arrow constraints
   * @param absUID            optional unique id for model variables to scope properly,
   *                          if none is provided, identity hash code of abs is used
   * @param negate            encode the assertion that traceFn is not in abs,
   *                          note that "mkNot(encodeTraceAbs(..." does not work due to skolomization
   * @return encoded trace abstraction
   */
  def encodeTraceAbs(abs: AbstractTrace, messageTranslator: MessageTranslator, traceFn: T, traceLen: T,
                     absUID: Option[String] = None, negate: Boolean = false)(implicit zctx: C): T = {
    val uniqueAbsId = absUID.getOrElse(System.identityHashCode(abs).toString)

    def ienc(sublen: T, f: LSPred, modelVarsConstraints: Map[String, PureExpr], traceFn: T,
             modelVars: Map[String, T], negate: Boolean): T = {
      val modelConstraints: List[T] = modelVarsConstraints.map {
        case (k, v: PureVar) => mkEq(mkModelVar(k, uniqueAbsId), mkObjVar(v))
        case _ => ???
      }.toList
      mkAnd(
        encodePred(f, traceFn, sublen, messageTranslator, modelVars, negate), mkAnd(modelConstraints))
    }

    // encoding is function of model variables to solver boolean expression T
    val modelVarsToEncoding: Map[String, T] => T = { modelVars =>
      val freshTraceFun = mkFreshTraceFn("arrowtf")
      val beforeIndEq =
        mkForallInt(mkIntVal(-1), traceLen, i =>
          mkEq(mkTraceConstraint(traceFn, i), mkTraceConstraint(freshTraceFun, i)))
      val (suffixConstraint, endlen) = abs.rightOfArrow.foldLeft((beforeIndEq, traceLen)) {
        case ((acc, ind), i) => (
          mkAnd(acc, assertIAt(ind, i, messageTranslator, freshTraceFun, negated = false, modelVars)),
          mkAdd(ind, mkIntVal(1))
        )
      }
      val absEnc = ienc(endlen, abs.a, abs.modelVars, freshTraceFun, modelVars, negate)
      mkAnd(absEnc, suffixConstraint)
    }

    val allLSVarsInPred = abs.a.lsVar ++ abs.rightOfArrow.flatMap(_.lsVar)
    if(negate)
      allLSVarsInPred.foldLeft(modelVarsToEncoding){
        case(acc,lsVar) => {
          (lsMap:Map[String,T]) => mkForallAddr(lsVar,tMV => acc(lsMap + (lsVar -> tMV)))
        }
      }(Map())
    else{
      val modelVarMap = allLSVarsInPred.map(varname => (varname,mkModelVar(varname,uniqueAbsId))).toMap
      modelVarsToEncoding(modelVarMap)
    }
  }

  protected def mkDistinct(pvList: Iterable[PureVar])(implicit zctx: C): T

  protected def mkDistinctT(tList: Iterable[T])(implicit zctx: C): T

  protected def encodeTypeConsteraints: StateTypeSolving

  protected def persist: ClassHierarchyConstraints

  private implicit val ch = persist

  def toAST(heap: Map[HeapPtEdge, PureExpr])(implicit zctx: C): T = {
    // The only constraint we get from the heap is that domain elements must be distinct
    // e.g. a^.f -> b^ * c^.f->d^ means a^ != c^
    // alternatively a^.f ->b^ * c^.g->d^ does not mean a^!=c^
    val fields = heap.groupBy {
      case (FieldPtEdge(_, fieldName), _) => fieldName
      case (StaticPtEdge(_, _), _) => "@static"
      case (ArrayPtEdge(_, _), _) => "@array"
    }
    val heapAst = fields.foldLeft(mkBoolVal(true)) { (acc, v) =>
      val pvList = v._2.flatMap {
        case (FieldPtEdge(pv, _), _) => Some(pv)
        case (StaticPtEdge(_, _), _) => None
        case (ArrayPtEdge(pv, _), _) => None //TODO: array encoding
      }
      mkAnd(acc, mkDistinct(pvList))
    }
    heapAst
  }

  def getPureValSet(pf:Set[PureConstraint]):Set[PureVal] = {
    pf.flatMap{
      case PureConstraint(lhs:PureVal, _, rhs:PureVal) => Set(lhs,rhs)
      case PureConstraint(_, _, rhs:PureVal) => Set(rhs)
      case PureConstraint(lhs:PureVal, _, _) => Set(lhs)
      case _ => Set()
    }
  }
  def toAST(state: State, typeToSolverConst: Map[Int, T],
            messageTranslator: MessageTranslator,
            maxWitness: Option[Int] = None, encode : StateTypeSolving,
            constMap:Map[PureVal, T])(implicit zctx: C): T = {
    val pure = state.pureFormula
    // typeFun is a function from addresses to concrete types in the program
    val typeFun = createTypeFun()

    // pure formula are for asserting that two abstract addresses alias each other or not
    //  as well as asserting upper and lower bounds on concrete types associated with addresses
    val pureNoTypes = pure.filter {
      case PureConstraint(_, Subtype, _) => throw new IllegalArgumentException()
      case _ => true
    }


    val pureAst = pureNoTypes.foldLeft(mkBoolVal(true))((acc, v) =>
      mkAnd(acc, toAST(v, constMap))
    )

    val typeConstraints = if (encode == SolverTypeSolving) {
      //      // Encode type constraints in Z3
      val typeConstraints = state.typeConstraints.map { case (k, v) => k -> v.getValues }
      //      val typeConstraints = persist.pureVarTypeMap(state)
      mkAnd(typeConstraints.map {
        case (pv, Some(ts)) => mkTypeConstraintForAddrExpr(typeFun, typeToSolverConst, toAST(pv), ts)
        case _ => mkBoolVal(true)
      }.toList)
    } else mkBoolVal(true)

    val heapAst = toAST(state.heapConstraints)

    // Identity hash code of trace abstraction used when encoding a state so that quantifiers are independent
    val stateUniqueID = System.identityHashCode(state).toString

    val tracefun = mkTraceFn(stateUniqueID)
    val len = mkIntVar(s"len_$stateUniqueID") // there exists a finite size of the trace for this state
    val trace = state.traceAbstraction.foldLeft(mkBoolVal(true)) {
      case (acc, v) => mkAnd(acc, encodeTraceAbs(v, messageTranslator,
        traceFn = tracefun, traceLen = len))
    }
    val out = mkAnd(mkAnd(mkAnd(pureAst, heapAst), trace), typeConstraints)
    maxWitness.foldLeft(out) { (acc, v) => mkAnd(mkLt(len, mkIntVal(v)), acc) }
  }

  case class MessageTranslator(states: List[State])(implicit zctx: C) {
    private val alli = allITraceAbs(states.flatMap(_.traceAbstraction).toSet)
    private val inameToI: Map[String, Set[I]] = alli.groupBy(_.identitySignature)
    private val inamelist = "OTHEROTHEROTHER" :: inameToI.keySet.toList
    private val iNameIntMap: Map[String, Int] = inamelist.zipWithIndex.toMap
    private val enum = mkEnum("inames", inamelist)

    def enumFromI(m: I): T = mkIName(enum, iNameIntMap(m.identitySignature))

    def getEnum: T = enum

    def nameFun: T = mkINameFn(enum)

    def iForMsg(m: TMessage): Option[I] = {
      val possibleI = alli.filter(ci => ci.contains(ci.mt,m.fwkSig.get))
//        ci.signatures.matches(m.fwkSig.get) && ci.mt == m.mType)
      assert(possibleI.size < 2)
      possibleI.headOption
    }

    def iForZ3Name(z3Name: String): Set[I] = {
      inameToI.getOrElse(z3Name, Set())
    }

  }

  /**
   * Remove all pure variables that are not reachable from a local, heap edge, or trace constraint
   * TODO: note that this could be optimized by dropping pure variables that are not reachable from a framework "registered" set when quiescent
   * @param state
   * @return
   */
  def gcPureVars(state:State):State = {
    val allPv = state.pureVars()

    // marked pure vars
    val localPVs = state.callStack.flatMap{
      case CallStackFrame(_, _, locals) => locals.collect{case (_,pv:PureVar) => pv}
    }.toSet
    val heapPVs = state.heapConstraints.flatMap{
      case (FieldPtEdge(pv1, _), pv2) => Set(pv1,pv2)
      case (StaticPtEdge(_,_), pv) => Set(pv)
      case (ArrayPtEdge(pv1,pv2),pv3) => Set(pv1,pv2,pv3)
    }.toSet
    val tracePVs = state.traceAbstraction.flatMap{
      case AbstractTrace(_, _, modelVars) => modelVars.collect{case (_, pv: PureVar) => pv}
    }
    val markedSet = localPVs ++ heapPVs ++ tracePVs
    // TODO: Currently, pv contains no relations that requires the "mark" phase of
    //  mark and sweep so we just move to sweep.
    //  all sources of heap edges are part of the root set since the framework may point to them.
    if (allPv == markedSet){
      state
    }else {
      state.copy(sf = state.sf.copy(pureFormula = state.pureFormula.filter{
        case PureConstraint(lhs:PureVar, NotEquals, _) if !markedSet.contains(lhs) =>
          false
        case PureConstraint(_, NotEquals, rhs:PureVar) if !markedSet.contains(rhs) =>
          false
        case PureConstraint(lhs, _, rhs) => markedSet.contains(lhs) || markedSet.contains(rhs)
        case _ => true
      }, typeConstraints = state.typeConstraints.filter{
        case (pv,_) => markedSet.contains(pv)
      }))
    }
  }

  def reduceStatePureVars(state: State): Option[State] = {
    assert(state.isSimplified, "reduceStatePureVars must be called on feasible state.")
    // TODO: test for trace enforcing that pure vars are equivalent
    def mergePV(state:State,oldPv:PureVar, newPv:PureVar):Option[State] = {
      val pv1tc = state.typeConstraints.get(oldPv)
      val pv2tc = state.typeConstraints.get(newPv)
      val joinedTc = (pv1tc,pv2tc) match{
        case (Some(tc),None) => Some(tc)
        case (None,Some(tc)) => Some(tc)
        case (None,None) => None
        case (Some(tc1),Some(tc2)) =>
          Some(tc1.intersect(tc2))
      }
      joinedTc match{
        case Some(tc) if tc.isEmpty => None
        case Some(tc) => Some(state.swapPv(oldPv,newPv).addTypeConstraint(newPv,tc)) //.copy(typeConstraints = state.typeConstraints + (newPv -> tc)))
        case None => Some(state.swapPv(oldPv,newPv))
      }
    }
    def containsEq(state: State):Boolean = {
      state.pureFormula.exists{
        case PureConstraint(lhs:PureVar, Equals, rhs:PureVar) => true
        case _ => false
      }
    }
    def existsNegation(pc:PureConstraint, state:State):Boolean = pc match{
      case PureConstraint(lhs, Equals, rhs) => state.pureFormula.exists{
        case PureConstraint(lhs1, NotEquals, rhs1) if lhs == lhs1 && rhs == rhs1 => true
        case PureConstraint(lhs1, NotEquals, rhs1) if lhs == rhs1 && rhs == lhs1 => true
        case _ => false
      }
      case PureConstraint(lhs, NotEquals, rhs) => state.pureFormula.exists{
        case PureConstraint(lhs1, Equals, rhs1) if lhs == lhs1 && rhs == rhs1 => true
        case PureConstraint(lhs1, Equals, rhs1) if lhs == rhs1 && rhs == lhs1 => true
        case _ => false
      }
    }
    // Iterate until all equal variables are cleared
    var swappedForSmallerPvOpt:Option[State] = Some(state)

    while(swappedForSmallerPvOpt.isDefined && containsEq(swappedForSmallerPvOpt.get)) {
      swappedForSmallerPvOpt = {
        swappedForSmallerPvOpt.get.pureFormula.foldLeft(Some(swappedForSmallerPvOpt.get): Option[State]) {
          case (Some(acc), pc@PureConstraint(v1@PureVar(id1), Equals, v2@PureVar(id2))) if id1 < id2 =>
            if (existsNegation(pc, acc)) {
              None
            } else {
//              val res = mergePV(acc.copy(pureFormula = acc.pureFormula - pc), v2, v1)
              val res = mergePV(acc.removePureConstraint(pc),v2,v1)
              res
            }
          case (Some(acc), pc@PureConstraint(v1@PureVar(id1), Equals, v2@PureVar(id2))) if id1 > id2 =>
            if (existsNegation(pc, acc)) {
              None
            } else {
//              val res = mergePV(acc.copy(pureFormula = acc.pureFormula - pc), v1, v2)
              val res = mergePV(acc.removePureConstraint(pc), v1,v2)
              res
            }
          case (Some(acc), pc@PureConstraint(PureVar(id1), Equals, PureVar(id2))) if id1 == id2 =>
//            Some(acc.copy(pureFormula = acc.pureFormula - pc))
            Some(acc.removePureConstraint(pc))
          case (acc, _) =>
            acc
        }
      }
    }

    if(swappedForSmallerPvOpt.isEmpty)
      return None
    val swappedForSmallerPv = swappedForSmallerPvOpt.get
    val allPv = swappedForSmallerPv.pureVars()
    val out = if (allPv.nonEmpty) {
      val maxPvValue = allPv.map { case PureVar(id) => id }.max
      swappedForSmallerPv.copy(nextAddr = maxPvValue + 1)
    } else
      swappedForSmallerPv
    Some(out)
  }

  def simplify(state: State, maxWitness: Option[Int] = None): Option[State] = {
    implicit val zctx = getSolverCtx
    if (state.isSimplified) Some(state) else {
      // Drop useless constraints
      val state2 = state.copy(sf = state.sf.copy(pureFormula = state.pureFormula.filter {
        case PureConstraint(v1, Equals, v2) if v1 == v2 => false
        case _ => true
      }))
      // If no type possible for a pure var, state is not feasible
      val pvMap2: Map[PureVar, TypeSet] = state.typeConstraints
      if (pvMap2.exists{ a => a._2.isEmpty }) {
        return None
      }
      push()
      val messageTranslator = MessageTranslator(List(state2))

      // Only encode types in Z3 for subsumption check due to slow-ness
      val encode = SetInclusionTypeSolving
//      val encode = SolverTypeSolving
      val (typesAreUniqe, typeMap) =if(encode == SolverTypeSolving) {
        val usedTypes = pvMap2.flatMap { case (_, tc) => tc.getValues.getOrElse(Set()) }.toSet
        mkTypeConstraints(usedTypes)
      }else {
        val typesAreUniqe = mkBoolVal(true)
        val typeMap = Map[Int, T]()
        (typesAreUniqe,typeMap)
      }

      val (uniqueConst, constMap) = mkConstConstraintsMap(getPureValSet(state2.pureFormula))
      val ast = mkAnd(uniqueConst,
        mkAnd(typesAreUniqe,
          toAST(state2, typeMap, messageTranslator, maxWitness, encode,constMap)))

      if (maxWitness.isDefined) {
        println(s"State ${System.identityHashCode(state2)} encoding: ")
        println(ast.toString)
      }
      val simpleAst = solverSimplify(ast, state2, messageTranslator, maxWitness.isDefined)

      pop()
      if(simpleAst.isEmpty)
        None
      else {
        val reducedState = if(encode == SetInclusionTypeSolving)
          reduceStatePureVars(state2.setSimplified()).map(gcPureVars)
        else Some(state2)
        reducedState.map(_.setSimplified())
      }
    }
  }

  // TODO: call stack is currently just a list of stack frames, this needs to be updated when top is added
  def stackCanSubsume(cs1: List[CallStackFrame], cs2: List[CallStackFrame]): Boolean = (cs1, cs2) match {
    case (CallStackFrame(ml1, _, locals1) :: t1, CallStackFrame(ml2, _, locals2) :: t2) if ml1 == ml2 =>
      locals1.forall { case (k, v) => locals2.get(k).contains(v) } &&
        stackCanSubsume(t1, t2)
    case (Nil, Nil) => true
    case _ => false
  }

  private def filterTypeConstraintsFromPf(pure: Set[PureConstraint]): Set[PureConstraint] = pure.filter {
    case PureConstraint(_, Subtype, _) => throw new IllegalStateException("TODO: remove TypeComp")
    case _ => true
  }


  def canSubsume(s1: State, s2: State, maxLen: Option[Int] = None): Boolean ={
    canSubsumeSlow(s1, s2, maxLen)
  }

  /**
   * Consider rearrangments of pure vars that could result in subsumption
   *
   * @param s1 subsuming state
   * @param s2 contained state
   * @return
   */
  def canSubsumeSlow(s1: State, s2: State, maxLen: Option[Int] = None): Boolean = {
    // Check if stack sizes or locations are different
    if (s1.callStack.size != s2.callStack.size)
      return false

    val stackLocsMatch = (s1.callStack zip s2.callStack).forall {
      case (fr1, fr2) => fr1.exitLoc == fr2.exitLoc
    }
    if (!stackLocsMatch)
      return false

    // Check if more fields are defined in the subsuming state (more fields means can't possibly subsume)
    def fieldNameCount(s: State): Map[String, Int] = {
      val fieldSeq: immutable.Iterable[String] = s.heapConstraints.collect {
        case (FieldPtEdge(_, name), _) => name
      }
      fieldSeq.groupBy(l => l).map(t => (t._1, t._2.size))
    }

    val s1FieldCount: Map[String, Int] = fieldNameCount(s1)
    val s2FieldCount: Map[String, Int] = fieldNameCount(s2)
    val s1FieldSet = s1FieldCount.keySet
    val s2FieldSet = s2FieldCount.keySet
    if (s1FieldSet.size > s2FieldSet.size)
      return false

    if (s1FieldSet.exists(fld => s1FieldCount(fld) > s2FieldCount.getOrElse(fld, -1)))
      return false



    //TODO: below is brute force approach to see if considering pure var rearrangments fixes the issue
    val pv1: Set[PureVar] = s1.pureVars()
    val pv2: Set[PureVar] = s2.pureVars()
    val allPv = pv1.union(pv2).toList
    // map s2 to something where all pvs are strictly larger
    val maxID = if (allPv.nonEmpty) allPv.maxBy(_.id) else PureVar(5)


    // Swap all pureVars to pureVars above the max found in either state
    // This way swapping doesn't interfere with itself
    val (s2Above, pvToTemp) = pv2.foldLeft((s2.copy(nextAddr = maxID.id + 1), Set[PureVar]())) {
      case ((st, nl), pv) =>
        val (freshPv, st2) = st.nextPv()
        (st2.swapPv(pv, freshPv), nl + freshPv)
    }

    // swap shared locals
    def levelLocalPv(s:State):Map[(String,Int), PureVar] = {
      s.callStack.zipWithIndex.flatMap{
        case (CallStackFrame(_,_,locals), level) =>
          locals.collect{case (StackVar(name),pValue:PureVar) => ((name,level),pValue)}
      }.toMap
    }
    val s1LocalMap = levelLocalPv(s1)
    val s2LocalMap = levelLocalPv(s2Above)
    val overlap = s1LocalMap.keySet.intersect(s2LocalMap.keySet)

    // Create mapping that must exist if subsumption is to occur, none if not feasible
    val s2LocalSwapMapOpt = overlap.foldLeft(Some(Map()):Option[Map[PureVar,PureVar]]){
      case (None,_) => None
      case (Some(acc),v) => {
        val s1Val = s1LocalMap(v)
        val s2Val = s2LocalMap(v)
        if(acc.contains(s1Val) && (acc(s1Val) != s2Val)){
          // Cannot subsume if two locals in subsuming state map to the same pure var and the subsumee maps to different
          // e.g.
          // s1: foo -> a * bar -> b * baz -> b
          // s2: foo -> c * bar -> b * baz -> a
          // s1 cannot subsume s2 since the heap `foo -> @1, bar -> @2, baz -> @3` is in s2 but not s1
          None
        }else{
          Some(acc + (s1Val -> s2Val))
        }
      }
    }

    if(s2LocalSwapMapOpt.isEmpty)
      return false

    val s2LocalSwapped = s2LocalSwapMapOpt.get.foldLeft(s2Above){
      case (acc,(newPv,oldPv)) => acc.swapPv(oldPv, newPv)
    }
    val removeFromPermS1 = overlap.map(k => s1LocalMap(k))
//    val allPvNoLocals = allPv.filter(v => !removeFromPerm.contains(v))
    val removeFromPermS2 = overlap.map(k => s2LocalMap(k))



    //TODO: extremely slow permutations there is probably a better way
    val startTime = System.currentTimeMillis()
    //filter out pure var pairings early if they can't be subsumed type wise
    def canMap(pv1:PureVar, pv2:PureVar):Boolean = {
      // Check if pv mapping is possible from types
      val tc1 = s1.typeConstraints.get(pv1)
      val tc2 = s2Above.typeConstraints.get(pv2)
      if(tc2.isEmpty)
        return true
      val typeCanSubs = tc1.forall(_.contains(tc2.get))

      def firstConstConstraint(pv : PureVar, s:State):Option[PureVal] = {
        s.pureFormula.collectFirst{
          case PureConstraint(lhs, Equals, pval:PureVal) if lhs == pv => pval
        }
      }
      val cc1 = firstConstConstraint(pv1, s1)
      val cc2 = firstConstConstraint(pv2, s2Above)
      val constCanSub = (cc1,cc2) match{
        case (None,None) => true
        case (None,Some(_)) => true
        case (Some(TopVal), _) => true
        case (Some(v1), Some(v2)) => v1 == v2
        case (Some(_), None) => false
      }

      typeCanSubs && constCanSub
    }

    //all possible renamings of variables
    val perm = BounderUtil.allMap(pv1 -- removeFromPermS1,pvToTemp -- removeFromPermS2,canMap)
    val noRenamingIfEmpty = if(perm.isEmpty) Map()::Nil else perm
//    val perm = allPvNoLocals.permutations.grouped(40)
    //TODO: add par back in?
    val out: Boolean = noRenamingIfEmpty.exists{perm =>
      val s2Swapped = perm.foldLeft(s2LocalSwapped) {
        case (s, (newPv, oldPv)) => s.swapPv(oldPv, newPv)
      }

//      //TODO: dbg code =============
//      if(simplify(s2Swapped).isEmpty){
//        println("===========")
//      }
//      //end dbg code

      val out1 = canSubsumeNoCombinations(s1, s2Swapped, maxLen)
      out1
    }
    val elapsedTime = System.currentTimeMillis() - startTime
    if(elapsedTime > 1000) {
      println(s"Subsumption time: $elapsedTime pvCount: ${allPv.size} Subsumption result: $out")
      println(s"  state1: $s1")
      println(s"  state2: $s2")
    }

//    val normalCanSubsume = canSubsumeFast(s1,s2,maxLen)
//    if (!out && normalCanSubsume) {
//      println()
//    }
    out
  }

  /**
   * Check if formula s2 is entirely contained within s1.  Used to determine if subsumption is sound.
   * Does not consider re-ordering of pure variables
   *
   * @param s1 subsuming state
   * @param s2 contained state
   * @return false if there exists a trace in s2 that is not in s1 otherwise true
   */
  def canSubsumeNoCombinations(s1: State, s2: State, maxLen: Option[Int] = None): Boolean = {
    implicit val zctx = getSolverCtx

    // Currently, the stack is strictly the app call string
    // When adding more abstraction to the stack, this needs to be modified
    if (!stackCanSubsume(s1.callStack, s2.callStack)) {
      //      logger.info(s"Stack no subsume STATE1: $s1  STATE2: $s2")
      return false
    }
    if (!s1.heapConstraints.forall { case (k, v) => s2.heapConstraints.get(k).contains(v) }) {
      //      logger.info(s"Heap no subsume STATE1: $s1  STATE2: $s2")
      //      dbg(s1,s2)
      return false
    }
    // TODO: if tc says two things can't be equal then smt formula says they can't be equal

    // check if each pure var in the subsuming state represents a greater than or equal set of types
    if (encodeTypeConsteraints == SetInclusionTypeSolving) {
      val s1TypeMap = s1.typeConstraints
      val s2TypeMap = s2.typeConstraints
      val typesSubsume = s1TypeMap.forall { case (pv, ts) =>
        val opts2Val = s2TypeMap.get(pv)
        opts2Val.forall(ts.contains) //s1 type set contains s2 type set and pv must exist in s2
      }
      //        s1TypeMap.keySet.forall(pv => s2TypeMap.get(pv).exists(tset => s1TypeMap(pv).union(tset) == s1TypeMap(pv)))
      if (!typesSubsume) {
        return false
      }
    }

    push()

    val pureFormulaEnc = {

      val (negTC1, tC2, uniqueType) = if (true || encodeTypeConsteraints == SolverTypeSolving) {
        val typeFun = createTypeFun()
        val allTypes = List(s1, s2).flatMap(_.typeConstraints.flatMap { case (_, v) => v.getValues.getOrElse(Set()) }).toSet
        val (uniqueType, typeMap) = mkTypeConstraints(allTypes)
        val state1Types = s1.typeConstraints.flatMap { case (pv, v) =>
          val typeValues = v.getValues
          if(typeValues.isDefined)
            Some(mkTypeConstraintForAddrExpr(typeFun, typeMap, toAST(pv), typeValues.get))
          else None
        }
        val state2Types = s2.typeConstraints.flatMap { case (pv, v) =>
          val typeValues = v.getValues
          if(typeValues.isDefined)
            Some(mkTypeConstraintForAddrExpr(typeFun, typeMap, toAST(pv), typeValues.get))
          else None
        }
        //        val state1Types = persist.pureVarTypeMap(s1).map{
        //          case (pv,ts) => mkTypeConstraint(typeFun, toAST(pv), ts)
        //        }
        //        val state2Types = persist.pureVarTypeMap(s2).map{
        //          case (pv,ts) => mkTypeConstraint(typeFun, toAST(pv), ts)
        //        }
        val notS1TypesEncoded = state1Types.foldLeft(mkBoolVal(false)) {
          (acc, v) => mkOr(acc, mkNot(v))
        }
        val s2TypesEncoded = state2Types.foldLeft(mkBoolVal(true)) {
          (acc, v) => mkAnd(acc, v)
        }
        (notS1TypesEncoded, s2TypesEncoded, uniqueType)
      } else (mkBoolVal(false), mkBoolVal(true), mkBoolVal(true))

      val s1pf = filterTypeConstraintsFromPf(s1.pureFormula)
      val s2pf = filterTypeConstraintsFromPf(s2.pureFormula)

      val (uniqueConst, constMap) = mkConstConstraintsMap(getPureValSet(s1pf ++ s2pf))

      // Pure formula that are not type constraints
      val negs1pure = s1pf.foldLeft(negTC1) {
        case (acc, constraint) => mkOr(mkNot(toAST(constraint, constMap)), acc)
      }

      val s2pure = s2pf.foldLeft(tC2) {
        case (acc, constraint) => mkAnd(toAST(constraint, constMap), acc)
      }

      (negs1pure, s2pure, uniqueType, uniqueConst)
    }

    val messageTranslator = MessageTranslator(List(s1, s2))
    val len = mkIntVar(s"len_")
    val traceFun = mkTraceFn("0")

    //TODO: Does this encode distinct constraint on dom of memory abstraction?
    val phi = s2.traceAbstraction.foldLeft(pureFormulaEnc._2) {
      case (acc, v) => mkAnd(acc, encodeTraceAbs(v, messageTranslator,
        traceFn = traceFun, len, absUID = Some("0")))
    }
    val negPhi = s1.traceAbstraction.foldLeft(pureFormulaEnc._1) {
      case (acc, v) => mkOr(acc, encodeTraceAbs(v, messageTranslator,
        traceFn = traceFun, len, absUID = Some("1"), negate = true))
    }

    val fp = mkAnd(
      negPhi,
      phi)
    // limit trace length for debug
    val f = maxLen match {
      case Some(v) =>
        // Print formula when debug mode enabled
        println(s"formula:\n $fp")
        mkAssert(mkLt(len, mkIntVal(v)))
        mkAnd(mkLt(len, mkIntVal(v)), fp)
      case None => fp
    }
    // pureFormulaEnc._3 is assertion that all types are unique
    // pureFormulaEnc._4 is unique const assertion
//    mkAssert(mkAnd(pureFormulaEnc._4 ,mkAnd(pureFormulaEnc._3, f)))
    mkAssert(pureFormulaEnc._4)
    mkAssert(pureFormulaEnc._3)
    mkAssert(negPhi)
    mkAssert(phi)

    val ti = checkSAT()
    if (ti && maxLen.isDefined) {
      println(s"===formula: $f")
      printDbgModel(messageTranslator, s1.traceAbstraction.union(s2.traceAbstraction), "")
    }
    pop()
    //    if(ti){
    //      logger.info(s"Pure or Trace no subsume STATE1: " +
    //        s"$s1  " +
    //        s"STATE2: $s2")
    //    }else{
    //      logger.info(s"Subsumed STATE1: " +
    //        s"${s1.toString.replace("\n"," ")}  " +
    //        s"STATE2: ${s2.toString.replace("\n"," ")}")
    //    }
    !ti
  }

  def encodeTrace(traceFN: T, trace: List[TMessage],
                  messageTranslator: MessageTranslator, valToT: Map[Int, T])(implicit zctx: C): T = {
    val assertEachMsg: List[T] = trace.zipWithIndex.flatMap {
      case (m, ind) =>
        val msgExpr = mkTraceConstraint(traceFN, mkIntVal(ind))
        val i = messageTranslator.iForMsg(m)
        val argConstraints: List[T] = m.args.zipWithIndex.map {
          case (TAddr(addr), ind) => mkEq(mkArgConstraint(mkArgFun(), mkIntVal(ind), msgExpr), valToT(addr))
          case (TNullVal, _) => ???
        }
        i.map(ii => {
          mkAnd(assertIAt(mkIntVal(ind), ii, messageTranslator, traceFN, negated = false, s => mkModelVar(s, "")),
            mkAnd(argConstraints)
          )
        })
    }
    assertEachMsg.foldLeft(mkBoolVal(true))((a, b) => mkAnd(a, b))
  }

  def witnessed(state: State): Boolean = {
    implicit val zctx = getSolverCtx
    if (state.heapConstraints.nonEmpty)
      return false
    if (state.callStack.nonEmpty)
      return false
    if (!traceInAbstraction(state, Nil))
      return false
    true
  }

  def traceInAbstraction(state: State, trace: List[TMessage], debug: Boolean = false)(implicit zctx: C): Boolean = {
    push()
    val messageTranslator = MessageTranslator(List(state))
    val assert = encodeTraceContained(state, trace, messageTranslator)
    mkAssert(assert)
    val sat = checkSAT()
    if (sat && debug) {
      println(s"model:\n $assert")
      printDbgModel(messageTranslator, state.traceAbstraction, "")
    }
    pop()
    sat
  }

  private def encodeTraceContained(state: State, trace: List[TMessage],
                                   messageTranslator: MessageTranslator)(implicit zctx: C): T = {
    val traceFn = mkTraceFn("")
    val len = mkIntVar(s"len_")
    val encodedAbs: Set[T] =
      state.traceAbstraction.map(encodeTraceAbs(_, messageTranslator, traceFn, len, Some("")))
    val pf = filterTypeConstraintsFromPf(state.pureFormula)

    val (uniqueConst, constMap) = mkConstConstraintsMap(getPureValSet(pf))
    val s2pure = pf.foldLeft(mkBoolVal(true)) {
      case (acc, constraint) => mkAnd(toAST(constraint,constMap), acc)
    }

    val distinctAddr: Map[Int, T] = (0 until 10).map(v => (v, mkAddrConst(v))).toMap
    val assertDistinct = mkDistinctT(distinctAddr.keySet.map(distinctAddr(_)))
    val encodedTrace = encodeTrace(traceFn, trace, messageTranslator, distinctAddr)
    mkAnd(uniqueConst, mkAnd(mkEq(len, mkIntVal(trace.length)),
      mkAnd(encodedAbs.foldLeft(mkAnd(assertDistinct, s2pure))((a, b) => mkAnd(a, b)), encodedTrace))
    )
  }

}
package edu.colorado.hopper.solver

import edu.colorado.plv.bounder.lifestate.LifeState
import edu.colorado.plv.bounder.lifestate.LifeState.{And, I, LSAbsBind, LSPred, NI, Not, Or}
import edu.colorado.plv.bounder.symbolicexecutor.state._

trait Assumptions

class UnknownSMTResult(msg : String) extends Exception(msg)

/** SMT solver parameterized by its AST or expression type */
trait StateSolver[T] {
  // checking
  def checkSAT : Boolean
  def checkSATWithAssumptions(assumes : List[String]) : Boolean

  def getUNSATCore : String
  def push() : Unit
  def pop() : Unit

  // cleanup
  def dispose() : Unit
  // conversion from pure constraints to AST type of solver (T)
//  def mkAssert(p : PureConstraint) : Unit = mkAssert(toAST(p))
//  def mkAssertWithAssumption(assume : String, p : PureConstraint) : Unit = mkAssert(mkImplies(mkBoolVar(assume), toAST(p)))

  // comparison operations
  protected def mkEq(lhs : T, rhs : T) : T
  protected def mkNe(lhs : T, rhs : T) : T
  protected def mkGt(lhs : T, rhs : T) : T
  protected def mkLt(lhs : T, rhs : T) : T
  protected def mkGe(lhs : T, rhs : T) : T
  protected def mkLe(lhs : T, rhs : T) : T

  // logical and arithmetic operations
  protected def mkNot(o : T) : T
  protected def mkAdd(lhs : T, rhs : T) : T
  protected def mkSub(lhs : T, rhs : T) : T
  protected def mkMul(lhs : T, rhs : T) : T
  protected def mkDiv(lhs : T, rhs : T) : T
  protected def mkRem(lhs : T, rhs : T) : T
  protected def mkAnd(lhs : T, rhs : T) : T
  protected def mkOr(lhs : T, rhs : T) : T
  protected def mkXor(lhs : T, rhs : T) : T

  // creation of variables, constants, assertions
  protected def mkIntVal(i : Int) : T
  protected def mkBoolVal(b : Boolean) : T
  protected def mkIntVar(s : String) : T
  protected def mkBoolVar(s : String) : T
  protected def mkObjVar(s:PureVar) : T //Symbolic variable
  protected def mkModelVar(s:String, pred:TraceAbstraction):T // variable in ls rule
  protected def mkAssert(t : T) : Unit
  protected def mkFieldFun(n: String): T
  protected def fieldEquals(fieldFun: T, t1 : T, t2: T):T
  protected def solverSimplify(t: T): Option[T]
  protected def mkTypeConstraint(typeFun: T, addr: T, tc: TypeConstraint):T
  protected def createTypeFun():T
  protected def mkINIFun(arity:Int, sig:String):T
  protected def mkINIConstraint(fun: T, modelVars: List[T]):T

  def toAST(p : PureConstraint, typeFun: T) : T = p match {
      // TODO: field constraints based on containing object constraints
    case PureConstraint(lhs: PureVar, TypeComp, rhs:TypeConstraint) =>
      mkTypeConstraint(typeFun, toAST(lhs), rhs)
    case PureConstraint(lhs, op, rhs) =>
      toAST(toAST(lhs), op, toAST(rhs))
    case _ => ???
  }
  def toAST(p : PureExpr) : T = p match {
    case p:PureVar => mkObjVar(p)
    case NullVal => mkIntVal(0)
    case ClassType(t) => ??? //handled at a higher level
    case _ =>
      ???
  }
  def toAST(lhs : T, op : CmpOp, rhs : T) : T = op match {
    case Equals => mkEq(lhs,rhs)
    case NotEquals => mkNe(lhs, rhs)
    case _ =>
      ???
  }

  def encodePred(combinedPred: LifeState.LSPred, abs: TraceAbstraction): T = combinedPred match{
    case And(l1,l2) => mkAnd(encodePred(l1,abs),encodePred(l2,abs))
    case LSAbsBind(k,v:PureVar) => mkEq(mkModelVar(k,abs), mkObjVar(v))
    case Or(l1, l2) => mkOr(encodePred(l1,abs), encodePred(l2,abs))
    case Not(l) => mkNot(encodePred(l,abs))
    case i@I(_,_, lsVars) => {
      val ifun = mkINIFun(lsVars.count(_ != "_"), i.identitySignature)
      mkINIConstraint(ifun, lsVars.map(mkModelVar(_,abs)))
    }
    case ni@NI(i1, i2) => {
      val args = i1.lsVar.union(i2.lsVar).toList.sorted
      val ifun = mkINIFun(args.size, ni.identitySignature)
      mkINIConstraint(ifun, args.map(mkModelVar(_,abs)))
    }
    case _ =>
      ???
  }

  def toAST(state: State): T = {
    // TODO: make all variables in this encoding unique from other states so multiple states can be run at once
    // TODO: add ls constraints to state
    // TODO: mapping from ? constraints to bools that can be retrieved from the model after solving
    val heap = state.heapConstraints
    val pure = state.pureFormula
    // TODO: handle static fields
    // typeFun is a function from addresses to concrete types in the program
    val typeFun = createTypeFun()

    // pure formula are for asserting that two abstract addresses alias each other or not
    //  as well as asserting upper and lower bounds on concrete types associated with addresses
    val pureAst = pure.foldLeft(mkBoolVal(true))((acc, v) =>
      mkAnd(acc, toAST(v, typeFun))
    )

    // Non static fields are modeled by a function from int to int.
    // A function is created for each fieldname.
    // For a constraint a^.f -> b^, it is asserted that field_f(a^) == b^
    val fields = heap.groupBy({ case (FieldPtEdge(_, fieldName), _) => fieldName })
    val heapAst = fields.foldLeft(mkBoolVal(true)) {
      case (acc, (field, heapConstraints)) => {
        val fieldFun = mkFieldFun(s"field_${field}")
        heapConstraints.foldLeft(acc) {
          case (acc, (FieldPtEdge(p, _), tgt)) =>
            mkAnd(acc, fieldEquals(fieldFun, toAST(p), toAST(tgt)))
        }
      }
    }

    val trace = state.traceAbstraction.foldLeft(mkBoolVal(true)) {
      case (acc, abs@LSAbstraction(pred, bind)) => {
        val combinedPred = bind.foldLeft(pred) { case (acc2, (k, v)) => And(LSAbsBind(k, v),acc2) }
        mkAnd(acc,encodePred(combinedPred,abs))
      }
      case _ =>
        ???
    }
    mkAnd(mkAnd(pureAst, heapAst),trace)
  }

  def simplify(state: State): Option[State] = {
    push()
    val ast = toAST(state)
    println(ast.toString)
    val simpleAst = solverSimplify(ast)

    pop()
    // TODO: garbage collect, if purevar can't be reached from reg or stack var, discard
    simpleAst.map(_ => state) //TODO: actually simplify?
  }
}
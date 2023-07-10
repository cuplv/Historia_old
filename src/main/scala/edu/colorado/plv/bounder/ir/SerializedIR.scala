package edu.colorado.plv.bounder.ir

import edu.colorado.plv.bounder.lifestate.SpecSpace
import edu.colorado.plv.bounder.solver.ClassHierarchyConstraints
import edu.colorado.plv.bounder.symbolicexecutor.AppCodeResolver
import upickle.default.{macroRW, ReadWriter => RW}

import scala.collection.BitSet

//TODO: serialize IR and points to, This should be able to continue where other left off
class TestIR(transitions: Set[TestTransition]) extends IRWrapper[String,String] {
  override def findMethodLoc(className: String, methodName: String): Iterable[MethodLoc] = ???

  override def findLineInMethod(className: String, methodName: String, line: Int): Iterable[AppLoc] = ???

  override def commandPredecessors(cmdWrapper: CmdWrapper): List[AppLoc] = ???

  override def commandNext(cmdWrapper: CmdWrapper): List[AppLoc] = ???

  override def isMethodEntry(cmdWrapper: CmdWrapper): Boolean = ???

  override def cmdAtLocation(loc: AppLoc): CmdWrapper = {
    transitions.find( t => t match{
      case CmdTransition(l1,_,l2) if (l2 == loc || l1 == loc) => true
      case _ => false
      }
    ).map(_.asInstanceOf[CmdTransition].cmd).get
  }

  override def makeInvokeTargets(invoke: AppLoc): UnresolvedMethodTarget = ???

  // TODO: serialized methods
  override def getAllMethods: Seq[MethodLoc] = Seq()

  override def getOverrideChain(method: MethodLoc): Seq[MethodLoc] = ???

  override def appCallSites(method: MethodLoc, resolver: AppCodeResolver): Seq[AppLoc] = ???

  override def canAlias(type1: String, type2: String): Boolean = true

  override def makeMethodRetuns(method: MethodLoc): List[AppLoc] = ???

  override def makeMethodTargets(source: MethodLoc): Set[MethodLoc] = ???

  override def isSuperClass(type1: String, type2: String): Boolean = ???

  override def degreeOut(cmd: AppLoc): Int = ???

  override def degreeIn(cmd: AppLoc): Int = ???

  override def getInterfaces: Set[String] = ???

  override def isLoopHead(cmd: AppLoc): Boolean = ???

  override def commandTopologicalOrder(cmdWrapper: CmdWrapper): Int = ???

  override def pointsToSet(loc: MethodLoc, local: LocalWrapper): TypeSet = TopTypeSet

  override def getThisVar(methodLoc: Loc): Option[LocalWrapper] = None

  override def getThisVar(methodLoc: MethodLoc): Option[LocalWrapper] = ???

  override def getClassHierarchyConstraints: ClassHierarchyConstraints = ???

  override def findInMethod(className: String, methodName: String, toFind: CmdWrapper => Boolean): Iterable[AppLoc] = ???

  override def appCallbackMsgCount: Int = ???
}

/**
 *
 * @param clazz
 * @param name
 * @param args use "_" for receiver on static method
 */
case class TestIRMethodLoc(clazz:String, name:String, args:List[Option[LocalWrapper]]) extends MethodLoc {
  override def simpleName: String = name

  override def classType: String = clazz

  override def argTypes: List[String] = args.map(_.map(_.localType).getOrElse("void"))

  /**
   * None for return if void
   * None for reciever if static
   *
   * @return list of args, [return,reciever, arg1,arg2 ...]
   */
  override def getArgs: List[Option[LocalWrapper]] = args.map{
    case Some(LocalWrapper("_", _)) => None
    case Some(local@LocalWrapper(_, _)) => Some(local)
    case None => None
  }

  override def isStatic: Boolean = ???

  override def isInterface: Boolean = ???

  override def bodyToString: String = ???
}

object TestIRMethodLoc{
  implicit val rw = upickle.default.readwriter[ujson.Value].bimap[MethodLoc](
    x =>
      ujson.Arr(x.simpleName, x.classType, x.argTypes),
    json =>
      TestIRMethodLoc(json(0).str, json(1).str, ???)
  )
}


case class TestIRLineLoc(line:Int, desc:String = "") extends LineLoc {
  override def toString: String = if(desc == "") line.toString else desc

  override def lineNumber: Int = line

  override def containingMethod: MethodLoc = ???

  override def isFirstLocInMethod: Boolean = ???
}
object TestIRLineLoc{
  implicit val rw:RW[TestIRMethodLoc] = macroRW
}

sealed trait TestTransition
case class CmdTransition(loc1: Loc, cmd:CmdWrapper , loc2:Loc) extends TestTransition
case class MethodTransition(loc1:Loc, loc2:Loc) extends TestTransition

case class TestMethod(methodLoc: TestIRMethodLoc, cmds: List[CmdWrapper])
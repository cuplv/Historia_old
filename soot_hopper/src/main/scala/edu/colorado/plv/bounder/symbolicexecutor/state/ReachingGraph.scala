package edu.colorado.plv.bounder.symbolicexecutor.state
import edu.colorado.plv.bounder.ir.{Loc, MethodLoc}
import javax.naming.InitialContext
import slick.jdbc.SQLiteProfile.api._
import soot.jimple.parser.node.AEmptyMethodBody

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import upickle.default.{macroRW, ReadWriter => RW}
import upickle.default.{read, write}

import scala.language.postfixOps

trait ReachingGraph {
  def getPredecessors(qry:Qry) : Iterable[Qry]
  def getSuccessors(qry:Qry) : Iterable[Qry]
}

sealed trait OutputMode
case object MemoryOutputMode$ extends OutputMode

case class DBOutputMode(dbfile:String) extends OutputMode{
  private val witnessQry = TableQuery[WitnessTable]
  private val methodQry = TableQuery[MethodTable]
  private val callEdgeQry = TableQuery[CallEdgeTable]
  val db = Database.forURL(s"jdbc:sqlite:$dbfile",driver="org.sqlite.JDBC")
  val setup = DBIO.seq(witnessQry.schema.create,
    methodQry.schema.create,
    callEdgeQry.schema.create)
  Await.result(db.run(setup), 20 seconds)
  private var id = 0
  def nextId:Int = {
    val oId = id
    id = id + 1
    oId
  }
  def writeNode(node: DBPathNode): Unit = {
    val qryState = node.qry match{
      case SomeQry(_,_) => "live"
      case BottomQry(_,_) => "refuted"
      case WitnessedQry(_,_) => "witnessed"
    }
    val writeFuture = db.run(witnessQry +=
      (node.thisID,qryState, write(node.qry.state), write(node.qry.loc),node.subsumedID, node.succID, node.depth))
    Await.result(writeFuture, 20 seconds)
  }
  def readNode(id: Int):IPathNode = {
    val q = witnessQry.filter(_.id === id)
    val qFuture = db.run(q.result)
    val res = Await.result(qFuture, 20 seconds)
    assert(res.size == 1, s"Failed to find unique node id: $id actual size: ${res.size}")
    val queryState: String = res.head._2
    val loc: Loc = read[Loc](res.head._4)
    val subsumingId: Option[Int] = res.head._5
    val pred: Option[Int] = res.head._6
    val state: State = read[State](res.head._3)
    val qry = queryState match{
      case "live" => SomeQry(state,loc)
      case "refuted" => BottomQry(state,loc)
      case "witnessed" => WitnessedQry(state,loc)
    }
    val depth = res.head._7
    DBPathNode(qry,id,pred,subsumingId,depth)
  }
  def writeMethod(method: MethodLoc,isCallback:Boolean):Unit ={
    val writeFuture = db.run(methodQry +=
      (nextId, method.simpleName, method.classType, method.bodyToString,isCallback))
    Await.result(writeFuture, 20 seconds)
  }
  def writeCallEdge(srcName:String, srcClass:String, tgtName:String,tgtClass:String, isCallin:Boolean):Unit = {
    val wf = db.run(callEdgeQry += (nextId, srcName,srcClass,tgtName,tgtClass,isCallin))
    Await.result(wf, 20 seconds)
  }

}
object DBOutputMode{
  implicit val rw:RW[DBOutputMode] = macroRW
}

class WitnessTable(tag:Tag) extends Table[(Int,String,String,String,Option[Int],Option[Int],Int)](tag,"PATH"){
  def id = column[Int]("NODE_ID", O.PrimaryKey)
  def queryState = column[String]("QUERY_STATE")
  def nodeState = column[String]("NODE_STATE")
  def nodeLoc = column[String]("NODE_LOC")
  def subsumingState = column[Option[Int]]("SUBSUMING_STATE")
  def pred = column[Option[Int]]("PRED")
  def depth = column[Int]("DEPTH")
  def * = (id,queryState,nodeState,nodeLoc,subsumingState,pred,depth)
}
class MethodTable(tag:Tag) extends Table[(Int,String,String,String,Boolean)](tag, "Methods"){
  def id = column[Int]("METHOD_ID", O.PrimaryKey)
  def methodName = column[String]("NAME")
  def declaringClass = column[String]("DECLARING_CLASS")
  def methodBody = column[String]("BODY")
  def isCallback = column[Boolean]("IS_CALLBACK")
  def * = (id,methodName,declaringClass,methodBody, isCallback)
}

class CallEdgeTable(tag:Tag) extends Table[(Int,String,String,String,String,Boolean)](tag,"CALL_EDGES"){
  def id = column[Int]("EDGE_ID", O.PrimaryKey)
  def srcName = column[String]("SRC_NAME")
  def srcClass = column[String]("SRC_CLASS")
  def tgtName = column[String]("TGT_NAME")
  def tgtClass = column[String]("TGT_CLASS")
  def isCallin = column[Boolean]("IS_CALLIN")
  def * = (id,srcName,srcClass,tgtName,tgtClass,isCallin)
}

object PathNode{
  def apply(qry:Qry, succ: Option[IPathNode], subsumed: Option[IPathNode])
           (implicit mode: OutputMode = MemoryOutputMode$):IPathNode = {
    val depth = succ.map(_.depth + 1).getOrElse(1)
    mode match {
      case MemoryOutputMode$ =>
        MemoryPathNode(qry, succ, subsumed, depth)
      case m@DBOutputMode(_) =>
        val id = m.nextId
        val succID = succ.map(n => n.asInstanceOf[DBPathNode].thisID)
        val subsumedID = subsumed.map(n => n.asInstanceOf[DBPathNode].thisID)
        val thisNode = DBPathNode(qry, id, succID, subsumedID,depth)
        m.writeNode(thisNode)
        thisNode
    }
  }
  def unapply(node : IPathNode): Option[(Qry, Boolean)] = node match{
    case MemoryPathNode(qry,_,subsumed,_) => Some((qry,subsumed.isDefined))
    case DBPathNode(qry,_, _,subsumedID,_) =>
      Some((qry,subsumedID.isDefined))
  }
}
sealed trait IPathNode{
  def depth:Int
  def qry:Qry
  def succ(implicit mode : OutputMode):Option[IPathNode]
  def subsumed(implicit mode : OutputMode): Option[IPathNode]
  def setSubsumed(v: Option[IPathNode]):IPathNode
}

case class MemoryPathNode(qry: Qry, succV : Option[IPathNode], subsumedV: Option[IPathNode], depth:Int) extends IPathNode {
  override def toString:String = {
    val qrystr = qry.toString
    val succstr = succV.map((a: IPathNode) =>
      a.toString).getOrElse("")
    qrystr + "\n" + succstr
  }

  override def setSubsumed(v: Option[IPathNode]): IPathNode = this.copy(subsumedV = v)

  override def succ(implicit mode: OutputMode): Option[IPathNode] = succV

  override def subsumed(implicit mode: OutputMode): Option[IPathNode] = subsumedV
}

case class DBPathNode(qry:Qry, thisID:Int,
                      succID:Option[Int],
                      subsumedID: Option[Int], depth:Int) extends IPathNode {
  override def succ(implicit db:OutputMode): Option[IPathNode] = succID.map(db.asInstanceOf[DBOutputMode].readNode)

  override def subsumed(implicit db:OutputMode): Option[IPathNode] = subsumedID.map(db.asInstanceOf[DBOutputMode].readNode)

  override def setSubsumed(v: Option[IPathNode]): IPathNode =
    this.copy(subsumedID = v.map(v2 => v2.asInstanceOf[DBPathNode].thisID))
}
object DBPathNode{
  implicit val rw:RW[DBPathNode] = macroRW
}
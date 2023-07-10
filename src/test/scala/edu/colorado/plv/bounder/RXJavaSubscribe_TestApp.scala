import edu.colorado.plv.bounder.BounderUtil
import edu.colorado.plv.bounder.BounderUtil.{Proven, Witnessed}
import edu.colorado.plv.bounder.ir.SootWrapper
import edu.colorado.plv.bounder.lifestate.LifeState.LSFalse
import edu.colorado.plv.bounder.lifestate.{LifecycleSpec, FragmentGetActivityNullSpec, RxJavaSpec, SpecSpace}
import edu.colorado.plv.bounder.solver.ClassHierarchyConstraints
import edu.colorado.plv.bounder.symbolicexecutor.state.{CallinReturnNonNull, PrettyPrinting, Qry}
import edu.colorado.plv.bounder.symbolicexecutor.{CHACallGraph, QueryFinished, SymbolicExecutorConfig, TransferFunctions}
import org.scalatest.funsuite.AnyFunSuite
import soot.SootMethod

class RXJavaSubscribe_TestApp extends AnyFunSuite{
  test("Prove location in stack trace is unreachable under a simple spec.") {
    val apk = getClass.getResource("/RXJavaSubscribe-fix-debug.apk").getPath
    assert(apk != null)
    val specs = Set(FragmentGetActivityNullSpec.getActivityNull,
      FragmentGetActivityNullSpec.getActivityNonNull,
      RxJavaSpec.call,
      //        RxJavaSpec.subscribeDoesNotReturnNull,
      RxJavaSpec.subscribeIsUnique
    )
    val w = new SootWrapper(apk,CHACallGraph, specs)
//    val transfer = (cha:ClassHierarchyConstraints) => new TransferFunctions[SootMethod,soot.Unit](w,
//      new SpecSpace(specs),cha)
    val config = SymbolicExecutorConfig(
      stepLimit = 200, w, new SpecSpace(specs),
      component = Some(List("example.com.rxjavasubscribebug.PlayerFragment.*")))
    val symbolicExecutor = config.getSymbolicExecutor

    val query = CallinReturnNonNull(
      "example.com.rxjavasubscribebug.PlayerFragment",
      "void lambda$onActivityCreated$1$PlayerFragment(java.lang.Object)",64,
      ".*getActivity.*")
    val result = symbolicExecutor.run(query).flatMap(a => a.terminals)
    assert(BounderUtil.interpretResult(result,QueryFinished) == Proven)
    assert(result.nonEmpty)
  }
}
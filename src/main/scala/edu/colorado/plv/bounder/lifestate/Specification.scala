package edu.colorado.plv.bounder.lifestate

import edu.colorado.plv.bounder.BounderUtil
import edu.colorado.plv.bounder.ir.{CBEnter, CBExit, CIEnter, CIExit}
import edu.colorado.plv.bounder.lifestate.LifeState.{And, Forall, LSConstraint, LSFalse, LSPred, LSSpec, LSTrue, NS, Not, Once, Or, SetSignatureMatcher, SignatureMatcher, SubClassMatcher}
import edu.colorado.plv.bounder.symbolicexecutor.state.{BoolVal, Equals, NamedPureVar, NotEquals, NullVal, PureExpr, TopVal}

object SpecSignatures {
  val a = NamedPureVar("a")
  val s = NamedPureVar("s")
  val f = NamedPureVar("f")
  val l = NamedPureVar("l")
  val t = NamedPureVar("t")
  val v = NamedPureVar("v")
  implicit val convertList:List[String]=>List[PureExpr] = LSExpParser.convertList
//  implicit val convert:String=>PureExpr = LSExpParser.convert


  //SetSignatureMatcher(activityTypeSet.map((_, "void onCreate(android.os.Bundle)")))

  // Activity lifecycle
  val Activity = Set("android.app.Activity", "androidx.fragment.app.FragmentActivity")

  val Activity_onResume: SignatureMatcher =
    SubClassMatcher(Activity, "void onResume\\(\\)", "Activity_onResume")
  val Activity_onCreate: SignatureMatcher =
    SubClassMatcher(Activity, "void onCreate\\(android.os.Bundle\\)",
    "Activity_onCreate")
  val Activity_finish: SignatureMatcher =
    SubClassMatcher(Activity, "void finish\\(\\)", "Activity_finish")
  val Activity_onResume_entry: Once =
    Once(CBEnter, Activity_onResume, List(TopVal, a))
  val Activity_onResume_exit: Once =
    Once(CBExit, Activity_onResume, List(TopVal, a))
  val Activity_onCreate_exit: Once =
    Once(CBExit, Activity_onCreate, List(TopVal, a))

  val Activity_onCreate_entry: Once =
    Once(CBEnter, Activity_onCreate, List(TopVal, a))

  val Activity_onPause: SignatureMatcher =
    SubClassMatcher(Activity,"void onPause\\(\\)", "Activity_onPause")
  val Activity_onPause_entry: Once = Once(CBEnter, Activity_onPause, List(TopVal, a))
  val Activity_onPause_exit: Once =
    Once(CBExit, Activity_onPause, List(TopVal, a))
//  val Activity_init: SignatureMatcher =
//    SubClassMatcher(Activity, "void \\<init\\>.*", "Activity_init")
//  val Activity_init_exit: I =
//    I(CBExit,Activity_init, List("_", "a"))
  val Activity_onDestroy: SignatureMatcher =
    SubClassMatcher(Activity, "void onDestroy\\(\\)", "Activity_onDestroy")
  val Activity_onDestroy_exit: Once = Once(CBExit, Activity_onDestroy, List(TopVal,a))

//  val Activity_init_entry: I = Activity_init_exit.copy(mt = CBEnter)

  val Activity_findView: SignatureMatcher=
    SubClassMatcher(Activity,".*findViewById.*","Activity_findView")
  val Activity_findView_exit: Once = Once(CIExit,
    Activity_findView, List(v,a))

  // Fragment getActivity
  private val Fragment = Set("android.app.Fragment","androidx.fragment.app.Fragment","android.support.v4.app.Fragment")
  val Fragment_getActivity: SignatureMatcher= SubClassMatcher(Fragment,
  ".*Activity getActivity\\(\\)", "Fragment_getActivity")
  val Fragment_get_activity_exit_null: Once = Once(CIExit, Fragment_getActivity, NullVal::f::Nil)

  val Fragment_get_activity_exit: Once = Once(CIExit, Fragment_getActivity, a::f::Nil)

  val Fragment_onActivityCreated: SignatureMatcher = SubClassMatcher(Fragment,
  "void onActivityCreated\\(android.os.Bundle\\)", "Fragment_onActivityCreated")

  val Fragment_onActivityCreated_entry: Once = Once(CBEnter, Fragment_onActivityCreated, TopVal::f::Nil)

  val Fragment_onDestroy: SignatureMatcher = SubClassMatcher(Fragment, "void onDestroy\\(\\)", "Fragment_onDestroy")
  val Fragment_onDestroy_exit: Once = Once(CBExit, Fragment_onDestroy, TopVal::f::Nil)

  // rxjava
  val RxJava_call: SignatureMatcher = SubClassMatcher("rx.functions.Action1", "void call\\(java.lang.Object\\)", "rxJava_call")

  val RxJava_call_entry: Once = Once(CBEnter, RxJava_call, TopVal::l::Nil)

  //TODO: check that this actually matches things
  val RxJava_create: SignatureMatcher = SubClassMatcher("rx.Single", "rx.Single create\\(rx.Single#OnSubscribe\\)", "rxJava_create")
  val RxJava_create_exit:Once = Once(CIExit, RxJava_create, t::Nil)

  val Subscriber = Set("rx.Subscriber","rx.SingleSubscriber","rx.Subscription",
    "rx.subscriptions.RefCountSubscription",
    "rx.subscriptions.RefCountSubscription")
  val RxJava_unsubscribe: SignatureMatcher = SubClassMatcher(Subscriber, "void unsubscribe\\(\\)", "rxJava_unsubscribe")

  val RxJava_unsubscribe_exit: Once = Once(CIExit, RxJava_unsubscribe, TopVal::s::Nil)

  val RxJava_subscribe: SignatureMatcher =
    SubClassMatcher("rx.Single", "rx.Subscription subscribe\\(rx.functions.Action1\\)",
      "RxJava_subscribe")
  val RxJava_subscribe_exit: Once = Once(CIExit, RxJava_subscribe, s::TopVal::l::Nil)
  val RxJava_subscribe_exit_null: Once = RxJava_subscribe_exit.copy(lsVars = NullVal::Nil)
}

object FragmentGetActivityNullSpec{

  val a = NamedPureVar("a")
  val f = NamedPureVar("f")
//  val cond = Or(Not(SpecSignatures.Fragment_onActivityCreated_entry), SpecSignatures.Fragment_onDestroy_exit)
  val fragmentActivityNotAttached: LSPred =
    Or(NS(SpecSignatures.Fragment_onDestroy_exit, SpecSignatures.Fragment_onActivityCreated_entry),
      Not(SpecSignatures.Fragment_onActivityCreated_entry))
  val fragmentActivityAttached: LSPred =
    NS(SpecSignatures.Fragment_onActivityCreated_entry,SpecSignatures.Fragment_onDestroy_exit)
  val getActivityNull: LSSpec = LSSpec(a::f::Nil, Nil,
    fragmentActivityNotAttached, SpecSignatures.Fragment_get_activity_exit,
    Set(LSConstraint(a, Equals, NullVal)))
//  val getActivityNonNull: LSSpec = LSSpec("a"::"f"::Nil, Nil,
//    fragmentActivityAttached, SpecSignatures.Fragment_get_activity_exit,
//    Set(LSConstraint("a", NotEquals, "@null")))
  val getActivityNonNull:LSSpec = LSSpec(a::f::Nil, Nil, LSTrue, SpecSignatures.Fragment_get_activity_exit)
}

object RxJavaSpec{

  val l = NamedPureVar("l")
  val s = NamedPureVar("s")
  //TODO: \/ I(create(l)) =======
  val subUnsub:LSPred = NS(
    SpecSignatures.RxJava_subscribe_exit,
    SpecSignatures.RxJava_unsubscribe_exit)
  val call:LSSpec = LSSpec(l::Nil, s::Nil, subUnsub, SpecSignatures.RxJava_call_entry)
//  val subscribeDoesNotReturnNull = LSSpec(LSFalse, SpecSignatures.RxJava_subscribe_exit_null)
  private val subscribe_s_only = SpecSignatures.RxJava_subscribe_exit.copy(lsVars = s::Nil)
  val subscribeIsUnique:LSSpec = LSSpec(s::Nil, Nil, Not(subscribe_s_only),
    subscribe_s_only) //,Set(LSConstraint("s",NotEquals,"@null")  )
  val spec = Set(call,subscribeIsUnique)
}

object LifecycleSpec {
  val a = NamedPureVar("a")
  val f = NamedPureVar("f")
  val v = NamedPureVar("v")
  //TODO: ======= check if this fixes connectBot
  val noResumeWhileFinish: LSSpec = LSSpec(a::Nil, Nil,
    Not(Once(CIExit, SpecSignatures.Activity_finish, TopVal::a::Nil)),
    SpecSignatures.Activity_onResume_entry
  )

  val viewAttached: LSPred = SpecSignatures.Activity_findView_exit //TODO: ... or findView on other view
  val destroyed: LSPred = NS(SpecSignatures.Activity_onDestroy_exit, SpecSignatures.Activity_onCreate_entry)
  val created: LSPred = NS(SpecSignatures.Activity_onCreate_entry, SpecSignatures.Activity_onDestroy_exit)
  val resumed: LSPred =
    NS(SpecSignatures.Activity_onResume_entry, SpecSignatures.Activity_onPause_exit)
  val paused:LSPred =
    Or(Not(SpecSignatures.Activity_onResume_entry),
      NS(SpecSignatures.Activity_onPause_exit, SpecSignatures.Activity_onResume_entry))
  val Activity_onResume_first_orAfter_onPause: LSSpec = LSSpec(a::Nil, Nil, Or(
    NS(SpecSignatures.Activity_onPause_exit, SpecSignatures.Activity_onResume_entry),
    And(Not(SpecSignatures.Activity_onPause_exit),Not(SpecSignatures.Activity_onResume_entry)))
    , SpecSignatures.Activity_onResume_entry)
  //val Activity_onResume_dummy:LSSpec = LSSpec("a"::Nil, Nil, LSTrue, SpecSignatures.Activity_onResume_entry)
  val Activity_onPause_onlyafter_onResume: LSSpec = LSSpec(a::Nil, Nil, resumed,
    SpecSignatures.Activity_onPause_entry)
  val Activity_created:LSPred = NS(SpecSignatures.Activity_onCreate_entry, SpecSignatures.Activity_onDestroy_exit)
  val Fragment_activityCreatedOnlyFirst:LSSpec = LSSpec(f::Nil, Nil,
    And(
      Not(SpecSignatures.Fragment_onDestroy_exit),
      And(Not(SpecSignatures.Fragment_onActivityCreated_entry),
      Not(SpecSignatures.Fragment_onActivityCreated_entry))
    ),
    SpecSignatures.Fragment_onActivityCreated_entry)
  val Fragment_activityCreatedOnlyOnce:LSSpec = LSSpec(f::Nil, Nil,
      Not(SpecSignatures.Fragment_onActivityCreated_entry),
    SpecSignatures.Fragment_onActivityCreated_entry)

//  val Activity_createdOnlyFirst:LSSpec = LSSpec("a"::Nil, Nil,
//    And(Not(SpecSignatures.Activity_onCreate_entry),Not(SpecSignatures.Activity_onDestroy_exit)),
//    SpecSignatures.Activity_onCreate_entry
//  )
  val Activity_createdOnlyFirst:LSSpec = LSSpec(a::Nil, Nil, Not(SpecSignatures.Activity_onCreate_entry),
    SpecSignatures.Activity_onCreate_entry)
//  val Activity_destroyAfterCreate:LSSpec = LSSpec("a"::Nil, Nil,
//    NI(SpecSignatures.Activity_onCreate_entry, SpecSignatures.Activity_onDestroy_exit),
//    SpecSignatures.Activity_onDestroy_exit)

  // TODO: This seems to cause timeouts when combined with the onClick spec
  val spec:Set[LSSpec] = Set(
    Fragment_activityCreatedOnlyFirst,
    Activity_createdOnlyFirst,
//    Activity_destroyAfterCreate, // Note: tried adding this because of a failing test, did not help
    Activity_onPause_onlyafter_onResume
  )
//    init_first_callback)
}

object ViewSpec {
  val a = NamedPureVar("a")
  val a2 = NamedPureVar("a2")
  val v = NamedPureVar("v")
  val l = NamedPureVar("l")

  implicit val convertList:List[String]=>List[PureExpr] = LSExpParser.convertList

  val anyViewCallin: Once = Once(CIEnter, SubClassMatcher("android.view.View",".*","View_AnyExceptOther"),List(TopVal, v) )
  val onClick:SignatureMatcher = SubClassMatcher("android.view.View$OnClickListener", ".*onClick.*", "ViewOnClickListener_onClick")
  val setOnClickListenerI:Once = Once(CIExit,
    SubClassMatcher("android.view.View",".*setOnClickListener.*","View_setOnClickListener"),
    List(TopVal,v,l)
  )
  val setOnClickListenerIl2:Once = Once(CIExit,
    SubClassMatcher("android.view.View",".*setOnClickListener.*","View_setOnClickListener"),
    List(TopVal,v,TopVal)
  )
  private val setOnClickListenerINull = Once(CIExit,
    SubClassMatcher("android.view.View",".*setOnClickListener.*","View_setOnClickListener"),
    List(TopVal,v,NullVal)
  )

   val setOnClickListener:LSPred = NS(setOnClickListenerI, setOnClickListenerINull) // TODO: setOnClickListenerIl2 causes bad proof in "finish allows click after pause, why?
//  val setOnClickListener:LSPred = setOnClickListenerI

  //TODO: fix disallowCallinAfterActivityPause , .* doesn't work as a matcher due to overlap
  // v a - viewAttached
  // a - destroyed
  val disallowCallinAfterActivityPause:LSSpec = LSSpec(v::Nil, a::Nil, And(LifecycleSpec.viewAttached,
    LifecycleSpec.destroyed),
    anyViewCallin)

  val clickWhileActive:LSSpec = LSSpec(l::Nil,a::v::Nil,
    And(And(setOnClickListener, LifecycleSpec.viewAttached), Or(LifecycleSpec.resumed,
      Once(CIExit, SpecSignatures.Activity_finish, TopVal::a::Nil))),
    Once(CBEnter, onClick, List(TopVal, l)))

  val setEnabled:SubClassMatcher = SubClassMatcher(Set("android.view.View"), ".*setEnabled.*", "View_setEnabled")
  private val buttonEnabled = Or(Not(Once(CIExit, setEnabled, TopVal::v::BoolVal(false)::Nil)),
    NS(Once(CIExit, setEnabled, TopVal::v::BoolVal(true)::Nil), Once(CIExit, setEnabled, TopVal::v::BoolVal(false)::Nil))
  )
//  private val buttonEnabled = Not(I(CIExit, setEnabled, "_"::"v"::"@false"::Nil)) //testing version, delete later
  val clickWhileNotDisabled:LSSpec = LSSpec(l::Nil, v::Nil,
    And(setOnClickListenerI, buttonEnabled),
    Once(CBEnter, onClick, List(TopVal, l)))
  private val fv1 = Once(CIExit, SpecSignatures.Activity_findView, v::a::Nil)
  private val fv2 = Once(CIExit, SpecSignatures.Activity_findView, v::a2::Nil)
  private val fv_exit = Once(CIExit, SpecSignatures.Activity_findView, v::TopVal::Nil)
  // Ɐv,a,a2. ¬ I(ci v:= a2.findViewByID()) \/ a = a2 <= ci v:= a.findViewByID()
  val viewOnlyReturnedFromOneActivity:LSSpec =
    LSSpec(a::v::Nil, Nil, Forall(a2::Nil, Or(Not(fv2), LSConstraint(a, Equals, a2))), fv1)
//  val sameIDSameView:LSSpec =
//    LSSpec()

//  val noDupeFindView:LSSpec = LSSpec("v"::Nil,Nil, Not(fv_exit), fv_exit)  //TODO: UNSOUND test version of noDupe
}
object SAsyncTask{
  val t = NamedPureVar("t")
  val AsyncTaskC = Set("android.os.AsyncTask")
  private val executeSig = SubClassMatcher(AsyncTaskC, ".*AsyncTask execute\\(.*\\)", "AsyncTask_execute")
  private val executeI = Once(CIExit, executeSig, TopVal::t::Nil)
  private val executeIEntry =
    Once(CIEnter, executeSig, TopVal::t::Nil)
  val disallowDoubleExecute:LSSpec = LSSpec(t::Nil, Nil, executeI, executeIEntry )
}
object SDialog{
  val a = NamedPureVar("a")
  val d = NamedPureVar("d")
  val DialogC = Set("android.app.ProgressDialog","android.app.Dialog")
  //android.app.ProgressDialog: void dismiss()>()
  val dismissSignature:SignatureMatcher = SubClassMatcher(DialogC, "void dismiss\\(\\)", "Dialog_dismiss")
  // <android.app.ProgressDialog: android.app.ProgressDialog
  //     show(android.content.Context,java.lang.CharSequence,java.lang.CharSequence)>($r2, "", "");
  val showSignature:SignatureMatcher = SubClassMatcher(DialogC, ".*show.*", "Dialog_show")
  val showI = Once(CIExit, showSignature, d::TopVal::a::Nil)

  val disallowDismiss:LSSpec = LSSpec(d::Nil, a::Nil,
    And(showI, LifecycleSpec.paused),
    Once(CIEnter, dismissSignature, TopVal::d::Nil))
  val showI2 = Once(CIExit, showSignature, d::TopVal::Nil)
  val noDupeShow:LSSpec = LSSpec(d::Nil, Nil, Not(showI2), showI2)
}

//TODO: multiple signature matchers matching the same method is not supported
//object ObjectSpec {
//  val anyCb:I = I(CBEnter, SubClassMatcher("java.lang.Object", ".*", "anyMethod"), List("_"))
//  val clinitExit:I = I(CBEnter, SubClassMatcher("java.lang.Object", ".*\\<clinit\\>.*", "clinit"), List())
//  val clinitFirst:LSSpec = LSSpec(Not(anyCb),clinitExit)
//}

object Dummy{
  val a = NamedPureVar("a")
  val l = NamedPureVar("l")
  val v = NamedPureVar("v")
  val specs = Set(
    LSSpec(a::Nil, Nil, LSTrue, SpecSignatures.Activity_onCreate_entry), // Dummy onCreate to include in trace
    LSSpec(a::Nil, Nil, LSTrue, SpecSignatures.Activity_onResume_entry), // Dummy onCreate to include in trace
    LSSpec(a::Nil, Nil, LSTrue, SpecSignatures.Activity_onPause_entry), // Dummy onCreate to include in trace
    LSSpec(a::Nil, Nil, LSTrue, SpecSignatures.Activity_onDestroy_exit), // Dummy onDestroy
    LSSpec(l::Nil, Nil, LSTrue, Once(CBEnter, SpecSignatures.RxJava_call, TopVal::l::Nil)), //Dummy call
    LSSpec(v::a::Nil, Nil, LSTrue, SpecSignatures.Activity_findView_exit),
    LSSpec(v::l::Nil,Nil, LSTrue, ViewSpec.setOnClickListenerI),
    LSSpec(l::Nil, Nil, LSTrue, Once(CBEnter, ViewSpec.onClick, TopVal::l::Nil))
  )
}
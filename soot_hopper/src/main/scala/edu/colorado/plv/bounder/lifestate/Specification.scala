package edu.colorado.plv.bounder.lifestate

import edu.colorado.plv.bounder.BounderUtil
import edu.colorado.plv.bounder.ir.{CBEnter, CBExit, CIEnter, CIExit}
import edu.colorado.plv.bounder.lifestate.LifeState.{And, I, LSConstraint, LSFalse, LSPred, LSSpec, LSTrue, NI, Not, Or, SetSignatureMatcher, SignatureMatcher, SubClassMatcher}
import edu.colorado.plv.bounder.symbolicexecutor.state.{Equals, NotEquals}

object SpecSignatures {

  //SetSignatureMatcher(activityTypeSet.map((_, "void onCreate(android.os.Bundle)")))

  // Activity lifecycle
  val Activity_onResume: SignatureMatcher =
    SubClassMatcher("android.app.Activity", "void onResume\\(\\)", "Activity_onResume")
  val Activity_onCreate: SignatureMatcher =
    SubClassMatcher("android.app.Activity", "void onCreate\\(android.os.Bundle\\)",
    "Activity_onCreate")
  val Activity_onResume_entry: I =
    I(CBEnter, Activity_onResume, List("_", "a"))
  val Activity_onResume_exit: I =
    I(CBExit, Activity_onResume, List("_", "a"))
  val Activity_onCreate_exit: I =
    I(CBExit, Activity_onCreate, List("_", "a"))

  val Activity_onCreate_entry: I =
    I(CBEnter, Activity_onCreate, List("_", "a"))

  val Activity_onPause: SignatureMatcher =
    SubClassMatcher("android.app.Activity","void onPause\\(\\)", "Activity_onPause")
  val Activity_onPause_entry: I = I(CBEnter, Activity_onPause, List("_", "a"))
  val Activity_onPause_exit: I =
    I(CBExit, Activity_onPause, List("_", "a"))
  val Activity_init: SignatureMatcher =
    SubClassMatcher("android.app.Activity", "void \\<init\\>.*", "Activity_init")
  val Activity_init_exit: I =
    I(CBExit,Activity_init, List("_", "a"))
  val Activity_onDestroy: SignatureMatcher =
    SubClassMatcher("android.app.Activity", "void onDestroy\\(\\)", "Activity_onDestroy")
  val Activity_onDestroy_exit: I = I(CBExit, Activity_onDestroy, List("_","a"))

  val Activity_init_entry: I = Activity_init_exit.copy(mt = CBEnter)

  val Activity_findView: SignatureMatcher=
    SubClassMatcher("android.app.Activity",".*findViewById.*","Activity_findView")
  val Activity_findView_exit: I = I(CIExit,
    Activity_findView, List("v","a"))

  // Fragment getActivity
  private val Fragment = Set("android.app.Fragment","androidx.fragment.app.Fragment")
  val Fragment_getActivity: SignatureMatcher= SubClassMatcher(Fragment,
  ".*Activity getActivity\\(\\)", "Fragment_getActivity")
  val Fragment_get_activity_exit_null: I = I(CIExit, Fragment_getActivity, "@null"::"f"::Nil)

  val Fragment_get_activity_exit: I = I(CIExit, Fragment_getActivity, "a"::"f"::Nil)

  val Fragment_onActivityCreated: SignatureMatcher = SubClassMatcher(Fragment,
  "void onActivityCreated\\(android.os.Bundle\\)", "Fragment_onActivityCreated")

  val Fragment_onActivityCreated_entry: I = I(CBEnter, Fragment_onActivityCreated, "_"::"f"::Nil)

  val Fragment_onDestroy_Signatures: SignatureMatcher = SubClassMatcher(Fragment, "void onDestroy\\(\\)", "Fragment_onDestroy")
  val Fragment_onDestroy_exit: I = I(CBExit, Fragment_onDestroy_Signatures, "_"::"f"::Nil)

  // rxjava
  val RxJava_call: SignatureMatcher = SubClassMatcher("rx.functions.Action1", "void call\\(java.lang.Object\\)", "rxJava_call")

  val RxJava_call_entry: I = I(CBEnter, RxJava_call, "_"::"l"::Nil)

  val Subscriber = Set("rx.Subscriber","rx.SingleSubscriber","rx.Subscription",
    "rx.subscriptions.RefCountSubscription",
    "rx.subscriptions.RefCountSubscription")
  val RxJava_unsubscribe: SignatureMatcher = SubClassMatcher(Subscriber, "void unsubscribe\\(\\)", "rxJava_unsubscribe")

  val RxJava_unsubscribe_exit: I = I(CIExit, RxJava_unsubscribe, "_"::"s"::Nil)

  val RxJava_subscribe: SignatureMatcher =
    SubClassMatcher("rx.Single", "rx.Subscription subscribe\\(rx.functions.Action1\\)",
      "RxJava_subscribe")
  val RxJava_subscribe_exit: I = I(CIExit, RxJava_subscribe, "s"::"_"::"l"::Nil)
  val RxJava_subscribe_exit_null: I = RxJava_subscribe_exit.copy(lsVars = "@null"::Nil)
}

object FragmentGetActivityNullSpec{
//  val cond = Or(Not(SpecSignatures.Fragment_onActivityCreated_entry), SpecSignatures.Fragment_onDestroy_exit)
  val cond: LSPred = NI(SpecSignatures.Fragment_onDestroy_exit, SpecSignatures.Fragment_onActivityCreated_entry)
  val getActivityNull: LSSpec = LSSpec(cond, SpecSignatures.Fragment_get_activity_exit_null)
  val getActivityNonNull: LSSpec = LSSpec(Not(cond), SpecSignatures.Fragment_get_activity_exit,
    Set(LSConstraint("a", NotEquals, "@null")))
}

object RxJavaSpec{
  val subUnsub:LSPred = NI(
    SpecSignatures.RxJava_subscribe_exit,
    SpecSignatures.RxJava_unsubscribe_exit)
  val call:LSSpec = LSSpec(subUnsub, SpecSignatures.RxJava_call_entry)
//  val subscribeDoesNotReturnNull = LSSpec(LSFalse, SpecSignatures.RxJava_subscribe_exit_null)
  val subscribeIsUnique:LSSpec = LSSpec(Not(SpecSignatures.RxJava_subscribe_exit.copy(lsVars = "s"::Nil)),
    SpecSignatures.RxJava_subscribe_exit) //,Set(LSConstraint("s",NotEquals,"@null")  )
}

object ActivityLifecycleSpec {
  //TODO:===== destroyed
  //TODO: view attached
  val viewAttached = SpecSignatures.Activity_findView_exit //TODO: ... or findView on other view
  val destroyedOrInit = NI(SpecSignatures.Activity_onDestroy_exit, SpecSignatures.Activity_onCreate_entry)
  val resumed = NI(SpecSignatures.Activity_onResume_entry, SpecSignatures.Activity_onPause_exit)
  val initPause = NI(SpecSignatures.Activity_onResume_entry, SpecSignatures.Activity_init_exit)
  val onPause_onlyafter_onResume_init = LSSpec(And(resumed,initPause),
    SpecSignatures.Activity_onPause_entry)
  val init_first_callback =
    LSSpec(And(
      Not(SpecSignatures.Activity_onCreate_exit),
      And(Not(SpecSignatures.Activity_onResume_exit),
        Not(SpecSignatures.Activity_onPause_exit))
    ),
      SpecSignatures.Activity_init_entry)

  val Fragment_activityCreatedOnlyFirst = LSSpec(
    And(
      Not(SpecSignatures.Fragment_onDestroy_exit),
      Not(SpecSignatures.Fragment_onActivityCreated_entry)
    ),
    SpecSignatures.Fragment_onActivityCreated_entry)
  val spec = new SpecSpace(Set(onPause_onlyafter_onResume_init, onPause_onlyafter_onResume_init, init_first_callback))
}

object ViewSpec {
  val anyViewCallin = SubClassMatcher("android.view.View",".*","anyViewCallin")
  // TODO:================= connect with act
  val disallowCallinAfterActivityPause = LSSpec(LSFalse, I(CIEnter, anyViewCallin, List("_", "v")))

}
package dotty.tools
package dotc
package typer

import core._
import ast._
import Contexts._, Types._, Flags._, Denotations._, Names._, StdNames._, NameOps._, Symbols._
import Trees._
import Constants._
import Scopes._
import annotation.unchecked
import util.Positions._
import util.{Stats, SimpleMap}
import util.common._
import Decorators._
import Uniques._
import ErrorReporting.{errorType, InfoString}
import config.Printers._
import collection.mutable

object ProtoTypes {

  import tpd._

  /** A trait defining an `isCompatible` method. */
  trait Compatibility {

    /** Is there an implicit conversion from `tp` to `pt`? */
    def viewExists(tp: Type, pt: Type)(implicit ctx: Context): Boolean

    /** A type `tp` is compatible with a type `pt` if one of the following holds:
     *    1. `tp` is a subtype of `pt`
     *    2. `pt` is by name parameter type, and `tp` is compatible with its underlying type
     *    3. there is an implicit conversion from `tp` to `pt`.
     */
    def isCompatible(tp: Type, pt: Type)(implicit ctx: Context): Boolean =
      tp.widenExpr <:< pt.widenExpr || viewExists(tp, pt)

    /** Test compatibility after normalization in a fresh typerstate. */
    def normalizedCompatible(tp: Type, pt: Type)(implicit ctx: Context) = {
      val nestedCtx = ctx.fresh.withExploreTyperState
      isCompatible(normalize(tp, pt)(nestedCtx), pt)(nestedCtx)
    }

    /** Check that the result type of the current method
     *  fits the given expected result type.
     */
    def constrainResult(mt: Type, pt: Type)(implicit ctx: Context): Boolean = pt match {
      case FunProto(_, result, _) =>
        mt match {
          case mt: MethodType =>
            mt.isDependent || constrainResult(mt.resultType, pt.resultType)
          case _ =>
            true
        }
      case _: ValueTypeOrProto if !(pt isRef defn.UnitClass) =>
        mt match {
          case mt: MethodType =>
            mt.isDependent || isCompatible(normalize(mt, pt), pt)
          case _ =>
            isCompatible(mt, pt)
        }
      case _ =>
        true
    }
  }

  object NoViewsAllowed extends Compatibility {
    override def viewExists(tp: Type, pt: Type)(implicit ctx: Context): Boolean = false
  }

  /** A prototype for expressions [] that are part of a selection operation:
   *
   *       [ ].name: proto
   */
  abstract case class SelectionProto(val name: Name, val memberProto: Type, val compat: Compatibility)
  extends CachedProxyType with ProtoType with ValueTypeOrProto {

    override def isMatchedBy(tp1: Type)(implicit ctx: Context) = {
      name == nme.WILDCARD || {
        val mbr = tp1.member(name)
        def qualifies(m: SingleDenotation) = compat.normalizedCompatible(m.info, memberProto)
        mbr match { // hasAltWith inlined for performance
          case mbr: SingleDenotation => mbr.exists && qualifies(mbr)
          case _ => mbr hasAltWith qualifies
        }
      }
    }

    def underlying(implicit ctx: Context) = WildcardType

    def derivedSelectionProto(name: Name, memberProto: Type, compat: Compatibility)(implicit ctx: Context) =
      if ((name eq this.name) && (memberProto eq this.memberProto) && (compat eq this.compat)) this
      else SelectionProto(name, memberProto, compat)

    override def equals(that: Any): Boolean = that match {
      case that: SelectionProto =>
        (name eq that.name) && (memberProto == that.memberProto) && (compat eq that.compat)
      case _ =>
        false
    }

    def map(tm: TypeMap)(implicit ctx: Context) = derivedSelectionProto(name, tm(memberProto), compat)
    def fold[T](x: T, ta: TypeAccumulator[T])(implicit ctx: Context) = ta(x, memberProto)

    override def computeHash = addDelta(doHash(name, memberProto), if (compat eq NoViewsAllowed) 1 else 0)
  }

  class CachedSelectionProto(name: Name, memberProto: Type, compat: Compatibility) extends SelectionProto(name, memberProto, compat)

  object SelectionProto {
    def apply(name: Name, memberProto: Type, compat: Compatibility)(implicit ctx: Context): SelectionProto = {
      val selproto = new CachedSelectionProto(name, memberProto, compat)
      if (compat eq NoViewsAllowed) unique(selproto) else selproto
    }
  }

  /** Create a selection proto-type, but only one level deep;
   *  treat constructors specially
   */
  def selectionProto(name: Name, tp: Type, typer: Typer)(implicit ctx: Context) =
    if (name.isConstructorName) WildcardType
    else tp match {
      case tp: UnapplyFunProto => new UnapplySelectionProto(name)
      case tp: ProtoType => SelectionProto(name, WildcardType, typer)
      case _ => SelectionProto(name, tp, typer)
    }

  /** A prototype for expressions [] that are in some unspecified selection operation
   *
   *    [].?: ?
   *
   *  Used to indicate that expression is in a context where the only valid
   *  operation is further selection. In this case, the expression need not be a value.
   *  @see checkValue
   */
  object AnySelectionProto extends SelectionProto(nme.WILDCARD, WildcardType, NoViewsAllowed)

  /** A prototype for selections in pattern constructors */
  class UnapplySelectionProto(name: Name) extends SelectionProto(name, WildcardType, NoViewsAllowed)

  trait ApplyingProto extends ProtoType

  /** A prototype for expressions that appear in function position
   *
   *  [](args): resultType
   */
  case class FunProto(args: List[untpd.Tree], override val resultType: Type, typer: Typer)(implicit ctx: Context)
  extends UncachedGroundType with ApplyingProto {
    private var myTypedArgs: List[Tree] = Nil

    /** A map in which typed arguments can be stored to be later integrated in `typedArgs`. */
    private var myTypedArg: SimpleMap[untpd.Tree, Tree] = SimpleMap.Empty

    def isMatchedBy(tp: Type)(implicit ctx: Context) =
      typer.isApplicable(tp, Nil, typedArgs, resultType)

    def derivedFunProto(args: List[untpd.Tree], resultType: Type, typer: Typer) =
      if ((args eq this.args) && (resultType eq this.resultType) && (typer eq this.typer)) this
      else new FunProto(args, resultType, typer)

    def argsAreTyped: Boolean = myTypedArgs.nonEmpty || args.isEmpty

    /** The typed arguments. This takes any arguments already typed using
     *  `typedArg` into account.
     */
    def typedArgs: List[Tree] = {
      if (!argsAreTyped)
        myTypedArgs = args mapconserve { arg =>
          val targ = myTypedArg(arg)
          if (targ != null) targ else typer.typed(arg)
        }
      myTypedArgs
    }

    /** Type single argument and remember the unadapted result in `myTypedArg`.
     *  used to avoid repeated typings of trees when backtracking.
     */
    def typedArg(arg: untpd.Tree, formal: Type)(implicit ctx: Context): Tree = {
      var targ = myTypedArg(arg)
      if (targ == null) {
        val counts = ctx.reporter.errorCounts
        targ = typer.typedUnadapted(arg, formal)
        if (ctx.reporter.wasSilent(counts))
          myTypedArg = myTypedArg.updated(arg, targ)
      }
      typer.adapt(targ, formal)
    }

    private var myTupled: Type = NoType

    /** The same proto-type but with all arguments combined in a single tuple */
    def tupled: FunProto = myTupled match {
      case pt: FunProto =>
        pt
      case _ =>
        myTupled = new FunProto(untpd.Tuple(args) :: Nil, resultType, typer)
        tupled
    }

    /** Somebody called the `tupled` method of this prototype */
    def isTupled: Boolean = myTupled.isInstanceOf[FunProto]

    override def toString = s"FunProto(${args mkString ","} => $resultType)"

    def map(tm: TypeMap)(implicit ctx: Context): FunProto =
      derivedFunProto(args, tm(resultType), typer)

    def fold[T](x: T, ta: TypeAccumulator[T])(implicit ctx: Context): T = ta(x, resultType)
  }

  /** A prototype for implicitly inferred views:
   *
   *    []: argType => resultType
   */
  abstract case class ViewProto(argType: Type, override val resultType: Type)(implicit ctx: Context)
  extends CachedGroundType with ApplyingProto {
    def isMatchedBy(tp: Type)(implicit ctx: Context): Boolean = /*ctx.conditionalTraceIndented(lookingForInfo, i"?.info isMatchedBy $tp ${tp.getClass}")*/ {
  	  ctx.typer.isApplicable(tp, argType :: Nil, resultType)
    }

    def derivedViewProto(argType: Type, resultType: Type)(implicit ctx: Context) =
      if ((argType eq this.argType) && (resultType eq this.resultType)) this
      else ViewProto(argType, resultType)

    def map(tm: TypeMap)(implicit ctx: Context): ViewProto = derivedViewProto(tm(argType), tm(resultType))

    def fold[T](x: T, ta: TypeAccumulator[T])(implicit ctx: Context): T = ta(ta(x, argType), resultType)

    override def namedPartsWith(p: NamedType => Boolean)(implicit ctx: Context): collection.Set[NamedType] =
      AndType.unchecked(argType, resultType).namedPartsWith(p) // this is more efficient than oring two namedParts sets
  }

  class CachedViewProto(argType: Type, resultType: Type)(implicit ctx: Context) extends ViewProto(argType, resultType) {
    override def computeHash = doHash(argType, resultType)
  }

  object ViewProto {
    def apply(argType: Type, resultType: Type)(implicit ctx: Context) =
      unique(new CachedViewProto(argType, resultType))
  }

  class UnapplyFunProto(typer: Typer)(implicit ctx: Context) extends FunProto(
      untpd.TypedSplice(dummyTreeOfType(WildcardType)) :: Nil, WildcardType, typer)

  /** A prototype for expressions [] that are type-parameterized:
   *
   *    [] [targs] resultType
   */
  case class PolyProto(targs: List[Type], override val resultType: Type) extends UncachedGroundType with ProtoType {
    override def isMatchedBy(tp: Type)(implicit ctx: Context) = {
      def isInstantiatable(tp: Type) = tp.widen match {
        case PolyType(paramNames) => paramNames.length == targs.length
        case _ => false
      }
      isInstantiatable(tp) || tp.member(nme.apply).hasAltWith(d => isInstantiatable(d.info))
    }

    def derivedPolyProto(targs: List[Type], resultType: Type) =
      if ((targs eq this.targs) && (resultType eq this.resultType)) this
      else PolyProto(targs, resultType)

    def map(tm: TypeMap)(implicit ctx: Context): PolyProto =
      derivedPolyProto(targs mapConserve tm, tm(resultType))

    def fold[T](x: T, ta: TypeAccumulator[T])(implicit ctx: Context): T =
      ta(ta.foldOver(x, targs), resultType)
  }

  /** A prototype for expressions [] that are known to be functions:
   *
   *    [] _
   */
  object AnyFunctionProto extends UncachedGroundType with ProtoType {
    def isMatchedBy(tp: Type)(implicit ctx: Context) = true
    def map(tm: TypeMap)(implicit ctx: Context) = this
    def fold[T](x: T, ta: TypeAccumulator[T])(implicit ctx: Context) = x
  }

  /** Add all parameters in given polytype `pt` to the constraint's domain.
   *  If the constraint contains already some of these parameters in its domain,
   *  make a copy of the polytype and add the copy's type parameters instead.
   *  Return either the original polytype, or the copy, if one was made.
   *  Also, if `owningTree` is non-empty, add a type variable for each parameter.
   *  @return  The added polytype, and the list of created type variables.
   */
  def constrained(pt: PolyType, owningTree: untpd.Tree)(implicit ctx: Context): (PolyType, List[TypeVar]) = {
    val state = ctx.typerState
    def howmany = if (owningTree.isEmpty) "no" else "some"
    def committable = if (ctx.typerState.isCommittable) "committable" else "uncommittable"
    assert(owningTree.isEmpty != ctx.typerState.isCommittable,
      s"inconsistent: $howmany typevars were added to $committable constraint ${state.constraint}")

    def newTypeVars(pt: PolyType): List[TypeVar] =
      for (n <- (0 until pt.paramNames.length).toList)
      yield new TypeVar(PolyParam(pt, n), state, owningTree)

    val added =
      if (state.constraint contains pt) pt.copy(pt.paramNames, pt.paramBounds, pt.resultType)
      else pt
    val tvars = if (owningTree.isEmpty) Nil else newTypeVars(added)
    state.constraint = state.constraint.add(added, tvars)
    (added, tvars)
  }

  /**  Same as `constrained(pt, EmptyTree)`, but returns just the created polytype */
  def constrained(pt: PolyType)(implicit ctx: Context): PolyType = constrained(pt, EmptyTree)._1

  /** The normalized form of a type
   *   - unwraps polymorphic types, tracking their parameters in the current constraint
   *   - skips implicit parameters
   *   - converts non-dependent method types to the corresponding function types
   *   - dereferences parameterless method types
   *   - dereferences nullary method types provided the corresponding function type
   *     is not a subtype of the expected type.
   * Note: We need to take account of the possibility of inserting a () argument list in normalization. Otherwise, a type with a
   *     def toString(): String
   * member would not count as a valid solution for ?{toString: String}. This would then lead to an implicit
   * insertion, with a nice explosion of inference search because of course every implicit result has some sort
   * of toString method. The problem is solved by dereferencing nullary method types if the corresponding
   * function type is not compatible with the prototype.
   */
  def normalize(tp: Type, pt: Type)(implicit ctx: Context): Type = Stats.track("normalize") {
    tp.widenSingleton match {
      case poly: PolyType => normalize(constrained(poly).resultType, pt)
      case mt: MethodType if !mt.isDependent /*&& !pt.isInstanceOf[ApplyingProto]*/ =>
        if (mt.isImplicit) mt.resultType
        else {
          val rt = normalize(mt.resultType, pt)
          if (pt.isInstanceOf[ApplyingProto])
            mt.derivedMethodType(mt.paramNames, mt.paramTypes, rt)
          else {
            val ft = defn.FunctionType(mt.paramTypes, rt)
            if (mt.paramTypes.nonEmpty || ft <:< pt) ft else rt
          }
        }
      case et: ExprType => et.resultType
      case _ => tp
    }
  }

  /** Approximate occurrences of parameter types and uninstantiated typevars
   *  by wildcard types.
   */
  final def wildApprox(tp: Type, theMap: WildApproxMap = null)(implicit ctx: Context): Type = tp match {
    case tp: NamedType => // default case, inlined for speed
      if (tp.symbol.isStatic) tp
      else tp.derivedSelect(wildApprox(tp.prefix, theMap))
    case tp: RefinedType => // default case, inlined for speed
      tp.derivedRefinedType(wildApprox(tp.parent, theMap), tp.refinedName, wildApprox(tp.refinedInfo, theMap))
    case tp: TypeBounds if tp.lo eq tp.hi => // default case, inlined for speed
      tp.derivedTypeAlias(wildApprox(tp.lo, theMap))
    case PolyParam(pt, pnum) =>
      WildcardType(wildApprox(pt.paramBounds(pnum)).bounds)
    case MethodParam(mt, pnum) =>
      WildcardType(TypeBounds.upper(wildApprox(mt.paramTypes(pnum))))
    case tp: TypeVar =>
      val inst = tp.instanceOpt
      if (inst.exists) wildApprox(inst)
      else ctx.typerState.constraint.at(tp.origin) match {
        case bounds: TypeBounds => wildApprox(WildcardType(bounds))
        case NoType => WildcardType
      }
    case tp: AndType =>
      val tp1a = wildApprox(tp.tp1)
      val tp2a = wildApprox(tp.tp2)
      def wildBounds(tp: Type) =
        if (tp.isInstanceOf[WildcardType]) tp.bounds else TypeBounds.upper(tp)
      if (tp1a.isInstanceOf[WildcardType] || tp2a.isInstanceOf[WildcardType])
        WildcardType(wildBounds(tp1a) & wildBounds(tp2a))
      else
        tp.derivedAndType(tp1a, tp2a)
    case tp: OrType =>
      val tp1a = wildApprox(tp.tp1)
      val tp2a = wildApprox(tp.tp2)
      if (tp1a.isInstanceOf[WildcardType] || tp2a.isInstanceOf[WildcardType])
        WildcardType(tp1a.bounds | tp2a.bounds)
      else
        tp.derivedOrType(tp1a, tp2a)
    case tp: SelectionProto =>
      tp.derivedSelectionProto(tp.name, wildApprox(tp.memberProto), NoViewsAllowed)
    case tp: ViewProto =>
      tp.derivedViewProto(wildApprox(tp.argType), wildApprox(tp.resultType))
    case  _: ThisType | _: BoundType | NoPrefix => // default case, inlined for speed
      tp
    case _ =>
      (if (theMap != null) theMap else new WildApproxMap).mapOver(tp)
  }

  private[ProtoTypes] class WildApproxMap(implicit ctx: Context) extends TypeMap {
    def apply(tp: Type) = wildApprox(tp, this)
  }

  private lazy val dummyTree = untpd.Literal(Constant(null))

  /** Dummy tree to be used as an argument of a FunProto or ViewProto type */
  def dummyTreeOfType(tp: Type): Tree = dummyTree withTypeUnchecked tp
}
package miniquill.quoter

import scala.quoted._
import scala.quoted.matching._
import scala.collection.mutable.ArrayBuffer
import scala.quoted.util.ExprMap
import miniquill.dsl.GenericEncoder

case class Encodeable[PrepareRow](uid: String, value: Expr[Any], encoder: Expr[GenericEncoder[Any, PrepareRow]])

object FindEncodeables {

  def apply[PrepareRow: Type](input: Expr[Any])(given qctx: QuoteContext): List[Encodeable[PrepareRow]] = {
    import qctx.tasty.{given, _}

    val quotationParser = new miniquill.parser.QuotationParser
    import quotationParser._

    val lifts = FindLifts[Any](input)
    lifts.collect {
      // Causes: this case is unreachable since class Tuple2 is not a subclass of class Expr
      // Not sure why. Probably language bug.
      //case `ScalarValueVase.apply`(liftValue, uid, _, tpe) =>
      case vase @ '{ ScalarValueVase.apply[$tpe]($liftValue, ${scala.quoted.matching.Const(uid: String)}) } =>
        summonExpr(given '[GenericEncoder[$tpe, PrepareRow]]) match {
          case Some(encoder) => 
            // Need to case to Encoder[Any] since we can't preserve types
            // in the list that is comming out (and we don't need to keep track
            // of types once we have the decoders)
            Encodeable(uid, liftValue, encoder.asInstanceOf[Expr[GenericEncoder[Any, PrepareRow]]])
          // TODO Error case and good message when can't find encoder
        }
    }
  }
}

object FindLifts {

  def apply[T](input: Expr[Any])(given qctx: QuoteContext, tpe: quoted.Type[T]): List[(String, Expr[Any])] = {
    import qctx.tasty.{given, _}

    val quotationParser = new miniquill.parser.QuotationParser
    import quotationParser._

    val buff: ArrayBuffer[(String, Expr[Any])] = new ArrayBuffer[(String, Expr[Any])]()
    val accum = new ExprMap {
      def transform[T](expr: Expr[T])(given qctx: QuoteContext, tpe: quoted.Type[T]): Expr[T] = {

        expr match {
          // TODO block foldOver in this case?
          // NOTE that using this kind of pattern match, lifts are matched for both compile and run times
          // In compile times the entire tree of passed-in-quotations is matched including the 'lifts' 
          // (i.e. Quotation.lifts) tuples that are returned so we just get ScalarValueVase.apply
          // matched from those (as well as from the body of the passed-in-quotation but that's fine
          // since we dedupe by the UUID *). During runtime however, the actual case class instance
          // of ScalarTag is matched by the below term.

          // * That is to say if we have a passed-in-quotation Quoted(body: ... ScalarValueVase.apply, lifts: ..., (ScalarValueVase.apply ....))
          // both the ScalarValueVase in the body as well as the ones in the tuple would be matched. This is fine
          // since we dedupe the scalar value lifts by their UUID.

          // TODO Why can't this be parsed with *: operator?
          // case '{ ScalarValueVase($tree, ${Const(uid)}) } => 
          //   buff += ((uid, expr))
          //   expr // can't go inside here or errors happen

          case MatchLift(tree, uid) =>
            buff += ((uid, tree))
            expr 

          // If the quotation is runtime, it needs to be matched so that we can add it to the tuple
          // of lifts (i.e. runtime values) and the later evaluate it during the 'run' function.
          // Match the vase and add it to the list.
          
          // This doesn't seem to work
          case MatchRuntimeQuotation(tree, uid) => // can't go inside here or errors happen
            buff += ((uid, tree))
            expr // can't go inside here or errors happen

          // case tree @ '{ QuotationVase.apply[$t]($inside, ${Const(uid)}) } =>
          //   println("========================== Runtime Quotation Matched ==========================")
          //   println(tree.unseal.underlyingArgument.seal.show)
          //   println("========================== End Runtime Quotation Matched ==========================")
          //   buff += ((uid, tree))
          //   expr // can't go inside here or errors happen

          case other =>
            expr
            //transformChildren(expr)
        }

        expr.unseal match {
          // Not including this causes execption "scala.tasty.reflect.ExprCastError: Expr: [ : Nothing]" in certain situations
          case Repeated(Nil, Inferred()) => expr 
          case _ => transformChildren[T](expr)
        }
      }
    }

    accum.transform(input) // check if really need underlyingArgument

    buff.toList
  }
}
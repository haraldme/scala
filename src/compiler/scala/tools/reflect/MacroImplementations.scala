package scala.tools.reflect

import scala.reflect.makro.{ReificationError, UnexpectedReificationError}
import scala.reflect.makro.runtime.Context
import scala.collection.mutable.ListBuffer
import scala.collection.mutable.Stack

abstract class MacroImplementations {
   val c: Context

  import c.universe.{Position => SPosition, _}

  def macro_StringInterpolation_f(parts: List[Tree], args: List[Tree], origApplyPos: SPosition): Tree = {
    // the parts all have the same position information (as the expression is generated by the compiler)
    // the args have correct position information

    // the following conditions can only be violated if invoked directly
    if (parts.length != args.length + 1) {
      if(parts.length == 0)
        c.abort(c.prefix.tree.pos, "too few parts")
      else if(args.length + 1 < parts.length)
        c.abort(if(args.length==0) c.enclosingPosition else args.last.pos,
            "too few arguments for interpolated string")
      else
        c.abort(args(parts.length-1).pos,
            "too many arguments for interpolated string")
    }
    val stringParts = parts map { 
      case Literal(Constant(s: String)) => s;
      case _ => throw new IllegalArgumentException("argument parts must be a list of string literals") 
    }

    val pi = stringParts.iterator
    val bldr = new java.lang.StringBuilder
    val evals = ListBuffer[ValDef]()
    val ids = ListBuffer[Ident]()
    val argsStack = Stack(args : _*)

    def defval(value: Tree, tpe: Type): Unit = {
      val freshName = newTermName(c.fresh("arg$"))
      evals += ValDef(Modifiers(), freshName, TypeTree(tpe) setPos value.pos.focus, value) setPos value.pos
      ids += Ident(freshName)
    }

    def isFlag(ch: Char): Boolean = {
      ch match {
        case '-' | '#' | '+' | ' ' | '0' | ',' | '(' => true
        case _ => false
      }
    }

    def checkType(arg: Tree, variants: Type*): Option[Type] = {
      variants.find(arg.tpe <:< _).orElse(
        variants.find(c.inferImplicitView(arg, arg.tpe, _) != EmptyTree).orElse(
            Some(variants(0))
        )
      )
    }

    val stdContextTags = new { val tc: c.type = c } with StdContextTags
    import stdContextTags._

    def conversionType(ch: Char, arg: Tree): Option[Type] = {
      ch match {
        case 'b' | 'B' =>
          if(arg.tpe <:< NullTpe) Some(NullTpe) else Some(BooleanTpe)
        case 'h' | 'H' =>
          Some(AnyTpe)
        case 's' | 'S' =>
          Some(AnyTpe)
        case 'c' | 'C' =>
          checkType(arg, CharTpe, ByteTpe, ShortTpe, IntTpe)
        case 'd' | 'o' | 'x' | 'X' =>
          checkType(arg, IntTpe, LongTpe, ByteTpe, ShortTpe, tagOfBigInt.tpe)
        case 'e' | 'E' | 'g' | 'G' | 'f' | 'a' | 'A'  =>
          checkType(arg, DoubleTpe, FloatTpe, tagOfBigDecimal.tpe)
        case 't' | 'T' =>
          checkType(arg, LongTpe, tagOfCalendar.tpe, tagOfDate.tpe)
        case _ => None
      }
    }

    def copyString(first: Boolean): Unit = {
      val str = StringContext.treatEscapes(pi.next())
      val strLen = str.length
      val strIsEmpty = strLen == 0
      var start = 0
      var idx = 0

      if (!first) {
        val arg = argsStack.pop
        if (strIsEmpty || (str charAt 0) != '%') {
          bldr append "%s"
          defval(arg, AnyTpe)
        } else {
          // PRE str is not empty and str(0) == '%'
          // argument index parameter is not allowed, thus parse
          //    [flags][width][.precision]conversion
          var pos = 1
          while(pos < strLen && isFlag(str charAt pos)) pos += 1
          while(pos < strLen && Character.isDigit(str charAt pos)) pos += 1
          if(pos < strLen && str.charAt(pos) == '.') { pos += 1
            while(pos < strLen && Character.isDigit(str charAt pos)) pos += 1
          }
          if(pos < strLen) {
            conversionType(str charAt pos, arg) match {
              case Some(tpe) => defval(arg, tpe)
              case None => c.error(arg.pos, "illegal conversion character")
            }
          } else {
            // TODO: place error message on conversion string
            c.error(arg.pos, "wrong conversion string")
          }
        }
        idx = 1
      }
      if (!strIsEmpty) {
        val len = str.length
        while (idx < len) {
          if (str(idx) == '%') {
            bldr append (str substring (start, idx)) append "%%"
            start = idx + 1
          }
          idx += 1
        }
        bldr append (str substring (start, idx))
      }
    }

    copyString(first = true)
    while (pi.hasNext) {
      copyString(first = false)
    }

    val fstring = bldr.toString
//  val expr = c.reify(fstring.format((ids.map(id => Expr(id).eval)) : _*))
//  https://issues.scala-lang.org/browse/SI-5824, therefore
    val expr =
      Apply(
        Select(
          Literal(Constant(fstring)),
          newTermName("format")),
        List(ids: _* )
      );

    Block(evals.toList, atPos(origApplyPos.focus)(expr)) setPos origApplyPos.makeTransparent
  }

}
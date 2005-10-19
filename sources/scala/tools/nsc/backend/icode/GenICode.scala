/* NSC -- new scala compiler
 * Copyright 2005 LAMP/EPFL
 * @author  Martin Odersky
 */

// $Id$

package scala.tools.nsc.backend.icode;

import scala.collection.mutable.{Map, HashMap};
import scala.tools.nsc.symtab._;


/**
 * TODO:
 *  - exception handling
 *  - synchronized blocks should add exception handlers that guarantee
 *    monitor releases in case of exceptions (like Java)?
 *  - switches with alternatives
 */
abstract class GenICode extends SubComponent  {
  import global._;
  import icodes._;
  import icodes.opcodes._;

  val phaseName = "genicode";

  override def newPhase(prev: Phase) = new ICodePhase(prev);

  class ICodePhase(prev: Phase) extends StdPhase(prev) {
    override def name = "genicode";

    override def description = "Generate ICode from the AST";

    var unit: CompilationUnit = _;

    // We assume definitions are alread initialized
    val STRING = REFERENCE(definitions.StringClass);

    // this depends on the backend! should be changed.
    val ANY_REF_CLASS = REFERENCE(definitions.ObjectClass);

    val SCALA_ALL    = REFERENCE(definitions.AllClass);
    val SCALA_ALLREF = REFERENCE(definitions.AllRefClass);
    val THROWABLE    = REFERENCE(definitions.ThrowableClass);

    ///////////////////////////////////////////////////////////

    override def run: Unit = {
      scalaPrimitives.init;
      super.run
    }

    override def apply(unit: CompilationUnit): Unit = {
      this.unit = unit;
      log("Generating icode for " + unit);
      gen(unit.body);
    }

    def gen(tree: Tree): Context = gen(tree, new Context());

    def gen(trees: List[Tree], ctx: Context): Context = {
      var ctx1 = ctx;
      for (val t <- trees)
        ctx1 = gen(t, ctx1);

      ctx1
    }

    /////////////////// Code generation ///////////////////////

    def gen(tree: Tree, ctx: Context): Context = tree match {
      case EmptyTree => ctx;

      case PackageDef(name, stats) => gen(stats, ctx setPackage name);

      case ClassDef(mods, name, tparams, tpt, impl) =>
        log("Generating class: " + tree.symbol.fullNameString);
        ctx setClass (new IClass(tree.symbol) setCompilationUnit unit);
        addClassFields(ctx, tree.symbol);
        classes = ctx.clazz :: classes;
        gen(impl, ctx);
        ctx setClass null;

      // !! modules should be eliminated by refcheck... or not?
      case ModuleDef(mods, name, impl) =>
        log("Generating module: " + tree.symbol.fullNameString);
        ctx setClass (new IClass(tree.symbol) setCompilationUnit unit);
        addClassFields(ctx, tree.symbol);
        classes = ctx.clazz :: classes;
        gen(impl, ctx);
        ctx setClass null;

      case ValDef(mods, name, tpt, rhs) => ctx; // we use the symbol to add fields

      case DefDef(mods, name, tparams, vparamss, tpt, rhs) =>
        log("Entering method " + name);
        val m = new IMethod(tree.symbol);
        m.sourceFile = unit.source.toString();
        m.returnType = if (tree.symbol.isConstructor) UNIT
                       else toTypeKind(tree.symbol.info.resultType);
        ctx.clazz.addMethod(m);

        var ctx1 = ctx.enterMethod(m, tree.asInstanceOf[DefDef]);
        addMethodParams(ctx1, vparamss);

        if (!m.isDeferred) {
          ctx1 = genLoad(rhs, ctx1, m.returnType);

          // reverse the order of the local variables, to match the source-order
          m.locals = m.locals.reverse;

          rhs match {
            case Block(_, Return(_)) => ();
            case Return(_) => ();
            case _ => ctx1.bb.emit(RETURN(m.returnType), rhs.pos);
          }
          ctx1.bb.close;
        } else
          ctx1.method.setCode(null);
        ctx1;

      case Template(parents, body) =>
        gen(body, ctx);

      case _ =>
        abort("Illegal tree in gen: " + tree);


/*  case AbsTypeDef(mods, name, lo, hi) =>                          (eliminated by erasure)
  case AliasTypeDef(mods, name, tparams, rhs) =>                  (eliminated by erasure)
  case Import(expr, selectors) =>                                 (eliminated by typecheck)
  case Attributed(attribute, definition) =>                       (eliminated by typecheck)
  case DocDef(comment, definition) =>                             (eliminated by typecheck)
  case CaseDef(pat, guard, body) =>                               (eliminated by transmatch)
  case Sequence(trees) =>                                         (eliminated by transmatch)
  case Alternative(trees) =>                                      (eliminated by transmatch)
  case Star(elem) =>                                              (eliminated by transmatch)
  case Bind(name, body) =>                                        (eliminated by transmatch)
  case ArrayValue(elemtpt, trees) =>                              (introduced by uncurry)
  case Function(vparams, body) =>                                 (eliminated by typecheck)
  case Typed(expr, tpt) =>                                         (eliminated by erasure)
  case TypeApply(fun, args) =>
  case SingletonTypeTree(ref) =>                                  (eliminated by typecheck)
  case SelectFromTypeTree(qualifier, selector) =>                 (eliminated by typecheck)
  case CompoundTypeTree(templ: Template) =>                        (eliminated by typecheck)
  case AppliedTypeTree(tpt, args) =>                               (eliminated by typecheck)
*/
    }

    private def genStat(trees: List[Tree], ctx: Context): Context = {
      var currentCtx = ctx;

      for (val t <- trees)
        currentCtx = genStat(t, currentCtx);

      currentCtx
    }

    /**
     * Generate code for the given tree. The trees should contain statements
     * and not produce any value. Use genLoad for expressions which leave
     * a value on top of the stack.
     *
     * @return a new context. This is necessary for control flow instructions
     * which may change the current basic block.
     */
    private def genStat(tree: Tree, ctx: Context): Context = tree match {
      case Assign(lhs @ Select(_, _), rhs) =>
        if (isStaticSymbol(lhs.symbol)) {
          val ctx1 = genLoad(rhs, ctx, toTypeKind(lhs.symbol.info));
          ctx1.bb.emit(STORE_FIELD(lhs.symbol, true), tree.pos);
          ctx1
        } else {
          var ctx1 = genLoadQualifier(lhs, ctx);
          ctx1 = genLoad(rhs, ctx1, toTypeKind(lhs.symbol.info));
          ctx1.bb.emit(STORE_FIELD(lhs.symbol, false), tree.pos);
          ctx1
        }

      case Assign(lhs, rhs) =>
//          assert(ctx.method.locals.contains(lhs.symbol) | ctx.clazz.fields.contains(lhs.symbol),
//                 "Assignment to inexistent local or field: " + lhs.symbol);
        val ctx1 = genLoad(rhs, ctx, toTypeKind(lhs.symbol.info));
        val Some(l) = ctx.method.lookupLocal(lhs.symbol);
        ctx1.bb.emit(STORE_LOCAL(l, lhs.symbol.isValueParameter), tree.pos);
        ctx1

      case _ =>
        log("Passing " + tree + " to genLoad");
        genLoad(tree, ctx, UNIT);
    }

    /**
     * Generate code for trees that produce values on the stack
     *
     * @param tree The tree to be translated
     * @param ctx  The current context
     * @param expectedType The type of the value to be generated on top of the
     *                     stack.
     * @return The new context. The only thing that may change is the current
     *         basic block (as the labels map is mutable).
     */
    private def genLoad(tree: Tree, ctx: Context, expectedType: TypeKind): Context = {
      var generatedType = expectedType;

      /**
       * Generate code for primitive arithmetic operations.
       */
      def genArithmeticOp(tree: Tree, ctx: Context, code: Int): Context = {
        val Apply(fun @ Select(larg, _), args) = tree;
        var ctx1 = ctx;
        var resKind = toTypeKind(larg.tpe);

        assert(args.length <= 1,
               "Too many arguments for primitive function: " + fun.symbol);
        assert(resKind.isNumericType | resKind == BOOL,
               resKind.toString() + " is not a numeric or boolean type [operation: " + fun.symbol + "]");

        args match {
          // unary operation
          case Nil =>
            ctx1 = genLoad(larg, ctx1, resKind);
            code match {
              case scalaPrimitives.POS => (); // nothing
              case scalaPrimitives.NEG => ctx1.bb.emit(CALL_PRIMITIVE(Negation(resKind)), larg.pos);
              case scalaPrimitives.NOT => ctx1.bb.emit(CALL_PRIMITIVE(Arithmetic(NOT, resKind)), larg.pos);
              case _ => abort("Unknown unary operation: " + fun.symbol.fullNameString + " code: " + code);
            }
            generatedType = resKind;

          // binary operation
          case rarg :: Nil =>
            resKind = getMaxType(larg.tpe :: rarg.tpe :: Nil);
            if (scalaPrimitives.isShiftOp(code) || scalaPrimitives.isBitwiseOp(code))
              assert(resKind.isIntType | resKind == BOOL,
                   resKind.toString() + " incompatible with arithmetic modulo operation: " + ctx1);

            ctx1 = genLoad(larg, ctx1, resKind);
            ctx1 = genLoad(rarg,
                           ctx1,  // check .NET size of shift arguments!
                           if (scalaPrimitives.isShiftOp(code)) INT else resKind);

            generatedType = resKind;
            code match {
              case scalaPrimitives.ADD => ctx1.bb.emit(CALL_PRIMITIVE(Arithmetic(ADD, resKind)), tree.pos);
              case scalaPrimitives.SUB => ctx1.bb.emit(CALL_PRIMITIVE(Arithmetic(SUB, resKind)), tree.pos);
              case scalaPrimitives.MUL => ctx1.bb.emit(CALL_PRIMITIVE(Arithmetic(MUL, resKind)), tree.pos);
              case scalaPrimitives.DIV => ctx1.bb.emit(CALL_PRIMITIVE(Arithmetic(DIV, resKind)), tree.pos);
              case scalaPrimitives.MOD => ctx1.bb.emit(CALL_PRIMITIVE(Arithmetic(REM, resKind)), tree.pos);
              case scalaPrimitives.OR  => ctx1.bb.emit(CALL_PRIMITIVE(Logical(OR, resKind)), tree.pos);
              case scalaPrimitives.XOR => ctx1.bb.emit(CALL_PRIMITIVE(Logical(XOR, resKind)), tree.pos);
              case scalaPrimitives.AND => ctx1.bb.emit(CALL_PRIMITIVE(Logical(AND, resKind)), tree.pos);
              case scalaPrimitives.LSL => ctx1.bb.emit(CALL_PRIMITIVE(Shift(LSL, resKind)), tree.pos);
                                          generatedType = resKind;
              case scalaPrimitives.LSR => ctx1.bb.emit(CALL_PRIMITIVE(Shift(LSR, resKind)), tree.pos);
                                          generatedType = resKind;
              case scalaPrimitives.ASR => ctx1.bb.emit(CALL_PRIMITIVE(Shift(ASR, resKind)), tree.pos);
                                          generatedType = resKind;
              case _ => abort("Unknown primitive: " + fun.symbol + "[" + code + "]");
            }

          case _ => abort("Too many arguments for primitive function: " + tree);
        }
        ctx1
      }

      /** Generate primitive array operations. */
      def genArrayOp(tree: Tree, ctx: Context, code: Int): Context = {
        import scalaPrimitives._;
        val Apply(Select(arrayObj, _), args) = tree;
        var ctx1 = genLoad(arrayObj, ctx, toTypeKind(arrayObj.tpe));

        if (scalaPrimitives.isArrayGet(code)) {
           // load argument on stack
           assert(args.length == 1,
                  "Too many arguments for array get operation: " + tree);
           ctx1 = genLoad(args.head, ctx1, INT);
        } else if (scalaPrimitives.isArraySet(code)) {
          assert(args.length == 2,
                 "Too many arguments for array set operation: " + tree);
          ctx1 = genLoad(args.head, ctx1, INT);
          ctx1 = genLoad(args.tail.head, ctx1, toTypeKind(args.tail.head.tpe));
        }

        code match {
          case ZARRAY_LENGTH =>
            ctx1.bb.emit(CALL_PRIMITIVE(ArrayLength(BOOL)), tree.pos);
          case BARRAY_LENGTH =>
            ctx1.bb.emit(CALL_PRIMITIVE(ArrayLength(BYTE)), tree.pos);
          case SARRAY_LENGTH =>
            ctx1.bb.emit(CALL_PRIMITIVE(ArrayLength(SHORT)), tree.pos);
          case CARRAY_LENGTH =>
            ctx1.bb.emit(CALL_PRIMITIVE(ArrayLength(CHAR)), tree.pos);
          case IARRAY_LENGTH =>
            ctx1.bb.emit(CALL_PRIMITIVE(ArrayLength(INT)), tree.pos);
          case LARRAY_LENGTH =>
            ctx1.bb.emit(CALL_PRIMITIVE(ArrayLength(LONG)), tree.pos);
          case FARRAY_LENGTH =>
            ctx1.bb.emit(CALL_PRIMITIVE(ArrayLength(FLOAT)), tree.pos);
          case DARRAY_LENGTH =>
            ctx1.bb.emit(CALL_PRIMITIVE(ArrayLength(DOUBLE)), tree.pos);
          case OARRAY_LENGTH =>
            ctx1.bb.emit(CALL_PRIMITIVE(ArrayLength(ANY_REF_CLASS)), tree.pos);

          case ZARRAY_GET =>
            ctx1.bb.emit(LOAD_ARRAY_ITEM(BOOL), tree.pos);
          case BARRAY_GET =>
            ctx1.bb.emit(LOAD_ARRAY_ITEM(BYTE), tree.pos);
          case SARRAY_GET =>
            ctx1.bb.emit(LOAD_ARRAY_ITEM(SHORT), tree.pos);
          case CARRAY_GET =>
            ctx1.bb.emit(LOAD_ARRAY_ITEM(CHAR), tree.pos);
          case IARRAY_GET =>
            ctx1.bb.emit(LOAD_ARRAY_ITEM(INT), tree.pos);
          case LARRAY_GET =>
            ctx1.bb.emit(LOAD_ARRAY_ITEM(LONG), tree.pos);
          case FARRAY_GET =>
            ctx1.bb.emit(LOAD_ARRAY_ITEM(FLOAT), tree.pos);
          case DARRAY_GET =>
            ctx1.bb.emit(LOAD_ARRAY_ITEM(DOUBLE), tree.pos);
          case OARRAY_GET =>
            ctx1.bb.emit(LOAD_ARRAY_ITEM(ANY_REF_CLASS), tree.pos);

          case ZARRAY_SET =>
            ctx1.bb.emit(STORE_ARRAY_ITEM(BOOL), tree.pos);
          case BARRAY_SET =>
            ctx1.bb.emit(STORE_ARRAY_ITEM(BYTE), tree.pos);
          case SARRAY_SET =>
            ctx1.bb.emit(STORE_ARRAY_ITEM(SHORT), tree.pos);
          case CARRAY_SET =>
            ctx1.bb.emit(STORE_ARRAY_ITEM(CHAR), tree.pos);
          case IARRAY_SET =>
            ctx1.bb.emit(STORE_ARRAY_ITEM(INT), tree.pos);
          case LARRAY_SET =>
            ctx1.bb.emit(STORE_ARRAY_ITEM(LONG), tree.pos);
          case FARRAY_SET =>
            ctx1.bb.emit(STORE_ARRAY_ITEM(FLOAT), tree.pos);
          case DARRAY_SET =>
            ctx1.bb.emit(STORE_ARRAY_ITEM(DOUBLE), tree.pos);
          case OARRAY_SET =>
            ctx1.bb.emit(STORE_ARRAY_ITEM(ANY_REF_CLASS), tree.pos);

          case _ =>
            abort("Unknown operation on arrays: " + tree + " code: " + code);
        }
        ctx1
      }

      // genLoad
      val resCtx: Context = tree match {
        case LabelDef(name, params, rhs) =>
          ctx.method.addLocals(params map (p => new Local(p.symbol, toTypeKind(p.symbol.info))));
          val ctx1 = ctx.newBlock;
          ctx1.labels.get(tree.symbol) match {
            case Some(label) => label.anchor(ctx1.bb);

            case None =>
              ctx1.labels += tree.symbol -> (new Label(tree.symbol) anchor ctx1.bb setParams (params map (.symbol)));
              log("Adding label " + tree.symbol);
          }
          ctx.bb.emit(JUMP(ctx1.bb), tree.pos);
          ctx.bb.close;
          genLoad(rhs, ctx1, toTypeKind(tree.symbol.info.resultType));

        case ValDef(_, _, _, rhs) =>
          var initialValue = rhs;
          val sym = tree.symbol;
          val local = new Local(sym, toTypeKind(sym.info));
          ctx.method.addLocal(local);

          if (rhs == EmptyTree) {
            log("Uninitialized variable " + tree + " at: " + unit.position(tree.pos));
            initialValue = zeroOf(local.kind);
          }

          val ctx1 = genLoad(initialValue, ctx, local.kind);
//          if (rhs != EmptyTree)
            ctx1.bb.emit(STORE_LOCAL(local, false), tree.pos);
          generatedType = UNIT;
          ctx1

        case If(cond, thenp, elsep) =>
          var thenCtx = ctx.newBlock;
          var elseCtx = ctx.newBlock;
          val contCtx = ctx.newBlock;
          genCond(cond, ctx, thenCtx, elseCtx);
          val ifKind = toTypeKind(tree.tpe);

          val thenKind = toTypeKind(thenp.tpe);
          val elseKind = if (elsep == EmptyTree) UNIT else toTypeKind(elsep.tpe);

          generatedType = ifKind;

          // we need to drop unneeded results, if one branch gives
          // unit and the other gives something on the stack, because
          // the type of 'if' is scala.Any, and its erasure would be Object.
          // But unboxed units are not Objects...
          if (thenKind == UNIT || elseKind == UNIT) {
            log("Will drop result from an if branch");
            thenCtx = genLoad(thenp, thenCtx, UNIT);
            elseCtx = genLoad(elsep, elseCtx, UNIT);
            assert(expectedType == UNIT, "I produce UNIT in a context where " + expectedType + " is expected!");
            generatedType = UNIT;
          } else {
            thenCtx = genLoad(thenp, thenCtx, ifKind);
            elseCtx = genLoad(elsep, elseCtx, ifKind);
          }

          thenCtx.bb.emit(JUMP(contCtx.bb), thenp.pos);
          thenCtx.bb.close;
          elseCtx.bb.emit(JUMP(contCtx.bb), elsep.pos);
          elseCtx.bb.close;
          contCtx;

        case Return(expr) =>
          val returnedKind = toTypeKind(expr.tpe);
          val ctx1 = genLoad(expr, ctx, returnedKind);
          ctx1.bb.emit(RETURN(returnedKind), tree.pos);
          ctx1.bb.enterIgnoreMode;
          // although strange, 'return' does not necessarily close a block
          //ctx1.bb.close;
          generatedType = expectedType;
          ctx1

        case Try(block, catches, finalizer) =>
          var ctx1 = ctx.newHandler.newBlock;
          ctx.bb.emit(JUMP(ctx1.bb), tree.pos);
          ctx.bb.close;
          val currentHandler = ctx.handlers.head;
          ctx1 = genLoad(block, ctx1, toTypeKind(block.tpe));
          assert(ctx1.handlers.head == currentHandler,
                 "Handler nesting violated. Expected: " +
                 currentHandler + " found: " + ctx1.handlers.head);
          // TODO: generate code for handlers and finalizer
          ctx1

        case Throw(expr) =>
          val ctx1 = genLoad(expr, ctx, THROWABLE);
          ctx1.bb.emit(THROW(), tree.pos);
          ctx1.bb.enterIgnoreMode;
          generatedType = SCALA_ALL;
          ctx1;

        case New(tpt) =>
          abort("Unexpected New");

        case Apply(TypeApply(fun, targs), _) =>
          val sym = fun.symbol;
          var ctx1 = ctx;
          var cast = false;

          if (sym == definitions.Object_isInstanceOf)
            cast = false
          else if (sym == definitions.Object_asInstanceOf)
            cast = true
          else
            abort("Unexpected type application " + fun + "[sym: " + sym + "]");

          val Select(obj, _) = fun;
          val l = toTypeKind(obj.tpe);
          val r = toTypeKind(targs.head.tpe);

          ctx1 = genLoadQualifier(fun, ctx);

          if (l.isValueType && r.isValueType)
            genConversion(l, r, ctx1, cast)
          else if (l.isValueType) {
            ctx1.bb.emit(DROP(l), fun.pos);
            ctx1.bb.emit(CONSTANT(Constant(false)))
          }
          else if (r.isValueType)
            genBoxedConversion(l, r, ctx1, cast)
          else
            genCast(l, r, ctx1, cast);

          generatedType = if (cast) r else BOOL;
          ctx1

        // 'super' call: Note: since constructors are supposed to
        // return an instance of what they construct, we have to take
        // special care. On JVM they are 'void', and Scala forbids (syntactically)
        // to call super constructors explicitly and/or use their 'returned' value.
        // therefore, we can ignore this fact, and generate code that leaves nothing
        // on the stack (contrary to what the type in the AST says).
        case Apply(fun @ Select(Super(_, mixin), _), args) =>
          log("Call to super: " + tree);
          val invokeStyle =
            if (fun.symbol.isConstructor) Static(true) else SuperCall(mixin);

          ctx.bb.emit(THIS(ctx.clazz.symbol), tree.pos);
          val ctx1 = genLoadArguments(args, fun.symbol.info.paramTypes, ctx);

          ctx1.bb.emit(CALL_METHOD(fun.symbol, invokeStyle), tree.pos);
          generatedType = if (fun.symbol.isConstructor)
                            UNIT
                          else
                            toTypeKind(fun.symbol.info.resultType);
          ctx1

        // 'new' constructor call: Note: since constructors are
        // thought to return an instance of what they construct,
        // we have to 'simulate' it by DUPlicating the freshly created
        // instance (on JVM, <init> methods return VOID).
        case Apply(fun @ Select(New(tpt), nme.CONSTRUCTOR), args) =>
          val ctor = fun.symbol;
          assert(ctor.isClassConstructor,
                 "'new' call to non-constructor: " + tree);

          generatedType = toTypeKind(tpt.tpe);
          assert(generatedType.isReferenceType || generatedType.isArrayType,
                 "Non reference type cannot be instantiated: " + generatedType);

          var ctx1 = ctx;

          generatedType match {
            case ARRAY(elem) =>
              ctx1 = genLoadArguments(args, ctor.info.paramTypes, ctx);
              ctx1.bb.emit(CREATE_ARRAY(elem), tree.pos);

            case REFERENCE(cls) =>
              assert(ctor.owner == cls,
                     "Symbol " + ctor.owner.fullNameString + "is different than " + tpt);
              ctx1.bb.emit(NEW(generatedType), tree.pos);
              ctx1.bb.emit(DUP(generatedType));
              ctx1 = genLoadArguments(args, ctor.info.paramTypes, ctx);

              ctx1.bb.emit(CALL_METHOD(ctor, Static(true)), tree.pos);

            case _ =>
              abort("Cannot instantiate " + tpt + "of kind: " + generatedType);
          }
          ctx1

        case Apply(fun, args) =>
          val sym = fun.symbol;

          if (sym.isLabel) {  // jump to a label
            val label = ctx.labels.get(sym) match {
              case Some(l) => l;

              // it is a forward jump, scan for labels
              case None =>
                log("Performing scan for label because of forward jump.");
                scanForLabels(ctx.defdef, ctx);
                ctx.labels.get(sym) match {
                  case Some(l) => l;
                  case _       => abort("Unknown label target: " + sym + " at: " + unit.position(fun.pos) + ": ctx: " + ctx);
                }
            }
            val ctx1 = genLoadLabelArguments(args, label, ctx);
            if (label.anchored)
              ctx1.bb.emit(JUMP(label.block), tree.pos);
            else
              ctx1.bb.emit(PJUMP(label), tree.pos);

            ctx1.bb.close;
            ctx1.newBlock;
          } else if (isPrimitive(fun.symbol)) { // primitive method call
            val Select(receiver, _) = fun;

            val code = scalaPrimitives.getPrimitive(fun.symbol, receiver.tpe);
            var ctx1 = ctx;

            if (scalaPrimitives.isArithmeticOp(code)) {
              ctx1 = genArithmeticOp(tree, ctx1, code);
            } else if (code == scalaPrimitives.CONCAT) {
              ctx1 = genStringConcat(tree, ctx1);
              generatedType = STRING;
            } else if (scalaPrimitives.isArrayOp(code)) {
              ctx1 = genArrayOp(tree, ctx1, code);
            } else if (scalaPrimitives.isLogicalOp(code) ||
                       scalaPrimitives.isComparisonOp(code)) {

              val trueCtx = ctx1.newBlock;
              val falseCtx = ctx1.newBlock;
              val afterCtx = ctx1.newBlock;
              log("Passing " + tree + " to genCond");
              genCond(tree, ctx1, trueCtx, falseCtx);
              trueCtx.bb.emit(CONSTANT(Constant(true)), tree.pos);
              trueCtx.bb.emit(JUMP(afterCtx.bb));
              trueCtx.bb.close;
              falseCtx.bb.emit(CONSTANT(Constant(false)), tree.pos);
              falseCtx.bb.emit(JUMP(afterCtx.bb));
              falseCtx.bb.close;
              generatedType = BOOL;
              ctx1 = afterCtx;
            } else if (code == scalaPrimitives.SYNCHRONIZED) {
              ctx1 = genLoadQualifier(fun, ctx1);
              ctx1.bb.emit(MONITOR_ENTER(), tree.pos);
              ctx1 = genLoad(args.head, ctx1, toTypeKind(tree.tpe.resultType));
              ctx1 = genLoadQualifier(fun, ctx1);
              ctx1.bb.emit(MONITOR_EXIT(), tree.pos);
            } else if (scalaPrimitives.isCoercion(code)) {
              ctx1 = genLoad(receiver, ctx1, toTypeKind(receiver.tpe));
              genCoercion(tree, ctx1, code);
            } else
              abort("Primitive operation not handled yet: " +
                    fun.symbol.fullNameString + "(" + fun.symbol.simpleName + ") "
                    + " at: " + unit.position(tree.pos));
            ctx1
          } else {  // normal method call
            log("Gen CALL_METHOD with sym: " + sym + " isStaticSymbol: " + isStaticSymbol(sym));
            var invokeStyle =
              if (isStaticSymbol(sym))
                Static(false)
              else if (sym.hasFlag(Flags.PRIVATE) || sym.isClassConstructor)
                Static(true)
              else
                Dynamic;

            var ctx1 = if (invokeStyle.hasInstance)
                         genLoadQualifier(fun, ctx);
                       else
                         ctx;

            ctx1 = genLoadArguments(args, fun.symbol.info.paramTypes, ctx1);

            ctx1.bb.emit(CALL_METHOD(sym, invokeStyle), tree.pos);
            generatedType = if (sym.isClassConstructor) UNIT else toTypeKind(sym.info.resultType);
            ctx1
          }

        case This(qual) =>
          assert(tree.symbol == ctx.clazz.symbol || tree.symbol.isModuleClass,
                 "Trying to access the this of another class: " +
                 "tree.symbol = " + tree.symbol + ", ctx.clazz.symbol = " + ctx.clazz.symbol);
          if (tree.symbol.isModuleClass && tree.symbol != ctx.clazz.symbol) {
            log("LOAD_MODULE from 'This'");
            ctx.bb.emit(LOAD_MODULE(tree.symbol), tree.pos);
            generatedType = REFERENCE(tree.symbol);
          } else {
            ctx.bb.emit(THIS(ctx.clazz.symbol), tree.pos);
            generatedType = REFERENCE(ctx.clazz.symbol);
          }
          ctx;

        case Select(Ident(nme.EMPTY_PACKAGE_NAME), module) =>
          assert(tree.symbol.isModule,
                 "Selection of non-module from empty package: " + tree.toString() +
                 " sym: " + tree.symbol +
                 " at: " + unit.position(tree.pos));
          log("LOAD_MODULE from Select(<emptypackage>)");
          ctx.bb.emit(LOAD_MODULE(tree.symbol), tree.pos);
          ctx

        case Select(qualifier, selector) =>
          val sym = tree.symbol;
          generatedType = toTypeKind(sym.info);

          if (sym.isModule) {
            log("LOAD_MODULE from Select(qualifier, selector)");
            ctx.bb.emit(LOAD_MODULE(sym), tree.pos);
            ctx
          } else if (isStaticSymbol(sym)) {
            ctx.bb.emit(LOAD_FIELD(sym, true), tree.pos);
            ctx
          } else {
            val ctx1 = genLoadQualifier(tree, ctx);
            ctx1.bb.emit(LOAD_FIELD(sym, false), tree.pos);
            ctx1
          }

        case Ident(name) =>
          if (!tree.symbol.isPackage) {
            if (tree.symbol.isModule) {
              log("LOAD_MODULE from Ident(name)");
              ctx.bb.emit(LOAD_MODULE(tree.symbol), tree.pos);
              generatedType = toTypeKind(tree.symbol.info);
            } else {
              val Some(l) = ctx.method.lookupLocal(tree.symbol);
              ctx.bb.emit(LOAD_LOCAL(l, tree.symbol.isValueParameter), tree.pos);
              generatedType = l.kind;
            }
          }
          ctx

        case Literal(value) =>
          if (value.tag != UnitTag)
            ctx.bb.emit(CONSTANT(value), tree.pos);
          generatedType = toTypeKind(value.tpe);
          ctx

        case Block(stats, expr) =>
          log("Entering block");
          assert(!(ctx.method eq null), "Block outside method");
          val ctx1 = genStat(stats, ctx);
          genLoad(expr, ctx1, expectedType);

        case Typed(expr, _) =>
          genLoad(expr, ctx, expectedType);

        case Assign(_, _) =>
          generatedType = UNIT;
          genStat(tree, ctx);

        case ArrayValue(tpt @ TypeTree(), elems) =>
          var ctx1 = ctx;
          val elmKind = toTypeKind(tpt.tpe);
          generatedType = ARRAY(elmKind);

          ctx1.bb.emit(CONSTANT(new Constant(elems.length)), tree.pos);
          ctx1.bb.emit(CREATE_ARRAY(elmKind));
          // inline array literals
          var i = 0;
          while (i < elems.length) {
            ctx1.bb.emit(DUP(generatedType), tree.pos);
            ctx1.bb.emit(CONSTANT(new Constant(i)));
            ctx1 = genLoad(elems(i), ctx1, elmKind);
            ctx1.bb.emit(STORE_ARRAY_ITEM(elmKind));
            i = i + 1;
          }
          ctx1

        case Match(selector, cases) =>
          log("Generating SWITCH statement.");
          var ctx1 = genLoad(selector, ctx, INT);
          val afterCtx = ctx1.newBlock;
          var caseCtx: Context  = null;
          val kind = toTypeKind(tree.tpe);

          var targets: List[BasicBlock] = Nil;
          var tags: List[Int] = Nil;
          var default: BasicBlock = afterCtx.bb;

          for (val caze <- cases)
            caze match {
              case CaseDef(Literal(value), EmptyTree, body) =>
                tags = value.intValue :: tags;
                val tmpCtx = ctx1.newBlock;
                targets = tmpCtx.bb :: targets;

                caseCtx = genLoad(body, tmpCtx , kind);
                caseCtx.bb.emit(JUMP(afterCtx.bb), caze.pos);
                caseCtx.bb.close;

              case CaseDef(Ident(nme.WILDCARD), EmptyTree, body) =>
                val tmpCtx = ctx1.newBlock;
                default = tmpCtx.bb;

                caseCtx = genLoad(body, tmpCtx , kind);
                caseCtx.bb.emit(JUMP(afterCtx.bb), caze.pos);
                caseCtx.bb.close;

              case _ => abort("Invalid case statement in switch-like pattern match: " +
                              tree + " at: " + unit.position(tree.pos));
            }
          ctx1.bb.emit(SWITCH(tags.reverse map (x => List(x)),
                             (default :: targets).reverse), tree.pos);
          ctx1.bb.close;
          afterCtx

        case EmptyTree => ctx;

        case _ => abort("Unexpected tree in genLoad: " + tree + " at: " + unit.position(tree.pos));
      }

      // emit conversion
      if (!(generatedType <:< expectedType)) {
        expectedType match {
          case UNIT =>
            resCtx.bb.emit(DROP(generatedType), tree.pos);
            log("Dropped an " + generatedType);

          case _ =>
            assert(generatedType != UNIT, "Can't convert from UNIT to " + expectedType +
                 tree + " at: " + unit.position(tree.pos));
            resCtx.bb.emit(CALL_PRIMITIVE(Conversion(generatedType, expectedType)), tree.pos);
        }
//      } else if (generatedType == SCALA_ALL && expectedType == UNIT)
//        resCtx.bb.emit(DROP(generatedType));
      } else if (generatedType == SCALA_ALL) {
        resCtx.bb.emit(DROP(generatedType));
        resCtx.bb.emit(getZeroOf(ctx.method.returnType));
        resCtx.bb.emit(RETURN(ctx.method.returnType));
        resCtx.bb.enterIgnoreMode;
      } else if (generatedType == SCALA_ALLREF) {
        resCtx.bb.emit(DROP(generatedType));
        resCtx.bb.emit(CONSTANT(Constant(null)));
      }

      resCtx;
    }

    /** Load the qualifier of `tree' on top of the stack. */
    private def genLoadQualifier(tree: Tree, ctx: Context): Context =
        tree match {
          case Select(qualifier, _) =>
            genLoad(qualifier, ctx, ANY_REF_CLASS); // !!
          case _ =>
            abort("Unknown qualifier " + tree);
        }

    /** Is this symbol static in the Java sense? */
    def isStaticSymbol(s: Symbol): Boolean =
      s.hasFlag(Flags.STATIC) || s.hasFlag(Flags.STATICMEMBER) || s.owner.isImplClass;

    /**
     * Generate code that loads args into label parameters.
     */
    private def genLoadLabelArguments(args: List[Tree], label: Label, ctx: Context): Context = {
      assert(args.length == label.params.length,
             "Wrong number of arguments in call to label " + label.symbol);
      var ctx1 = ctx;
      var arg = args;
      var param = label.params;

      // store arguments in reverse order on the stack
      while (arg != Nil) {
        val Some(l) = ctx.method.lookupLocal(param.head);
        ctx1 = genLoad(arg.head, ctx1, l.kind);
        arg = arg.tail;
        param = param.tail;
      }

      // store arguments in the right variables
      arg = args.reverse; param = label.params.reverse;
      while (arg != Nil) {
        val Some(l) = ctx.method.lookupLocal(param.head);
        ctx1.bb.emit(STORE_LOCAL(l, param.head.isValueParameter), arg.head.pos);
        arg = arg.tail;
        param = param.tail;
      }

      ctx1
    }

    private def genLoadArguments(args: List[Tree], tpes: List[Type], ctx: Context): Context = {
      assert(args.length == tpes.length, "Wrong number of arguments in call " + ctx);

      var ctx1 = ctx;
      var arg = args;
      var tpe = tpes;
      while (arg != Nil) {
        ctx1 = genLoad(arg.head, ctx1, toTypeKind(tpe.head));
        arg = arg.tail;
        tpe = tpe.tail;
      }
      ctx1
    }

    def genConversion(from: TypeKind, to: TypeKind, ctx: Context, cast: Boolean) = {
      if (cast)
        ctx.bb.emit(CALL_PRIMITIVE(Conversion(from, to)));
      else {
        ctx.bb.emit(DROP(from));
        ctx.bb.emit(CONSTANT(Constant(from == to)));
      }
    }

    def genBoxedConversion(from: TypeKind, to: TypeKind, ctx: Context, cast: Boolean) = {
      assert(to.isValueType, "Expecting conversion to value type: " + to);
      val boxedCls = definitions.boxedClass(to.toType.symbol);
      if (cast) {
        ctx.bb.emit(CHECK_CAST(REFERENCE(boxedCls)));
        ctx.bb.emit(LOAD_FIELD(definitions.getMember(boxedCls, "value"), false));
      } else
        ctx.bb.emit(IS_INSTANCE(REFERENCE(boxedCls)));
    }

    def genCast(from: TypeKind, to: TypeKind, ctx: Context, cast: Boolean) = {
      if (cast)
        ctx.bb.emit(CHECK_CAST(to));
      else
        ctx.bb.emit(IS_INSTANCE(to));
    }

    def zeroOf(k: TypeKind): Tree = k match {
      case UNIT            => Literal(());
      case BOOL            => Literal(false);
      case BYTE            => Literal(0: Byte);
      case SHORT           => Literal(0: Short);
      case CHAR            => Literal(0: Char);
      case INT             => Literal(0: Int);
      case LONG            => Literal(0: Long);
      case FLOAT           => Literal(0.0f);
      case DOUBLE          => Literal(0.0d);
      case REFERENCE(cls)  => Literal(null: Any);
      case ARRAY(elem)     => Literal(null: Any);
    }

    def getZeroOf(k: TypeKind): Instruction = k match {
      case UNIT            => CONSTANT(Constant(()));
      case BOOL            => CONSTANT(Constant(false));
      case BYTE            => CONSTANT(Constant(0: Byte));
      case SHORT           => CONSTANT(Constant(0: Short));
      case CHAR            => CONSTANT(Constant(0: Char));
      case INT             => CONSTANT(Constant(0: Int));
      case LONG            => CONSTANT(Constant(0: Long));
      case FLOAT           => CONSTANT(Constant(0.0f));
      case DOUBLE          => CONSTANT(Constant(0.0d));
      case REFERENCE(cls)  => CONSTANT(Constant(null: Any));
      case ARRAY(elem)     => CONSTANT(Constant(null: Any));
    }


    /** Is the given symbol a primitive operation? */
    def isPrimitive(fun: Symbol): Boolean = {
      import scalaPrimitives._;

      if (scalaPrimitives.isPrimitive(fun))
        scalaPrimitives.getPrimitive(fun) match {
          case EQUALS | HASHCODE | TOSTRING  => false;
          case _ => true;
        }
      else
        false;
    }

    def genCoercion(tree: Tree, ctx: Context, code: Int) = {
      import scalaPrimitives._;
      code match {
        case B2B => ();
        case B2C => ctx.bb.emit(CALL_PRIMITIVE(Conversion(BYTE, CHAR)), tree.pos);
        case B2S => ctx.bb.emit(CALL_PRIMITIVE(Conversion(BYTE, SHORT)), tree.pos);
        case B2I => ctx.bb.emit(CALL_PRIMITIVE(Conversion(BYTE, INT)), tree.pos);
        case B2L => ctx.bb.emit(CALL_PRIMITIVE(Conversion(BYTE, LONG)), tree.pos);
        case B2F => ctx.bb.emit(CALL_PRIMITIVE(Conversion(BYTE, FLOAT)), tree.pos);
        case B2D => ctx.bb.emit(CALL_PRIMITIVE(Conversion(BYTE, DOUBLE)), tree.pos);

        case S2B => ctx.bb.emit(CALL_PRIMITIVE(Conversion(SHORT, BYTE)), tree.pos);
        case S2S => ();
        case S2C => ctx.bb.emit(CALL_PRIMITIVE(Conversion(SHORT, CHAR)), tree.pos);
        case S2I => ctx.bb.emit(CALL_PRIMITIVE(Conversion(SHORT, INT)), tree.pos);
        case S2L => ctx.bb.emit(CALL_PRIMITIVE(Conversion(SHORT, LONG)), tree.pos);
        case S2F => ctx.bb.emit(CALL_PRIMITIVE(Conversion(SHORT, FLOAT)), tree.pos);
        case S2D => ctx.bb.emit(CALL_PRIMITIVE(Conversion(SHORT, DOUBLE)), tree.pos);

        case C2B => ctx.bb.emit(CALL_PRIMITIVE(Conversion(CHAR, BYTE)), tree.pos);
        case C2S => ctx.bb.emit(CALL_PRIMITIVE(Conversion(CHAR, SHORT)), tree.pos);
        case C2C => ();
        case C2I => ctx.bb.emit(CALL_PRIMITIVE(Conversion(CHAR, INT)), tree.pos);
        case C2L => ctx.bb.emit(CALL_PRIMITIVE(Conversion(CHAR, LONG)), tree.pos);
        case C2F => ctx.bb.emit(CALL_PRIMITIVE(Conversion(CHAR, FLOAT)), tree.pos);
        case C2D => ctx.bb.emit(CALL_PRIMITIVE(Conversion(CHAR, DOUBLE)), tree.pos);

        case I2B => ctx.bb.emit(CALL_PRIMITIVE(Conversion(INT, BYTE)), tree.pos);
        case I2S => ctx.bb.emit(CALL_PRIMITIVE(Conversion(INT, SHORT)), tree.pos);
        case I2C => ctx.bb.emit(CALL_PRIMITIVE(Conversion(INT, CHAR)), tree.pos);
        case I2I => ();
        case I2L => ctx.bb.emit(CALL_PRIMITIVE(Conversion(INT, LONG)), tree.pos);
        case I2F => ctx.bb.emit(CALL_PRIMITIVE(Conversion(INT, FLOAT)), tree.pos);
        case I2D => ctx.bb.emit(CALL_PRIMITIVE(Conversion(INT, DOUBLE)), tree.pos);

        case L2B => ctx.bb.emit(CALL_PRIMITIVE(Conversion(LONG, BYTE)), tree.pos);
        case L2S => ctx.bb.emit(CALL_PRIMITIVE(Conversion(LONG, SHORT)), tree.pos);
        case L2C => ctx.bb.emit(CALL_PRIMITIVE(Conversion(LONG, CHAR)), tree.pos);
        case L2I => ctx.bb.emit(CALL_PRIMITIVE(Conversion(LONG, INT)), tree.pos);
        case L2L => ();
        case L2F => ctx.bb.emit(CALL_PRIMITIVE(Conversion(LONG, FLOAT)), tree.pos);
        case L2D => ctx.bb.emit(CALL_PRIMITIVE(Conversion(LONG, DOUBLE)), tree.pos);

        case F2B => ctx.bb.emit(CALL_PRIMITIVE(Conversion(FLOAT, BYTE)), tree.pos);
        case F2S => ctx.bb.emit(CALL_PRIMITIVE(Conversion(FLOAT, SHORT)), tree.pos);
        case F2C => ctx.bb.emit(CALL_PRIMITIVE(Conversion(FLOAT, CHAR)), tree.pos);
        case F2I => ctx.bb.emit(CALL_PRIMITIVE(Conversion(FLOAT, INT)), tree.pos);
        case F2L => ctx.bb.emit(CALL_PRIMITIVE(Conversion(FLOAT, LONG)), tree.pos);
        case F2F => ();
        case F2D => ctx.bb.emit(CALL_PRIMITIVE(Conversion(FLOAT, DOUBLE)), tree.pos);

        case D2B => ctx.bb.emit(CALL_PRIMITIVE(Conversion(DOUBLE, BYTE)), tree.pos);
        case D2S => ctx.bb.emit(CALL_PRIMITIVE(Conversion(DOUBLE, SHORT)), tree.pos);
        case D2C => ctx.bb.emit(CALL_PRIMITIVE(Conversion(DOUBLE, CHAR)), tree.pos);
        case D2I => ctx.bb.emit(CALL_PRIMITIVE(Conversion(DOUBLE, INT)), tree.pos);
        case D2L => ctx.bb.emit(CALL_PRIMITIVE(Conversion(DOUBLE, LONG)), tree.pos);
        case D2F => ctx.bb.emit(CALL_PRIMITIVE(Conversion(DOUBLE, FLOAT)), tree.pos);
        case D2D => ();

        case _ => abort("Unknown coercion primitive: " + code);
      }
    }

    /** Generate string concatenation. */
    def genStringConcat(tree: Tree, ctx: Context): Context = {
      val Apply(Select(larg, _), rarg) = tree;
      var ctx1 = ctx;

      assert(rarg.length == 1,
             "Too many parameters for string concatenation");

      val concatenations = liftStringConcat(tree);
      log("Lifted string concatenations for " + tree + "\n to: " + concatenations);

      ctx1.bb.emit(CALL_PRIMITIVE(StartConcat), tree.pos);
      for (val elem <- concatenations) {
        val kind = toTypeKind(elem.tpe);
        ctx1 = genLoad(elem, ctx1, kind);
        ctx1.bb.emit(CALL_PRIMITIVE(StringConcat(kind)), elem.pos);
      }
      ctx1.bb.emit(CALL_PRIMITIVE(EndConcat), tree.pos);

      ctx1;
    }

    /**
     * Returns a list of trees that each should be concatenated, from
     * left to right. It turns a chained call like "a".+("b").+("c") into
     * a list of arguments.
     */
    def liftStringConcat(tree: Tree): List[Tree] = tree match {
      case Apply(fun @ Select(larg, method), rarg) =>
        if (isPrimitive(fun.symbol) &&
            scalaPrimitives.getPrimitive(fun.symbol) == scalaPrimitives.CONCAT)
          liftStringConcat(larg) ::: rarg
        else
          List(tree);
      case _ =>
        List(tree);
    }


    /**
     * Traverse the tree and store label stubs in the contxt. This is
     * necessary to handle forward jumps, because at a label application
     * with arguments, the symbols of the corresponding LabelDef parameters
     * are not yet known.
     *
     * Since it is expensive to traverse each method twice, this method is called
     * only when forward jumps really happen, and then it re-traverses the whole
     * method, scanning for LabelDefs.
     *
     * TODO: restrict the scanning to smaller subtrees than the whole method.
     *  It is sufficient to scan the trees of the innermost enclosing block.
     */
    private def scanForLabels(tree: Tree, ctx: Context): Unit =
      new Traverser() {
        override def traverse(tree: Tree): Unit = tree match {

          case LabelDef(name, params, rhs) =>
            ctx.labels += tree.symbol -> (new Label(tree.symbol) setParams(params map (.symbol)));
            //super.traverse(rhs);

          case _ => super.traverse(tree);
        }
      } traverse(tree);

    /**
     * Generate code for conditional expressions. The two basic blocks
     * represent the continuation in case of success/failure of the
     * test.
     */
    private def genCond(tree: Tree,
                        ctx: Context,
                        thenCtx: Context,
                        elseCtx: Context): Unit =
    {
      def genComparisonOp(l: Tree, r: Tree, code: Int): Unit = {
        val op: TestOp = code match {
          case scalaPrimitives.LT => LT;
          case scalaPrimitives.LE => LE;
          case scalaPrimitives.GT => GT;
          case scalaPrimitives.GE => GE;
          case scalaPrimitives.ID | scalaPrimitives.EQ => EQ;
          case scalaPrimitives.NI | scalaPrimitives.NE => NE;

          case _ => abort("Unknown comparison primitive: " + code);
        };

        val kind = getMaxType(l.tpe :: r.tpe :: Nil);
        var ctx1 = genLoad(l, ctx, kind);
            ctx1 = genLoad(r, ctx1, kind);
        ctx1.bb.emit(CJUMP(thenCtx.bb, elseCtx.bb, op, kind), r.pos);
        ctx1.bb.close;
      }

      log("Entering genCond with tree: " + tree);

      tree match {
        case Apply(fun, args)
          if isPrimitive(fun.symbol) =>
            assert(args.length <= 1,
                   "Too many arguments for primitive function: " + fun.symbol);
            val code = scalaPrimitives.getPrimitive(fun.symbol);

            if (code == scalaPrimitives.ZNOT) {
              val Select(leftArg, _) = fun;
              genCond(leftArg, ctx, elseCtx, thenCtx);
            } else if ((code == scalaPrimitives.EQ ||
                        code == scalaPrimitives.NE)) {
              val Select(leftArg, _) = fun;
              if (toTypeKind(leftArg.tpe).isReferenceType) {
                if (code == scalaPrimitives.EQ)
                  genEqEqPrimitive(leftArg, args.head, ctx, thenCtx, elseCtx);
                else
                  genEqEqPrimitive(leftArg, args.head, ctx, elseCtx, thenCtx);
              }
              else
                genComparisonOp(leftArg, args.head, code);
            } else if (scalaPrimitives.isComparisonOp(code)) {
              val Select(leftArg, _) = fun;
              genComparisonOp(leftArg, args.head, code);
            } else {
              code match {
                case scalaPrimitives.ZAND =>
                  val Select(leftArg, _) = fun;

                  val ctxInterm = ctx.newBlock;
                  genCond(leftArg, ctx, ctxInterm, elseCtx);
                  genCond(args.head, ctxInterm, thenCtx, elseCtx);

                case scalaPrimitives.ZOR =>
                  val Select(leftArg, _) = fun;

                  val ctxInterm = ctx.newBlock;
                  genCond(leftArg, ctx, thenCtx, ctxInterm);
                  genCond(args.head, ctxInterm, thenCtx, elseCtx);

                case _ =>
                  var ctx1 = genLoad(tree, ctx, BOOL);
                  ctx1.bb.emit(CZJUMP(thenCtx.bb, elseCtx.bb, NE, BOOL), tree.pos);
                  ctx1.bb.close;
              }
            }

        case _ =>
          var ctx1 = genLoad(tree, ctx, BOOL);
          ctx1.bb.emit(CZJUMP(thenCtx.bb, elseCtx.bb, NE, BOOL), tree.pos);
          ctx1.bb.close;
      }
    }

    val eqEqTemp: Name = "eqEqTemp$";


    /**
     * Generate the "==" code for object references. It is equivalent of
     * if (l == null) then r == null else l.equals(r);
     */
    def genEqEqPrimitive(l: Tree, r: Tree, ctx: Context, thenCtx: Context, elseCtx: Context): Unit = {
      var eqEqTempVar: Symbol = null;
      var eqEqTempLocal: Local = null;

      ctx.method.lookupLocal(eqEqTemp) match {
        case Some(local) => eqEqTempVar = local.sym; eqEqTempLocal = local;
        case None =>
          eqEqTempVar = ctx.method.symbol.newVariable(l.pos, eqEqTemp);
          eqEqTempVar.setInfo(definitions.AnyRefClass.typeConstructor);
          eqEqTempLocal = new Local(eqEqTempVar, REFERENCE(definitions.AnyRefClass));
          ctx.method.addLocal(eqEqTempLocal);
      }

      var ctx1 = genLoad(l, ctx, ANY_REF_CLASS);
      ctx1 = genLoad(r, ctx1, ANY_REF_CLASS);
      val tmpNullCtx = ctx1.newBlock;
      val tmpNonNullCtx = ctx1.newBlock;
      ctx1.bb.emit(STORE_LOCAL(eqEqTempLocal, false), l.pos);
      ctx1.bb.emit(DUP(ANY_REF_CLASS));
      ctx1.bb.emit(CZJUMP(tmpNullCtx.bb, tmpNonNullCtx.bb, EQ, ANY_REF_CLASS));
      ctx1.bb.close;

      tmpNullCtx.bb.emit(DROP(ANY_REF_CLASS), l.pos); // type of AnyRef
      tmpNullCtx.bb.emit(LOAD_LOCAL(eqEqTempLocal, false));
      tmpNullCtx.bb.emit(CZJUMP(thenCtx.bb, elseCtx.bb, EQ, ANY_REF_CLASS));
      tmpNullCtx.bb.close;

      tmpNonNullCtx.bb.emit(LOAD_LOCAL(eqEqTempLocal, false), l.pos);
      tmpNonNullCtx.bb.emit(CALL_METHOD(definitions.Object_equals, Dynamic));
      tmpNonNullCtx.bb.emit(CZJUMP(thenCtx.bb, elseCtx.bb, NE, BOOL));
      tmpNonNullCtx.bb.close;
    }

    /**
     * Add all fields of the given class symbol to the current ICode
     * class.
     */
    private def addClassFields(ctx: Context, cls: Symbol): Unit = {
      assert(ctx.clazz.symbol eq cls,
             "Classes are not the same: " + ctx.clazz.symbol + ", " + cls);

      for (val f <- cls.info.decls.elements)
        if (!f.isMethod && f.isTerm)
          ctx.clazz.addField(new IField(f));
    }

    /**
     * Add parameters to the current ICode method. It is assumed the methods
     * have been uncurried, so the list of lists contains just one list.
     */
    private def addMethodParams(ctx: Context, vparamss: List[List[ValDef]]): Unit =
      vparamss match {
        case Nil => ()

        case vparams :: Nil =>
          for (val p <- vparams)
            ctx.method.addParam(new Local(p.symbol, toTypeKind(p.symbol.info)));
          ctx.method.params = ctx.method.params.reverse;

        case _ =>
          abort("Malformed parameter list: " + vparamss);
      }


    def getMaxType(ts: List[Type]): TypeKind = {
      def maxType(a: TypeKind, b: TypeKind): TypeKind =
        a maxType b;

      val kinds = ts map toTypeKind;
      kinds reduceLeft maxType;
    }


    /////////////////////// Context ////////////////////////////////


    /**
     * The Context class keeps information relative to the current state
     * in code generation
     */
    class Context {

      /** The current package. */
      var packg: Name = _;

      /** The current class. */
      var clazz: IClass = _;

      /** The current method. */
      var method: IMethod = _;

      /** The current basic block. */
      var bb: BasicBlock = _;

      /** Map from label symbols to label objects. */
      var labels: HashMap[Symbol, Label] = new HashMap();

      /** Current method definition. */
      var defdef: DefDef = _;

      /** current exception handlers */
      var handlers: List[ExceptionHandler] = NoHandler :: Nil;

      var handlerCount = 0;

      override def toString(): String = {
        val buf = new StringBuffer();
        buf.append("\tpackage: ").append(packg).append('\n');
        buf.append("\tclazz: ").append(clazz).append('\n');
        buf.append("\tmethod: ").append(method).append('\n');
        buf.append("\tbb: ").append(bb).append('\n');
        buf.append("\tlabels: ").append(labels).append('\n');
        buf.toString()
      }


      def this(other: Context) = {
        this();
        this.packg = other.packg;
        this.clazz = other.clazz;
        this.method = other.method;
        this.bb = other.bb;
        this.labels = other.labels;
        this.defdef = other.defdef;
        this.handlers = other.handlers;
        this.handlerCount = other.handlerCount;
      }

      def setPackage(p: Name): this.type = {
        this.packg = p;
        this
      }

      def setClass(c: IClass): this.type = {
        this.clazz = c;
        this
      }

      def setMethod(m: IMethod): this.type = {
        this.method = m;
        this
      }

      def setBasicBlock(b: BasicBlock): this.type = {
        this.bb = b;
        this
      }

      /** Prepare a new context upon entry into a method */
      def enterMethod(m: IMethod, d: DefDef): Context = {
        val ctx1 = new Context(this) setMethod(m);
        ctx1.labels = new HashMap();
        ctx1.method.code = new Code(m.symbol.simpleName.toString());
        ctx1.bb = ctx1.method.code.startBlock;
        ctx1.defdef = d;
        ctx1
      }

      /** Return a new context for a new basic block. */
      def newBlock: Context = {
        val block = method.code.newBlock;
        handlers foreach (h => h addBlock block);
        new Context(this) setBasicBlock block;
      }

      def newHandler: Context = {
        handlerCount = handlerCount + 1;
        val exh = new ExceptionHandler("" + handlerCount) setOuter handlers.head;
        handlers = exh :: handlers;
        this
      }

      def exitHandler: Context = {
        assert(handlerCount > 0,
               "Wrong nesting of exception handlers.");
        handlerCount = handlerCount - 1;
        handlers = handlers.tail;
        this
      }
    }

    /**
     * Represent a label in the current method code. In order
     * to support forward jumps, labels can be created without
     * having a deisgnated target block. They can later be attached
     * by calling `anchor'.
     */
    class Label(val symbol: Symbol) {
      var anchored = false;
      var block: BasicBlock = _;
      var params: List[Symbol] = _;

      private var toPatch: List[Instruction] = Nil;

      /** Fix this label to the given basic block. */
      def anchor(b: BasicBlock): Label = {
        assert(!anchored, "Cannot anchor an already anchored label!");
        anchored = true;
        this.block = b;
        this
      }

      def setParams(p: List[Symbol]): Label = {
        assert(params == null, "Cannot set label parameters twice!");
        params = p;
        this
      }

      /** Add an instruction that refers to this label. */
      def addCallingInstruction(i: Instruction) =
        toPatch = i :: toPatch;

      /**
       * Patch the code by replacing pseudo call instructions with
       * jumps to the given basic block.
       */
      def patch(code: Code): Unit = {
        def substMap: Map[Instruction, Instruction] = {
          val map = new HashMap[Instruction, Instruction]();

          toPatch foreach (i => map += i -> patch(i));
          map
        }

        val map = substMap;
        code traverse (.subst(map));
      }

      /**
       * Return the patched instruction. If the given instruction
       * jumps to this label, replace it with the basic block. Otherwise,
       * return the same instruction. Conditional jumps have more than one
       * label, so they are replaced only if all labels are anchored.
       */
      def patch(instr: Instruction): Instruction = {
        assert(anchored, "Cannot patch until this label is anchored: " + this);

        instr match {
          case PJUMP(self)
          if (self == this) => JUMP(block);

          case PCJUMP(self, failure, cond, kind)
          if (self == this && failure.anchored) =>
            CJUMP(block, failure.block, cond, kind);

          case PCJUMP(success, self, cond, kind)
          if (self == this && success.anchored) =>
            CJUMP(success.block, block, cond, kind);

          case PCZJUMP(self, failure, cond, kind)
          if (self == this && failure.anchored) =>
            CZJUMP(block, failure.block, cond, kind);

          case PCZJUMP(success, self, cond, kind)
          if (self == this && success.anchored) =>
            CZJUMP(success.block, block, cond, kind);

          case _ => instr;
        }
      }
    }

    ///////////////// Fake instructions //////////////////////////

    /**
     * Pseudo jump: it takes a Label instead of a basick block.
     * It is used temporarily during code generation. It is replaced
     * by a real JUMP instruction when all labels are resolved.
     */
    class PseudoJUMP(label: Label) extends Instruction {
      override def toString(): String ="PJUMP " + label.symbol.simpleName;

      override def consumed = 0;
      override def produced = 0;

      // register with the given label
      if (!label.anchored)
        label.addCallingInstruction(this);
    }

    case class PJUMP(where: Label) extends PseudoJUMP(where);

    case class PCJUMP(success: Label, failure: Label, cond: TestOp, kind: TypeKind)
         extends PseudoJUMP(success) {

       if (!failure.anchored)
         failure.addCallingInstruction(this);
    }

    case class PCZJUMP(success: Label, failure: Label, cond: TestOp, kind: TypeKind)
         extends PseudoJUMP(success) {

       if (!failure.anchored)
         failure.addCallingInstruction(this);
    }

  }
}


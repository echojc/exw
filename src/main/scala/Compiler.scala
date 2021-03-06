import java.lang.{ Double ⇒ D }
import org.objectweb.asm._, Ops._

object Compiler {
  val O = "Ljava/lang/Object;"

  def compile(asts: lime.List, unit: String): Array[Byte] = {
    val c = new ClassWriter(ClassWriter.COMPUTE_FRAMES)
    c.visit(V1_7, ACC_PUBLIC + ACC_SUPER, unit, null, "java/lang/Object", null)
    asts foreach compileAst(c)
    c.visitEnd()
    c.toByteArray()
  }

  def compileAst(c: ClassWriter)(ast: Object): Unit = ast match {
    case 'def %:: (Symbol(name) %:: args) %:: bodys ⇒
      val m = c.visitMethod(ACC_PUBLIC + ACC_STATIC, name, genDesc(args), null, Array[String]("java/lang/Exception"))
      m.visitCode()
      bodys foreach compileAst(m)
      // TODO: make sure stack is balanced
      m.visitInsn(ARETURN)
      m.visitMaxs(0, 0)
      m.visitEnd()
  }

  private def genDesc(args: lime.List): String =
    s"(${Seq.fill(args.len().asInstanceOf[D].toInt)(O).mkString})$O"

  def compileAst(m: MethodVisitor)(ast: Object): Unit =
    (inlines(m) orElse statics(m))(ast)

  private def statics(m: MethodVisitor): PartialFunction[Object, Unit] = {
    case d: java.lang.Double ⇒
      quotes(m)(d)
    case s: String ⇒
      quotes(m)(s)
    case 'quote %:: (expr: Object) %:: limeNil() ⇒
      quotes(m)(expr)
  }

  private def quotes(m: MethodVisitor): PartialFunction[Object, Unit] = {
    case d: java.lang.Double ⇒
      m.visitLdcInsn(d); box(m)
    case s: String ⇒
      m.visitLdcInsn(s)
    case Symbol(s) ⇒
      m.visitFieldInsn(GETSTATIC, "scala/Symbol$", "MODULE$", "Lscala/Symbol$;")
      m.visitLdcInsn(s)
      m.visitMethodInsn(INVOKEVIRTUAL, "scala/Symbol$", "apply", "(Ljava/lang/String;)Lscala/Symbol;", false)
    case list: lime.List ⇒
      list foreach { elem ⇒
        m.visitTypeInsn(NEW, "lime/Cons")
        m.visitInsn(DUP)
        quotes(m)(elem) // once quoted, always quoted
      }
      m.visitMethodInsn(INVOKESTATIC, "lime/Nil", "get", "()Llime/List;", false)
      list foreach { _ ⇒
        m.visitMethodInsn(INVOKESPECIAL, "lime/Cons", "<init>", s"($O$O)V", false)
      }
  }

  object InlineMath { def unapply(s: Symbol): Option[Int] = s match {
      case Symbol("+") ⇒ Some(DADD)
      case Symbol("-") ⇒ Some(DSUB)
      case Symbol("*") ⇒ Some(DMUL)
      case Symbol("/") ⇒ Some(DDIV)
      case Symbol("%") ⇒ Some(DREM)
      case _           ⇒ None
  }}
  object InlineList { def unapply(s: Symbol): Option[String] = s match {
      case 'car    ⇒ Some("car")
      case 'cdr    ⇒ Some("cdr")
      case 'length ⇒ Some("len")
      case _       ⇒ None
  }}
  private def inlines(m: MethodVisitor): PartialFunction[Object, Unit] = {
    case InlineMath(fun) %:: (fst: Object) %:: (snd: Object) %:: limeNil() ⇒
      compileAst(m)(fst); unbox(m)
      compileAst(m)(snd); unbox(m)
      m.visitInsn(fun); box(m)
    case InlineList(fun) %:: (list: Object) %:: limeNil() ⇒
      compileAst(m)(list)
      m.visitTypeInsn(CHECKCAST, "lime/List");
      m.visitMethodInsn(INVOKEVIRTUAL, "lime/List", fun, s"()$O", false);
    case 'cons %:: (fst: Object) %:: (snd: Object) %:: limeNil() ⇒
      m.visitTypeInsn(NEW, "lime/Cons")
      m.visitInsn(DUP)
      compileAst(m)(fst)
      compileAst(m)(snd)
      m.visitMethodInsn(INVOKESPECIAL, "lime/Cons", "<init>", s"($O$O)V", false)
  }

  private def unbox(m: MethodVisitor): Unit = {
    m.visitTypeInsn(CHECKCAST, "java/lang/Double")
    m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false);
  }

  private def box(m: MethodVisitor): Unit = {
    m.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false)
  }

  // lime.List support
  object %:: {
    def unapply(list: lime.List): Option[(Object, lime.List)] = list match {
      case cons: lime.Cons ⇒ Some(cons.car(), cons.cdr().asInstanceOf[lime.List])
      case _: lime.Nil     ⇒ None
    }
  }
  object limeNil {
    def unapply(list: lime.List): Boolean = list.isInstanceOf[lime.Nil]
  }
  implicit class LimeListOps(list: lime.List) {
    def foreach(f: Object ⇒ Unit): Unit = {
      var cur: lime.List = list
      while (!cur.isInstanceOf[lime.Nil]) {
        f(cur.car())
        cur = cur.cdr().asInstanceOf[lime.List]
      }
    }
  }
}

package japgolly.nyaya.test

import scala.collection.generic.CanBuildFrom
import scala.collection.immutable.NumericRange
import com.nicta.rng.{Rng, Size}
import scalaz._, Scalaz._, Validation._

class Gen[A](val f: GenSize => Rng[A]) {

  def data(gs: GenSize, ss: SampleSize): Rng[EphemeralStream[A]] =
    f(gs).fill(ss.value).map(EphemeralStream(_: _*))

  def map     [B](g: A => B)           = new Gen[B](s => f(s) map g)
  def mapr    [B](g: Rng[A] => Rng[B]) = new Gen[B](g compose f)
  def flatMap [B](g: A => Gen[B])      = new Gen[B](s => f(s).flatMap(a => g(a).f(s)))
  def flatMapS[B](g: A => GenS[B])     = new GenS[B](s => f(s).flatMap(a => g(a).f(s)))

  def subst[B >: A]    : Gen[B] = map[B](a => a)

  def withFilter(p: A => Boolean): Gen[A] =
    map(a => if (p(a)) a else
    // This seems crazy to be but it's how scala.Future does it
      throw new NoSuchElementException("Gen.withFilter predicate is not satisfied"))

  private def sizeOp[B](g: Rng[A] => Size => Rng[B]): GenS[B] =
    new GenS(s => g(f(s))(s.value))

  private def sizeOp[B, C](g: Rng[A] => Size => Rng[B], h: B => C): GenS[C] =
    new GenS(s => g(f(s))(s.value) map h)

  private def combrng[B, C](b: Gen[B], c: (Rng[A], Rng[B]) => Rng[C]): Gen[C] =
    new Gen(s => c(f(s), b.f(s)))

  def fill(n: Int): Gen[List[A]]             = mapr(_ fill n)
  def list        : GenS[List[A]]            = sizeOp(_.list)
  def list1       : GenS[NonEmptyList[A]]    = sizeOp(_.list1)
  def set         : GenS[Set[A]]             = sizeOp(_.list, (_: List[A]).toSet)
  def set1        : GenS[Set[A]]             = sizeOp(_.list1, (_: NonEmptyList[A]).list.toSet)
  def vector      : GenS[Vector[A]]          = sizeOp(_.vector)
  def vector1     : GenS[Vector[A]]          = vector.flatMap(s => this.map(s :+ _))
  def estream     : GenS[EphemeralStream[A]] = sizeOp(_.stream)
  def estream1    : GenS[EphemeralStream[A]] = estream.flatMap(s => this.map(_ ##:: s))
  def stream      : GenS[Stream[A]]          = estream.map(_.toStream)
  def stream1     : GenS[Stream[A]]          = stream.flatMap(s => this.map(_ #:: s))
  def option      : Gen[Option[A]]           = mapr(_.option)
  def pair        : Gen[(A, A)]              = mapr(r => Rng.pair(r, r))
  def triple      : Gen[(A, A, A)]           = mapr(r => Rng.triple(r, r, r))

  def ***       [X](x: Gen[X]): Gen[(A, X)]       = combrng[X, (A, X)](x, _ *** _)
  def \/        [X](x: Gen[X]): Gen[A \/ X]       = combrng[X, A \/ X](x, _ \/ _)
  def +++       [X](x: Gen[X]): Gen[A \/ X]       = combrng[X, A \/ X](x, _ +++ _)
  def validation[X](x: Gen[X]): Gen[A \?/ X]      = combrng[X, A \?/ X](x, _ validation _)
  def \?/       [X](x: Gen[X]): Gen[A \?/ X]      = combrng[X, A \?/ X](x, _ \?/ _)
  def either    [X](x: Gen[X]): Gen[Either[A, X]] = combrng[X, Either[A, X]](x, _ eitherS _)

  def \&/[X](that: Gen[X]): Gen[A \&/ X] = {
    import scalaz.\&/._
    Gen.oneofG(
      this.map(This.apply),
      that.map(That.apply),
      flatMap(a => that.map(b => Both(a, b))))
  }

  def mapBy[K](k: Gen[K]): Gen[Map[K, A]] = Gen.pair(k, this).list.map(_.toMap)
  def mapTo[V](v: Gen[V]): Gen[Map[A, V]] = v mapBy this

  /** Returns a new collection of the same type in a randomly chosen order.
    *
    *  @return         the shuffled collection
    */
  def shuffle[T, CC[X] <: TraversableOnce[X]](implicit ev: A <:< CC[T], bf: CanBuildFrom[CC[T], T, CC[T]]): Gen[CC[T]] =
    // Copied from scala.util.Random.shuffle
    flatMap { xs =>
      import scala.collection.mutable.ArrayBuffer

      @inline def swap(buf: ArrayBuffer[T], i1: Int, i2: Int): ArrayBuffer[T] = {
        val tmp = buf(i1)
        buf(i1) = buf(i2)
        buf(i2) = tmp
        buf
      }

      Gen.insert(new ArrayBuffer[T] ++= xs).flatMap { buf =>
        var g = Gen.insert(buf)
        for (n <- buf.length to 2 by -1) {
          g = g.flatMap(buf => Gen.chooseint(0, n - 1).map(k => swap(buf, n - 1, k)))
        }
        g.map(buf => (bf(xs) ++= buf).result())
      }
    }
}

class GenS[A](f: GenSize => Rng[A]) extends Gen(f) {
  /** Apply an upper bound to the GenSize. */
  def lim(size: Int) = {
    val t = GenSize(size)
    new GenS[A](s => f(if (s.value > size) t else s))
  }

  override def map    [B](g: A => B)           = new GenS[B](s => f(s) map g)
  override def mapr   [B](g: Rng[A] => Rng[B]) = new GenS[B](g compose f)
  override def flatMap[B](g: A => Gen[B])      = new GenS[B](s => f(s).flatMap(a => g(a).f(s)))

  def sup: Gen[A] = this
}

object Gen {

  implicit object GenScalaz extends Monad[Gen] {
    def bind[A, B](a: Gen[A])(f: A => Gen[B]) = a flatMap f
    def point[A](a: => A) = insert(a)
  }
  
  object Covariance {
    implicit def genCovariance[A, B >: A](r: Gen[A]) = r.subst[B]
  }

  def unsized[A](rng: Rng[A]) =
    new Gen[A](_ => rng)

  def lift[A](f: Size => Rng[A]) =
    new GenS[A](s => f(Size(s.value)))

  def double         : Gen[Double]  = Rng.double.gen
  def float          : Gen[Float]   = Rng.float.gen
  def long           : Gen[Long]    = rng_long.gen
  def int            : Gen[Int]     = Rng.int.gen
  def byte           : Gen[Byte]    = Rng.byte.gen
  def short          : Gen[Short]   = Rng.short.gen
  def unit           : Gen[Unit]    = Rng.unit.gen
  def boolean        : Gen[Boolean] = Rng.boolean.gen
  def positivedouble : Gen[Double]  = Rng.positivedouble.gen
  def negativedouble : Gen[Double]  = Rng.negativedouble.gen
  def positivefloat  : Gen[Float]   = Rng.positivefloat.gen
  def negativefloat  : Gen[Float]   = Rng.negativefloat.gen
  def positivelong   : Gen[Long]    = Rng.positivelong.gen
  def negativelong   : Gen[Long]    = Rng.negativelong.gen
  def positiveint    : Gen[Int]     = Rng.positiveint.gen
  def negativeint    : Gen[Int]     = Rng.negativeint.gen
  def digit          : Gen[Digit]   = Rng.digit.gen
  def numeric        : Gen[Char]    = Rng.numeric.gen
  def char           : Gen[Char]    = Rng.char.gen
  def upper          : Gen[Char]    = Rng.upper.gen
  def lower          : Gen[Char]    = Rng.lower.gen
  def alpha          : Gen[Char]    = Rng.alpha.gen
  def alphanumeric   : Gen[Char]    = Rng.alphanumeric.gen

  def digits              : GenS[List[Digit]]         = lift(Rng.digits)
  def digits1             : GenS[NonEmptyList[Digit]] = lift(Rng.digits1)
  def numerics            : GenS[List[Char]]          = lift(Rng.numerics)
  def numerics1           : GenS[NonEmptyList[Char]]  = lift(Rng.numerics1)
  def chars               : GenS[List[Char]]          = lift(Rng.chars)
  def chars1              : GenS[NonEmptyList[Char]]  = lift(Rng.chars1)
  def uppers              : GenS[List[Char]]          = lift(Rng.uppers)
  def uppers1             : GenS[NonEmptyList[Char]]  = lift(Rng.uppers1)
  def lowers              : GenS[List[Char]]          = lift(Rng.lowers)
  def lowers1             : GenS[NonEmptyList[Char]]  = lift(Rng.lowers1)
  def alphas              : GenS[List[Char]]          = lift(Rng.alphas)
  def alphas1             : GenS[NonEmptyList[Char]]  = lift(Rng.alphas1)
  def alphanumerics       : GenS[List[Char]]          = lift(Rng.alphanumerics)
  def alphanumerics1      : GenS[NonEmptyList[Char]]  = lift(Rng.alphanumerics1)
  def string              : GenS[String]              = lift(Rng.string)
  def string1             : GenS[String]              = lift(Rng.string1)
  def upperstring         : GenS[String]              = lift(Rng.upperstring)
  def upperstring1        : GenS[String]              = lift(Rng.upperstring1)
  def lowerstring         : GenS[String]              = lift(Rng.lowerstring)
  def lowerstring1        : GenS[String]              = lift(Rng.lowerstring1)
  def alphastring         : GenS[String]              = lift(Rng.alphastring)
  def alphastring1        : GenS[String]              = lift(Rng.alphastring1)
  def numericstring       : GenS[String]              = lift(Rng.numericstring)
  def numericstring1      : GenS[String]              = lift(Rng.numericstring1)
  def alphanumericstring  : GenS[String]              = lift(Rng.alphanumericstring)
  def alphanumericstring1 : GenS[String]              = lift(Rng.alphanumericstring1)
  def identifier          : GenS[NonEmptyList[Char]]  = lift(Rng.identifier)
  def identifierstring    : GenS[String]              = lift(Rng.identifierstring)
  def propernoun          : GenS[NonEmptyList[Char]]  = lift(Rng.propernoun)
  def propernounstring    : GenS[String]              = lift(Rng.propernounstring)

  def insert[A]    (a: A)                  : Gen[A]      = Rng.insert(a).gen
  /** Args are inclusive. [l,h] */
  def chooselong   (l: Long, h: Long)      : Gen[Long]   = Rng.chooselong(l,h).gen
  /** Args are inclusive. [l,h] */
  def choosedouble (l: Double, h: Double)  : Gen[Double] = Rng.choosedouble(l,h).gen
  /** Args are inclusive. [l,h] */
  def choosefloat  (l: Float, h: Float)    : Gen[Float]  = Rng.choosefloat(l,h).gen
  /** Args are inclusive. [l,h] */
  def chooseint    (l: Int, h: Int)        : Gen[Int]    = Rng.chooseint(l,h).gen
  def oneofL[A]    (x: NonEmptyList[A])    : Gen[A]      = Rng.oneofL(x).gen
  def oneof[A]     (a: A, as: A*)          : Gen[A]      = Rng.oneof(a, as: _*).gen
  def oneofV[A]    (x: OneAnd[Vector, A])  : Gen[A]      = Rng.oneofV(x).gen

  def pair[A, B](A: Gen[A], B: Gen[B]): Gen[(A, B)] = tuple2(A, B)
  def triple[A, B, C](A: Gen[A], B: Gen[B], C: Gen[C]): Gen[(A, B, C)] = tuple3(A, B, C)

  def sequence[T[_], A](x: T[Gen[A]])(implicit T: Traverse[T]): Gen[T[A]] = T.sequence(x)

  def sequencePair[X, A](x: X, r: Gen[A]): Gen[(X, A)] = sequence[({type f[x] = (X, x)})#f, A]((x, r))

  def distribute  [F[_], B]   (a: Gen[F[B]])(implicit D: Distributive[F])            : F[Gen[B]]             = D.cosequence(a)
  def distributeR [A, B]      (a: Gen[A => B])                                       : A => Gen[B]           = distribute[({type f[x] = A => x})#f, B](a)
  def distributeRK[A, B]      (a: Gen[A => B])                                       : Kleisli[Gen, A, B]    = Kleisli(distributeR(a))
  def distributeK [F[_], A, B](a: Gen[Kleisli[F, A, B]])(implicit D: Distributive[F]): Kleisli[F, A, Gen[B]] = distribute[({type f[x] = Kleisli[F, A, x]})#f, B](a)

  private def freqRng[A](s: GenSize): ((Int, Gen[A])) => (Int, Rng[A]) =
    x => (x._1, x._2.f(s))

  def frequency[A](x: (Int, Gen[A]), xs: (Int, Gen[A])*): Gen[A] =
    new Gen[A](s => {
      val f = freqRng[A](s)
      Rng.frequency(f(x), xs.map(f): _*)
    })

  def frequencyL[A](l: NonEmptyList[(Int, Gen[A])]): Gen[A] =
    new Gen[A](s => Rng.frequencyL(l map freqRng[A](s)))

  /** Nicta one uses +. | will be faster. */
  private[this] def rng_long: Rng[Long] =
    Rng.int.flatMap(a => Rng.int.map(b =>
      (a.toLong << 32) | b.toLong
    ))

  // -------------------------------------------------------------------------------------------------------------------

  def oneofG[A](a: Gen[A], as: Gen[A]*): Gen[A] =
    Rng.oneof(a, as: _*).gen.flatMap(r => r)

  def oneofGC[A, B >: A](a: Gen[A], as: Gen[A]*): Gen[B] =
    oneofG(a.subst[B], as.map(_.subst[B]): _*)

  def charof(ev: Char, s: String, rs: NumericRange[Char]*): Gen[Char] =
    oneof(ev, rs.foldLeft(s.to[Seq])(_ ++ _.toSeq): _*)

//  def oneofUnsafe[A](s: Seq[A]): Gen[A] =
//    oneof(s.head, s.tail: _*)

  def shuffle[T, CC[X] <: TraversableOnce[X]](xs: CC[T])(implicit bf: CanBuildFrom[CC[T], T, CC[T]]): Gen[CC[T]] =
    Gen.insert(xs).shuffle

  // Generated by bin/gen-tuple_rnggen
  def tuple2[A,B](A:Gen[A], B:Gen[B]): Gen[(A,B)] = for {a←A;b←B} yield (a,b)
  def tuple3[A,B,C](A:Gen[A], B:Gen[B], C:Gen[C]): Gen[(A,B,C)] = for {a←A;b←B;c←C} yield (a,b,c)
  def tuple4[A,B,C,D](A:Gen[A], B:Gen[B], C:Gen[C], D:Gen[D]): Gen[(A,B,C,D)] = for {a←A;b←B;c←C;d←D} yield (a,b,c,d)
  def tuple5[A,B,C,D,E](A:Gen[A], B:Gen[B], C:Gen[C], D:Gen[D], E:Gen[E]): Gen[(A,B,C,D,E)] = for {a←A;b←B;c←C;d←D;e←E} yield (a,b,c,d,e)
  def tuple6[A,B,C,D,E,F](A:Gen[A], B:Gen[B], C:Gen[C], D:Gen[D], E:Gen[E], F:Gen[F]): Gen[(A,B,C,D,E,F)] = for {a←A;b←B;c←C;d←D;e←E;f←F} yield (a,b,c,d,e,f)
  def tuple7[A,B,C,D,E,F,G](A:Gen[A], B:Gen[B], C:Gen[C], D:Gen[D], E:Gen[E], F:Gen[F], G:Gen[G]): Gen[(A,B,C,D,E,F,G)] = for {a←A;b←B;c←C;d←D;e←E;f←F;g←G} yield (a,b,c,d,e,f,g)
  def tuple8[A,B,C,D,E,F,G,H](A:Gen[A], B:Gen[B], C:Gen[C], D:Gen[D], E:Gen[E], F:Gen[F], G:Gen[G], H:Gen[H]): Gen[(A,B,C,D,E,F,G,H)] = for {a←A;b←B;c←C;d←D;e←E;f←F;g←G;h←H} yield (a,b,c,d,e,f,g,h)
  def tuple9[A,B,C,D,E,F,G,H,I](A:Gen[A], B:Gen[B], C:Gen[C], D:Gen[D], E:Gen[E], F:Gen[F], G:Gen[G], H:Gen[H], I:Gen[I]): Gen[(A,B,C,D,E,F,G,H,I)] = for {a←A;b←B;c←C;d←D;e←E;f←F;g←G;h←H;i←I} yield (a,b,c,d,e,f,g,h,i)
  def apply2[A,B,Z](z: (A,B)⇒Z)(A:Gen[A], B:Gen[B]): Gen[Z] = for {a←A;b←B} yield z(a,b)
  def apply3[A,B,C,Z](z: (A,B,C)⇒Z)(A:Gen[A], B:Gen[B], C:Gen[C]): Gen[Z] = for {a←A;b←B;c←C} yield z(a,b,c)
  def apply4[A,B,C,D,Z](z: (A,B,C,D)⇒Z)(A:Gen[A], B:Gen[B], C:Gen[C], D:Gen[D]): Gen[Z] = for {a←A;b←B;c←C;d←D} yield z(a,b,c,d)
  def apply5[A,B,C,D,E,Z](z: (A,B,C,D,E)⇒Z)(A:Gen[A], B:Gen[B], C:Gen[C], D:Gen[D], E:Gen[E]): Gen[Z] = for {a←A;b←B;c←C;d←D;e←E} yield z(a,b,c,d,e)
  def apply6[A,B,C,D,E,F,Z](z: (A,B,C,D,E,F)⇒Z)(A:Gen[A], B:Gen[B], C:Gen[C], D:Gen[D], E:Gen[E], F:Gen[F]): Gen[Z] = for {a←A;b←B;c←C;d←D;e←E;f←F} yield z(a,b,c,d,e,f)
  def apply7[A,B,C,D,E,F,G,Z](z: (A,B,C,D,E,F,G)⇒Z)(A:Gen[A], B:Gen[B], C:Gen[C], D:Gen[D], E:Gen[E], F:Gen[F], G:Gen[G]): Gen[Z] = for {a←A;b←B;c←C;d←D;e←E;f←F;g←G} yield z(a,b,c,d,e,f,g)
  def apply8[A,B,C,D,E,F,G,H,Z](z: (A,B,C,D,E,F,G,H)⇒Z)(A:Gen[A], B:Gen[B], C:Gen[C], D:Gen[D], E:Gen[E], F:Gen[F], G:Gen[G], H:Gen[H]): Gen[Z] = for {a←A;b←B;c←C;d←D;e←E;f←F;g←G;h←H} yield z(a,b,c,d,e,f,g,h)
  def apply9[A,B,C,D,E,F,G,H,I,Z](z: (A,B,C,D,E,F,G,H,I)⇒Z)(A:Gen[A], B:Gen[B], C:Gen[C], D:Gen[D], E:Gen[E], F:Gen[F], G:Gen[G], H:Gen[H], I:Gen[I]): Gen[Z] = for {a←A;b←B;c←C;d←D;e←E;f←F;g←G;h←H;i←I} yield z(a,b,c,d,e,f,g,h,i)
}

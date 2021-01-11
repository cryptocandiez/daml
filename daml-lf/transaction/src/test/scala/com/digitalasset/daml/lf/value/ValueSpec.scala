// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.lf
package value

import scala.collection.compat._
import data.{Bytes, ImmArray, Ref}
import Value._
import Ref.{Identifier, Name}
import com.daml.lf.transaction.TransactionVersion
import test.ValueGenerators.{coidGen, idGen, nameGen}
import test.TypedValueGenerators.{RNil, genAddend, ValueAddend => VA}
import com.daml.scalatest.Unnatural
import org.scalacheck.{Arbitrary, Gen, Shrink}
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers
import org.scalatest.freespec.AnyFreeSpec
import org.scalatestplus.scalacheck.{Checkers, ScalaCheckPropertyChecks}
import scalaz.{Order, Tag}
import scalaz.std.anyVal._
import scalaz.syntax.functor._
import scalaz.syntax.order._
import scalaz.scalacheck.{ScalazProperties => SzP}
import scalaz.scalacheck.ScalaCheckBinding._
import shapeless.syntax.singleton._

class ValueSpec
    extends AnyFreeSpec
    with Matchers
    with Inside
    with Checkers
    with ScalaCheckPropertyChecks {
  import ValueSpec._

  "serialize" - {
    val exceedsNesting = (1 to MAXIMUM_NESTING + 1).foldRight[Value[Nothing]](ValueInt64(42)) {
      case (_, v) => ValueVariant(None, Ref.Name.assertFromString("foo"), v)
    }
    val exceedsNestingError = s"exceeds maximum nesting value of $MAXIMUM_NESTING"
    val matchesNesting = (1 to MAXIMUM_NESTING).foldRight[Value[Nothing]](ValueInt64(42)) {
      case (_, v) => ValueVariant(None, Ref.Name.assertFromString("foo"), v)
    }

    "rejects excessive nesting" in {
      exceedsNesting.serializable() shouldBe ImmArray(exceedsNestingError)
    }

    "accepts just right nesting" in {
      matchesNesting.serializable() shouldBe ImmArray.empty
    }
  }

  "VersionedValue" - {

    val pkgId = Ref.PackageId.assertFromString("pkgId")
    val tmplId = Ref.Identifier(pkgId, Ref.QualifiedName.assertFromString("Mod:Template"))

    "does not bump version when" - {

      "ensureNoCid is used " in {
        val value = VersionedValue[ContractId](TransactionVersion.minVersion, ValueUnit)
        val contract = ContractInst(tmplId, value, "agreed")
        value.ensureNoCid.map(_.version) shouldBe Right(TransactionVersion.minVersion)
        contract.ensureNoCid.map(_.arg.version) shouldBe Right(TransactionVersion.minVersion)

      }

    }

  }

  "ContractID.V1.build" - {

    "rejects too long suffix" in {

      def suffix(size: Int) =
        Bytes.fromByteArray(Array.iterate(0.toByte, size)(b => (b + 1).toByte))

      val hash = crypto.Hash.hashPrivateKey("some hash")
      import ContractId.V1.build
      build(hash, suffix(0)) shouldBe Symbol("right")
      build(hash, suffix(94)) shouldBe Symbol("right")
      build(hash, suffix(95)) shouldBe Symbol("left")
      build(hash, suffix(96)) shouldBe Symbol("left")
      build(hash, suffix(127)) shouldBe Symbol("left")

    }

  }

  "Equal" - {
    import com.daml.lf.value.test.ValueGenerators._
    import org.scalacheck.Arbitrary
    type T = VersionedValue[Unnatural[ContractId]]
    implicit val arbT: Arbitrary[T] =
      Arbitrary(versionedValueGen.map(VersionedValue.map1(Unnatural(_))))

    "obeys Equal laws" in checkLaws(SzP.equal.laws[T])

    "results preserve natural == results" in forAll { (a: T, b: T) =>
      scalaz.Equal[T].equal(a, b) shouldBe (a == b)
    }
  }

  "ContractId" - {
    type T = ContractId
    implicit val arbT: Arbitrary[T] = Arbitrary(coidGen)
    "Order" - {
      "obeys Order laws" in checkLaws(SzP.order.laws[T])
    }
  }

  // XXX can factor like FlatSpecCheckLaws
  private def checkLaws(props: org.scalacheck.Properties) =
    forEvery(Table(("law", "property"), props.properties.toSeq: _*)) { (_, p) =>
      check(p, minSuccessful(20))
    }

  private def checkOrderPreserved[Cid: Arbitrary: Shrink: Order](
      va: VA,
      scope: Value.LookupVariantEnum,
  ) = {
    import va.{injord, injarb, injshrink}
    implicit val targetOrd: Order[Value[Cid]] = Tag unsubst Value.orderInstance(scope)
    forAll(minSuccessful(20)) { (a: va.Inj[Cid], b: va.Inj[Cid]) =>
      (a ?|? b) should ===(va.inj(a) ?|? va.inj(b))
    }
  }

  "Order" - {
    type Cid = Int
    type T = Value[Cid]

    val FooScope: Value.LookupVariantEnum =
      Map(fooVariantId -> ImmArray("quux", "baz"), fooEnumId -> ImmArray("quux", "baz"))
        .transform((_, ns) => ns map Ref.Name.assertFromString)
        .lift

    "for primitive, matching types" - {
      val EmptyScope: Value.LookupVariantEnum = _ => None
      implicit val ord: Order[T] = Tag unsubst Value.orderInstance(EmptyScope)

      "obeys order laws" in forAll(genAddend, minSuccessful(100)) { va =>
        implicit val arb: Arbitrary[T] = va.injarb[Cid] map (va.inj(_))
        checkLaws(SzP.order.laws[T])
      }
    }

    "for record and variant types" - {
      implicit val ord: Order[T] = Tag unsubst Value.orderInstance(FooScope)
      "obeys order laws" in forEvery(Table("va", fooRecord, fooVariant)) { va =>
        implicit val arb: Arbitrary[T] = va.injarb[Cid] map (va.inj(_))
        checkLaws(SzP.order.laws[T])
      }

      "matches constructor rank" in {
        val fooCp = shapeless.Coproduct[fooVariant.Inj[Cid]]
        val quux = fooCp(Symbol("quux") ->> 42L)
        val baz = fooCp(Symbol("baz") ->> 42L)
        (fooVariant.inj(quux) ?|? fooVariant.inj(baz)) shouldBe scalaz.Ordering.LT
      }

      "preserves base order" in forEvery(Table("va", fooRecord, fooVariant)) { va =>
        checkOrderPreserved[Cid](va, FooScope)
      }
    }

    "for enum types" - {
      "obeys order laws" in forAll(enumDetailsAndScopeGen, minSuccessful(20)) {
        case (details, scope) =>
          implicit val ord: Order[T] = Tag unsubst Value.orderInstance(scope)
          forEvery(Table("va", details.values.toSeq: _*)) { ea =>
            implicit val arb: Arbitrary[T] = ea.injarb[Cid] map (ea.inj(_))
            checkLaws(SzP.order.laws[T])
          }
      }

      "matches constructor rank" in forAll(enumDetailsAndScopeGen, minSuccessful(20)) {
        case (details, scope) =>
          implicit val ord: Order[T] = Tag unsubst Value.orderInstance(scope)
          forEvery(Table("va", details.values.toSeq: _*)) { ea =>
            implicit val arb: Arbitrary[T] = ea.injarb[Cid] map (ea.inj(_))
            forAll(minSuccessful(20)) { (a: T, b: T) =>
              inside((a, b)) { case (ValueEnum(_, ac), ValueEnum(_, bc)) =>
                (a ?|? b) should ===((ea.values indexOf ac) ?|? (ea.values indexOf bc))
              }
            }
          }
      }

      "preserves base order" in forAll(enumDetailsAndScopeGen, minSuccessful(20)) {
        case (details, scope) =>
          forEvery(Table("va", details.values.toSeq: _*)) { ea =>
            checkOrderPreserved[Cid](ea, scope)
          }
      }
    }
  }

}

object ValueSpec {
  private val fooSpec =
    Symbol("quux") ->> VA.int64 :: Symbol("baz") ->> VA.int64 :: RNil
  private val (_, fooRecord) = VA.record(Identifier assertFromString "abc:Foo:FooRec", fooSpec)
  private val fooVariantId = Identifier assertFromString "abc:Foo:FooVar"
  private val (_, fooVariant) = VA.variant(fooVariantId, fooSpec)
  private val fooEnumId = Identifier assertFromString "abc:Foo:FooEnum"

  private[this] val scopeOfEnumsGen: Gen[Map[Identifier, Seq[Name]]] =
    Gen.mapOf(Gen.zip(idGen, Gen.nonEmptyContainerOf[Set, Name](nameGen) map (_.toSeq)))

  private val enumDetailsAndScopeGen
      : Gen[(Map[Identifier, VA.EnumAddend[Seq[Name]]], Value.LookupVariantEnum)] =
    scopeOfEnumsGen flatMap { details =>
      (
        details transform ((name, members) => VA.enum(name, members)._2),
        details
          .transform((_, members) => members.to(ImmArray))
          .lift,
      )
    }
  /*
  private val genFoos: Gen[(ImmArray[Name], ValueAddend)] =
    Gen.nonEmptyContainerOf[ImmArray, Name](ValueGenerators.nameGen)
    Gen.oneOf(
    fooRecord,
    fooVariant,
     map ()
  )
   */
}

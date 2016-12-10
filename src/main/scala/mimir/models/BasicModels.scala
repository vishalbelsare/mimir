package mimir.models

import mimir.algebra._

import scala.util._

abstract class SingleVarModel(name: String) extends Model(name) {

  def varType(argTypes: List[Type]): Type
  def bestGuess(args: List[PrimitiveValue]): PrimitiveValue
  def sample(randomness: Random, args: List[PrimitiveValue]): PrimitiveValue
  def reason(args: List[PrimitiveValue]): String
  def feedback(args: List[PrimitiveValue], v: PrimitiveValue): Unit
  def isAcknowledged (args: List[PrimitiveValue]): Boolean

  def varType(x: Int, argTypes: List[Type]): Type =
    varType(argTypes)
  def bestGuess(x:Int, args: List[PrimitiveValue]): PrimitiveValue =
    bestGuess(args)
  def sample(x:Int, randomness: Random, args: List[PrimitiveValue]): PrimitiveValue =
    sample(randomness, args)
  def reason(x:Int, args: List[PrimitiveValue]): String =
    reason(args)
  def feedback(x:Int, args: List[PrimitiveValue], v: PrimitiveValue): Unit = 
    feedback(args, v)
  def isAcknowledged (idx: Int, args: List[PrimitiveValue]): Boolean =
    isAcknowledged(args)
}

case class IndependentVarsModel(override val name: String, vars: List[SingleVarModel]) extends Model(name) {

  def varType(idx: Int, argTypes: List[Type]) =
    vars(idx).varType(argTypes)
  def bestGuess(idx: Int, args: List[PrimitiveValue]) = 
    vars(idx).bestGuess(args);
  def sample(idx: Int, randomness: Random, args: List[PrimitiveValue]) =
    vars(idx).sample(randomness, args)
  def reason(idx: Int, args: List[PrimitiveValue]): String =
    vars(idx).reason(idx, args)
  def feedback(idx: Int, args: List[PrimitiveValue], v: PrimitiveValue): Unit =
    vars(idx).feedback(args, v)
  def isAcknowledged (idx: Int, args: List[PrimitiveValue]): Boolean =
    vars(idx).isAcknowledged(args)
}

object UniformDistribution extends SingleVarModel("UNIFORM") with Serializable {
  def varType(argTypes: List[Type]) = TFloat()
  def bestGuess(args: List[PrimitiveValue]) = 
    FloatPrimitive((args(0).asDouble + args(1).asDouble) / 2.0)
  def sample(randomness: Random, args: List[PrimitiveValue]) = {
    val low = args(0).asDouble
    val high = args(1).asDouble
    FloatPrimitive(
      (randomness.nextDouble() * (high - low)) + high
    )
  }
  def bestGuessExpr(args: List[Expression]) = 
    Arithmetic(Arith.Div,
      Arithmetic(Arith.Add, args(0), args(1)),
      FloatPrimitive(2.0)
    )
  def sampleExpr(randomness: Expression, args: List[Expression]) = 
    Arithmetic(Arith.Add,
      Arithmetic(Arith.Mult,
        randomness,
        Arithmetic(Arith.Sub, args(1), args(0))
      ),
      args(0)
    )


  def reason(args: List[PrimitiveValue]): String = 
    "I put in a random value between "+args(0)+" and "+args(1)

  def feedback(args: List[PrimitiveValue], v: PrimitiveValue): Unit =
    throw ModelException("Unsupported: Feedback on UniformDistribution")

  def isAcknowledged (args: List[PrimitiveValue]): Boolean =
    false
}

case class NoOpModel(override val name: String, vt: Type, reasonText:String) 
  extends SingleVarModel(name) 
  with Serializable 
{

  var acked = false

  def varType(argTypes: List[Type]) = vt
  def bestGuess(args: List[PrimitiveValue]) = args(0)
  def sample(randomness: Random, args: List[PrimitiveValue]) = args(0)
  def reason(args: List[PrimitiveValue]): String = reasonText
  def feedback(args: List[PrimitiveValue], v: PrimitiveValue): Unit = { acked = true }
  def isAcknowledged (args: List[PrimitiveValue]): Boolean = acked
}
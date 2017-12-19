package mimir.models

import scala.util.Random
import com.typesafe.scalalogging.slf4j.Logger

import mimir.Database
import mimir.algebra._
import mimir.util._


object TypeInferenceModel
{
  val logger = Logger(org.slf4j.LoggerFactory.getLogger("mimir.models.TypeInferenceModel"))

  def priority: Type => Int =
  {
    case TUser(_)     => 20
    case TInt()       => 10
    case TBool()      => 10
    case TDate()      => 10
    case TTimestamp() => 10
    case TInterval()  => 10
    case TType()      => 10
    case TFloat()     => 5
    case TString()    => 0
    case TRowId()     => -5
    case TAny()       => -10
  }

  def detectType(v: String): Iterable[Type] = {
    Type.tests.flatMap({ case (t, regexp) =>
      regexp.findFirstMatchIn(v).map(_ => t)
    })++
    TypeRegistry.matchers.flatMap({ case (regexp, name) =>
      regexp.findFirstMatchIn(v).map(_ => TUser(name))
    })

  }
}

@SerialVersionUID(1001L)
class TypeInferenceModel(name: String, val columns: IndexedSeq[String], defaultFrac: Double)
  extends Model(name)
  with SourcedFeedback
  with FiniteDiscreteDomain
{
  var sampleLimit = 1000
  var totalVotes = 
    { val v = new scala.collection.mutable.ArraySeq[Double](columns.length)
      for(col <- (0 until columns.size)){ v.update(col, 0.0) }
      v
    }
  val votes = 
    columns.map(
      _ => scala.collection.mutable.Map[Type, Double]()
    )

  def train(db: Database, query: Operator) =
  {
    Timer.monitor(s"Train $name", TypeInferenceModel.logger.info(_)){
      db.query(
        Limit(0, Some(sampleLimit), Project(
          columns.map( c => ProjectArg(c, Var(c)) ),
          query
        ))
      ) { _.foreach { row => learn(row.tuple)  } }
    }
    TypeInferenceModel.logger.debug(s"VOTES:${columns.zip(votes).map { col => "\n   "+col._1+": "+col._2.map { vote => "\n      "+vote._1+"->"+vote._2 }}}")
  }

  final def learn(row: Seq[PrimitiveValue]):Unit =
  {
    row.zipWithIndex.foreach({ case (v, idx) => learn(idx, v) })
  }

  final def learn(idx: Int, p: PrimitiveValue):Unit =
  {
    p match { 
      case null            => ()
      case NullPrimitive() => ()
      case _               => learn(idx, p.asString)
    }
  }

  final def learn(idx: Int, v: String):Unit =
  {
    totalVotes(idx) += 1.0
    val candidates = TypeInferenceModel.detectType(v)
    TypeInferenceModel.logger.trace(s"Guesses for '$v': $candidates")
    val votesForCurrentIdx = votes(idx)
    for(t <- candidates){
      votesForCurrentIdx(t) = votesForCurrentIdx.getOrElse(t, 0.0) + 1.0 
    }
  }

  private final def voteList(idx: Int) = 
    (TString(), defaultFrac * totalVotes(idx)) :: votes(idx).toList

  private final def rankFn(x:(Type, Double)) =
    (x._2, TypeInferenceModel.priority(x._1) )

  def varType(idx: Int, argTypes: Seq[Type]) = TType()
  def sample(idx: Int, randomness: Random, args: Seq[PrimitiveValue], hints: Seq[PrimitiveValue]): PrimitiveValue = {
    val column = args(0).asInt
    TypePrimitive(
      RandUtils.pickFromWeightedList(randomness, voteList(column))
    )
  }

  def bestGuess(idx: Int, args: Seq[PrimitiveValue], hints: Seq[PrimitiveValue]): PrimitiveValue = 
  {
    val column = args(0).asInt
    getFeedback(idx, args) match {
      case None => {
        val guess =  voteList(column).maxBy( rankFn _ )._1
        TypePrimitive(guess)
      }
      case Some(s) => Cast(TType(), s)
    }
  }

  def validateChoice(idx: Int, v: PrimitiveValue): Boolean =
    try { Cast(TType(), v); true } catch { case _:RAException => false }


  def reason(idx: Int, args: Seq[PrimitiveValue], hints: Seq[PrimitiveValue]): String = {
    val column = args(0).asInt
    getFeedback(idx, args) match {
      case None => {
        val (guess, guessVotes) = voteList(column).maxBy( rankFn _ )
        val defaultPct = (defaultFrac * 100).toInt
        val guessPct = ((guessVotes / totalVotes(column))*100).toInt
        val typeStr = Type.toString(guess).toUpperCase
        val reason =
          guess match {
            case TString() =>
              s"not more than $defaultPct% of the data fit anything else"
            case _ if (guessPct >= 100) =>
              "all of the data fit"
            case _ => 
              s"around $guessPct% of the data fit"
          }
        s"I guessed that $name.${columns(column)} was of type $typeStr because $reason"
      }
      case Some(t) =>
        val typeStr = Cast(TType(), t).toString.toUpperCase
        s"${getReasonWho(column,args)} told me that $name.${columns(column)} was of type $typeStr"
    }
  }

  def getDomain(idx: Int, args: Seq[PrimitiveValue], hints: Seq[PrimitiveValue]): Seq[(PrimitiveValue,Double)] = 
  {
    val column = args(0).asInt
    votes(column).toList.map( x => (TypePrimitive(x._1), x._2)) ++ Seq( (TypePrimitive(TString()), defaultFrac) )
  }

  def feedback(idx: Int, args: Seq[PrimitiveValue], v: PrimitiveValue): Unit =
  {
    if(v.isInstanceOf[TypePrimitive]){
      setFeedback(idx, args, v)
    } else {
      throw new ModelException(s"Invalid choice for $name: $v")
    }
  }

  def isAcknowledged(idx: Int,args: Seq[mimir.algebra.PrimitiveValue]): Boolean =
    isPerfectGuess(args(0).asInt) || (getFeedback(idx, args) != None)
  def isPerfectGuess(column: Int): Boolean =
    voteList(column).map( _._2 ).max >= totalVotes(column)
  def getFeedbackKey(idx: Int, args: Seq[PrimitiveValue]): String = 
    args(0).asString
  def argTypes(idx: Int): Seq[Type] = 
    Seq(TInt())
  def hintTypes(idx: Int): Seq[Type] = 
    Seq()


  def confidence (idx: Int, args: Seq[PrimitiveValue], hints:Seq[PrimitiveValue]) : Double = {
    val column = args(0).asInt
    getFeedback(idx, args) match {
      case None => {
        val (guess, guessVotes) = voteList(column).maxBy( rankFn _ )
        val defaultPct = (defaultFrac * 100).toInt
        val guessPct = ((guessVotes / totalVotes(column))*100).toInt
        val typeStr = Type.toString(guess).toUpperCase
        if (guessPct > defaultPct)
          guessVotes / totalVotes(column)
        else
          defaultFrac
        }
      case Some(t) => 1.0
    }
  }
  
}

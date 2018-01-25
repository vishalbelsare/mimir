package mimir.models

import java.io._
import java.util.Calendar
import org.specs2.specification._

import org.specs2.mutable._
import mimir.algebra._
import mimir.util._
import mimir.test._

object TypeInferenceModelComparison extends SQLTestSpecification("TypeInferenceComparisonTests")
with BeforeAll
{
 sequential
 def BeforeAll = {
   update("CREATE TABLE Progressive_update(Category1 float, Category2 string)")
   loadCSV("Progressive_update", new File("test/data/InferenceModelProgressiveSanity.csv"))
 }

  def guess(model: Model): Type =
  {
    model.bestGuess(0, List[PrimitiveValue](), List()) match {
      case TypePrimitive(t) => t
      case x => throw new RAException(s"Type inference model guessed a non-type primitive: $x")
    }
  }
  
  "The Type Inference Model Sanity Check" should
  {
    "Determine first 1000 values" >> 
    {
      println("Running Original method at time: " + Calendar.getInstance.getTime)
      val model = new TypeInferenceModel("PROGRESSIVE_UPDATE:CATEGORY1",Array("CATEGORY1"), 0.5, 1000, db.table("Progressive_update"))
      model.train(db,table("PROGRESSIVE_UPDATE"))
      println("Original method completion time: " + Calendar.getInstance.getTime + "\n" + "And guessed: " + model.getDomain(0, Seq(IntPrimitive(0L)), Seq()))
      guess(model) must be equalTo(TInt()) or equalTo(TFloat())
    }
    
    //TODO: Add test that cycles and take progress, outputs the sequence
    "Determine the subsequent values" >> 
    {
      val model = new TypeInferenceModel("PROGRESSIVE_UPDATE:CATEGORY1",Array("CATEGORY1"),0.5,1000,db.table("Progressive_update"))
      db.models.persist(model)
      
      guess(model) must be equalTo(TString())
    }
  }
  
  
}
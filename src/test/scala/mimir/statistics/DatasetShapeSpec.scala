package mimir.statistics

import org.specs2.mutable._
import org.specs2.specification._
import java.io.File
import mimir.algebra.{Var, StringPrimitive, ID}
import mimir.test._
import mimir.util._

object DatasetShapeSpec
  extends SQLTestSpecification("DatasetShapeSpec")
  with BeforeAll
{

  def beforeAll = {
    db.loader.loadTable(
      targetTable = Some(ID("Z")), 
      sourceFile = "test/r_test/z.csv",
      datasourceErrors = true
    )
    db.loader.loadTable(
      targetTable = Some(ID("Z_BAD")), 
      sourceFile = "test/r_test/z_bad.csv",
      datasourceErrors = true
    )
  }

  "The Dataset Shape Detector" should 
  {
    "Detect the basics" >> {
      val ret = DatasetShape.detect(db, db.table("Z")).map { _.description }
      ret must contain { contain("A, B") }.atLeastOnce // columns
      ret must contain { contain("A should be an int") }.atLeastOnce // columns
    }
    
    "Detect and test the basics with Adaptive Schema and work with DataSource Errors" >> {
      //good data
      db.adaptiveSchemas.create( 
        ID("Z_SW"), 
        ID("SHAPE_WATCHER"),
        db.table("Z"), 
        Seq(StringPrimitive("MIMIR_SHAPE_Z")), 
        "Z_SHAPE"
      )
      //bad data
      db.adaptiveSchemas.create( 
        ID("Z_BAD_SW"), 
        ID("SHAPE_WATCHER"), 
        db.table("Z_BAD"), 
        Seq(Var(ID("MIMIR_SHAPE_Z"))), 
        "Z_BAD_SHAPE"
      )
      
      db.views.create(ID("Z_BAD_S"), db.adaptiveSchemas.viewFor(ID("Z_BAD_SW"), ID("Z_BAD_SW")).get)
      
      val resultSets = 
        LoggerUtils.debug(
          // "mimir.ctables.CTExplainer"
        ) {
          db.uncertainty.explainEverything(db.table("Z_BAD_S"))
        }
      
      resultSets.map(_.all(db).map(_.toJSON)).flatten must contain(eachOf(
          """{"rowidarg":-1,"source":"MIMIR_SHAPE_Z","confirmed":false,"varid":0,"english":"Missing expected column 'B'","repair":{"selector":"warning"},"args":[0,"'Missing expected column 'B''"]}""",
          """{"rowidarg":-1,"source":"MIMIR_SHAPE_Z","confirmed":false,"varid":0,"english":"A had no nulls before, but now has 2","repair":{"selector":"warning"},"args":[3,"'A had no nulls before, but now has 2'"]}""",
          """{"rowidarg":-1,"source":"MIMIR_SHAPE_Z","confirmed":false,"varid":0,"english":"Unexpected column 'B_0'","repair":{"selector":"warning"},"args":[0,"'Unexpected column 'B_0''"]}""",
          """{"rowidarg":0,"source":"MIMIR_DSE_WARNING_Z_BAD_DSE","confirmed":false,"varid":0,"english":"The value [ NULL ] is uncertain because there is an error(s) in the data source on row 911701464 of Z_BAD. The raw value of the row in the data source is [ ,,, ]","repair":{"selector":"warning"},"args":["'911701464'","'_c0'","NULL","',,,'"]}""",
          """{"rowidarg":0,"source":"MIMIR_DSE_WARNING_Z_BAD_DSE","confirmed":false,"varid":0,"english":"The value [ ---- ] is uncertain because there is an error(s) in the data source on row -2127400930 of Z_BAD. The raw value of the row in the data source is [ ---- ]","repair":{"selector":"warning"},"args":["'-2127400930'","'_c0'","'----'","'----'"]}""",
          """{"rowidarg":0,"source":"MIMIR_DSE_WARNING_Z_BAD_DSE","confirmed":false,"varid":0,"english":"The value [ NULL ] is uncertain because there is an error(s) in the data source on row 911701464 of Z_BAD. The raw value of the row in the data source is [ ,,, ]","repair":{"selector":"warning"},"args":["'911701464'","'_c1'","NULL","',,,'"]}""",
          """{"rowidarg":0,"source":"MIMIR_DSE_WARNING_Z_BAD_DSE","confirmed":false,"varid":0,"english":"The value [ NULL ] is uncertain because there is an error(s) in the data source on row -2127400930 of Z_BAD. The raw value of the row in the data source is [ ---- ]","repair":{"selector":"warning"},"args":["'-2127400930'","'_c1'","NULL","'----'"]}"""
          ))
      
      val result = query("""
        SELECT * FROM Z_BAD_S
      """)(_.toList.map(_.tuple))
      result must not be empty
    }

  }

}
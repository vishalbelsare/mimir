package mimir.demo

import mimir.exec.ResultIterator
import mimir.test._
import mimir.util.TimeUtils
import org.specs2.matcher.FileMatchers

object SparkTestsSqlite extends SQLTestSpecification("databases/debug",Map("reset" -> "NO", "inline" -> "NO"))
  with FileMatchers
{

  // The demo spec uses cumulative tests --- Each stage depends on the stages that
  // precede it.  The 'sequential' keyword below is necessary to prevent Specs2 from
  // automatically parallelizing testing.
  sequential

  def time[A](description: String, op: () => A): A = {
    val t:StringBuilder = new StringBuilder()
    TimeUtils.monitor(description, op, println(_))
  }


  "The Basic Demo" should {
    "Be able to open the database" >> {
      db // force the DB to be loaded
      dbFile must beAFile
    }

    "Load Files" >> {
      //"test/data/ratings1.csv"
/*
      val logFile = "test/data/ratings1.csv" // Should be some file on your system
      val conf = new SparkConf().setAppName("Simple Application").setMaster("local[*]")
      val sc = new SparkContext(conf)
      val logData = sc.textFile(logFile, 2).cache()
      val numAs = logData.filter(line => line.contains("4")).count()
      val numBs = logData.filter(line => line.contains("b")).count()
      println("Lines with a: %s, Lines with b: %s".format(numAs, numBs))
*/
/*
      val logFile = "test/data/ratings1.csv" // Should be some file on your system
      val conf = new SparkConf().setAppName("Simple Application").setMaster("local[*]")
      val sc = new SparkContext(conf)
      val spark = SparkSession
      .builder()
      .config(conf)
      .getOrCreate()
      
      spark.read.csv("test/data/ratings1.csv").show()
*/
//      val res1: ResultIterator = query("SELECT A FROM R")
//      val res2: ResultIterator = query("SELECT * FROM R")
/*
      time("AVERAGE 2M rows, first time",() => {
        val res3: ResultIterator = query("SELECT AVG(bearing) FROM MTA_RAW")
      })

      time("AVERAGE 2M rows, second time",() => {
        val res3: ResultIterator = query("SELECT AVG(bearing) FROM MTA_RAW")
      })
*/
      time("Simple UDF Test",() => {
        val res3: ResultIterator = query("SELECT SUM(SIMPLETEST(bearing)) FROM MTA_RAW")
      })
//      val res3: ResultIterator = query("SELECT * FROM R , CITYRAW")
//      val res4: ResultIterator = query("SELECT * FROM CITYRAW")

      println("done")
      true
    }

  }
}

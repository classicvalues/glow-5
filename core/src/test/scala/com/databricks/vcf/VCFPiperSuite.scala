/**
 * Copyright (C) 2018 Databricks, Inc.
 *
 * Portions of this software incorporate or are derived from software contained within Apache Spark,
 * and this modified software differs from the Apache Spark software provided under the Apache
 * License, Version 2.0, a copy of which you may obtain at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.databricks.vcf

import scala.collection.JavaConverters._

import org.apache.spark.{SparkException, TaskContext}
import org.apache.spark.sql.DataFrame

import com.databricks.hls.SparkGenomics
import com.databricks.hls.common.TestUtils._
import com.databricks.hls.sql.HLSBaseTest
import com.databricks.hls.transformers.ProcessHelper

class VCFPiperSuite extends HLSBaseTest {
  lazy val sess = spark
  private val na12878 = s"$testDataHome/NA12878_21_10002403.vcf"

  private def readVcf(vcf: String): DataFrame = {
    spark
      .read
      .format("com.databricks.vcf")
      .option("includeSampleIds", "true")
      .option("flattenInfoFields", "true")
      .load(vcf)
  }

  def pipeScript(vcf: String, script: String): (DataFrame, DataFrame) = {
    val inputDf = readVcf(vcf)
    val options = Map(
      "inputFormatter" -> "vcf",
      "outputFormatter" -> "vcf",
      "in_vcfHeader" -> "infer",
      "cmd" -> s"""["$script"]""")
    val outputDf = SparkGenomics.transform("pipe", inputDf, options)

    (inputDf, outputDf)
  }

  test("Prepend chr") {
    val sess = spark
    import sess.implicits._

    val (_, df) = pipeScript(
      na12878,
      s"$testDataHome/vcf/scripts/prepend-chr.sh"
    )
    df.cache()

    // Prepends chr
    val distinctContigNames = df.select("contigName").as[String].distinct.collect
    assert(distinctContigNames.length == 1 && distinctContigNames.head == "chr21")

    // Include sample names
    val sampleSeq = df.select("genotypes.sampleId").as[Seq[String]].head
    assert(sampleSeq == Seq("NA12878"))

    // Flattens INFO fields
    val sorSeq = df.select("INFO_SOR").as[Double].collect
    assert(sorSeq.min ~== 0.551 relTol 0.2)
  }

  test("Remove INFO") {
    val (_, df) = pipeScript(
      na12878,
      s"$testDataHome/vcf/scripts/remove-info.sh"
    )

    assert(!df.schema.fieldNames.exists(_.startsWith("INFO_")))
  }

  test("Remove non-header rows") {
    val (inputDf, outputDf) = pipeScript(
      na12878,
      s"$testDataHome/vcf/scripts/remove-rows.sh"
    )

    assert(inputDf.schema == outputDf.schema)
    assert(outputDf.isEmpty)
  }

  private val baseTextOptions =
    Map("inputFormatter" -> "vcf", "outputFormatter" -> "text", "in_vcfHeader" -> "infer")
  test("environment variables") {
    import sess.implicits._

    val options = baseTextOptions ++ Map(
        "cmd" -> """["printenv"]""",
        "ENV_animal" -> "monkey",
        "env_a" -> "b",
        "eNv_C" -> "D")
    val df = readVcf(na12878)
    val output = SparkGenomics
      .transform("pipe", df, options)
      .as[String]
      .collect()
      .toSeq
    assert(output.contains("animal=monkey"))
    assert(output.contains("a=b"))
    assert(output.contains("c=D"))
  }

  test("empty partition") {
    val df = readVcf(na12878).limit(0).repartition(8)
    assert(df.count == 0)

    val options = baseTextOptions ++ Map("cmd" -> """["wc", "-l"]""")
    val output = SparkGenomics.transform("pipe", df, options)
    assert(output.count() == 8)
  }

  test("stdin and stderr threads are cleaned up for successful commands") {
    pipeScript(na12878, "cat")
    eventually {
      assert(
        !Thread
          .getAllStackTraces
          .asScala
          .keySet
          .exists(_.getName.startsWith(ProcessHelper.STDIN_WRITER_THREAD_PREFIX)))
      assert(
        !Thread
          .getAllStackTraces
          .asScala
          .keySet
          .exists(_.getName.startsWith(ProcessHelper.STDERR_READER_THREAD_PREFIX)))
    }
  }

  test("command doesn't exist") {
    val ex = intercept[SparkException] {
      pipeScript(na12878, "totallyfakecommandthatdoesntexist")
    }
    assert(ex.getMessage.contains("No such file or directory"))

    // threads should still be cleaned up
    eventually {
      assert(
        !Thread
          .getAllStackTraces
          .asScala
          .keySet
          .exists(_.getName.startsWith(ProcessHelper.STDIN_WRITER_THREAD_PREFIX)))
      assert(
        !Thread
          .getAllStackTraces
          .asScala
          .keySet
          .exists(_.getName.startsWith(ProcessHelper.STDERR_READER_THREAD_PREFIX)))
    }
  }

  test("command fails") {
    val df = readVcf(na12878).limit(0).repartition(8)
    val options = baseTextOptions ++ Map("cmd" -> """["bash", "-c", "exit 1"]""")
    val ex = intercept[SparkException] {
      SparkGenomics.transform("pipe", df, options).count()
    }
    assert(ex.getMessage.contains("Subprocess exited with status 1"))

    // threads should still be cleaned up
    eventually {
      assert(
        !Thread
          .getAllStackTraces
          .asScala
          .keySet
          .exists(_.getName.startsWith(ProcessHelper.STDIN_WRITER_THREAD_PREFIX)))
      assert(
        !Thread
          .getAllStackTraces
          .asScala
          .keySet
          .exists(_.getName.startsWith(ProcessHelper.STDERR_READER_THREAD_PREFIX)))
    }
  }

  test("empty text partitions read to vcf") {
    val df = spark.read.text(na12878).limit(0).repartition(2)
    val options = Map(
      "inputFormatter" -> "text",
      "outputFormatter" -> "vcf",
      "in_vcfHeader" -> "infer",
      "cmd" -> s"""["cat", "-"]""")
    assertThrows[SparkException](SparkGenomics.transform("pipe", df, options))
  }

  test("empty vcf partitions write header") {
    val df = readVcf(na12878).limit(0).repartition(1)
    val options = Map(
      "inputFormatter" -> "vcf",
      "outputFormatter" -> "text",
      "in_vcfHeader" -> na12878,
      "cmd" -> s"""["cat", "-"]""")
    val output = SparkGenomics.transform("pipe", df, options)
    assert(output.count() == 28)
  }

  test("task context is defined in each thread") {
    val sess = spark
    import sess.implicits._

    val input = spark
      .read
      .format("com.databricks.vcf")
      .option("includeSampleIds", "true")
      .option("vcfRowSchema", "true")
      .load(na12878)
      .as[VCFRow]
    val df = input.map { el =>
      require(TaskContext.get != null)
      el
    }.toDF

    val options = Map(
      "inputFormatter" -> "vcf",
      "outputFormatter" -> "vcf",
      "in_vcfHeader" -> na12878,
      "cmd" -> s"""["cat", "-"]""")
    val output = SparkGenomics.transform("pipe", df, options)
    assert(output.count() == 4)
  }
}
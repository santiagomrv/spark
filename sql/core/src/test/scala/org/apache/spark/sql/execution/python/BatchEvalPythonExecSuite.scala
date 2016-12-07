/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.python

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

import org.apache.spark.api.python.PythonFunction
import org.apache.spark.sql.catalyst.expressions.{And, AttributeReference, In}
import org.apache.spark.sql.execution.{FilterExec, SparkPlanTest}
import org.apache.spark.sql.test.SharedSQLContext
import org.apache.spark.sql.types.BooleanType

class BatchEvalPythonExecSuite extends SparkPlanTest with SharedSQLContext {
    import testImplicits.newProductEncoder
    import testImplicits.localSeqToDatasetHolder

  override def beforeAll(): Unit = {
    super.beforeAll()
    spark.udf.registerPython("dummyPythonUDF", new MyDummyPythonUDF)
  }

  override def afterAll(): Unit = {
    spark.sessionState.functionRegistry.dropFunction("dummyPythonUDF")
    super.afterAll()
  }

  test("Python UDF: push down deterministic FilterExec predicates") {
    val df = Seq(("Hello", 4)).toDF("a", "b")
      .where("dummyPythonUDF(b) and dummyPythonUDF(a) and a in (3, 4)")
    val qualifiedPlanNodes = df.queryExecution.executedPlan.collect {
      case f @ FilterExec(And(_: AttributeReference, _: AttributeReference), _) => f
      case b: BatchEvalPythonExec => b
      case f @ FilterExec(_: In, _) => f
    }
    assert(qualifiedPlanNodes.size == 3)
  }

  test("Nested Python UDF: push down deterministic FilterExec predicates") {
    val df = Seq(("Hello", 4)).toDF("a", "b")
      .where("dummyPythonUDF(a, dummyPythonUDF(a, b)) and a in (3, 4)")
    val qualifiedPlanNodes = df.queryExecution.executedPlan.collect {
      case f @ FilterExec(_: AttributeReference, _) => f
      case b: BatchEvalPythonExec => b
      case f @ FilterExec(_: In, _) => f
    }
    assert(qualifiedPlanNodes.size == 4)
  }

  test("Python UDF: no push down on non-deterministic FilterExec predicates") {
    val df = Seq(("Hello", 4)).toDF("a", "b")
      .where("dummyPythonUDF(a) and rand() > 3")
    val qualifiedPlanNodes = df.queryExecution.executedPlan.collect {
      case f: FilterExec => f
      case b: BatchEvalPythonExec => b
    }
    assert(qualifiedPlanNodes.size == 2)
  }

  test("Python UDF refers to the attributes from more than one child") {
    val df = Seq(("Hello", 4)).toDF("a", "b")
    val df2 = Seq(("Hello", 4)).toDF("c", "d")
    val joinDF = df.join(df2).where("dummyPythonUDF(a, c) == dummyPythonUDF(d, c)")

    val e = intercept[RuntimeException] {
      joinDF.queryExecution.executedPlan
    }.getMessage
    assert(Seq("Invalid PythonUDF dummyUDF", "requires attributes from more than one child")
      .forall(e.contains))
  }
}

// This Python UDF is dummy and just for testing. Unable to execute.
class DummyUDF extends PythonFunction(
  command = Array[Byte](),
  envVars = Map("" -> "").asJava,
  pythonIncludes = ArrayBuffer("").asJava,
  pythonExec = "",
  pythonVer = "",
  broadcastVars = null,
  accumulator = null)

class MyDummyPythonUDF
  extends UserDefinedPythonFunction(name = "dummyUDF", func = new DummyUDF, dataType = BooleanType)

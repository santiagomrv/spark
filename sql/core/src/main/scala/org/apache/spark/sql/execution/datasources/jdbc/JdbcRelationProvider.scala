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

package org.apache.spark.sql.execution.datasources.jdbc

import java.util.Properties

import org.apache.spark.Partition
import org.apache.spark.sql._
import org.apache.spark.sql.sources.{CreatableRelationProvider, BaseRelation, DataSourceRegister, RelationProvider}

class JdbcRelationProvider
  extends RelationProvider
  with CreatableRelationProvider
  with DataSourceRegister {

  override def shortName(): String = "jdbc"

  /** Returns a new base relation with the given parameters. */
  override def createRelation(
      sqlContext: SQLContext,
      parameters: Map[String, String]): BaseRelation = {
    val jdbcOptions = new JDBCOptions(parameters)
    if (jdbcOptions.partitionColumn != null
      && (jdbcOptions.lowerBound == null
        || jdbcOptions.upperBound == null
        || jdbcOptions.numPartitions == null)) {
      sys.error("Partitioning incompletely specified")
    }

    val partitionInfo = if (jdbcOptions.partitionColumn == null) {
      null
    } else {
      JDBCPartitioningInfo(
        jdbcOptions.partitionColumn,
        jdbcOptions.lowerBound.toLong,
        jdbcOptions.upperBound.toLong,
        jdbcOptions.numPartitions.toInt)
    }
    val parts = JDBCRelation.columnPartition(partitionInfo)
    val properties = new Properties() // Additional properties that we will pass to getConnection
    parameters.foreach(kv => properties.setProperty(kv._1, kv._2))
    JDBCRelation(jdbcOptions.url, jdbcOptions.table, parts, properties)(sqlContext.sparkSession)
  }


  override def createRelation(
      sqlContext: SQLContext,
      mode: SaveMode,
      parameters: Map[String, String],
      data: DataFrame): BaseRelation = {
    val jdbcOptions = new JDBCOptions(parameters)
    val parts = Array.empty[Partition]
    val properties = new Properties() // Additional properties that we will pass to getConnection
    parameters.foreach(kv => properties.setProperty(kv._1, kv._2))
    data.write
      .mode(mode)
      .jdbc(jdbcOptions.url, jdbcOptions.table, properties)
    JDBCRelation(jdbcOptions.url, jdbcOptions.table, parts, properties)(sqlContext.sparkSession)
  }
}

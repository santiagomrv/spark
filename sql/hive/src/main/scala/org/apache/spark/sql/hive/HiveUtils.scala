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

package org.apache.spark.sql.hive

import java.io.{File, IOException}
import java.net.{URL, URLClassLoader}
import java.nio.charset.StandardCharsets
import java.sql.Timestamp
import java.util.concurrent.TimeUnit

import scala.collection.JavaConverters._
import scala.collection.mutable.HashMap
import scala.language.implicitConversions

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.FileSystem
import org.apache.hadoop.hive.common.StatsSetupConst
import org.apache.hadoop.hive.common.`type`.HiveDecimal
import org.apache.hadoop.hive.conf.HiveConf
import org.apache.hadoop.hive.conf.HiveConf.ConfVars
import org.apache.hadoop.hive.metastore.{TableType => HiveTableType}
import org.apache.hadoop.hive.metastore.api.{FieldSchema, SerDeInfo, StorageDescriptor}
import org.apache.hadoop.hive.ql.metadata.{Partition => HivePartition, Table => HiveTable}
import org.apache.hadoop.hive.serde2.io.{DateWritable, TimestampWritable}
import org.apache.hadoop.util.VersionInfo

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.internal.Logging
import org.apache.spark.internal.config.CATALOG_IMPLEMENTATION
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.catalog._
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.hive.client._
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.internal.SQLConf._
import org.apache.spark.sql.types._
import org.apache.spark.util.Utils


private[spark] object HiveUtils extends Logging {

  def withHiveExternalCatalog(sc: SparkContext): SparkContext = {
    sc.conf.set(CATALOG_IMPLEMENTATION.key, "hive")
    sc
  }

  /** The version of hive used internally by Spark SQL. */
  val hiveExecutionVersion: String = "1.2.1"

  val HIVE_METASTORE_VERSION = SQLConfigBuilder("spark.sql.hive.metastore.version")
    .doc("Version of the Hive metastore. Available options are " +
        s"<code>0.12.0</code> through <code>$hiveExecutionVersion</code>.")
    .stringConf
    .createWithDefault(hiveExecutionVersion)

  val HIVE_EXECUTION_VERSION = SQLConfigBuilder("spark.sql.hive.version")
    .doc("Version of Hive used internally by Spark SQL.")
    .stringConf
    .createWithDefault(hiveExecutionVersion)

  val HIVE_METASTORE_JARS = SQLConfigBuilder("spark.sql.hive.metastore.jars")
    .doc(s"""
      | Location of the jars that should be used to instantiate the HiveMetastoreClient.
      | This property can be one of three options: "
      | 1. "builtin"
      |   Use Hive ${hiveExecutionVersion}, which is bundled with the Spark assembly when
      |   <code>-Phive</code> is enabled. When this option is chosen,
      |   <code>spark.sql.hive.metastore.version</code> must be either
      |   <code>${hiveExecutionVersion}</code> or not defined.
      | 2. "maven"
      |   Use Hive jars of specified version downloaded from Maven repositories.
      | 3. A classpath in the standard format for both Hive and Hadoop.
      """.stripMargin)
    .stringConf
    .createWithDefault("builtin")

  val CONVERT_METASTORE_PARQUET = SQLConfigBuilder("spark.sql.hive.convertMetastoreParquet")
    .doc("When set to false, Spark SQL will use the Hive SerDe for parquet tables instead of " +
      "the built in support.")
    .booleanConf
    .createWithDefault(true)

  val CONVERT_METASTORE_PARQUET_WITH_SCHEMA_MERGING =
    SQLConfigBuilder("spark.sql.hive.convertMetastoreParquet.mergeSchema")
      .doc("When true, also tries to merge possibly different but compatible Parquet schemas in " +
        "different Parquet data files. This configuration is only effective " +
        "when \"spark.sql.hive.convertMetastoreParquet\" is true.")
      .booleanConf
      .createWithDefault(false)

  val CONVERT_METASTORE_ORC = SQLConfigBuilder("spark.sql.hive.convertMetastoreOrc")
    .doc("When set to false, Spark SQL will use the Hive SerDe for ORC tables instead of " +
      "the built in support.")
    .booleanConf
    .createWithDefault(true)

  val HIVE_METASTORE_SHARED_PREFIXES = SQLConfigBuilder("spark.sql.hive.metastore.sharedPrefixes")
    .doc("A comma separated list of class prefixes that should be loaded using the classloader " +
      "that is shared between Spark SQL and a specific version of Hive. An example of classes " +
      "that should be shared is JDBC drivers that are needed to talk to the metastore. Other " +
      "classes that need to be shared are those that interact with classes that are already " +
      "shared. For example, custom appenders that are used by log4j.")
    .stringConf
    .toSequence
    .createWithDefault(jdbcPrefixes)

  private def jdbcPrefixes = Seq(
    "com.mysql.jdbc", "org.postgresql", "com.microsoft.sqlserver", "oracle.jdbc")

  val HIVE_METASTORE_BARRIER_PREFIXES = SQLConfigBuilder("spark.sql.hive.metastore.barrierPrefixes")
    .doc("A comma separated list of class prefixes that should explicitly be reloaded for each " +
      "version of Hive that Spark SQL is communicating with. For example, Hive UDFs that are " +
      "declared in a prefix that typically would be shared (i.e. <code>org.apache.spark.*</code>).")
    .stringConf
    .toSequence
    .createWithDefault(Nil)

  val HIVE_THRIFT_SERVER_ASYNC = SQLConfigBuilder("spark.sql.hive.thriftServer.async")
    .doc("When set to true, Hive Thrift server executes SQL queries in an asynchronous way.")
    .booleanConf
    .createWithDefault(true)

  /**
   * The version of the hive client that will be used to communicate with the metastore.  Note that
   * this does not necessarily need to be the same version of Hive that is used internally by
   * Spark SQL for execution.
   */
  private def hiveMetastoreVersion(conf: SQLConf): String = {
    conf.getConf(HIVE_METASTORE_VERSION)
  }

  /**
   * The location of the jars that should be used to instantiate the HiveMetastoreClient.  This
   * property can be one of three options:
   *  - a classpath in the standard format for both hive and hadoop.
   *  - builtin - attempt to discover the jars that were used to load Spark SQL and use those. This
   *              option is only valid when using the execution version of Hive.
   *  - maven - download the correct version of hive on demand from maven.
   */
  private def hiveMetastoreJars(conf: SQLConf): String = {
    conf.getConf(HIVE_METASTORE_JARS)
  }

  /**
   * A comma separated list of class prefixes that should be loaded using the classloader that
   * is shared between Spark SQL and a specific version of Hive. An example of classes that should
   * be shared is JDBC drivers that are needed to talk to the metastore. Other classes that need
   * to be shared are those that interact with classes that are already shared.  For example,
   * custom appenders that are used by log4j.
   */
  private def hiveMetastoreSharedPrefixes(conf: SQLConf): Seq[String] = {
    conf.getConf(HIVE_METASTORE_SHARED_PREFIXES).filterNot(_ == "")
  }

  /**
   * A comma separated list of class prefixes that should explicitly be reloaded for each version
   * of Hive that Spark SQL is communicating with.  For example, Hive UDFs that are declared in a
   * prefix that typically would be shared (i.e. org.apache.spark.*)
   */
  private def hiveMetastoreBarrierPrefixes(conf: SQLConf): Seq[String] = {
    conf.getConf(HIVE_METASTORE_BARRIER_PREFIXES).filterNot(_ == "")
  }

  /**
   * Configurations needed to create a [[HiveClient]].
   */
  private[hive] def hiveClientConfigurations(hadoopConf: Configuration): Map[String, String] = {
    // Hive 0.14.0 introduces timeout operations in HiveConf, and changes default values of a bunch
    // of time `ConfVar`s by adding time suffixes (`s`, `ms`, and `d` etc.).  This breaks backwards-
    // compatibility when users are trying to connecting to a Hive metastore of lower version,
    // because these options are expected to be integral values in lower versions of Hive.
    //
    // Here we enumerate all time `ConfVar`s and convert their values to numeric strings according
    // to their output time units.
    Seq(
      ConfVars.METASTORE_CLIENT_CONNECT_RETRY_DELAY -> TimeUnit.SECONDS,
      ConfVars.METASTORE_CLIENT_SOCKET_TIMEOUT -> TimeUnit.SECONDS,
      ConfVars.METASTORE_CLIENT_SOCKET_LIFETIME -> TimeUnit.SECONDS,
      ConfVars.HMSHANDLERINTERVAL -> TimeUnit.MILLISECONDS,
      ConfVars.METASTORE_EVENT_DB_LISTENER_TTL -> TimeUnit.SECONDS,
      ConfVars.METASTORE_EVENT_CLEAN_FREQ -> TimeUnit.SECONDS,
      ConfVars.METASTORE_EVENT_EXPIRY_DURATION -> TimeUnit.SECONDS,
      ConfVars.METASTORE_AGGREGATE_STATS_CACHE_TTL -> TimeUnit.SECONDS,
      ConfVars.METASTORE_AGGREGATE_STATS_CACHE_MAX_WRITER_WAIT -> TimeUnit.MILLISECONDS,
      ConfVars.METASTORE_AGGREGATE_STATS_CACHE_MAX_READER_WAIT -> TimeUnit.MILLISECONDS,
      ConfVars.HIVES_AUTO_PROGRESS_TIMEOUT -> TimeUnit.SECONDS,
      ConfVars.HIVE_LOG_INCREMENTAL_PLAN_PROGRESS_INTERVAL -> TimeUnit.MILLISECONDS,
      ConfVars.HIVE_STATS_JDBC_TIMEOUT -> TimeUnit.SECONDS,
      ConfVars.HIVE_STATS_RETRIES_WAIT -> TimeUnit.MILLISECONDS,
      ConfVars.HIVE_LOCK_SLEEP_BETWEEN_RETRIES -> TimeUnit.SECONDS,
      ConfVars.HIVE_ZOOKEEPER_SESSION_TIMEOUT -> TimeUnit.MILLISECONDS,
      ConfVars.HIVE_ZOOKEEPER_CONNECTION_BASESLEEPTIME -> TimeUnit.MILLISECONDS,
      ConfVars.HIVE_TXN_TIMEOUT -> TimeUnit.SECONDS,
      ConfVars.HIVE_COMPACTOR_WORKER_TIMEOUT -> TimeUnit.SECONDS,
      ConfVars.HIVE_COMPACTOR_CHECK_INTERVAL -> TimeUnit.SECONDS,
      ConfVars.HIVE_COMPACTOR_CLEANER_RUN_INTERVAL -> TimeUnit.MILLISECONDS,
      ConfVars.HIVE_SERVER2_THRIFT_HTTP_MAX_IDLE_TIME -> TimeUnit.MILLISECONDS,
      ConfVars.HIVE_SERVER2_THRIFT_HTTP_WORKER_KEEPALIVE_TIME -> TimeUnit.SECONDS,
      ConfVars.HIVE_SERVER2_THRIFT_HTTP_COOKIE_MAX_AGE -> TimeUnit.SECONDS,
      ConfVars.HIVE_SERVER2_THRIFT_LOGIN_BEBACKOFF_SLOT_LENGTH -> TimeUnit.MILLISECONDS,
      ConfVars.HIVE_SERVER2_THRIFT_LOGIN_TIMEOUT -> TimeUnit.SECONDS,
      ConfVars.HIVE_SERVER2_THRIFT_WORKER_KEEPALIVE_TIME -> TimeUnit.SECONDS,
      ConfVars.HIVE_SERVER2_ASYNC_EXEC_SHUTDOWN_TIMEOUT -> TimeUnit.SECONDS,
      ConfVars.HIVE_SERVER2_ASYNC_EXEC_KEEPALIVE_TIME -> TimeUnit.SECONDS,
      ConfVars.HIVE_SERVER2_LONG_POLLING_TIMEOUT -> TimeUnit.MILLISECONDS,
      ConfVars.HIVE_SERVER2_SESSION_CHECK_INTERVAL -> TimeUnit.MILLISECONDS,
      ConfVars.HIVE_SERVER2_IDLE_SESSION_TIMEOUT -> TimeUnit.MILLISECONDS,
      ConfVars.HIVE_SERVER2_IDLE_OPERATION_TIMEOUT -> TimeUnit.MILLISECONDS,
      ConfVars.SERVER_READ_SOCKET_TIMEOUT -> TimeUnit.SECONDS,
      ConfVars.HIVE_LOCALIZE_RESOURCE_WAIT_INTERVAL -> TimeUnit.MILLISECONDS,
      ConfVars.SPARK_CLIENT_FUTURE_TIMEOUT -> TimeUnit.SECONDS,
      ConfVars.SPARK_JOB_MONITOR_TIMEOUT -> TimeUnit.SECONDS,
      ConfVars.SPARK_RPC_CLIENT_CONNECT_TIMEOUT -> TimeUnit.MILLISECONDS,
      ConfVars.SPARK_RPC_CLIENT_HANDSHAKE_TIMEOUT -> TimeUnit.MILLISECONDS
    ).map { case (confVar, unit) =>
      confVar.varname -> HiveConf.getTimeVar(hadoopConf, confVar, unit).toString
    }.toMap
  }

  /**
   * Create a [[HiveClient]] used for execution.
   *
   * Currently this must always be Hive 13 as this is the version of Hive that is packaged
   * with Spark SQL. This copy of the client is used for execution related tasks like
   * registering temporary functions or ensuring that the ThreadLocal SessionState is
   * correctly populated.  This copy of Hive is *not* used for storing persistent metadata,
   * and only point to a dummy metastore in a temporary directory.
   */
  protected[hive] def newClientForExecution(
      conf: SparkConf,
      hadoopConf: Configuration): HiveClientImpl = {
    logInfo(s"Initializing execution hive, version $hiveExecutionVersion")
    val loader = new IsolatedClientLoader(
      version = IsolatedClientLoader.hiveVersion(hiveExecutionVersion),
      sparkConf = conf,
      execJars = Seq(),
      hadoopConf = hadoopConf,
      config = newTemporaryConfiguration(useInMemoryDerby = true),
      isolationOn = false,
      baseClassLoader = Utils.getContextOrSparkClassLoader)
    loader.createClient().asInstanceOf[HiveClientImpl]
  }

  /**
   * Create a [[HiveClient]] used to retrieve metadata from the Hive MetaStore.
   *
   * The version of the Hive client that is used here must match the metastore that is configured
   * in the hive-site.xml file.
   */
  protected[hive] def newClientForMetadata(
      conf: SparkConf,
      hadoopConf: Configuration): HiveClient = {
    val configurations = hiveClientConfigurations(hadoopConf)
    newClientForMetadata(conf, hadoopConf, configurations)
  }

  protected[hive] def newClientForMetadata(
      conf: SparkConf,
      hadoopConf: Configuration,
      configurations: Map[String, String]): HiveClient = {
    val sqlConf = new SQLConf
    sqlConf.setConf(SQLContext.getSQLProperties(conf))
    val hiveMetastoreVersion = HiveUtils.hiveMetastoreVersion(sqlConf)
    val hiveMetastoreJars = HiveUtils.hiveMetastoreJars(sqlConf)
    val hiveMetastoreSharedPrefixes = HiveUtils.hiveMetastoreSharedPrefixes(sqlConf)
    val hiveMetastoreBarrierPrefixes = HiveUtils.hiveMetastoreBarrierPrefixes(sqlConf)
    val metaVersion = IsolatedClientLoader.hiveVersion(hiveMetastoreVersion)

    val isolatedLoader = if (hiveMetastoreJars == "builtin") {
      if (hiveExecutionVersion != hiveMetastoreVersion) {
        throw new IllegalArgumentException(
          "Builtin jars can only be used when hive execution version == hive metastore version. " +
            s"Execution: $hiveExecutionVersion != Metastore: $hiveMetastoreVersion. " +
            "Specify a vaild path to the correct hive jars using $HIVE_METASTORE_JARS " +
            s"or change ${HIVE_METASTORE_VERSION.key} to $hiveExecutionVersion.")
      }

      // We recursively find all jars in the class loader chain,
      // starting from the given classLoader.
      def allJars(classLoader: ClassLoader): Array[URL] = classLoader match {
        case null => Array.empty[URL]
        case urlClassLoader: URLClassLoader =>
          urlClassLoader.getURLs ++ allJars(urlClassLoader.getParent)
        case other => allJars(other.getParent)
      }

      val classLoader = Utils.getContextOrSparkClassLoader
      val jars = allJars(classLoader)
      if (jars.length == 0) {
        throw new IllegalArgumentException(
          "Unable to locate hive jars to connect to metastore. " +
            "Please set spark.sql.hive.metastore.jars.")
      }

      logInfo(
        s"Initializing HiveMetastoreConnection version $hiveMetastoreVersion using Spark classes.")
      new IsolatedClientLoader(
        version = metaVersion,
        sparkConf = conf,
        hadoopConf = hadoopConf,
        execJars = jars.toSeq,
        config = configurations,
        isolationOn = true,
        barrierPrefixes = hiveMetastoreBarrierPrefixes,
        sharedPrefixes = hiveMetastoreSharedPrefixes)
    } else if (hiveMetastoreJars == "maven") {
      // TODO: Support for loading the jars from an already downloaded location.
      logInfo(
        s"Initializing HiveMetastoreConnection version $hiveMetastoreVersion using maven.")
      IsolatedClientLoader.forVersion(
        hiveMetastoreVersion = hiveMetastoreVersion,
        hadoopVersion = VersionInfo.getVersion,
        sparkConf = conf,
        hadoopConf = hadoopConf,
        config = configurations,
        barrierPrefixes = hiveMetastoreBarrierPrefixes,
        sharedPrefixes = hiveMetastoreSharedPrefixes)
    } else {
      // Convert to files and expand any directories.
      val jars =
        hiveMetastoreJars
          .split(File.pathSeparator)
          .flatMap {
          case path if new File(path).getName == "*" =>
            val files = new File(path).getParentFile.listFiles()
            if (files == null) {
              logWarning(s"Hive jar path '$path' does not exist.")
              Nil
            } else {
              files.filter(_.getName.toLowerCase.endsWith(".jar"))
            }
          case path =>
            new File(path) :: Nil
        }
          .map(_.toURI.toURL)

      logInfo(
        s"Initializing HiveMetastoreConnection version $hiveMetastoreVersion " +
          s"using ${jars.mkString(":")}")
      new IsolatedClientLoader(
        version = metaVersion,
        sparkConf = conf,
        hadoopConf = hadoopConf,
        execJars = jars.toSeq,
        config = configurations,
        isolationOn = true,
        barrierPrefixes = hiveMetastoreBarrierPrefixes,
        sharedPrefixes = hiveMetastoreSharedPrefixes)
    }
    isolatedLoader.createClient()
  }

  /** Constructs a configuration for hive, where the metastore is located in a temp directory. */
  def newTemporaryConfiguration(useInMemoryDerby: Boolean): Map[String, String] = {
    val withInMemoryMode = if (useInMemoryDerby) "memory:" else ""

    val tempDir = Utils.createTempDir()
    val localMetastore = new File(tempDir, "metastore")
    val propMap: HashMap[String, String] = HashMap()
    // We have to mask all properties in hive-site.xml that relates to metastore data source
    // as we used a local metastore here.
    HiveConf.ConfVars.values().foreach { confvar =>
      if (confvar.varname.contains("datanucleus") || confvar.varname.contains("jdo")
        || confvar.varname.contains("hive.metastore.rawstore.impl")) {
        propMap.put(confvar.varname, confvar.getDefaultExpr())
      }
    }
    propMap.put(SQLConf.WAREHOUSE_PATH.key, localMetastore.toURI.toString)
    propMap.put(HiveConf.ConfVars.METASTORECONNECTURLKEY.varname,
      s"jdbc:derby:${withInMemoryMode};databaseName=${localMetastore.getAbsolutePath};create=true")
    propMap.put("datanucleus.rdbms.datastoreAdapterClassName",
      "org.datanucleus.store.rdbms.adapter.DerbyAdapter")

    // SPARK-11783: When "hive.metastore.uris" is set, the metastore connection mode will be
    // remote (https://cwiki.apache.org/confluence/display/Hive/AdminManual+MetastoreAdmin
    // mentions that "If hive.metastore.uris is empty local mode is assumed, remote otherwise").
    // Remote means that the metastore server is running in its own process.
    // When the mode is remote, configurations like "javax.jdo.option.ConnectionURL" will not be
    // used (because they are used by remote metastore server that talks to the database).
    // Because execution Hive should always connects to an embedded derby metastore.
    // We have to remove the value of hive.metastore.uris. So, the execution Hive client connects
    // to the actual embedded derby metastore instead of the remote metastore.
    // You can search HiveConf.ConfVars.METASTOREURIS in the code of HiveConf (in Hive's repo).
    // Then, you will find that the local metastore mode is only set to true when
    // hive.metastore.uris is not set.
    propMap.put(ConfVars.METASTOREURIS.varname, "")

    propMap.toMap
  }

  protected val primitiveTypes =
    Seq(StringType, IntegerType, LongType, DoubleType, FloatType, BooleanType, ByteType,
      ShortType, DateType, TimestampType, BinaryType)

  protected[sql] def toHiveString(a: (Any, DataType)): String = a match {
    case (struct: Row, StructType(fields)) =>
      struct.toSeq.zip(fields).map {
        case (v, t) => s""""${t.name}":${toHiveStructString(v, t.dataType)}"""
      }.mkString("{", ",", "}")
    case (seq: Seq[_], ArrayType(typ, _)) =>
      seq.map(v => (v, typ)).map(toHiveStructString).mkString("[", ",", "]")
    case (map: Map[_, _], MapType(kType, vType, _)) =>
      map.map {
        case (key, value) =>
          toHiveStructString((key, kType)) + ":" + toHiveStructString((value, vType))
      }.toSeq.sorted.mkString("{", ",", "}")
    case (null, _) => "NULL"
    case (d: Int, DateType) => new DateWritable(d).toString
    case (t: Timestamp, TimestampType) => new TimestampWritable(t).toString
    case (bin: Array[Byte], BinaryType) => new String(bin, StandardCharsets.UTF_8)
    case (decimal: java.math.BigDecimal, DecimalType()) =>
      // Hive strips trailing zeros so use its toString
      HiveDecimal.create(decimal).toString
    case (other, tpe) if primitiveTypes contains tpe => other.toString
  }

  /** Hive outputs fields of structs slightly differently than top level attributes. */
  protected def toHiveStructString(a: (Any, DataType)): String = a match {
    case (struct: Row, StructType(fields)) =>
      struct.toSeq.zip(fields).map {
        case (v, t) => s""""${t.name}":${toHiveStructString(v, t.dataType)}"""
      }.mkString("{", ",", "}")
    case (seq: Seq[_], ArrayType(typ, _)) =>
      seq.map(v => (v, typ)).map(toHiveStructString).mkString("[", ",", "]")
    case (map: Map[_, _], MapType(kType, vType, _)) =>
      map.map {
        case (key, value) =>
          toHiveStructString((key, kType)) + ":" + toHiveStructString((value, vType))
      }.toSeq.sorted.mkString("{", ",", "}")
    case (null, _) => "null"
    case (s: String, StringType) => "\"" + s + "\""
    case (decimal, DecimalType()) => decimal.toString
    case (other, tpe) if primitiveTypes contains tpe => other.toString
  }

  /* -------------------------------------------------------- *
   |  Helper methods for converting to and from Hive classes  |
   * -------------------------------------------------------- */

  private def toInputFormat(name: String) =
    Utils.classForName(name).asInstanceOf[Class[_ <: org.apache.hadoop.mapred.InputFormat[_, _]]]

  private def toOutputFormat(name: String) =
    Utils.classForName(name)
      .asInstanceOf[Class[_ <: org.apache.hadoop.hive.ql.io.HiveOutputFormat[_, _]]]

  def toHiveColumn(c: CatalogColumn): FieldSchema = {
    new FieldSchema(c.name, c.dataType, c.comment.orNull)
  }

  def fromHiveColumn(hc: FieldSchema): CatalogColumn = {
    new CatalogColumn(
      name = hc.getName,
      dataType = hc.getType,
      nullable = true,
      comment = Option(hc.getComment))
  }

  // TODO: merge this with HiveClientImpl#toHiveTable
  def toHiveTable(table: CatalogTable, owner: Option[String] = None): HiveTable = {
    val hiveTable = new HiveTable(table.database, table.identifier.table)
    // For EXTERNAL_TABLE, we also need to set EXTERNAL field in the table properties.
    // Otherwise, Hive metastore will change the table to a MANAGED_TABLE.
    // (metastore/src/java/org/apache/hadoop/hive/metastore/ObjectStore.java#L1095-L1105)
    hiveTable.setTableType(table.tableType match {
      case CatalogTableType.EXTERNAL =>
        hiveTable.setProperty("EXTERNAL", "TRUE")
        HiveTableType.EXTERNAL_TABLE
      case CatalogTableType.MANAGED =>
        HiveTableType.MANAGED_TABLE
      case CatalogTableType.INDEX => HiveTableType.INDEX_TABLE
      case CatalogTableType.VIEW => HiveTableType.VIRTUAL_VIEW
    })
    // Note: In Hive the schema and partition columns must be disjoint sets
    val (partCols, schema) = table.schema.map(toHiveColumn).partition { c =>
      table.partitionColumnNames.contains(c.getName)
    }
    if (table.schema.isEmpty) {
      // This is a hack to preserve existing behavior. Before Spark 2.0, we do not
      // set a default serde here (this was done in Hive), and so if the user provides
      // an empty schema Hive would automatically populate the schema with a single
      // field "col". However, after SPARK-14388, we set the default serde to
      // LazySimpleSerde so this implicit behavior no longer happens. Therefore,
      // we need to do it in Spark ourselves.
      hiveTable.setFields(
        Seq(new FieldSchema("col", "array<string>", "from deserializer")).asJava)
    } else {
      hiveTable.setFields(schema.asJava)
    }
    hiveTable.setPartCols(partCols.asJava)
    // TODO: set sort columns here too
    hiveTable.setBucketCols(table.bucketColumnNames.asJava)
    if (owner.isDefined) hiveTable.setOwner(owner.get)
    hiveTable.setNumBuckets(table.numBuckets)
    hiveTable.setCreateTime((table.createTime / 1000).toInt)
    hiveTable.setLastAccessTime((table.lastAccessTime / 1000).toInt)
    val sd = hiveTable.getSd
    table.storage.locationUri.foreach(sd.setLocation)
    table.storage.inputFormat.map(toInputFormat).foreach(hiveTable.setInputFormatClass)
    table.storage.outputFormat.map(toOutputFormat).foreach(hiveTable.setOutputFormatClass)
    hiveTable.setSerializationLib(
      table.storage.serde.getOrElse("org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe"))
    table.storage.serdeProperties.foreach { case (k, v) => hiveTable.setSerdeParam(k, v) }
    table.properties.foreach { case (k, v) => hiveTable.setProperty(k, v) }
    table.comment.foreach { c => hiveTable.setProperty("comment", c) }
    table.viewOriginalText.foreach { t => hiveTable.setViewOriginalText(t) }
    table.viewText.foreach { t => hiveTable.setViewExpandedText(t) }
    hiveTable
  }

  def toHivePartition(
      p: CatalogTablePartition,
      ht: HiveTable): HivePartition = {
    val tpart = new org.apache.hadoop.hive.metastore.api.Partition
    val partValues = ht.getPartCols.asScala.map { hc =>
      p.spec.get(hc.getName).getOrElse {
        throw new IllegalArgumentException(
          s"Partition spec is missing a value for column '${hc.getName}': ${p.spec}")
      }
    }
    val storageDesc = new StorageDescriptor
    val serdeInfo = new SerDeInfo
    p.storage.locationUri.foreach(storageDesc.setLocation)
    p.storage.inputFormat.foreach(storageDesc.setInputFormat)
    p.storage.outputFormat.foreach(storageDesc.setOutputFormat)
    p.storage.serde.foreach(serdeInfo.setSerializationLib)
    serdeInfo.setParameters(p.storage.serdeProperties.asJava)
    storageDesc.setSerdeInfo(serdeInfo)
    tpart.setDbName(ht.getDbName)
    tpart.setTableName(ht.getTableName)
    tpart.setValues(partValues.asJava)
    tpart.setSd(storageDesc)
    new HivePartition(ht, tpart)
  }

  def fromHivePartition(hp: HivePartition): CatalogTablePartition = {
    val apiPartition = hp.getTPartition
    CatalogTablePartition(
      spec = Option(hp.getSpec).map(_.asScala.toMap).getOrElse(Map.empty),
      storage = CatalogStorageFormat(
        locationUri = Option(apiPartition.getSd.getLocation),
        inputFormat = Option(apiPartition.getSd.getInputFormat),
        outputFormat = Option(apiPartition.getSd.getOutputFormat),
        serde = Option(apiPartition.getSd.getSerdeInfo.getSerializationLib),
        compressed = apiPartition.getSd.isCompressed,
        serdeProperties = apiPartition.getSd.getSerdeInfo.getParameters.asScala.toMap))
  }

  def getHiveQlPartitions(
      sparkSession: SparkSession,
      table: CatalogTable,
      predicates: Seq[Expression] = Nil): Seq[HivePartition] = {
    val dbName = table.identifier.database.getOrElse {
      throw new IllegalArgumentException(s"table ${table.identifier} did not specify database")
    }
    val rawPartitions = if (sparkSession.sessionState.conf.metastorePartitionPruning) {
      sparkSession.sessionState.catalog.listPartitionsByFilter(table.identifier, predicates)
    } else {
      // When metastore partition pruning is turned off, we cache the list of all partitions to
      // mimic the behavior of Spark < 1.5
      sparkSession.sessionState.catalog.listPartitions(table.identifier)
    }

    rawPartitions.map { p =>
      val tPartition = new org.apache.hadoop.hive.metastore.api.Partition
      tPartition.setDbName(dbName)
      tPartition.setTableName(table.identifier.table)
      tPartition.setValues(table.partitionColumns.map(a => p.spec(a.name)).asJava)

      val sd = new org.apache.hadoop.hive.metastore.api.StorageDescriptor()
      tPartition.setSd(sd)
      sd.setCols(table.schema.map(toHiveColumn).asJava)
      p.storage.locationUri.foreach(sd.setLocation)
      p.storage.inputFormat.foreach(sd.setInputFormat)
      p.storage.outputFormat.foreach(sd.setOutputFormat)

      val serdeInfo = new org.apache.hadoop.hive.metastore.api.SerDeInfo
      sd.setSerdeInfo(serdeInfo)
      // maps and lists should be set only after all elements are ready (see HIVE-7975)
      p.storage.serde.foreach(serdeInfo.setSerializationLib)

      val serdeParameters = new java.util.HashMap[String, String]()
      table.storage.serdeProperties.foreach { case (k, v) => serdeParameters.put(k, v) }
      p.storage.serdeProperties.foreach { case (k, v) => serdeParameters.put(k, v) }
      serdeInfo.setParameters(serdeParameters)

      new HivePartition(toHiveTable(table), tPartition)
    }
  }

  def getHiveTableSizeInBytes(
      hiveTable: HiveTable,
      sparkSession: SparkSession): Long = {
    val totalSize = hiveTable.getParameters.get(StatsSetupConst.TOTAL_SIZE)
    val rawDataSize = hiveTable.getParameters.get(StatsSetupConst.RAW_DATA_SIZE)
    // TODO: check if this estimate is valid for tables after partition pruning.
    // NOTE: getting `totalSize` directly from params is kind of hacky, but this should be
    // relatively cheap if parameters for the table are populated into the metastore.
    // Besides `totalSize`, there are also `numFiles`, `numRows`, `rawDataSize` keys
    // (see StatsSetupConst in Hive) that we can look at in the future.

    // When table is external,`totalSize` is always zero, which will influence join strategy
    // so when `totalSize` is zero, use `rawDataSize` instead
    // if the size is still less than zero, we try to get the file size from HDFS.
    // given this is only needed for optimization, if the HDFS call fails we return the default.
    if (totalSize != null && totalSize.toLong > 0L) {
      totalSize.toLong
    } else if (rawDataSize != null && rawDataSize.toLong > 0) {
      rawDataSize.toLong
    } else if (sparkSession.sessionState.conf.fallBackToHdfsForStatsEnabled) {
      try {
        val hadoopConf = sparkSession.sessionState.newHadoopConf()
        val fs: FileSystem = hiveTable.getPath.getFileSystem(hadoopConf)
        fs.getContentSummary(hiveTable.getPath).getLength
      } catch {
        case e: IOException =>
          logWarning("Failed to get table size from hdfs.", e)
          sparkSession.sessionState.conf.defaultSizeInBytes
      }
    } else {
      sparkSession.sessionState.conf.defaultSizeInBytes
    }
  }

}

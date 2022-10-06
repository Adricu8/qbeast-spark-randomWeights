/*
 * Copyright 2021 Qbeast Analytics, S.L.
 */
package io.qbeast.spark.internal.sources.catalog

import io.qbeast.context.QbeastContext.metadataManager
import io.qbeast.core.model.QTableID
import io.qbeast.spark.internal.sources.v2.QbeastTableImpl
import io.qbeast.spark.table.IndexedTableFactory
import org.apache.hadoop.fs.Path
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.analysis.CannotReplaceMissingTableException
import org.apache.spark.sql.catalyst.catalog._
import org.apache.spark.sql.connector.catalog.{Identifier, Table}
import org.apache.spark.sql.connector.expressions.Transform
import org.apache.spark.sql.execution.datasources.DataSource
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{
  AnalysisExceptionFactory,
  DataFrame,
  SaveMode,
  SparkSession,
  SparkTransformUtils,
  V1TableQbeast
}

import java.util
import scala.collection.JavaConverters._

/**
 * Object containing all the method utilities for creating and loading
 * a Qbeast formatted Table into the Catalog
 */
object QbeastCatalogUtils {

  val QBEAST_PROVIDER_NAME: String = "qbeast"

  lazy val spark: SparkSession = SparkSession.active

  /**
   * Checks if the provider is Qbeast
   * @param provider the provider, if any
   * @return
   */
  def isQbeastProvider(provider: Option[String]): Boolean = {
    provider.isDefined && provider.get == QBEAST_PROVIDER_NAME
  }

  /**
   * Checks if an Identifier is set with a path
   * @param ident the Identifier
   * @return
   */
  def isPathTable(ident: Identifier): Boolean = {
    new Path(ident.name()).isAbsolute
  }

  def isPathTable(identifier: TableIdentifier): Boolean = {
    isPathTable(Identifier.of(identifier.database.toArray, identifier.table))
  }

  /** Checks if a table already exists for the provided identifier. */
  def getTableIfExists(
      table: TableIdentifier,
      existingSessionCatalog: SessionCatalog): Option[CatalogTable] = {
    // If this is a path identifier, we cannot return an existing CatalogTable. The Create command
    // will check the file system itself
    if (isPathTable(table)) return None
    val tableExists = existingSessionCatalog.tableExists(table)
    if (tableExists) {
      val oldTable = existingSessionCatalog.getTableMetadata(table)
      if (oldTable.tableType == CatalogTableType.VIEW) {
        throw AnalysisExceptionFactory.create(
          s"$table is a view. You may not write data into a view.")
      }
      if (!isQbeastProvider(oldTable.provider)) {
        throw AnalysisExceptionFactory.create(s"$table is not a Qbeast table.")
      }
      Some(oldTable)
    } else {
      None
    }
  }

  /**
   * Creates a Table on the Catalog
   * @param ident the Identifier of the table
   * @param schema the schema of the table
   * @param partitions the partitions of the table, if any
   * @param allTableProperties all the table properties
   * @param writeOptions the write properties of the table
   * @param dataFrame the dataframe to write, if any
   * @param tableCreationMode the creation mode (could be CREATE, REPLACE or CREATE OR REPLACE)
   * @param tableFactory the indexed table factory
   * @param existingSessionCatalog the existing session catalog
   */

  def createQbeastTable(
      ident: Identifier,
      schema: StructType,
      partitions: Array[Transform],
      allTableProperties: util.Map[String, String],
      writeOptions: Map[String, String],
      dataFrame: Option[DataFrame],
      tableCreationMode: CreationMode,
      tableFactory: IndexedTableFactory,
      existingSessionCatalog: SessionCatalog): Unit = {

    val isPathTable = QbeastCatalogUtils.isPathTable(ident)

    // Checks if the location properties are coherent
    if (isPathTable
      && allTableProperties.containsKey("location")
      // The location property can be qualified and different from the path in the identifier, so
      // we check `endsWith` here.
      && Option(allTableProperties.get("location")).exists(!_.endsWith(ident.name()))) {
      throw AnalysisExceptionFactory.create(
        s"CREATE TABLE contains two different locations: ${ident.name()} " +
          s"and ${allTableProperties.get("location")}.")
    }

    // Get table location
    val location = if (isPathTable) {
      Option(ident.name())
    } else {
      Option(allTableProperties.get("location"))
    }

    // Define the table type.
    // Either can be EXTERNAL (if the location is defined) or MANAGED
    val tableType =
      if (location.isDefined) CatalogTableType.EXTERNAL else CatalogTableType.MANAGED
    val locUriOpt = location.map(CatalogUtils.stringToURI)

    val id = TableIdentifier(ident.name(), ident.namespace().lastOption)
    val existingTableOpt = QbeastCatalogUtils.getTableIfExists(id, existingSessionCatalog)
    val loc = locUriOpt
      .orElse(existingTableOpt.flatMap(_.storage.locationUri))
      .getOrElse(existingSessionCatalog.defaultTablePath(id))

    // Initialize the path option
    val storage = DataSource
      .buildStorageFormatFromOptions(writeOptions)
      .copy(locationUri = Option(loc))

    val commentOpt = Option(allTableProperties.get("comment"))
    val (partitionColumns, bucketSpec) = SparkTransformUtils.convertTransforms(partitions)

    // Create the CatalogTable representation for updating the Catalog
    val table = new CatalogTable(
      identifier = id,
      tableType = tableType,
      storage = storage,
      schema = schema,
      provider = Some("qbeast"),
      partitionColumnNames = partitionColumns,
      bucketSpec = bucketSpec,
      properties = allTableProperties.asScala.toMap,
      comment = commentOpt)

    // Write data, if any
    val append = tableCreationMode.saveMode == SaveMode.Append
    dataFrame.map { df =>
      tableFactory
        .getIndexedTable(QTableID(loc.toString))
        .save(df, allTableProperties.asScala.toMap, append)
    }

    // Update the existing session catalog with the Qbeast table information
    updateCatalog(
      QTableID(loc.toString),
      tableCreationMode,
      table,
      isPathTable,
      existingTableOpt,
      existingSessionCatalog)
  }

  private def checkLogCreation(tableID: QTableID): Unit = {
    // If the Log is not created
    // We make sure we create the table physically
    // So new data can be inserted
    val isLogCreated = metadataManager.existsLog(tableID)
    if (!isLogCreated) metadataManager.createLog(tableID)
  }

  /**
   * Based on DeltaCatalog updateCatalog private method,
   * it maintains the consistency of creating a table
   * calling the spark session catalog.
   * @param operation
   * @param table
   * @param isPathTable
   * @param existingTableOpt
   * @param existingSessionCatalog
   */
  private def updateCatalog(
      tableID: QTableID,
      operation: CreationMode,
      table: CatalogTable,
      isPathTable: Boolean,
      existingTableOpt: Option[CatalogTable],
      existingSessionCatalog: SessionCatalog): Unit = {

    operation match {
      case _ if isPathTable => // do nothing
      case TableCreationMode.CREATE_TABLE =>
        // To create the table, check if the log exists/create a new one
        // create table in the SessionCatalog
        checkLogCreation(tableID)
        existingSessionCatalog.createTable(
          table,
          ignoreIfExists = existingTableOpt.isDefined,
          validateLocation = false)
      case TableCreationMode.REPLACE_TABLE | TableCreationMode.CREATE_OR_REPLACE
          if existingTableOpt.isDefined =>
        // REPLACE the metadata of the table with the new one
        existingSessionCatalog.alterTable(table)
      case TableCreationMode.REPLACE_TABLE =>
        // Throw an exception if the table to replace does not exists
        val ident = Identifier.of(table.identifier.database.toArray, table.identifier.table)
        throw new CannotReplaceMissingTableException(ident)
      case TableCreationMode.CREATE_OR_REPLACE =>
        checkLogCreation(tableID)
        existingSessionCatalog.createTable(
          table,
          ignoreIfExists = false,
          validateLocation = false)
    }
  }

  /**
   * Loads a qbeast table based on the underlying table
   * @param table the underlying table
   * @return a Table with Qbeast information and implementations
   */
  def loadQbeastTable(table: Table, tableFactory: IndexedTableFactory): Table = {

    val prop = table.properties()
    val schema = table.schema()

    table match {
      case V1TableQbeast(t) =>
        val catalogTable = t.v1Table

        val path: String = if (catalogTable.tableType == CatalogTableType.EXTERNAL) {
          // If it's an EXTERNAL TABLE, we can find the path through the Storage Properties
          catalogTable.storage.locationUri.get.toString
        } else if (catalogTable.tableType == CatalogTableType.MANAGED) {
          // If it's a MANAGED TABLE, the location is set in the former catalogTable
          catalogTable.location.toString
        } else {
          // Otherwise, TODO
          throw AnalysisExceptionFactory.create("No path found for table " + table.name())
        }
        new QbeastTableImpl(
          catalogTable.identifier.identifier,
          new Path(path),
          prop.asScala.toMap,
          Some(schema),
          Some(catalogTable),
          tableFactory)

      case _ => table
    }
  }

}

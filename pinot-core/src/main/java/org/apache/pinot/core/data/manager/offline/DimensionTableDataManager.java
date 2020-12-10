/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.core.data.manager.offline;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.apache.commons.collections.CollectionUtils;
import org.apache.pinot.common.metadata.ZKMetadataProvider;
import org.apache.pinot.core.data.manager.SegmentDataManager;
import org.apache.pinot.core.data.readers.MultiplePinotSegmentRecordReader;
import org.apache.pinot.core.indexsegment.IndexSegment;
import org.apache.pinot.core.segment.index.loader.IndexLoadingConfig;
import org.apache.pinot.spi.data.FieldSpec;
import org.apache.pinot.spi.data.Schema;
import org.apache.pinot.spi.data.readers.GenericRow;
import org.apache.pinot.spi.data.readers.PrimaryKey;


/**
 * Dimension Table is a special type of OFFLINE table which is assigned to all servers
 * in a tenant and is used to execute a LOOKUP Transform Function. Content should be small
 * enough to easily fit in memory (<200MB).
 *
 * DimensionTableDataManager uses Registry of Singletons pattern to store one instance per table
 * which can be accessed via 'getInstanceByTableName' static method.
 */
public class DimensionTableDataManager extends OfflineTableDataManager {
  // Storing singletons per table in a HashMap
  private static final Map<String, DimensionTableDataManager> _instances = new ConcurrentHashMap<>();

  private DimensionTableDataManager() {}

  public static DimensionTableDataManager createInstanceByTableName(String tableName) {
    _instances.putIfAbsent(tableName, new DimensionTableDataManager());
    return _instances.get(tableName);
  }

  @VisibleForTesting
  public static DimensionTableDataManager registerDimensionTable(String tableName, DimensionTableDataManager instance) {
    _instances.putIfAbsent(tableName, instance);
    return _instances.get(tableName);
  }

  public static DimensionTableDataManager getInstanceByTableName(String tableName) {
    return _instances.get(tableName);
  }

  // DimensionTableDataManager Instance properties
  //

  // _lookupTable is a HashMap used for fetching records from a table given the primary key
  private final Map<PrimaryKey, GenericRow> _lookupTable = new HashMap<>();
  private final ReadWriteLock _rwl = new ReentrantReadWriteLock();
  private final Lock _lookupTableReadLock = _rwl.readLock();
  private final Lock _lookupTableWriteLock = _rwl.writeLock();

  private Schema _tableSchema;
  private List<String> _primaryKeyColumns;

  @Override
  protected void doInit() {
    super.doInit();

    _tableSchema = ZKMetadataProvider.getTableSchema(_propertyStore, _tableNameWithType);
    Preconditions.checkState(_tableSchema != null, "Failed to find schema for table: %s", _tableNameWithType);
    _primaryKeyColumns = _tableSchema.getPrimaryKeyColumns();
    Preconditions.checkState(!CollectionUtils.isEmpty(_primaryKeyColumns),
        "Primary key columns must be configured for dimension tables");
  }

  @Override
  public void addSegment(File indexDir, IndexLoadingConfig indexLoadingConfig)
      throws Exception {
    super.addSegment(indexDir, indexLoadingConfig);
    try {
      prepareLookupTable();
      _logger.info("Successfully loaded lookup table for {}", getTableName());
    } catch (Exception e) {
      throw new RuntimeException(
          String.format("Error loading lookup table: %s", getTableName()),e);
    }
  }

  @Override
  public void removeSegment(String segmentName) {
    super.removeSegment(segmentName);
    try {
      prepareLookupTable();
      _logger.info("Successfully removed segment and reloaded lookup table for {}", getTableName());
    } catch (Exception e) {
      _logger.error("Error reloading lookup table after segment remove for table {}", getTableName());
    }
  }

  private void prepareLookupTable() throws Exception {
    _lookupTableWriteLock.lock();
    try {
      List<SegmentDataManager> segmentManagers = acquireAllSegments();
      List<File> indexDirs = new ArrayList<>();

      for (SegmentDataManager segmentManager: segmentManagers) {
        IndexSegment indexSegment = segmentManager.getSegment();
        indexDirs.add(indexSegment.getSegmentMetadata().getIndexDir());
      }
      MultiplePinotSegmentRecordReader reader = new MultiplePinotSegmentRecordReader(indexDirs);

      _lookupTable.clear();
      while (reader.hasNext()) {
        GenericRow row = reader.next();
        _lookupTable.put(row.getPrimaryKey(_primaryKeyColumns), row);
      }
    } finally {
      _lookupTableWriteLock.unlock();
    }
  }

  public GenericRow lookupRowByPrimaryKey(PrimaryKey pk) {
    _lookupTableReadLock.lock();
    try {
      return _lookupTable.get(pk);
    } finally {
      _lookupTableReadLock.unlock();
    }
  }

  public FieldSpec getColumnFieldSpec(String columnName) {
    return _tableSchema.getFieldSpecFor(columnName);
  }
}

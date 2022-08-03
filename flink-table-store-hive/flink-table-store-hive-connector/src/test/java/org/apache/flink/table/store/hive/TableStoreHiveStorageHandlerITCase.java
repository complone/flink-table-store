/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.store.hive;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.connectors.hive.FlinkEmbeddedHiveRunner;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.StringData;
import org.apache.flink.table.data.TimestampData;
import org.apache.flink.table.store.CoreOptions;
import org.apache.flink.table.store.FileStoreTestUtils;
import org.apache.flink.table.store.file.WriteMode;
import org.apache.flink.table.store.hive.objectinspector.TableStoreObjectInspectorFactory;
import org.apache.flink.table.store.table.FileStoreTable;
import org.apache.flink.table.store.table.sink.TableCommit;
import org.apache.flink.table.store.table.sink.TableWrite;
import org.apache.flink.table.types.logical.LogicalType;
import org.apache.flink.table.types.logical.RowType;
import org.apache.flink.types.RowKind;

import com.klarna.hiverunner.HiveShell;
import com.klarna.hiverunner.annotations.HiveSQL;
import org.apache.hadoop.hive.common.type.HiveDecimal;
import org.apache.hadoop.hive.serde2.objectinspector.ListObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.MapObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.AbstractPrimitiveJavaObjectInspector;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * IT cases for {@link TableStoreHiveStorageHandler} and {@link
 * org.apache.flink.table.store.mapred.TableStoreInputFormat}.
 */
@RunWith(FlinkEmbeddedHiveRunner.class)
public class TableStoreHiveStorageHandlerITCase {

    @ClassRule public static TemporaryFolder folder = new TemporaryFolder();

    @HiveSQL(files = {})
    private static HiveShell hiveShell;

    private static String engine;

    @BeforeClass
    public static void beforeClass() {
        // TODO Currently FlinkEmbeddedHiveRunner can only be used for one test class,
        //  so we have to select engine randomly. Write our own Hive tester in the future.
        engine = ThreadLocalRandom.current().nextBoolean() ? "mr" : "tez";
    }

    @Before
    public void before() {
        if ("mr".equals(engine)) {
            hiveShell.execute("SET hive.execution.engine=mr");
        } else if ("tez".equals(engine)) {
            hiveShell.execute("SET hive.execution.engine=tez");
            hiveShell.execute("SET tez.local.mode=true");
            hiveShell.execute("SET hive.jar.directory=" + folder.getRoot().getAbsolutePath());
            hiveShell.execute("SET tez.staging-dir=" + folder.getRoot().getAbsolutePath());
        } else {
            throw new UnsupportedOperationException("Unsupported engine " + engine);
        }

        hiveShell.execute("CREATE DATABASE IF NOT EXISTS test_db");
        hiveShell.execute("USE test_db");
    }

    @After
    public void after() {
        hiveShell.execute("DROP DATABASE IF EXISTS test_db CASCADE");
    }

    @Test
    public void testReadExternalTableNoPartitionWithPk() throws Exception {
        List<RowData> data =
                Arrays.asList(
                        GenericRowData.of(1, 10L, StringData.fromString("Hi"), 100L),
                        GenericRowData.of(1, 20L, StringData.fromString("Hello"), 200L),
                        GenericRowData.of(2, 30L, StringData.fromString("World"), 300L),
                        GenericRowData.of(1, 10L, StringData.fromString("Hi Again"), 1000L),
                        GenericRowData.ofKind(
                                RowKind.DELETE, 2, 30L, StringData.fromString("World"), 300L),
                        GenericRowData.of(2, 40L, null, 400L),
                        GenericRowData.of(3, 50L, StringData.fromString("Store"), 200L));
        String tableName =
                createChangelogExternalTable(
                        RowType.of(
                                new LogicalType[] {
                                    DataTypes.INT().getLogicalType(),
                                    DataTypes.BIGINT().getLogicalType(),
                                    DataTypes.STRING().getLogicalType(),
                                    DataTypes.BIGINT().getLogicalType()
                                },
                                new String[] {"a", "b", "c", "d"}),
                        Collections.emptyList(),
                        Arrays.asList("a", "b"),
                        data);

        List<String> actual = hiveShell.executeQuery("SELECT * FROM " + tableName + " ORDER BY b");
        List<String> expected =
                Arrays.asList(
                        "1\t10\tHi Again\t1000",
                        "1\t20\tHello\t200",
                        "2\t40\tNULL\t400",
                        "3\t50\tStore\t200");
        Assert.assertEquals(expected, actual);

        actual = hiveShell.executeQuery("SELECT c, b FROM " + tableName + " ORDER BY b");
        expected = Arrays.asList("Hi Again\t10", "Hello\t20", "NULL\t40", "Store\t50");
        Assert.assertEquals(expected, actual);

        actual = hiveShell.executeQuery("SELECT * FROM " + tableName + " WHERE d > 200 ORDER BY b");
        expected = Arrays.asList("1\t10\tHi Again\t1000", "2\t40\tNULL\t400");
        Assert.assertEquals(expected, actual);

        actual =
                hiveShell.executeQuery(
                        "SELECT a, sum(d) FROM " + tableName + " GROUP BY a ORDER BY a");
        expected = Arrays.asList("1\t1200", "2\t400", "3\t200");
        Assert.assertEquals(expected, actual);

        actual =
                hiveShell.executeQuery(
                        "SELECT d, sum(b) FROM " + tableName + " GROUP BY d ORDER BY d");
        expected = Arrays.asList("200\t70", "400\t40", "1000\t10");
        Assert.assertEquals(expected, actual);

        actual =
                hiveShell.executeQuery(
                        "SELECT T1.a, T1.b, T1.d + T2.d FROM "
                                + tableName
                                + " T1 INNER JOIN "
                                + tableName
                                + " T2 ON T1.a = T2.a AND T1.b = T2.b ORDER BY T1.a, T1.b");
        expected = Arrays.asList("1\t10\t2000", "1\t20\t400", "2\t40\t800", "3\t50\t400");
        Assert.assertEquals(expected, actual);

        actual =
                hiveShell.executeQuery(
                        "SELECT T1.a, T1.b, T2.b, T1.d + T2.d FROM "
                                + tableName
                                + " T1 INNER JOIN "
                                + tableName
                                + " T2 ON T1.a = T2.a ORDER BY T1.a, T1.b, T2.b");
        expected =
                Arrays.asList(
                        "1\t10\t10\t2000",
                        "1\t10\t20\t1200",
                        "1\t20\t10\t1200",
                        "1\t20\t20\t400",
                        "2\t40\t40\t800",
                        "3\t50\t50\t400");
        Assert.assertEquals(expected, actual);
    }

    @Test
    public void testReadExternalTableWithPartitionWithPk() throws Exception {
        List<RowData> data =
                Arrays.asList(
                        GenericRowData.of(1, 10, 100L, StringData.fromString("Hi")),
                        GenericRowData.of(2, 10, 200L, StringData.fromString("Hello")),
                        GenericRowData.of(1, 20, 300L, StringData.fromString("World")),
                        GenericRowData.of(1, 10, 100L, StringData.fromString("Hi Again")),
                        GenericRowData.ofKind(
                                RowKind.DELETE, 1, 20, 300L, StringData.fromString("World")),
                        GenericRowData.of(2, 20, 100L, null),
                        GenericRowData.of(1, 30, 200L, StringData.fromString("Store")));
        String tableName =
                createChangelogExternalTable(
                        RowType.of(
                                new LogicalType[] {
                                    DataTypes.INT().getLogicalType(),
                                    DataTypes.INT().getLogicalType(),
                                    DataTypes.BIGINT().getLogicalType(),
                                    DataTypes.STRING().getLogicalType()
                                },
                                new String[] {"pt", "a", "b", "c"}),
                        Collections.singletonList("pt"),
                        Arrays.asList("pt", "a"),
                        data);

        List<String> actual =
                hiveShell.executeQuery("SELECT * FROM " + tableName + " ORDER BY pt, a");
        List<String> expected =
                Arrays.asList(
                        "1\t10\t100\tHi Again",
                        "1\t30\t200\tStore",
                        "2\t10\t200\tHello",
                        "2\t20\t100\tNULL");
        Assert.assertEquals(expected, actual);

        actual = hiveShell.executeQuery("SELECT c, a FROM " + tableName + " ORDER BY c, a");
        expected = Arrays.asList("NULL\t20", "Hello\t10", "Hi Again\t10", "Store\t30");
        Assert.assertEquals(expected, actual);

        actual =
                hiveShell.executeQuery(
                        "SELECT * FROM " + tableName + " WHERE b > 100 ORDER BY pt, a");
        expected = Arrays.asList("1\t30\t200\tStore", "2\t10\t200\tHello");
        Assert.assertEquals(expected, actual);

        actual =
                hiveShell.executeQuery(
                        "SELECT pt, sum(b), max(c) FROM " + tableName + " GROUP BY pt ORDER BY pt");
        expected = Arrays.asList("1\t300\tStore", "2\t300\tHello");
        Assert.assertEquals(expected, actual);

        actual =
                hiveShell.executeQuery(
                        "SELECT a, sum(b), max(c) FROM " + tableName + " GROUP BY a ORDER BY a");
        expected = Arrays.asList("10\t300\tHi Again", "20\t100\tNULL", "30\t200\tStore");
        Assert.assertEquals(expected, actual);

        actual =
                hiveShell.executeQuery(
                        "SELECT b, sum(a), max(c) FROM " + tableName + " GROUP BY b ORDER BY b");
        expected = Arrays.asList("100\t30\tHi Again", "200\t40\tStore");
        Assert.assertEquals(expected, actual);

        actual =
                hiveShell.executeQuery(
                        "SELECT a, b FROM (SELECT T1.a AS a, T1.b + T2.b AS b FROM "
                                + tableName
                                + " T1 JOIN "
                                + tableName
                                + " T2 ON T1.a = T2.a) T3 ORDER BY a, b");
        expected = Arrays.asList("10\t200", "10\t300", "10\t300", "10\t400", "20\t200", "30\t400");
        Assert.assertEquals(expected, actual);

        actual =
                hiveShell.executeQuery(
                        "SELECT b, a FROM (SELECT T1.b AS b, T1.a + T2.a AS a FROM "
                                + tableName
                                + " T1 JOIN "
                                + tableName
                                + " T2 ON T1.b = T2.b) T3 ORDER BY b, a");
        expected =
                Arrays.asList(
                        "100\t20", "100\t30", "100\t30", "100\t40", "200\t20", "200\t40", "200\t40",
                        "200\t60");
        Assert.assertEquals(expected, actual);
    }

    @Test
    public void testReadExternalTableNoPartitionWithValueCount() throws Exception {
        List<RowData> data =
                Arrays.asList(
                        GenericRowData.of(1, 10L, StringData.fromString("Hi"), 100L),
                        GenericRowData.of(1, 20L, StringData.fromString("Hello"), 200L),
                        GenericRowData.of(2, 30L, StringData.fromString("World"), 300L),
                        GenericRowData.of(1, 10L, StringData.fromString("Hi Again"), 1000L),
                        GenericRowData.ofKind(
                                RowKind.DELETE, 2, 30L, StringData.fromString("World"), 300L),
                        GenericRowData.of(2, 40L, null, 400L),
                        GenericRowData.of(3, 50L, StringData.fromString("Store"), 200L));
        String tableName =
                createChangelogExternalTable(
                        RowType.of(
                                new LogicalType[] {
                                    DataTypes.INT().getLogicalType(),
                                    DataTypes.BIGINT().getLogicalType(),
                                    DataTypes.STRING().getLogicalType(),
                                    DataTypes.BIGINT().getLogicalType()
                                },
                                new String[] {"a", "b", "c", "d"}),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        data);

        List<String> actual =
                hiveShell.executeQuery("SELECT * FROM " + tableName + " ORDER BY b, d");
        List<String> expected =
                Arrays.asList(
                        "1\t10\tHi\t100",
                        "1\t10\tHi Again\t1000",
                        "1\t20\tHello\t200",
                        "2\t40\tNULL\t400",
                        "3\t50\tStore\t200");
        Assert.assertEquals(expected, actual);

        actual = hiveShell.executeQuery("SELECT c, b FROM " + tableName + " ORDER BY c");
        expected = Arrays.asList("NULL\t40", "Hello\t20", "Hi\t10", "Hi Again\t10", "Store\t50");
        Assert.assertEquals(expected, actual);

        actual =
                hiveShell.executeQuery("SELECT * FROM " + tableName + " WHERE d <> 200 ORDER BY d");
        expected = Arrays.asList("1\t10\tHi\t100", "2\t40\tNULL\t400", "1\t10\tHi Again\t1000");
        Assert.assertEquals(expected, actual);

        actual =
                hiveShell.executeQuery(
                        "SELECT a, sum(d) FROM " + tableName + " GROUP BY a ORDER BY a");
        expected = Arrays.asList("1\t1300", "2\t400", "3\t200");
        Assert.assertEquals(expected, actual);

        actual =
                hiveShell.executeQuery(
                        "SELECT d, sum(b) FROM " + tableName + " GROUP BY d ORDER BY d");
        expected = Arrays.asList("100\t10", "200\t70", "400\t40", "1000\t10");
        Assert.assertEquals(expected, actual);

        actual =
                hiveShell.executeQuery(
                        "SELECT T1.b, T1.d, T2.d FROM "
                                + tableName
                                + " T1 JOIN "
                                + tableName
                                + " T2 ON T1.b = T2.b ORDER BY T1.b, T1.d, T2.d");
        expected =
                Arrays.asList(
                        "10\t100\t100",
                        "10\t100\t1000",
                        "10\t1000\t100",
                        "10\t1000\t1000",
                        "20\t200\t200",
                        "40\t400\t400",
                        "50\t200\t200");
        Assert.assertEquals(expected, actual);
    }

    @Test
    public void testReadExternalTableWithPartitionWithValueCount() throws Exception {
        List<RowData> data =
                Arrays.asList(
                        GenericRowData.of(1, 10, 100L, StringData.fromString("Hi")),
                        GenericRowData.of(2, 10, 200L, StringData.fromString("Hello")),
                        GenericRowData.of(1, 20, 300L, StringData.fromString("World")),
                        GenericRowData.of(1, 10, 100L, StringData.fromString("Hi Again")),
                        GenericRowData.ofKind(
                                RowKind.DELETE, 1, 20, 300L, StringData.fromString("World")),
                        GenericRowData.of(2, 20, 400L, null),
                        GenericRowData.of(1, 30, 500L, StringData.fromString("Store")));
        String tableName =
                createChangelogExternalTable(
                        RowType.of(
                                new LogicalType[] {
                                    DataTypes.INT().getLogicalType(),
                                    DataTypes.INT().getLogicalType(),
                                    DataTypes.BIGINT().getLogicalType(),
                                    DataTypes.STRING().getLogicalType()
                                },
                                new String[] {"pt", "a", "b", "c"}),
                        Collections.singletonList("pt"),
                        Collections.emptyList(),
                        data);

        List<String> actual =
                hiveShell.executeQuery("SELECT * FROM " + tableName + " ORDER BY pt, a, c");
        List<String> expected =
                Arrays.asList(
                        "1\t10\t100\tHi",
                        "1\t10\t100\tHi Again",
                        "1\t30\t500\tStore",
                        "2\t10\t200\tHello",
                        "2\t20\t400\tNULL");
        Assert.assertEquals(expected, actual);

        actual = hiveShell.executeQuery("SELECT c, b FROM " + tableName + " ORDER BY c");
        expected =
                Arrays.asList("NULL\t400", "Hello\t200", "Hi\t100", "Hi Again\t100", "Store\t500");
        Assert.assertEquals(expected, actual);

        actual =
                hiveShell.executeQuery(
                        "SELECT * FROM " + tableName + " WHERE b < 400 ORDER BY b, c");
        expected = Arrays.asList("1\t10\t100\tHi", "1\t10\t100\tHi Again", "2\t10\t200\tHello");
        Assert.assertEquals(expected, actual);

        actual =
                hiveShell.executeQuery(
                        "SELECT pt, max(a), min(c) FROM " + tableName + " GROUP BY pt ORDER BY pt");
        expected = Arrays.asList("1\t30\tHi", "2\t20\tHello");
        Assert.assertEquals(expected, actual);

        actual =
                hiveShell.executeQuery(
                        "SELECT a, sum(b), min(c) FROM " + tableName + " GROUP BY a ORDER BY a");
        expected = Arrays.asList("10\t400\tHello", "20\t400\tNULL", "30\t500\tStore");
        Assert.assertEquals(expected, actual);

        actual =
                hiveShell.executeQuery(
                        "SELECT T1.b, T1.c, T2.c FROM "
                                + tableName
                                + " T1 JOIN "
                                + tableName
                                + " T2 ON T1.b = T2.b ORDER BY T1.b, T1.c, T2.c");
        expected =
                Arrays.asList(
                        "100\tHi\tHi",
                        "100\tHi\tHi Again",
                        "100\tHi Again\tHi",
                        "100\tHi Again\tHi Again",
                        "200\tHello\tHello",
                        "400\tNULL\tNULL",
                        "500\tStore\tStore");
        Assert.assertEquals(expected, actual);
    }

    @Test
    public void testReadExternalTableNoPartitionAppendOnly() throws Exception {
        List<RowData> data =
                Arrays.asList(
                        GenericRowData.of(1, 10L, StringData.fromString("Hi"), 100L),
                        GenericRowData.of(1, 20L, StringData.fromString("Hello"), 200L),
                        GenericRowData.of(2, 30L, StringData.fromString("World"), 300L),
                        GenericRowData.of(1, 10L, StringData.fromString("Hi Again"), 1000L),
                        GenericRowData.of(2, 40L, null, 400L),
                        GenericRowData.of(3, 50L, StringData.fromString("Store"), 200L));
        String tableName =
                createAppendOnlyExternalTable(
                        RowType.of(
                                new LogicalType[] {
                                    DataTypes.INT().getLogicalType(),
                                    DataTypes.BIGINT().getLogicalType(),
                                    DataTypes.STRING().getLogicalType(),
                                    DataTypes.BIGINT().getLogicalType()
                                },
                                new String[] {"a", "b", "c", "d"}),
                        Collections.emptyList(),
                        data);

        List<String> actual =
                hiveShell.executeQuery("SELECT * FROM " + tableName + " ORDER BY a, b, c");
        List<String> expected =
                Arrays.asList(
                        "1\t10\tHi\t100",
                        "1\t10\tHi Again\t1000",
                        "1\t20\tHello\t200",
                        "2\t30\tWorld\t300",
                        "2\t40\tNULL\t400",
                        "3\t50\tStore\t200");
        Assert.assertEquals(expected, actual);

        actual = hiveShell.executeQuery("SELECT c, b FROM " + tableName + " ORDER BY c");
        expected =
                Arrays.asList(
                        "NULL\t40",
                        "Hello\t20",
                        "Hi\t10",
                        "Hi Again\t10",
                        "Store\t50",
                        "World\t30");
        Assert.assertEquals(expected, actual);

        actual =
                hiveShell.executeQuery(
                        "SELECT * FROM " + tableName + " WHERE d < 300 ORDER BY b, d");
        expected = Arrays.asList("1\t10\tHi\t100", "1\t20\tHello\t200", "3\t50\tStore\t200");
        Assert.assertEquals(expected, actual);

        actual =
                hiveShell.executeQuery(
                        "SELECT a, sum(d) FROM " + tableName + " GROUP BY a ORDER BY a");
        expected = Arrays.asList("1\t1300", "2\t700", "3\t200");
        Assert.assertEquals(expected, actual);

        actual =
                hiveShell.executeQuery(
                        "SELECT T1.a, T1.b, T2.b FROM "
                                + tableName
                                + " T1 JOIN "
                                + tableName
                                + " T2 ON T1.a = T2.a WHERE T1.a > 1 ORDER BY T1.a, T1.b, T2.b");
        expected = Arrays.asList("2\t30\t30", "2\t30\t40", "2\t40\t30", "2\t40\t40", "3\t50\t50");
        Assert.assertEquals(expected, actual);
    }

    @Test
    public void testReadExternalTableWithPartitionAppendOnly() throws Exception {
        List<RowData> data =
                Arrays.asList(
                        GenericRowData.of(1, 10, 100L, StringData.fromString("Hi")),
                        GenericRowData.of(2, 10, 200L, StringData.fromString("Hello")),
                        GenericRowData.of(1, 20, 300L, StringData.fromString("World")),
                        GenericRowData.of(1, 10, 100L, StringData.fromString("Hi Again")),
                        GenericRowData.of(2, 20, 400L, null),
                        GenericRowData.of(1, 30, 500L, StringData.fromString("Store")));
        String tableName =
                createAppendOnlyExternalTable(
                        RowType.of(
                                new LogicalType[] {
                                    DataTypes.INT().getLogicalType(),
                                    DataTypes.INT().getLogicalType(),
                                    DataTypes.BIGINT().getLogicalType(),
                                    DataTypes.STRING().getLogicalType()
                                },
                                new String[] {"pt", "a", "b", "c"}),
                        Collections.singletonList("pt"),
                        data);

        List<String> actual =
                hiveShell.executeQuery("SELECT * FROM " + tableName + " ORDER BY pt, a, c");
        List<String> expected =
                Arrays.asList(
                        "1\t10\t100\tHi",
                        "1\t10\t100\tHi Again",
                        "1\t20\t300\tWorld",
                        "1\t30\t500\tStore",
                        "2\t10\t200\tHello",
                        "2\t20\t400\tNULL");
        Assert.assertEquals(expected, actual);

        actual = hiveShell.executeQuery("SELECT c, b FROM " + tableName + " ORDER BY c");
        expected =
                Arrays.asList(
                        "NULL\t400",
                        "Hello\t200",
                        "Hi\t100",
                        "Hi Again\t100",
                        "Store\t500",
                        "World\t300");
        Assert.assertEquals(expected, actual);

        actual =
                hiveShell.executeQuery(
                        "SELECT * FROM " + tableName + " WHERE b < 400 ORDER BY b, c");
        expected =
                Arrays.asList(
                        "1\t10\t100\tHi",
                        "1\t10\t100\tHi Again",
                        "2\t10\t200\tHello",
                        "1\t20\t300\tWorld");
        Assert.assertEquals(expected, actual);

        actual =
                hiveShell.executeQuery(
                        "SELECT pt, max(a), min(c) FROM " + tableName + " GROUP BY pt ORDER BY pt");
        expected = Arrays.asList("1\t30\tHi", "2\t20\tHello");
        Assert.assertEquals(expected, actual);

        actual =
                hiveShell.executeQuery(
                        "SELECT a, sum(b), min(c) FROM " + tableName + " GROUP BY a ORDER BY a");
        expected = Arrays.asList("10\t400\tHello", "20\t700\tWorld", "30\t500\tStore");
        Assert.assertEquals(expected, actual);

        actual =
                hiveShell.executeQuery(
                        "SELECT T1.a, T1.b, T2.b FROM "
                                + tableName
                                + " T1 JOIN "
                                + tableName
                                + " T2 ON T1.a = T2.a WHERE T1.a > 10 ORDER BY T1.a, T1.b, T2.b");
        expected =
                Arrays.asList(
                        "20\t300\t300",
                        "20\t300\t400",
                        "20\t400\t300",
                        "20\t400\t400",
                        "30\t500\t500");
        Assert.assertEquals(expected, actual);
    }

    private String createChangelogExternalTable(
            RowType rowType,
            List<String> partitionKeys,
            List<String> primaryKeys,
            List<RowData> data)
            throws Exception {
        String path = "/usr/local/share/path/";
        Configuration conf = new Configuration();
        conf.setString(CoreOptions.PATH, path);
        conf.setInteger(CoreOptions.BUCKET, 2);
        conf.setString(CoreOptions.FILE_FORMAT, "orc");
        FileStoreTable table =
                FileStoreTestUtils.createFileStoreTable(conf, rowType, partitionKeys, primaryKeys);

        return writeData(table, path, data);
    }

    private String createAppendOnlyExternalTable(
            RowType rowType, List<String> partitionKeys, List<RowData> data) throws Exception {
        String path = "/usr/local/share/path/";
        Configuration conf = new Configuration();
        conf.setString(CoreOptions.PATH, path);
        conf.setInteger(CoreOptions.BUCKET, 2);
        conf.setString(CoreOptions.FILE_FORMAT, "orc");
        conf.set(CoreOptions.WRITE_MODE, WriteMode.APPEND_ONLY);
        conf.set(CoreOptions.COMPACTION_MAX_SORTED_RUN_NUM, 10);
        conf.set(CoreOptions.COMPRESS_TYPE, "zlib");
        FileStoreTable table =
                FileStoreTestUtils.createFileStoreTable(
                        conf, rowType, partitionKeys, Collections.emptyList());

        return writeData(table, path, data);
    }

    private String writeData(FileStoreTable table, String path, List<RowData> data)
            throws Exception {
        TableWrite write = table.newWrite();
        TableCommit commit = table.newCommit("user");
        for (RowData rowData : data) {
            write.write(rowData);
            if (ThreadLocalRandom.current().nextInt(5) == 0) {
                commit.commit(UUID.randomUUID().toString(), write.prepareCommit(false));
            }
        }
        commit.commit(UUID.randomUUID().toString(), write.prepareCommit(true));
        write.close();

        String tableName = "test_table_" + (UUID.randomUUID().toString().substring(0, 4));
        hiveShell.execute(
                String.join(
                        "\n",
                        Arrays.asList(
                                "CREATE EXTERNAL TABLE " + tableName + " ",
                                "STORED BY '" + TableStoreHiveStorageHandler.class.getName() + "'",
                                "LOCATION '" + path + "'")));
        return tableName;
    }

    @Test
    public void testReadAllSupportedTypes() throws Exception {
        String root = folder.newFolder().toString();
        Configuration conf = new Configuration();
        conf.setString(CoreOptions.PATH, root);
        conf.setString(CoreOptions.FILE_FORMAT, "avro");
        FileStoreTable table =
                FileStoreTestUtils.createFileStoreTable(
                        conf,
                        RandomGenericRowDataGenerator.ROW_TYPE,
                        Collections.emptyList(),
                        Collections.singletonList("f_int"));

        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<GenericRowData> input = new ArrayList<>();
        for (int i = random.nextInt(10); i > 0; i--) {
            while (true) {
                // pk must not be null
                GenericRowData rowData = RandomGenericRowDataGenerator.generate();
                if (!rowData.isNullAt(3)) {
                    input.add(rowData);
                    break;
                }
            }
        }

        TableWrite write = table.newWrite();
        TableCommit commit = table.newCommit("user");
        for (GenericRowData rowData : input) {
            write.write(rowData);
        }
        commit.commit("0", write.prepareCommit(true));
        write.close();

        hiveShell.execute(
                String.join(
                        "\n",
                        Arrays.asList(
                                "CREATE EXTERNAL TABLE test_table",
                                "STORED BY '" + TableStoreHiveStorageHandler.class.getName() + "'",
                                "LOCATION '" + root + "'")));
        List<Object[]> actual =
                hiveShell.executeStatement("SELECT * FROM test_table WHERE f_int > 0");

        Map<Integer, GenericRowData> expected = new HashMap<>();
        for (GenericRowData rowData : input) {
            int key = rowData.getInt(3);
            if (key > 0) {
                expected.put(key, rowData);
            }
        }
        for (Object[] actualRow : actual) {
            int key = (int) actualRow[3];
            Assert.assertTrue(expected.containsKey(key));
            GenericRowData expectedRow = expected.get(key);
            Assert.assertEquals(expectedRow.getArity(), actualRow.length);
            for (int i = 0; i < actualRow.length; i++) {
                if (expectedRow.isNullAt(i)) {
                    Assert.assertNull(actualRow[i]);
                    continue;
                }
                ObjectInspector oi =
                        TableStoreObjectInspectorFactory.create(
                                RandomGenericRowDataGenerator.LOGICAL_TYPES.get(i));
                switch (oi.getCategory()) {
                    case PRIMITIVE:
                        AbstractPrimitiveJavaObjectInspector primitiveOi =
                                (AbstractPrimitiveJavaObjectInspector) oi;
                        Object expectedObject =
                                primitiveOi.getPrimitiveJavaObject(expectedRow.getField(i));
                        if (expectedObject instanceof byte[]) {
                            Assert.assertArrayEquals(
                                    (byte[]) expectedObject, (byte[]) actualRow[i]);
                        } else if (expectedObject instanceof HiveDecimal) {
                            // HiveDecimal will remove trailing zeros
                            // so we have to compare it from the original DecimalData
                            Assert.assertEquals(expectedRow.getField(i).toString(), actualRow[i]);
                        } else {
                            Assert.assertEquals(
                                    String.valueOf(expectedObject), String.valueOf(actualRow[i]));
                        }
                        break;
                    case LIST:
                        ListObjectInspector listOi = (ListObjectInspector) oi;
                        Assert.assertEquals(
                                String.valueOf(listOi.getList(expectedRow.getField(i)))
                                        .replace(" ", ""),
                                actualRow[i]);
                        break;
                    case MAP:
                        MapObjectInspector mapOi = (MapObjectInspector) oi;
                        Map<String, String> expectedMap = new HashMap<>();
                        mapOi.getMap(expectedRow.getField(i))
                                .forEach(
                                        (k, v) -> expectedMap.put(k.toString(), String.valueOf(v)));
                        String actualString = actualRow[i].toString();
                        actualString = actualString.substring(1, actualString.length() - 1);
                        for (String kv : actualString.split(",")) {
                            if (kv.trim().isEmpty()) {
                                continue;
                            }
                            String[] split = kv.split(":");
                            String k = split[0].substring(1, split[0].length() - 1);
                            Assert.assertEquals(expectedMap.get(k), split[1]);
                            expectedMap.remove(k);
                        }
                        break;
                    default:
                        throw new UnsupportedOperationException();
                }
            }
            expected.remove(key);
        }
        Assert.assertTrue(expected.isEmpty());
    }

    @Test
    public void testPredicatePushDown() throws Exception {
        String path = folder.newFolder().toURI().toString();
        Configuration conf = new Configuration();
        conf.setString(CoreOptions.PATH, path);
        conf.setString(CoreOptions.FILE_FORMAT, "avro");
        FileStoreTable table =
                FileStoreTestUtils.createFileStoreTable(
                        conf,
                        RowType.of(
                                new LogicalType[] {DataTypes.INT().getLogicalType()},
                                new String[] {"a"}),
                        Collections.emptyList(),
                        Collections.emptyList());

        // TODO add NaN related tests after FLINK-27627 and FLINK-27628 are fixed

        TableWrite write = table.newWrite();
        TableCommit commit = table.newCommit("user");
        write.write(GenericRowData.of(1));
        commit.commit("0", write.prepareCommit(true));
        write.write(GenericRowData.of((Object) null));
        commit.commit("1", write.prepareCommit(true));
        write.write(GenericRowData.of(2));
        write.write(GenericRowData.of(3));
        write.write(GenericRowData.of((Object) null));
        commit.commit("2", write.prepareCommit(true));
        write.write(GenericRowData.of(4));
        write.write(GenericRowData.of(5));
        write.write(GenericRowData.of(6));
        commit.commit("3", write.prepareCommit(true));
        write.close();

        hiveShell.execute(
                String.join(
                        "\n",
                        Arrays.asList(
                                "CREATE EXTERNAL TABLE test_table",
                                "STORED BY '" + TableStoreHiveStorageHandler.class.getName() + "'",
                                "LOCATION '" + path + "'")));
        Assert.assertEquals(
                Arrays.asList("1", "5"),
                hiveShell.executeQuery("SELECT * FROM test_table WHERE a = 1 OR a = 5"));
        Assert.assertEquals(
                Arrays.asList("2", "3", "6"),
                hiveShell.executeQuery(
                        "SELECT * FROM test_table WHERE a <> 1 AND a <> 4 AND a <> 5"));
        Assert.assertEquals(
                Arrays.asList("2", "3", "6"),
                hiveShell.executeQuery(
                        "SELECT * FROM test_table WHERE NOT (a = 1 OR a = 5) AND NOT a = 4"));
        Assert.assertEquals(
                Arrays.asList("1", "2", "3"),
                hiveShell.executeQuery("SELECT * FROM test_table WHERE a < 4"));
        Assert.assertEquals(
                Arrays.asList("1", "2", "3"),
                hiveShell.executeQuery("SELECT * FROM test_table WHERE a <= 3"));
        Assert.assertEquals(
                Arrays.asList("4", "5", "6"),
                hiveShell.executeQuery("SELECT * FROM test_table WHERE a > 3"));
        Assert.assertEquals(
                Arrays.asList("4", "5", "6"),
                hiveShell.executeQuery("SELECT * FROM test_table WHERE a >= 4"));
        Assert.assertEquals(
                Arrays.asList("1", "3"),
                hiveShell.executeQuery("SELECT * FROM test_table WHERE a IN (0, 1, 3, 7)"));
        Assert.assertEquals(
                Collections.singletonList("3"),
                hiveShell.executeQuery("SELECT * FROM test_table WHERE a IN (0, NULL, 3, 7)"));
        Assert.assertEquals(
                Arrays.asList("4", "6"),
                hiveShell.executeQuery(
                        "SELECT * FROM test_table WHERE a NOT IN (0, 1, 3, 2, 5, 7)"));
        Assert.assertEquals(
                Collections.emptyList(),
                hiveShell.executeQuery(
                        "SELECT * FROM test_table WHERE a NOT IN (0, 1, NULL, 2, 5, 7)"));
        Assert.assertEquals(
                Arrays.asList("2", "3"),
                hiveShell.executeQuery("SELECT * FROM test_table WHERE a BETWEEN 2 AND 3"));
        Assert.assertEquals(
                Arrays.asList("1", "5", "6"),
                hiveShell.executeQuery("SELECT * FROM test_table WHERE a NOT BETWEEN 2 AND 4"));
        Assert.assertEquals(
                Arrays.asList("NULL", "NULL"),
                hiveShell.executeQuery("SELECT * FROM test_table WHERE a IS NULL"));
        Assert.assertEquals(
                Arrays.asList("1", "2", "3", "4", "5", "6"),
                hiveShell.executeQuery("SELECT * FROM test_table WHERE a IS NOT NULL"));
    }

    @Test
    public void testDateAndTimestamp() throws Exception {
        String path = folder.newFolder().toURI().toString();
        Configuration conf = new Configuration();
        conf.setString(CoreOptions.PATH, path);
        conf.setString(CoreOptions.FILE_FORMAT, "avro");
        FileStoreTable table =
                FileStoreTestUtils.createFileStoreTable(
                        conf,
                        RowType.of(
                                new LogicalType[] {
                                    DataTypes.DATE().getLogicalType(),
                                    DataTypes.TIMESTAMP(3).getLogicalType()
                                },
                                new String[] {"dt", "ts"}),
                        Collections.emptyList(),
                        Collections.emptyList());

        TableWrite write = table.newWrite();
        TableCommit commit = table.newCommit("user");
        write.write(
                GenericRowData.of(
                        375, /* 1971-01-11 */
                        TimestampData.fromLocalDateTime(
                                LocalDateTime.of(2022, 5, 17, 17, 29, 20))));
        commit.commit("0", write.prepareCommit(true));
        write.write(GenericRowData.of(null, null));
        commit.commit("1", write.prepareCommit(true));
        write.write(GenericRowData.of(376 /* 1971-01-12 */, null));
        write.write(
                GenericRowData.of(
                        null,
                        TimestampData.fromLocalDateTime(LocalDateTime.of(2022, 6, 18, 8, 30, 0))));
        commit.commit("2", write.prepareCommit(true));
        write.close();

        hiveShell.execute(
                String.join(
                        "\n",
                        Arrays.asList(
                                "CREATE EXTERNAL TABLE test_table",
                                "STORED BY '" + TableStoreHiveStorageHandler.class.getName() + "'",
                                "LOCATION '" + path + "'")));
        Assert.assertEquals(
                Collections.singletonList("1971-01-11\t2022-05-17 17:29:20.0"),
                hiveShell.executeQuery("SELECT * FROM test_table WHERE dt = '1971-01-11'"));
        Assert.assertEquals(
                Collections.singletonList("1971-01-11\t2022-05-17 17:29:20.0"),
                hiveShell.executeQuery(
                        "SELECT * FROM test_table WHERE ts = '2022-05-17 17:29:20'"));
        Assert.assertEquals(
                Collections.singletonList("1971-01-12\tNULL"),
                hiveShell.executeQuery("SELECT * FROM test_table WHERE dt = '1971-01-12'"));
        Assert.assertEquals(
                Collections.singletonList("NULL\t2022-06-18 08:30:00.0"),
                hiveShell.executeQuery(
                        "SELECT * FROM test_table WHERE ts = '2022-06-18 08:30:00'"));
    }
}

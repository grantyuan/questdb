/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2024 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.cairo;

import io.questdb.cairo.vm.Vm;
import io.questdb.cairo.vm.api.MemoryCMR;
import io.questdb.cairo.vm.api.MemoryR;
import io.questdb.cairo.vm.api.MemoryW;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.std.FilesFacade;
import io.questdb.std.LongList;
import io.questdb.std.MemoryTag;
import io.questdb.std.Mutable;
import io.questdb.std.Numbers;
import io.questdb.std.Os;
import io.questdb.std.Unsafe;
import io.questdb.std.Vect;
import io.questdb.std.datetime.microtime.TimestampFormatUtils;
import io.questdb.std.datetime.millitime.MillisecondClock;
import io.questdb.std.str.LPSZ;
import io.questdb.std.str.StringSink;

import java.io.Closeable;

public class ColumnVersionReader implements Closeable, Mutable {
    public static final int BLOCK_SIZE = 4;
    public static final int BLOCK_SIZE_BYTES = BLOCK_SIZE * Long.BYTES;
    public static final int BLOCK_SIZE_MSB = Numbers.msb(BLOCK_SIZE);
    // PARTITION_TIMESTAMP_OFFSET = 0;
    public static final int COLUMN_INDEX_OFFSET = 1;
    public static final int COLUMN_NAME_TXN_OFFSET = 2;
    public static final int COLUMN_TOP_OFFSET = 3;
    public static final long COL_TOP_DEFAULT_PARTITION = Long.MIN_VALUE;
    public static final int OFFSET_VERSION_64 = 0;
    public static final int OFFSET_OFFSET_A_64 = OFFSET_VERSION_64 + 8;
    public static final int OFFSET_SIZE_A_64 = OFFSET_OFFSET_A_64 + 8;
    public static final int OFFSET_OFFSET_B_64 = OFFSET_SIZE_A_64 + 8;
    public static final int OFFSET_SIZE_B_64 = OFFSET_OFFSET_B_64 + 8;
    public static final int HEADER_SIZE = OFFSET_SIZE_B_64 + 8;
    static final int TIMESTAMP_ADDED_PARTITION_OFFSET = COLUMN_TOP_OFFSET;
    private final static Log LOG = LogFactory.getLog(ColumnVersionReader.class);
    protected final LongList cachedColumnVersionList = new LongList();
    private MemoryCMR mem;
    private boolean ownMem;
    private long version;

    @Override
    public void clear() {
        if (ownMem) {
            mem.close();
        }
    }

    @Override
    public void close() {
        clear();
    }

    public void dumpTo(MemoryW mem) {
        mem.putLong(OFFSET_VERSION_64, version);
        boolean areaA = (version & 1L) == 0L;
        final long offset = HEADER_SIZE;
        mem.putLong(areaA ? OFFSET_OFFSET_A_64 : OFFSET_OFFSET_B_64, offset);
        final long size = (long) (cachedColumnVersionList.size() / BLOCK_SIZE) * BLOCK_SIZE_BYTES;
        mem.putLong(areaA ? OFFSET_SIZE_A_64 : OFFSET_SIZE_B_64, size);

        int i = 0;
        long p = offset;
        long lim = offset + size;

        while (p < lim) {
            mem.putLong(p, cachedColumnVersionList.getQuick(i));
            mem.putLong(p + COLUMN_INDEX_OFFSET * Long.BYTES, cachedColumnVersionList.getQuick(i + COLUMN_INDEX_OFFSET));
            mem.putLong(p + COLUMN_NAME_TXN_OFFSET * Long.BYTES, cachedColumnVersionList.getQuick(i + COLUMN_NAME_TXN_OFFSET));
            mem.putLong(p + COLUMN_TOP_OFFSET * Long.BYTES, cachedColumnVersionList.getQuick(i + COLUMN_TOP_OFFSET));
            i += BLOCK_SIZE;
            p += BLOCK_SIZE_BYTES;
        }
    }

    public LongList getCachedColumnVersionList() {
        return cachedColumnVersionList;
    }

    public long getColumnNameTxn(long partitionTimestamp, int columnIndex) {
        int versionRecordIndex = getRecordIndex(partitionTimestamp, columnIndex);
        return versionRecordIndex > -1 ? cachedColumnVersionList.getQuick(versionRecordIndex + COLUMN_NAME_TXN_OFFSET) : getDefaultColumnNameTxn(columnIndex);
    }

    public long getColumnNameTxnByIndex(int versionRecordIndex) {
        return versionRecordIndex > -1 ? cachedColumnVersionList.getQuick(versionRecordIndex + COLUMN_NAME_TXN_OFFSET) : -1L;
    }

    /**
     * Checks that column exists in the partition and returns the column top
     *
     * @param partitionTimestamp timestamp of the partition
     * @param columnIndex        column index
     * @return column top in the partition or -1 if column does not exist in the partition
     */
    public long getColumnTop(long partitionTimestamp, int columnIndex) {
        // Check if there is explicit record for this partitionTimestamp / columnIndex combination
        int recordIndex = getRecordIndex(partitionTimestamp, columnIndex);
        return getColumnTopByIndexOrDefault(recordIndex, partitionTimestamp, columnIndex, -1L);
    }

    public long getColumnTopByIndex(int versionRecordIndex) {
        return versionRecordIndex > -1 ? cachedColumnVersionList.getQuick(versionRecordIndex + COLUMN_TOP_OFFSET) : 0L;
    }

    public long getColumnTopByIndexOrDefault(int recordIndex, long partitionTimestamp, int columnIndex, long defaultValue) {
        if (recordIndex > -1L) {
            return cachedColumnVersionList.getQuick(recordIndex + COLUMN_TOP_OFFSET);
        }

        // Check if column has been already added before this partition
        long columnTopDefaultPartition = getColumnTopPartitionTimestamp(columnIndex);
        if (columnTopDefaultPartition <= partitionTimestamp) {
            return 0;
        }

        // This column does not exist in the partition
        return defaultValue;
    }

    /**
     * Get partition when the column was added first into the table.
     * All partitions before that one should not have any data in the column
     * All partitions after that will have 0 column top (column fully exists)
     * Exception is when O3 commit can overwrite column top for any partition where the column did not exist
     * with concrete column top value
     *
     * @param columnIndex column index
     * @return the partition timestamp where column added or Long.MIN_VALUE if column was present from table creation
     */
    public long getColumnTopPartitionTimestamp(int columnIndex) {
        int index = getRecordIndex(COL_TOP_DEFAULT_PARTITION, columnIndex);
        return index > -1 ? getColumnTopByIndex(index) : Long.MIN_VALUE;
    }

    /**
     * Returns the column top without checking that column exists in the partition
     *
     * @param partitionTimestamp timestamp of the partition
     * @param columnIndex        column index
     * @return column top in the partition or 0 if column does not exist in the partition or column exists with no column top
     */
    public long getColumnTopQuick(long partitionTimestamp, int columnIndex) {
        int index = getRecordIndex(partitionTimestamp, columnIndex);
        return getColumnTopByIndex(index);
    }

    public long getDefaultColumnNameTxn(int columnIndex) {
        int index = getRecordIndex(COL_TOP_DEFAULT_PARTITION, columnIndex);
        return index > -1 ? getColumnNameTxnByIndex(index) : -1L;
    }

    public long getMaxPartitionVersion(long partitionTimestamp) {
        long maxVersion = -1;
        int index = cachedColumnVersionList.binarySearchBlock(BLOCK_SIZE_MSB, partitionTimestamp, Vect.BIN_SEARCH_SCAN_UP);
        if (index > -1) {
            final int sz = cachedColumnVersionList.size();
            for (; index < sz && cachedColumnVersionList.getQuick(index) == partitionTimestamp; index += BLOCK_SIZE) {
                final long thisTimestamp = cachedColumnVersionList.getQuick(index);
                if (thisTimestamp != partitionTimestamp) {
                    break;
                }
                final long columnVersion = cachedColumnVersionList.getQuick(index + COLUMN_NAME_TXN_OFFSET);
                maxVersion = Math.max(maxVersion, columnVersion);
            }
        }
        return maxVersion;
    }

    public int getRecordIndex(long partitionTimestamp, int columnIndex) {
        int index = cachedColumnVersionList.binarySearchBlock(BLOCK_SIZE_MSB, partitionTimestamp, Vect.BIN_SEARCH_SCAN_UP);
        if (index > -1) {
            final int sz = cachedColumnVersionList.size();
            for (; index < sz && cachedColumnVersionList.getQuick(index) == partitionTimestamp; index += BLOCK_SIZE) {
                final long thisIndex = cachedColumnVersionList.getQuick(index + COLUMN_INDEX_OFFSET);
                final long thisTimestamp = cachedColumnVersionList.getQuick(index);
                if (thisTimestamp != partitionTimestamp) {
                    break;
                }

                if (thisIndex == columnIndex) {
                    return index;
                }

                if (thisIndex > columnIndex) {
                    break;
                }
            }
        }
        return -1;
    }

    public long getVersion() {
        return version;
    }

    public ColumnVersionReader ofRO(FilesFacade ff, LPSZ fileName) {
        version = -1;
        if (this.mem == null || !ownMem) {
            this.mem = Vm.getCMRInstance();
        }
        this.mem.of(ff, fileName, 0, HEADER_SIZE, MemoryTag.MMAP_TABLE_READER);
        ownMem = true;
        return this;
    }

    public void ofRO(MemoryCMR mem) {
        if (this.mem != null && ownMem) {
            this.mem.close();
        }
        this.mem = mem;
        ownMem = false;
        version = -1;
    }

    /**
     * Copies column versions from the given reader.
     */
    public void readFrom(ColumnVersionReader columnVersionReader) {
        this.version = columnVersionReader.version;
        cachedColumnVersionList.clear();
        cachedColumnVersionList.addAll(columnVersionReader.cachedColumnVersionList);
    }

    public void readSafe(MillisecondClock microsecondClock, long spinLockTimeout) {
        final long tick = microsecondClock.getTicks();
        while (true) {
            long version = unsafeGetVersion();
            if (version == this.version) {
                return;
            }
            Unsafe.getUnsafe().loadFence();

            final long offset;
            final long size;

            final boolean areaA = (version & 1L) == 0;
            if (areaA) {
                offset = mem.getLong(OFFSET_OFFSET_A_64);
                size = mem.getLong(OFFSET_SIZE_A_64);
            } else {
                offset = mem.getLong(OFFSET_OFFSET_B_64);
                size = mem.getLong(OFFSET_SIZE_B_64);
            }

            Unsafe.getUnsafe().loadFence();
            if (version == unsafeGetVersion()) {
                mem.resize(offset + size);
                readUnsafe(offset, size, cachedColumnVersionList, mem);

                Unsafe.getUnsafe().loadFence();
                if (version == unsafeGetVersion()) {
                    this.version = version;
                    LOG.debug().$("read clean version ").$(version).$(", offset ").$(offset).$(", size ").$(size).$();
                    return;
                }
            }

            if (microsecondClock.getTicks() - tick > spinLockTimeout) {
                LOG.error().$("Column Version read timeout [timeout=").$(spinLockTimeout).$("ms]").$();
                throw CairoException.critical(0).put("Column Version read timeout");
            }
            Os.pause();
            LOG.debug().$("read dirty version ").$(version).$(", retrying").$();
        }
    }

    public long readUnsafe() {
        long version = mem.getLong(OFFSET_VERSION_64);

        boolean areaA = (version & 1L) == 0L;
        long offset = areaA ? mem.getLong(OFFSET_OFFSET_A_64) : mem.getLong(OFFSET_OFFSET_B_64);
        long size = areaA ? mem.getLong(OFFSET_SIZE_A_64) : mem.getLong(OFFSET_SIZE_B_64);
        mem.resize(offset + size);
        readUnsafe(offset, size, cachedColumnVersionList, mem);
        return version;
    }

    @Override
    public String toString() {
        // Used for debugging, don't use Misc.getThreadLocalSink() to not mess with other debugging values
        StringSink sink = new StringSink();
        sink.put("{[");
        for (int i = 0; i < cachedColumnVersionList.size(); i += BLOCK_SIZE) {
            long timestamp = cachedColumnVersionList.getQuick(i);
            int columnIndex = (int) cachedColumnVersionList.getQuick(i + COLUMN_INDEX_OFFSET);
            long columnNameTxn = cachedColumnVersionList.getQuick(i + COLUMN_NAME_TXN_OFFSET);
            long columnTop = cachedColumnVersionList.getQuick(i + COLUMN_TOP_OFFSET);

            if (i > 0) {
                sink.put(",");
            }
            sink.put("\n{columnIndex: ").put(columnIndex).put(", ");
            boolean isDefaultPartition = timestamp == COL_TOP_DEFAULT_PARTITION;
            if (isDefaultPartition) {
                sink.put("defaultNameTxn: ").put(columnNameTxn).put(", ");
                sink.put("addedPartition: '");
                TimestampFormatUtils.appendDateTime(sink, columnTop);
                sink.put("'}");
            } else {
                sink.put("nameTxn: ").put(columnNameTxn).put(", ");
                sink.put("partition: '");
                TimestampFormatUtils.appendDateTime(sink, timestamp);
                sink.put("', ");
                sink.put("columnTop: ").put(columnTop).put("}");
            }
        }
        sink.put("\n]}");
        return sink.toString();
    }

    private static void readUnsafe(long offset, long areaSize, LongList cachedList, MemoryR mem) {
        long lim = offset + areaSize;
        mem.extend(lim);
        int i = 0;
        long p = offset;

        assert areaSize % BLOCK_SIZE_BYTES == 0;

        cachedList.setPos((int) ((areaSize / BLOCK_SIZE_BYTES) * BLOCK_SIZE));

        while (p < lim) {
            cachedList.setQuick(i, mem.getLong(p));
            cachedList.setQuick(i + COLUMN_INDEX_OFFSET, mem.getLong(p + COLUMN_INDEX_OFFSET * Long.BYTES));
            cachedList.setQuick(i + COLUMN_NAME_TXN_OFFSET, mem.getLong(p + COLUMN_NAME_TXN_OFFSET * Long.BYTES));
            cachedList.setQuick(i + COLUMN_TOP_OFFSET, mem.getLong(p + COLUMN_TOP_OFFSET * Long.BYTES));
            i += BLOCK_SIZE;
            p += BLOCK_SIZE_BYTES;
        }
    }

    private long unsafeGetVersion() {
        return mem.getLong(OFFSET_VERSION_64);
    }
}

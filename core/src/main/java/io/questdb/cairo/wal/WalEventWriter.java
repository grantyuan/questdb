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

package io.questdb.cairo.wal;

import io.questdb.cairo.CairoConfiguration;
import io.questdb.cairo.CairoException;
import io.questdb.cairo.ColumnType;
import io.questdb.cairo.CommitMode;
import io.questdb.cairo.VarcharTypeDriver;
import io.questdb.cairo.sql.BindVariableService;
import io.questdb.cairo.sql.Function;
import io.questdb.cairo.vm.Vm;
import io.questdb.cairo.vm.api.MemoryMARW;
import io.questdb.griffin.SqlExecutionContext;
import io.questdb.std.AtomicIntList;
import io.questdb.std.BoolList;
import io.questdb.std.CharSequenceIntHashMap;
import io.questdb.std.Files;
import io.questdb.std.FilesFacade;
import io.questdb.std.MemoryTag;
import io.questdb.std.Numbers;
import io.questdb.std.ObjList;
import io.questdb.std.Rnd;
import io.questdb.std.Unsafe;
import io.questdb.std.str.Path;
import io.questdb.std.str.StringSink;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;

import static io.questdb.cairo.wal.WalUtils.*;

class WalEventWriter implements Closeable {
    private final CairoConfiguration configuration;
    private final MemoryMARW eventMem = Vm.getCMARWInstance();
    private final FilesFacade ff;
    private final StringSink sink = new StringSink();
    private long indexFd;
    private AtomicIntList initialSymbolCounts;
    private long longBuffer;
    private long startOffset = 0;
    private BoolList symbolMapNullFlags;
    private int txn = 0;
    private ObjList<CharSequenceIntHashMap> txnSymbolMaps;

    WalEventWriter(CairoConfiguration configuration) {
        this.configuration = configuration;
        this.ff = configuration.getFilesFacade();
    }

    @Override
    public void close() {
        close(true, Vm.TRUNCATE_TO_POINTER);
    }

    public void close(boolean truncate, byte truncateMode) {
        eventMem.close(truncate, truncateMode);
        Unsafe.free(longBuffer, Long.BYTES, MemoryTag.NATIVE_TABLE_WAL_WRITER);
        longBuffer = 0L;
        ff.close(indexFd);
        indexFd = -1;
    }

    /**
     * Size in bytes consumed by the events file, including any symbols.
     */
    public long size() {
        return eventMem.getAppendOffset();
    }

    private void appendBindVariableValuesByIndex(BindVariableService bindVariableService) {
        final int count = bindVariableService != null ? bindVariableService.getIndexedVariableCount() : 0;
        eventMem.putInt(count);
        for (int i = 0; i < count; i++) {
            appendFunctionValue(bindVariableService.getFunction(i));
        }
    }

    private void appendBindVariableValuesByName(BindVariableService bindVariableService) {
        final int count = bindVariableService != null ? bindVariableService.getNamedVariables().size() : 0;
        eventMem.putInt(count);

        if (count > 0) {
            final ObjList<CharSequence> namedVariables = bindVariableService.getNamedVariables();
            for (int i = 0; i < count; i++) {
                final CharSequence name = namedVariables.get(i);
                eventMem.putStr(name);
                sink.clear();
                sink.put(':').put(name);
                appendFunctionValue(bindVariableService.getFunction(sink));
            }
        }
    }

    private int appendData(byte txnType, long startRowID, long endRowID, long minTimestamp, long maxTimestamp, boolean outOfOrder, long lastRefreshBaseTxn, long lastRefreshTimestamp) {
        startOffset = eventMem.getAppendOffset() - Integer.BYTES;
        eventMem.putLong(txn);
        eventMem.putByte(txnType);
        eventMem.putLong(startRowID);
        eventMem.putLong(endRowID);
        eventMem.putLong(minTimestamp);
        eventMem.putLong(maxTimestamp);
        eventMem.putBool(outOfOrder);
        if (txnType == WalTxnType.MAT_VIEW_DATA) {
            assert lastRefreshBaseTxn != Numbers.LONG_NULL;
            eventMem.putLong(lastRefreshBaseTxn);
            eventMem.putLong(lastRefreshTimestamp);
        }
        writeSymbolMapDiffs();
        eventMem.putInt(startOffset, (int) (eventMem.getAppendOffset() - startOffset));
        eventMem.putInt(-1);

        appendIndex(eventMem.getAppendOffset() - Integer.BYTES);
        eventMem.putInt(WALE_MAX_TXN_OFFSET_32, txn);
        if (txnType == WalTxnType.MAT_VIEW_DATA) {
            eventMem.putInt(WAL_FORMAT_OFFSET_32, WALE_MAT_VIEW_FORMAT_VERSION);
        }
        return txn++;
    }

    private void appendFunctionValue(Function function) {
        final int type = function.getType();
        eventMem.putInt(type);

        switch (ColumnType.tagOf(type)) {
            case ColumnType.BOOLEAN:
                eventMem.putBool(function.getBool(null));
                break;
            case ColumnType.BYTE:
                eventMem.putByte(function.getByte(null));
                break;
            case ColumnType.GEOBYTE:
                eventMem.putByte(function.getGeoByte(null));
                break;
            case ColumnType.SHORT:
                eventMem.putShort(function.getShort(null));
                break;
            case ColumnType.GEOSHORT:
                eventMem.putShort(function.getGeoShort(null));
                break;
            case ColumnType.CHAR:
                eventMem.putChar(function.getChar(null));
                break;
            case ColumnType.INT:
                eventMem.putInt(function.getInt(null));
                break;
            case ColumnType.IPv4:
                eventMem.putInt(function.getIPv4(null));
                break;
            case ColumnType.GEOINT:
                eventMem.putInt(function.getGeoInt(null));
                break;
            case ColumnType.FLOAT:
                eventMem.putFloat(function.getFloat(null));
                break;
            case ColumnType.LONG:
                eventMem.putLong(function.getLong(null));
                break;
            case ColumnType.GEOLONG:
                eventMem.putLong(function.getGeoLong(null));
                break;
            case ColumnType.DATE:
                eventMem.putLong(function.getDate(null));
                break;
            case ColumnType.TIMESTAMP:
                eventMem.putLong(function.getTimestamp(null));
                break;
            case ColumnType.DOUBLE:
                eventMem.putDouble(function.getDouble(null));
                break;
            case ColumnType.STRING:
                eventMem.putStr(function.getStrA(null));
                break;
            case ColumnType.VARCHAR:
                VarcharTypeDriver.appendPlainValue(eventMem, function.getVarcharA(null));
                break;
            case ColumnType.BINARY:
                eventMem.putBin(function.getBin(null));
                break;
            case ColumnType.UUID:
                eventMem.putLong128(function.getLong128Lo(null), function.getLong128Hi(null));
                break;
            default:
                throw new UnsupportedOperationException("unsupported column type: " + ColumnType.nameOf(type));
        }
    }

    private void appendIndex(long value) {
        Unsafe.getUnsafe().putLong(longBuffer, value);
        if (ff.append(indexFd, longBuffer, Long.BYTES) != Long.BYTES) {
            throw CairoException.critical(ff.errno()).put("could not append WAL event index value [value=").put(value).put(']');
        }
    }

    private void init() {
        eventMem.putInt(0);
        eventMem.putInt(WALE_FORMAT_VERSION);
        eventMem.putInt(-1);

        appendIndex(WALE_HEADER_SIZE);
        txn = 0;
    }

    private void writeSymbolMapDiffs() {
        final int columns = txnSymbolMaps.size();
        for (int columnIndex = 0; columnIndex < columns; columnIndex++) {
            final CharSequenceIntHashMap symbolMap = txnSymbolMaps.getQuick(columnIndex);
            if (symbolMap != null) {
                final int initialCount = initialSymbolCounts.get(columnIndex);
                if (initialCount > 0 || (initialCount == 0 && symbolMap.size() > 0)) {
                    eventMem.putInt(columnIndex);
                    eventMem.putBool(symbolMapNullFlags.get(columnIndex));
                    eventMem.putInt(initialCount);

                    final int size = symbolMap.size();
                    long appendAddress = eventMem.getAppendOffset();
                    eventMem.putInt(size);

                    int symbolCount = 0;
                    for (int j = 0; j < size; j++) {
                        final CharSequence symbol = symbolMap.keys().getQuick(j);
                        assert symbol != null;
                        final int value = symbolMap.get(symbol);
                        // Ignore symbols cached from symbolMapReader
                        if (value >= initialCount) {
                            eventMem.putInt(value);
                            eventMem.putStr(symbol);
                            symbolCount++;
                        }
                    }
                    // Update the size with the exact symbolCount
                    // An empty SymbolMapDiff can be created because symbolCount can be 0
                    // in case all cached symbols come from symbolMapReader.
                    // Alternatively, two-pass approach can be used.
                    eventMem.putInt(appendAddress, symbolCount);
                    eventMem.putInt(SymbolMapDiffImpl.END_OF_SYMBOL_ENTRIES);
                }
            }
        }
        eventMem.putInt(SymbolMapDiffImpl.END_OF_SYMBOL_DIFFS);
    }

    int appendData(long startRowID, long endRowID, long minTimestamp, long maxTimestamp, boolean outOfOrder) {
        return appendData(
                startRowID,
                endRowID,
                minTimestamp,
                maxTimestamp,
                outOfOrder,
                Numbers.LONG_NULL,
                Numbers.LONG_NULL
        );
    }

    int appendData(long startRowID, long endRowID, long minTimestamp, long maxTimestamp, boolean outOfOrder, long lastRefreshBaseTxn, long lastRefreshTimestamp) {
        byte msgType = lastRefreshBaseTxn != Numbers.LONG_NULL ? WalTxnType.MAT_VIEW_DATA : WalTxnType.DATA;
        return appendData(msgType, startRowID, endRowID, minTimestamp, maxTimestamp, outOfOrder, lastRefreshBaseTxn, lastRefreshTimestamp);
    }

    int appendSql(int cmdType, CharSequence sqlText, SqlExecutionContext sqlExecutionContext) {
        startOffset = eventMem.getAppendOffset() - Integer.BYTES;
        eventMem.putLong(txn);
        eventMem.putByte(WalTxnType.SQL);
        eventMem.putInt(cmdType); // byte would be enough probably
        eventMem.putStr(sqlText);
        final Rnd rnd = sqlExecutionContext.getRandom();
        eventMem.putLong(rnd.getSeed0());
        eventMem.putLong(rnd.getSeed1());
        final BindVariableService bindVariableService = sqlExecutionContext.getBindVariableService();
        appendBindVariableValuesByIndex(bindVariableService);
        appendBindVariableValuesByName(bindVariableService);
        eventMem.putInt(startOffset, (int) (eventMem.getAppendOffset() - startOffset));
        eventMem.putInt(-1);

        appendIndex(eventMem.getAppendOffset() - Integer.BYTES);
        eventMem.putInt(WALE_MAX_TXN_OFFSET_32, txn);
        return txn++;
    }

    int invalidate(boolean invalid, @Nullable CharSequence invalidationReason) {
        startOffset = eventMem.getAppendOffset() - Integer.BYTES;
        eventMem.putLong(txn);
        eventMem.putByte(WalTxnType.MAT_VIEW_INVALIDATE);
        eventMem.putBool(invalid);
        eventMem.putStr(invalidationReason);
        eventMem.putInt(startOffset, (int) (eventMem.getAppendOffset() - startOffset));
        eventMem.putInt(-1);

        appendIndex(eventMem.getAppendOffset() - Integer.BYTES);
        eventMem.putInt(WALE_MAX_TXN_OFFSET_32, txn);
        eventMem.putInt(WAL_FORMAT_OFFSET_32, WALE_MAT_VIEW_FORMAT_VERSION);
        return txn++;
    }

    void of(ObjList<CharSequenceIntHashMap> txnSymbolMaps, AtomicIntList initialSymbolCounts, BoolList symbolMapNullFlags) {
        this.txnSymbolMaps = txnSymbolMaps;
        this.initialSymbolCounts = initialSymbolCounts;
        this.symbolMapNullFlags = symbolMapNullFlags;
    }

    void openEventFile(Path path, int pathLen, boolean truncate, boolean systemTable) {
        if (eventMem.getFd() > -1) {
            close(truncate, Vm.TRUNCATE_TO_POINTER);
        }
        eventMem.of(
                ff,
                path.trimTo(pathLen).concat(EVENT_FILE_NAME).$(),
                systemTable ? configuration.getSystemWalEventAppendPageSize() : configuration.getWalEventAppendPageSize(),
                -1,
                MemoryTag.NATIVE_TABLE_WAL_WRITER,
                CairoConfiguration.O_NONE,
                Files.POSIX_MADV_RANDOM
        );
        indexFd = ff.openRW(path.trimTo(pathLen).concat(EVENT_INDEX_FILE_NAME).$(), CairoConfiguration.O_NONE);
        longBuffer = Unsafe.malloc(Long.BYTES, MemoryTag.NATIVE_TABLE_WAL_WRITER);
        init();
    }

    void rollback() {
        eventMem.putInt(startOffset, -1);
        eventMem.putInt(WALE_MAX_TXN_OFFSET_32, --txn - 1);
        // Do not truncate files, these files may be read by WAL Apply job at the moment.
        // This is very rare case, WALE will not be written anymore after this call.
        // Not truncating the files saves from reading complexity.
    }

    void sync() {
        int commitMode = configuration.getCommitMode();
        if (commitMode != CommitMode.NOSYNC) {
            eventMem.sync(commitMode == CommitMode.ASYNC);
            ff.fsync(indexFd);
        }
    }

    int truncate() {
        startOffset = eventMem.getAppendOffset() - Integer.BYTES;
        eventMem.putLong(txn);
        eventMem.putByte(WalTxnType.TRUNCATE);
        eventMem.putInt(startOffset, (int) (eventMem.getAppendOffset() - startOffset));
        eventMem.putInt(-1);

        appendIndex(eventMem.getAppendOffset() - Integer.BYTES);
        eventMem.putInt(WALE_MAX_TXN_OFFSET_32, txn);
        return txn++;
    }
}

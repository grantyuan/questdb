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

package io.questdb.cairo.sql;

import io.questdb.cairo.TableReader;

/**
 * Defines a page frame cursor backed with an in-house database table.
 */
public interface TablePageFrameCursor extends PageFrameCursor, SymbolTableSource {

    @Override
    StaticSymbolTable getSymbolTable(int columnIndex);

    // same TableReader is available on each page frame
    TableReader getTableReader();

    /**
     * Returns the REAL row id of given row on current page.
     * This is used for e.g. updating rows.
     *
     * @param rowIndex - page index of row
     * @return real row id
     */
    long getUpdateRowId(long rowIndex);

    TablePageFrameCursor of(PartitionFrameCursor partitionFrameCursor);
}

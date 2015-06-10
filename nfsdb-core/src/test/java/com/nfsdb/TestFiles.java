/*******************************************************************************
 *   _  _ ___ ___     _ _
 *  | \| | __/ __| __| | |__
 *  | .` | _|\__ \/ _` | '_ \
 *  |_|\_|_| |___/\__,_|_.__/
 *
 *  Copyright (c) 2014-2015. The NFSdb project and its contributors.
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
 ******************************************************************************/
package com.nfsdb;

import com.nfsdb.exceptions.JournalException;
import com.nfsdb.utils.Files;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class TestFiles {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static void touch(File file) throws IOException {
        FileOutputStream fos = new FileOutputStream(file);
        fos.close();
    }

    @Test
    public void testDeleteDir() throws Exception {
        File r = temporaryFolder.newFolder("to_delete");
        Assert.assertTrue(new File(r, "a/b/c").mkdirs());
        Assert.assertTrue(new File(r, "d/e/f").mkdirs());
        touch(new File(r, "d/1.txt"));
        touch(new File(r, "a/b/2.txt"));
        Assert.assertTrue(Files.delete(r));
        Assert.assertFalse(r.exists());
    }

    @Test
    public void testWriteStringToFile() throws IOException, JournalException {
        File f = temporaryFolder.newFile();
        Files.writeStringToFile(f, "TEST123");
        Assert.assertEquals("TEST123", Files.readStringFromFile(f));
    }
}
